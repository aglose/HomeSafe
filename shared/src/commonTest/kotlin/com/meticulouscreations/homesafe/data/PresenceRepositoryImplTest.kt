package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceRepositoryImplTest {

    private class FakeConnection(url: String?) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics() = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
    }

    private class FakeTokens(override val isSupported: Boolean, private val value: String?) : PushTokenProvider {
        override suspend fun token() = value
    }

    /** A fake relay that remembers each device's `away` and answers `/presence` from it. */
    private class Harness(scope: TestScope, supported: Boolean = true, token: String? = "tok-pixel") {
        val away = mutableMapOf("tok-pixel" to false, "tok-iphone" to true)
        val requests = mutableListOf<String>()
        var bodies = mutableListOf<String>()
        private val engine = MockEngine { req ->
            requests += "${req.method.value} ${req.url.encodedPath}${req.url.encodedQuery.let { if (it.isBlank()) "" else "?$it" }}"
            check(req.url.port == 8787) { "relay port" }
            when {
                req.method == HttpMethod.Put && req.url.encodedPath.endsWith("/presence") -> {
                    val token = req.url.encodedPath.removePrefix("/devices/").removeSuffix("/presence")
                    val body = req.bodyText().also { bodies += it }
                    away[token] = "true" in body
                    respond(presenceJson(token), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                req.url.encodedPath == "/presence" ->
                    respond(presenceJson(req.url.parameters["token"]), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        private fun presenceJson(thisToken: String?): String {
            val devices = away.entries.joinToString(",") { (token, isAway) ->
                """{"name":"${if (token == "tok-pixel") "Google Pixel" else "iPhone"}","platform":"${if (token == "tok-pixel") "android" else "ios"}","away":$isAway,"away_updated":${if (isAway) "1700000000.5" else "null"},"this_device":${token == thisToken}}"""
            }
            return """{"devices":[$devices],"everyone_away":${away.values.all { it }}}"""
        }

        private fun HttpRequestData.bodyText(): String = (body as TextContent).text

        private val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val connection = FakeConnection("http://192.168.68.55:8971")
        val repository = PresenceRepositoryImpl(
            relayApi = PushRelayApi(client),
            tokenProvider = FakeTokens(supported, token),
            connectionRepository = connection,
            appScope = scope.backgroundScope,
            pollIntervalMs = 100,
        )
    }

    private suspend fun TestScope.eventually(what: String, cond: suspend () -> Boolean) {
        repeat(200) { advanceUntilIdle(); if (cond()) return; withContext(Dispatchers.Default) { delay(25) } }
        fail("Timed out waiting for $what")
    }

    private suspend fun TestScope.settle() {
        repeat(8) { advanceUntilIdle(); withContext(Dispatchers.Default) { delay(25) } }
    }

    @Test
    fun pollsOnlyWhileObservedAndStopsWhenNobodyIsLooking() = runTest {
        val h = Harness(this)
        settle()
        assertTrue(h.requests.isEmpty(), "nothing collects presence yet, so the relay is left alone")
        assertEquals(HouseholdPresence.EMPTY, h.repository.presence.value)

        val collector: Job = backgroundScope.launch { h.repository.presence.collect {} }
        eventually("the first read") { h.repository.presence.value.devices.size == 2 }
        assertEquals("GET /presence?token=tok-pixel", h.requests.first())
        val snapshot = h.repository.presence.value
        assertFalse(snapshot.everyoneAway)
        assertEquals("Google Pixel", snapshot.thisDevice?.name)
        assertFalse(snapshot.thisDevice!!.away)
        assertEquals(1_700_000_000.5, snapshot.devices.single { it.platform == "ios" }.updatedEpochSeconds)
        eventually("a re-poll") { h.requests.size >= 2 }

        collector.cancel()
        settle()
        val quiet = h.requests.size
        settle()
        assertEquals(quiet, h.requests.size, "no requests once the last collector is gone")
    }

    @Test
    fun settingAwayPutsThisDevicesTokenAndAdoptsTheRelaysAnswer() = runTest {
        val h = Harness(this)
        val result = h.repository.setThisDeviceAway(true)
        assertTrue(result.isSuccess, result.toString())
        assertEquals(listOf("PUT /devices/tok-pixel/presence"), h.requests)
        assertEquals("""{"away":true}""", h.bodies.single())
        val presence = h.repository.presence.value
        assertTrue(presence.everyoneAway, "both phones away now")
        assertTrue(presence.thisDevice!!.away)

        assertTrue(h.repository.setThisDeviceAway(false).isSuccess)
        assertFalse(h.repository.presence.value.everyoneAway)
    }

    @Test
    fun cannotSetPresenceWithoutAPushIdentityOrAServer() = runTest {
        val unsupported = Harness(this, supported = false)
        assertTrue(unsupported.repository.setThisDeviceAway(true).isFailure)
        assertTrue(unsupported.requests.isEmpty())

        val noToken = Harness(this, token = null)
        assertTrue(noToken.repository.setThisDeviceAway(true).isFailure)
        assertTrue(noToken.requests.isEmpty())

        val offline = Harness(this)
        offline.connection.currentServerUrl.value = null
        assertTrue(offline.repository.setThisDeviceAway(true).isFailure)
        assertTrue(offline.repository.refresh().isFailure)
        assertTrue(offline.requests.isEmpty())
    }

    @Test
    fun refreshReadsNowAndSignOutForgetsTheHousehold() = runTest {
        val h = Harness(this)
        assertTrue(h.repository.refresh().isSuccess)
        assertEquals(2, h.repository.presence.value.devices.size)

        backgroundScope.launch { h.repository.presence.collect {} }
        h.connection.currentServerUrl.value = null
        eventually("sign-out clears presence") { h.repository.presence.value == HouseholdPresence.EMPTY }
    }
}
