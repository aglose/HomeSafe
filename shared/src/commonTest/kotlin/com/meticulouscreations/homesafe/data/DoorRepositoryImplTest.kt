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
import io.ktor.content.TextContent
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

    /**
     * A relay with two doors. Each PUT takes the next of [putGates] (when there is one) to wait on
     * and the next of [putStatuses] (OK when there are none left), and keeps what it was sent.
     */
    private class Harness(scope: TestScope, connection: ConnectionRepository) {
        val notify = mutableMapOf("0x01" to "away", "0x02" to "away")
        val putStatuses = ArrayDeque<HttpStatusCode>()
        val putGates = ArrayDeque<CompletableDeferred<Unit>>()
        val requests = mutableListOf<String>()
        private fun doors() = notify.entries.joinToString(",", """{"doors":[""", """],"events":[],"broker":true,"bridge":true}""") { (id, mode) ->
            """{"id":"$id","name":"Door $id","open":false,"notify":"$mode"}"""
        }
        private val engine = MockEngine { req ->
            when (req.method) {
                HttpMethod.Put -> {
                    val gate = putGates.removeFirstOrNull()
                    val status = putStatuses.removeFirstOrNull() ?: HttpStatusCode.OK
                    val id = req.url.encodedPath.removePrefix("/doors/")
                    val mode = (req.body as TextContent).text.substringAfter("\"notify\":\"").substringBefore("\"")
                    requests += "PUT $id $mode"
                    gate?.await()
                    if (status == HttpStatusCode.OK) notify[id] = mode
                    respond(if (status == HttpStatusCode.OK) doors() else "{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                else -> {
                    requests += "GET ${req.url.encodedPath}"
                    respond(doors(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
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
        eventually("the first read") { h.repository.doors.value.doors.size == 2 }
        assertEquals("GET /doors", h.requests.first())
    }

    private fun Harness.shown(id: String) = repository.doors.value.doors.single { it.id == id }.notify

    @Test
    fun aSettingShowsAtOnceAndAFailureTakesBackOnlyThatDoor() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.65:8971"))
        h.repository.refresh().getOrThrow()
        val gate = CompletableDeferred<Unit>()
        h.putGates += gate
        h.putStatuses += HttpStatusCode.BadGateway
        val failing = async { h.repository.setNotify("0x01", DoorNotify.ALWAYS) }
        eventually("the setting shown before the relay answers") { h.shown("0x01") == DoorNotify.ALWAYS }
        val other = async { h.repository.setNotify("0x02", DoorNotify.NEVER) }
        eventually("the other door's choice shown too") { h.shown("0x02") == DoorNotify.NEVER }
        gate.complete(Unit)
        assertTrue(failing.await().isFailure)
        assertTrue(other.await().isSuccess)
        assertEquals(DoorNotify.AWAY, h.shown("0x01"), "the failed choice is taken back")
        assertEquals(DoorNotify.NEVER, h.shown("0x02"), "and only that one")
    }

    @Test
    fun twoQuickChoicesReachTheRelayInOrderAndTheLastOneStays() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.65:8971"))
        h.repository.refresh().getOrThrow()
        val gate = CompletableDeferred<Unit>()
        h.putGates += gate
        h.putStatuses += HttpStatusCode.BadGateway
        val first = async { h.repository.setNotify("0x01", DoorNotify.ALWAYS) }
        eventually("the first save on its way") { h.requests.lastOrNull() == "PUT 0x01 always" }
        val second = async { h.repository.setNotify("0x01", DoorNotify.NEVER) }
        eventually("the second choice shown") { h.shown("0x01") == DoorNotify.NEVER }
        assertEquals("PUT 0x01 always", h.requests.last(), "the second save waits for the first")
        gate.complete(Unit)
        assertTrue(first.await().isFailure)
        assertTrue(second.await().isSuccess)
        assertEquals(listOf("PUT 0x01 always", "PUT 0x01 never"), h.requests.filter { it.startsWith("PUT") })
        assertEquals(DoorNotify.NEVER, h.shown("0x01"), "a superseded choice failing takes nothing back")
        assertEquals("never", h.notify["0x01"])
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
