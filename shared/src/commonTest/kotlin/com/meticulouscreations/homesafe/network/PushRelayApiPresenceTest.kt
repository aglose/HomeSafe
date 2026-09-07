package com.meticulouscreations.homesafe.network

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
import kotlin.test.assertTrue

/**
 * The relay decides which phones may declare the house empty (release installs, plus iOS while
 * there's no iOS release channel). The app only has to carry that verdict faithfully — and tell
 * the relay who it is, and which build, in the first place.
 */
class PushRelayApiPresenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class Seen(val bodies: MutableList<String> = mutableListOf(), val authorizations: MutableList<String?> = mutableListOf())

    private fun api(respondWith: String): Pair<PushRelayApi, Seen> {
        val seen = Seen()
        val engine = MockEngine { req ->
            seen.bodies += (req.body as? TextContent)?.text.orEmpty()
            seen.authorizations += req.headers[HttpHeaders.Authorization]
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
    fun aDebugPhoneComesBackFlaggedAndDoesNotDecidePresence() = runTest {
        val (api, seen) = api(
            """{"devices":[
                 {"name":"Google Pixel 10 Pro XL","platform":"android","away":true,"away_updated":1.0,"this_device":true,"counts":true},
                 {"name":"Google sdk_gphone64_arm64","platform":"android","away":false,"this_device":false,"counts":false,"pending_away":true}
               ],"everyone_away":true,"home":{"lat":40.5,"lng":-80.25,"radius_m":150}}""",
        )
        val presence = api.getPresence("http://frigate:8971", "dev-1", secret = "s3cret").getOrThrow()
        assertEquals(2, presence.devices.size)
        assertTrue(presence.everyoneAway, "the relay's verdict stands: the emulator doesn't hold the house open")
        assertTrue(presence.thisDevice!!.countsForAway)
        val emulator = presence.devices.single { it.name.startsWith("Google sdk") }
        assertFalse(emulator.countsForAway)
        assertTrue(emulator.pendingAway)
        assertEquals(listOf("Google Pixel 10 Pro XL"), presence.countingDevices.map { it.name })
        assertEquals(40.5, presence.home?.latitude)
        assertEquals(150.0, presence.home?.radiusMeters)
        assertEquals("Bearer s3cret", seen.authorizations.single())
    }

    @Test
    fun aRelayThatPredatesTheNewFieldsStillWorks() = runTest {
        val (api, _) = api("""{"devices":[{"name":"Pixel","platform":"android","away":false,"this_device":true}],"everyone_away":false}""")
        val presence = api.getPresence("http://frigate:8971", "dev-1").getOrThrow()
        assertTrue(presence.devices.single().countsForAway)
        assertFalse(presence.devices.single().pendingAway)
        assertEquals(null, presence.home)
    }
}
