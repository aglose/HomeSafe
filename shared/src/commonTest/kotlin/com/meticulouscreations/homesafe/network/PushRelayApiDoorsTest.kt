package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorEvent
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The door sensors, as relay.py's `/doors` answers. */
class PushRelayApiDoorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class Seen(val method: HttpMethod, val url: String, val auth: String?, val body: String?)

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, respondWith: String, seen: MutableList<Seen> = mutableListOf()): PushRelayApi {
        val engine = MockEngine { req ->
            seen += Seen(req.method, req.url.toString(), req.headers[HttpHeaders.Authorization], (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString())
            respond(respondWith, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PushRelayApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } })
    }

    // As the relay answered with one ThirdReality sensor paired, 2026-10-17.
    private val body = """{"doors": [{"id": "0x282c02bfffe6aa01", "name": "Front door", "model": "Third Reality 3RDS17BZ", "open": true, "changed": 1792301831.74,
        "battery": 90, "battery_low": null, "link": 140, "online": true, "last_seen": 1792301831.74, "notify": "always"},
        {"id": "0x282c02bfffe6aa02", "name": "", "model": null, "open": null, "changed": null, "battery": null, "battery_low": null, "link": null,
        "online": null, "last_seen": null, "notify": "away"}],
        "events": [{"door": "0x282c02bfffe6aa01", "open": true, "at": 1792301831.34, "pushed": "away"}], "broker": true, "bridge": true, "away": false}"""

    @Test
    fun theDoorsAreReadAsThisInstall() = runTest {
        val seen = mutableListOf<Seen>()
        val snapshot = api(respondWith = body, seen = seen).getDoors("http://100.99.163.71:8971", "device-1", "secret-1").getOrThrow()
        assertEquals("http://100.99.163.71:8787/doors?device=device-1&days=7", seen.single().url)
        assertEquals("Bearer secret-1", seen.single().auth)
        assertEquals(
            Door(
                "0x282c02bfffe6aa01", "Front door", open = true, changedAtMillis = 1_792_301_831_740, battery = 90, linkQuality = 140, online = true,
                notify = DoorNotify.ALWAYS, model = "Third Reality 3RDS17BZ",
            ),
            snapshot.doors[0],
        )
        assertEquals(Door("0x282c02bfffe6aa02", "0x282c02bfffe6aa02", open = null), snapshot.doors[1], "an unnamed sensor goes by its address")
        assertEquals(listOf(DoorEvent("0x282c02bfffe6aa01", open = true, atMillis = 1_792_301_831_340, pushed = "away")), snapshot.events)
        assertTrue(snapshot.listening)
        assertTrue(snapshot.supported)
    }

    @Test
    fun aRelayFromBeforeDoorSensorsAnswers404() = runTest {
        val snapshot = api(status = HttpStatusCode.NotFound, respondWith = """{"detail": "Not Found"}""").getDoors("http://192.168.68.65:8971").getOrThrow()
        assertFalse(snapshot.supported)
    }

    @Test
    fun aBrokerThatIsDownIsNotListening() = runTest {
        val snapshot = api(respondWith = """{"doors": [], "events": [], "broker": false, "bridge": false}""").getDoors("http://192.168.68.65:8971").getOrThrow()
        assertFalse(snapshot.listening)
    }

    @Test
    fun aDoorsSettingIsPut() = runTest {
        val seen = mutableListOf<Seen>()
        api(respondWith = body, seen = seen).setDoorNotify("http://192.168.68.65:8971", "0x282c02bfffe6aa01", DoorNotify.NEVER).getOrThrow()
        assertEquals(HttpMethod.Put, seen.single().method)
        assertEquals("http://192.168.68.65:8787/doors/0x282c02bfffe6aa01", seen.single().url)
        assertEquals("""{"notify":"never"}""", seen.single().body)
    }

    @Test
    fun aRefusalFails() = runTest {
        assertTrue(api(status = HttpStatusCode.Unauthorized, respondWith = "{}").setDoorNotify("http://192.168.68.65:8971", "x", DoorNotify.AWAY).isFailure)
    }
}
