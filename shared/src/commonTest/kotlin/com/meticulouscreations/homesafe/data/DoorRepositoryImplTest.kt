package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class DoorRepositoryImplTest {

    private class FakeConnection(url: String?) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = Res.string.biometric_name_generic
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    /** A relay with one door; a PUT waits on [putGate] and then answers [putStatus]. */
    private class Harness(scope: TestScope, connection: ConnectionRepository) {
        var notify = "away"
        var putStatus = HttpStatusCode.OK
        var putGate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<String>()
        private fun doors() = """{"doors":[{"id":"0x01","name":"Front door","open":false,"notify":"$notify"}],"events":[],"broker":true,"bridge":true}"""
        private val engine = MockEngine { req ->
            requests += "${req.method.value} ${req.url.encodedPath}"
            when {
                req.method == HttpMethod.Put -> {
                    putGate?.await()
                    if (putStatus == HttpStatusCode.OK) notify = "always"
                    respond(if (putStatus == HttpStatusCode.OK) doors() else "{}", putStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                else -> respond(doors(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val repository = DoorRepositoryImpl(
            relayApi = PushRelayApi(HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }),
            identity = DeviceIdentityStore(InMemorySettingsDao()),
            connectionRepository = connection,
            appScope = scope.backgroundScope,
            pollIntervalMs = 100,
        )
    }

    private suspend fun TestScope.eventually(what: String, cond: suspend () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (cond()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("Timed out waiting for $what")
    }

    @Test
    fun theDoorsArePolledOnlyWhileSomethingWatches() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.65:8971"))
        advanceUntilIdle()
        assertTrue(h.requests.isEmpty())
        backgroundScope.launch { h.repository.doors.collect {} }
        eventually("the first read") { h.repository.doors.value.doors.size == 1 }
        assertEquals("GET /doors", h.requests.first())
    }

    @Test
    fun aSettingShowsAtOnceAndAFailureTakesItBack() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.65:8971"))
        h.repository.refresh().getOrThrow()
        h.putGate = CompletableDeferred()
        h.putStatus = HttpStatusCode.BadGateway
        val saving = async { h.repository.setNotify("0x01", DoorNotify.ALWAYS) }
        eventually("the setting shown before the relay answers") { h.repository.doors.value.doors.single().notify == DoorNotify.ALWAYS }
        h.putGate?.complete(Unit)
        assertTrue(saving.await().isFailure)
        assertEquals(DoorNotify.AWAY, h.repository.doors.value.doors.single().notify)
    }

    @Test
    fun signingOutForgetsTheDoors() = runTest {
        val connection = FakeConnection("http://192.168.68.65:8971")
        val h = Harness(this, connection)
        h.repository.refresh().getOrThrow()
        connection.currentServerUrl.value = null
        eventually("the doors forgotten") { h.repository.doors.value == DoorsSnapshot.EMPTY }
    }
}
