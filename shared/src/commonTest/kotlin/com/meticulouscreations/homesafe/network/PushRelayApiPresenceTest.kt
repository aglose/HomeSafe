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
 * the relay which build it is in the first place.
 */
class PushRelayApiPresenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun api(respondWith: String, seen: MutableList<String> = mutableListOf()): Pair<PushRelayApi, MutableList<String>> {
        val engine = MockEngine { req ->
            seen += (req.body as? TextContent)?.text.orEmpty()
            respond(respondWith, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return PushRelayApi(client) to seen
    }

    @Test
    fun registrationTellsTheRelayWhichBuildThisIs() = runTest {
        val (api, bodies) = api("""{"ok":true}""")
        assertTrue(api.registerDevice("http://frigate:8971", "tok", platform = "android", name = "Pixel 10", build = "release").isSuccess)
        assertTrue(""""build":"release"""" in bodies.single(), bodies.single())
    }

    @Test
    fun aDebugPhoneComesBackFlaggedAndDoesNotDecidePresence() = runTest {
        val (api, _) = api(
            """{"devices":[
                 {"name":"Google Pixel 10 Pro XL","platform":"android","away":true,"away_updated":1.0,"this_device":true,"counts":true},
                 {"name":"Google sdk_gphone64_arm64","platform":"android","away":false,"this_device":false,"counts":false}
               ],"everyone_away":true}""",
        )
        val presence = api.getPresence("http://frigate:8971", "tok").getOrThrow()
        assertEquals(2, presence.devices.size)
        assertTrue(presence.everyoneAway, "the relay's verdict stands: the emulator doesn't hold the house open")
        assertTrue(presence.thisDevice!!.countsForAway)
        assertFalse(presence.devices.single { it.name.startsWith("Google sdk") }.countsForAway)
        assertEquals(listOf("Google Pixel 10 Pro XL"), presence.countingDevices.map { it.name })
    }

    @Test
    fun aRelayThatPredatesTheFlagStillCountsItsDevices() = runTest {
        val (api, _) = api("""{"devices":[{"name":"Pixel","platform":"android","away":false,"this_device":true}],"everyone_away":false}""")
        val presence = api.getPresence("http://frigate:8971").getOrThrow()
        assertTrue(presence.devices.single().countsForAway)
    }
}
