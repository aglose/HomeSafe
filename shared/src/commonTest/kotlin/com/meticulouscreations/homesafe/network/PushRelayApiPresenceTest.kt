package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.NotTrackedReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The relay decides which two phones away mode follows (one Android phone and one iPhone, each a
 * release build on real hardware). The app only has to carry that verdict faithfully — and tell
 * the relay who it is, which phone, and which build, in the first place.
 */
class PushRelayApiPresenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class Seen(
        val bodies: MutableList<String> = mutableListOf(),
        val authorizations: MutableList<String?> = mutableListOf(),
        val requests: MutableList<String> = mutableListOf(),
    )

    private fun api(respondWith: String): Pair<PushRelayApi, Seen> {
        val seen = Seen()
        val engine = MockEngine { req ->
            seen.bodies += (req.body as? TextContent)?.text.orEmpty()
            seen.authorizations += req.headers[HttpHeaders.Authorization]
            seen.requests += "${req.method.value} ${req.url.encodedPath}${req.url.encodedQuery.let { if (it.isBlank()) "" else "?$it" }}"
            respond(respondWith, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return PushRelayApi(client) to seen
    }

    @Test
    fun registrationTellsTheRelayWhoThisIsAndGetsASecretBack() = runTest {
        val (api, seen) = api("""{"ok":true,"device_id":"dev-1","secret":"s3cret"}""")
        val registration = DeviceRegistration(deviceId = "dev-1", token = "fcm-tok", platform = "android", name = "Pixel 10", build = "release")
        val credentials = api.registerDevice("http://frigate:8971", registration).getOrThrow()
        assertEquals("dev-1", credentials.deviceId)
        assertEquals("s3cret", credentials.secret)
        val body = seen.bodies.single()
        assertTrue(""""device_id":"dev-1"""" in body, body)
        assertTrue(""""build":"release"""" in body, body)
        assertTrue(""""token":"fcm-tok"""" in body, body)
        assertEquals(null, seen.authorizations.single(), "first registration: nothing to bear yet, the cookie does it")
    }

    @Test
    fun anIphoneRegistersWithNoTokenAndAReRegistrationBearsItsSecret() = runTest {
        val (api, seen) = api("""{"ok":true,"device_id":"dev-ios","secret":"s3cret"}""")
        val registration = DeviceRegistration(deviceId = "dev-ios", token = null, platform = "ios", name = "Apple iPhone", build = "debug")
        assertTrue(api.registerDevice("http://frigate:8971", registration, secret = "s3cret").isSuccess)
        assertTrue(""""token":null""" in seen.bodies.single(), seen.bodies.single())
        assertEquals("Bearer s3cret", seen.authorizations.single())
    }

    @Test
    fun theRelaySaysWhetherItCanPushToTheToken() = runTest {
        val registration = DeviceRegistration(deviceId = "dev-ios", token = "a1".repeat(32), platform = "ios", name = "Apple iPhone", build = "release")
        val (noApns, _) = api("""{"ok":true,"device_id":"dev-ios","secret":"s3cret","push":false}""")
        assertEquals(false, noApns.registerDevice("http://frigate:8971", registration).getOrThrow().push, "no APNs key on the relay yet")
        val (apns, _) = api("""{"ok":true,"device_id":"dev-ios","secret":"s3cret","push":true}""")
        assertEquals(true, apns.registerDevice("http://frigate:8971", registration).getOrThrow().push)
        val (older, _) = api("""{"ok":true,"device_id":"dev-ios","secret":"s3cret"}""")
        assertEquals(false, older.registerDevice("http://frigate:8971", registration).getOrThrow().push, "a relay too old to say can't push to an iPhone")
        val android = DeviceRegistration(deviceId = "dev-1", token = "fcm-tok", platform = "android", name = "Pixel 10", build = "release")
        assertEquals(true, older.registerDevice("http://frigate:8971", android).getOrThrow().push, "but it can to Android, as always")
    }

    @Test
    fun registrationSaysWhichPhoneThisIsAndWhetherItIsAnEmulator() = runTest {
        val (api, seen) = api("""{"ok":true,"device_id":"dev-1","secret":"s3cret"}""")
        val registration = DeviceRegistration(
            deviceId = "dev-1",
            token = null,
            platform = "android",
            name = "Pixel 10",
            build = "release",
            hardwareId = "a1b2c3",
            emulator = true,
        )
        api.registerDevice("http://frigate:8971", registration).getOrThrow()
        val body = seen.bodies.single()
        assertTrue(""""hardware_id":"a1b2c3"""" in body, body)
        assertTrue(""""emulator":true""" in body, body)
    }

    @Test
    fun theTrackedPhonesComeBackWithThisOneFlagged() = runTest {
        val (api, seen) = api(
            """{"devices":[
                 {"id":"dev-1","name":"Google Pixel 10 Pro XL","platform":"android","away":true,"away_updated":1.0,"this_device":true,"counts":true},
                 {"id":"dev-2","name":"Apple iPhone","platform":"ios","away":false,"this_device":false,"counts":true,"pending_away":true}
               ],"everyone_away":false,"home":{"lat":40.5,"lng":-80.25,"radius_m":150},"not_tracked":null}""",
        )
        val presence = api.getPresence("http://frigate:8971", "dev-1", secret = "s3cret").getOrThrow()
        assertEquals(listOf("dev-1", "dev-2"), presence.devices.map { it.id })
        assertTrue(presence.thisDevice!!.away)
        assertTrue(presence.devices.single { it.id == "dev-2" }.pendingAway)
        assertNull(presence.thisDeviceNotTracked)
        assertEquals(40.5, presence.home?.latitude)
        assertEquals(150.0, presence.home?.radiusMeters)
        assertEquals("Bearer s3cret", seen.authorizations.single())
    }

    @Test
    fun anInstallTheRelayDoesNotTrackIsToldWhy() = runTest {
        val reasons = mapOf(
            "debug" to NotTrackedReason.DEBUG_BUILD,
            "emulator" to NotTrackedReason.EMULATOR,
            "taken" to NotTrackedReason.ANOTHER_PHONE,
            "old_app" to NotTrackedReason.UNKNOWN,
            "unregistered" to NotTrackedReason.UNKNOWN,
        )
        reasons.forEach { (wire, reason) ->
            val (api, _) = api("""{"devices":[{"id":"dev-1","name":"Pixel","platform":"android","away":false}],"everyone_away":false,"not_tracked":"$wire"}""")
            val presence = api.getPresence("http://frigate:8971", "dev-debug").getOrThrow()
            assertNull(presence.thisDevice)
            assertEquals(reason, presence.thisDeviceNotTracked, wire)
        }
    }

    @Test
    fun eachDeviceCarriesItsIdAndWhenTheRelayLastHeardFromIt() = runTest {
        val (api, _) = api(
            """{"devices":[{"id":"dev-old","name":"Apple iPhone","platform":"ios","away":false,"this_device":false,"last_seen":1700000000.5}],
               "everyone_away":false}""",
        )
        val device = api.getPresence("http://frigate:8971", "dev-1").getOrThrow().devices.single()
        assertEquals("dev-old", device.id)
        assertEquals(1_700_000_000.5, device.lastSeenEpochSeconds)
    }

    @Test
    fun aRelayThatPredatesTheNewFieldsStillWorks() = runTest {
        val (api, _) = api(
            """{"devices":[{"name":"Pixel","platform":"android","away":false,"this_device":true,"counts":false,"build":"debug"}],
               "everyone_away":false,"authority":null}""",
        )
        val presence = api.getPresence("http://frigate:8971", "dev-1").getOrThrow()
        assertFalse(presence.devices.single().pendingAway)
        assertEquals(null, presence.devices.single().id, "no id: the row simply can't be removed from the app")
        assertEquals(null, presence.devices.single().lastSeenEpochSeconds)
        assertEquals(null, presence.home)
        assertNull(presence.thisDeviceNotTracked)
    }
}
