package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceSource
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceRepositoryImplTest {

    private class FakeConnection(url: String?, recent: ConnectionRecord? = null) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(recent)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
    }

    /**
     * A fake relay that remembers each device's `away` and answers `/presence` from it. Devices
     * are known by id; this install is "dev-pixel", already registered with secret "s3cret".
     */
    private class Harness(scope: TestScope, connection: ConnectionRepository, failOn: Set<String> = emptySet()) {
        val away = mutableMapOf("dev-pixel" to false, "dev-iphone" to true)
        var home: HomeLocation? = null
        val requests = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        var bodies = mutableListOf<String>()
        private val engine = MockEngine { req ->
            requests += "${req.method.value} ${req.url.host}${req.url.encodedPath}${req.url.encodedQuery.let { if (it.isBlank()) "" else "?$it" }}"
            authorizations += req.headers[HttpHeaders.Authorization]
            check(req.url.port == 8787) { "relay port" }
            when {
                req.url.host in failOn -> respond("", HttpStatusCode.BadGateway)

                req.method == HttpMethod.Put && req.url.encodedPath.endsWith("/presence") -> {
                    val id = req.url.encodedPath.removePrefix("/devices/").removeSuffix("/presence")
                    val body = req.bodyText().also { bodies += it }
                    away[id] = "\"away\":true" in body
                    respond(presenceJson(id), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                req.method == HttpMethod.Put && req.url.encodedPath == "/home" -> {
                    bodies += req.bodyText()
                    home = HomeLocation(40.0, -80.0, 150.0)
                    respond(presenceJson(null), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                req.method == HttpMethod.Delete && req.url.encodedPath == "/home" -> {
                    home = null
                    respond(presenceJson(null), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                req.url.encodedPath == "/presence" ->
                    respond(presenceJson(req.url.parameters["device"]), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        private fun presenceJson(thisDevice: String?): String {
            val devices = away.entries.joinToString(",") { (id, isAway) ->
                """{"name":"${if (id == "dev-pixel") "Google Pixel" else "iPhone"}","platform":"${if (id == "dev-pixel") "android" else "ios"}","away":$isAway,"away_updated":${if (isAway) "1700000000.5" else "null"},"this_device":${id == thisDevice}}"""
            }
            val homeJson = home?.let { """{"lat":${it.latitude},"lng":${it.longitude},"radius_m":${it.radiusMeters}}""" } ?: "null"
            return """{"devices":[$devices],"everyone_away":${away.values.all { it }},"home":$homeJson}"""
        }

        private fun HttpRequestData.bodyText(): String = (body as TextContent).text

        private val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val dao = InMemorySettingsDao()
        val repository = PresenceRepositoryImpl(
            relayApi = PushRelayApi(client),
            identity = DeviceIdentityStore(dao),
            connectionRepository = connection,
            appScope = scope.backgroundScope,
            pollIntervalMs = 100,
        )
    }

    private suspend fun Harness.registered(): Harness = apply {
        dao.upsertDeviceIdentity(DeviceIdentityEntity(deviceId = "dev-pixel", secret = "s3cret"))
    }

    private suspend fun TestScope.eventually(what: String, cond: suspend () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (cond()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("Timed out waiting for $what")
    }

    private suspend fun TestScope.settle() {
        repeat(8) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(25) }
        }
    }

    @Test
    fun pollsOnlyWhileObservedAndStopsWhenNobodyIsLooking() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.55:8971")).registered()
        settle()
        assertTrue(h.requests.isEmpty(), "nothing collects presence yet, so the relay is left alone")
        assertEquals(HouseholdPresence.EMPTY, h.repository.presence.value)

        val collector: Job = backgroundScope.launch { h.repository.presence.collect {} }
        eventually("the first read") { h.repository.presence.value.devices.size == 2 }
        assertEquals("GET 192.168.68.55/presence?device=dev-pixel", h.requests.first())
        assertEquals("Bearer s3cret", h.authorizations.first(), "the install's secret rides along, so this works with no session too")
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
    fun settingAwayPutsThisDevicesIdAndAdoptsTheRelaysAnswer() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.55:8971")).registered()
        val result = h.repository.setThisDeviceAway(true)
        assertTrue(result.isSuccess, result.toString())
        assertEquals(listOf("PUT 192.168.68.55/devices/dev-pixel/presence"), h.requests)
        assertEquals("""{"away":true,"source":"manual","dwell_seconds":0}""", h.bodies.single())
        val presence = h.repository.presence.value
        assertTrue(presence.everyoneAway, "both phones away now")
        assertTrue(presence.thisDevice!!.away)

        assertTrue(h.repository.setThisDeviceAway(false).isSuccess)
        assertFalse(h.repository.presence.value.everyoneAway)
    }

    @Test
    fun aGeofenceExitArmsAwayWithItsDwellAndSource() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.55:8971")).registered()
        assertTrue(h.repository.setThisDeviceAway(true, PresenceSource.GEOFENCE, dwellSeconds = 600).isSuccess)
        assertEquals("""{"away":true,"source":"geofence","dwell_seconds":600}""", h.bodies.single())
    }

    @Test
    fun anIdentityIsMintedOnFirstUseWhenNoneWasRegisteredYet() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.55:8971")) // not registered(): no row at all
        assertTrue(h.repository.setThisDeviceAway(true).isSuccess)
        val id = h.dao.getDeviceIdentity()!!.deviceId
        assertTrue(id.length >= 32, "a UUID, kept for next time: $id")
        assertEquals("PUT 192.168.68.55/devices/$id/presence", h.requests.single())
        assertNull(h.authorizations.single(), "no secret yet, so the session cookie has to carry it")
    }

    @Test
    fun withNoLiveConnectionTheLastServerIsTriedRemoteAddressFirstThenLan() = runTest {
        val recent = ConnectionRecord(serverUrl = "http://100.99.163.71:8971", localUrl = "http://192.168.68.55:8971", connectedAtEpochMillis = 0)
        // Tailscale is down on the phone: the remote address fails, the LAN one answers.
        val h = Harness(this, FakeConnection(url = null, recent = recent), failOn = setOf("100.99.163.71")).registered()
        val result = h.repository.setThisDeviceAway(true, PresenceSource.GEOFENCE, 600)
        assertTrue(result.isSuccess, result.toString())
        assertEquals(
            listOf("PUT 100.99.163.71/devices/dev-pixel/presence", "PUT 192.168.68.55/devices/dev-pixel/presence"),
            h.requests,
        )
        assertEquals(listOf<String?>("Bearer s3cret", "Bearer s3cret"), h.authorizations, "a cold background wake has only the secret")
    }

    @Test
    fun settingHomeRidesOnTheSessionAndUpdatesTheSnapshot() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.55:8971")).registered()
        assertTrue(h.repository.setHome(HomeLocation(40.0, -80.0, 150.0)).isSuccess)
        assertEquals("PUT 192.168.68.55/home?device=dev-pixel", h.requests.single())
        assertEquals("""{"lat":40.0,"lng":-80.0,"radius_m":150.0}""", h.bodies.single())
        assertNull(h.authorizations.single(), "a user action: the cookie, not the device secret")
        assertEquals(HomeLocation(40.0, -80.0, 150.0), h.repository.presence.value.home)

        assertTrue(h.repository.setHome(null).isSuccess)
        assertEquals("DELETE 192.168.68.55/home?device=dev-pixel", h.requests.last())
        assertNull(h.repository.presence.value.home)
    }

    @Test
    fun cannotSetPresenceWithoutAnyServer() = runTest {
        val nowhere = Harness(this, FakeConnection(url = null, recent = null)).registered()
        assertTrue(nowhere.repository.setThisDeviceAway(true).isFailure)
        assertTrue(nowhere.requests.isEmpty())
    }
}
