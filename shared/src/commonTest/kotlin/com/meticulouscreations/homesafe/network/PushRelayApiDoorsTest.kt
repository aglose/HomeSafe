package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorEvent
import com.meticulouscreations.homesafe.domain.model.DoorJoin
import com.meticulouscreations.homesafe.domain.model.DoorJoinStatus
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorPairing
import com.meticulouscreations.homesafe.domain.model.DoorSpot
import com.meticulouscreations.homesafe.domain.model.FloorPlan
import com.meticulouscreations.homesafe.domain.model.PlanDoor
import com.meticulouscreations.homesafe.domain.model.PlanFloor
import com.meticulouscreations.homesafe.domain.model.PlanPoint
import com.meticulouscreations.homesafe.domain.model.PlanRoom
import com.meticulouscreations.homesafe.domain.model.PlanStairs
import com.meticulouscreations.homesafe.domain.model.PlanWall
import com.meticulouscreations.homesafe.domain.model.StairDirection
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_error_no_zigbee
import homesafe.shared.generated.resources.doors_error_zigbee_silent
import homesafe.shared.generated.resources.doors_rename_invalid
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
import kotlin.test.assertNull
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
        assertTrue(snapshot.answered)
    }

    @Test
    fun aReadCanWaitForTheNextChange() = runTest {
        val seen = mutableListOf<Seen>()
        val versioned = body.replace(""""away": false}""", """"away": false, "version": 1792301831741}""")
        val snapshot = api(respondWith = versioned, seen = seen)
            .getDoors("http://100.99.163.71:8971", "device-1", "secret-1", since = 1_792_301_831_740, waitSeconds = 25).getOrThrow()
        assertEquals("http://100.99.163.71:8787/doors?device=device-1&days=7&since=1792301831740&wait=25", seen.single().url)
        assertEquals(1_792_301_831_741, snapshot.version)
        assertEquals(null, api(respondWith = body).getDoors("http://100.99.163.71:8971").getOrThrow().version, "a relay from before the wait")
    }

    @Test
    fun aRelayFromBeforeDoorSensorsAnswers404() = runTest {
        val snapshot = api(status = HttpStatusCode.NotFound, respondWith = """{"detail": "Not Found"}""").getDoors("http://192.168.68.65:8971").getOrThrow()
        assertFalse(snapshot.supported)
        assertTrue(snapshot.answered)
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
    fun pairingIsPostedAndItsWindowAndWhatJoinedAreRead() = runTest {
        val seen = mutableListOf<Seen>()
        val pairing = body.replace(
            """"away": false}""",
            """"away": false, "pairing": {"open": true, "until": 1792301900.5, "joined": [
                {"id": "0x282c02bfffe6aa01", "name": "Front door", "status": "joined", "at": 1792301831.0},
                {"id": "0x282c02bfffe6aa09", "name": "0x282c02bfffe6aa09", "status": "interviewing", "at": 1792301832.0},
                {"id": "0x282c02bfffe6aa0a", "status": "leaving", "at": 1792301833.0}]}}""",
        )
        val snapshot = api(respondWith = pairing, seen = seen).setDoorPairing("http://192.168.68.65:8971", 254).getOrThrow()
        assertEquals(HttpMethod.Post, seen.single().method)
        assertEquals("http://192.168.68.65:8787/doors/pairing", seen.single().url)
        assertEquals("""{"seconds":254}""", seen.single().body)
        assertEquals(
            DoorPairing(
                open = true,
                untilMillis = 1_792_301_900_500,
                joined = listOf(
                    DoorJoin("0x282c02bfffe6aa01", "Front door", DoorJoinStatus.JOINED, 1_792_301_831_000),
                    DoorJoin("0x282c02bfffe6aa09", "0x282c02bfffe6aa09", DoorJoinStatus.INTERVIEWING, 1_792_301_832_000),
                ),
            ),
            snapshot.pairing,
            "a status this app doesn't know is left out",
        )
        assertNull(api(respondWith = body).getDoors("http://192.168.68.65:8971").getOrThrow().pairing, "a relay that can't pair")
    }

    @Test
    fun aDoorIsRenamedWithAPut() = runTest {
        val seen = mutableListOf<Seen>()
        api(respondWith = body, seen = seen).renameDoor("http://192.168.68.65:8971", "0x282c02bfffe6aa01", "Porch door").getOrThrow()
        assertEquals(HttpMethod.Put, seen.single().method)
        assertEquals("http://192.168.68.65:8787/doors/0x282c02bfffe6aa01/name", seen.single().url)
        assertEquals("""{"name":"Porch door"}""", seen.single().body)
    }

    @Test
    fun whyZigbee2MqttCouldntIsSaid() = runTest {
        suspend fun refusal(status: HttpStatusCode, detail: String) =
            api(status = status, respondWith = """{"detail": "$detail"}""").setDoorPairing("http://192.168.68.65:8971", 254).exceptionOrNull() as LocalizedException
        assertEquals(UiText.of(Res.string.doors_error_no_zigbee), refusal(HttpStatusCode.ServiceUnavailable, "The relay can't reach Zigbee2MQTT").text)
        assertEquals(UiText.of(Res.string.doors_error_zigbee_silent), refusal(HttpStatusCode.GatewayTimeout, "Zigbee2MQTT didn't answer").text)
        assertEquals(UiText.Verbatim("Zigbee2MQTT: Coordinator busy"), refusal(HttpStatusCode.BadGateway, "Zigbee2MQTT: Coordinator busy").text, "Zigbee2MQTT's own words")
        val invalid = api(status = HttpStatusCode.BadRequest, respondWith = """{"detail": "no"}""").renameDoor("http://192.168.68.65:8971", "x", "a/b").exceptionOrNull()
        assertEquals(UiText.of(Res.string.doors_rename_invalid), (invalid as LocalizedException).text)
    }

    @Test
    fun aRefusalFails() = runTest {
        assertTrue(api(status = HttpStatusCode.Unauthorized, respondWith = "{}").setDoorNotify("http://192.168.68.65:8971", "x", DoorNotify.AWAY).isFailure)
    }

    @Test
    fun theFloorPlanIsReadAndAFloorWithNoOutlineOrAShortWallIsLeftOut() = runTest {
        val seen = mutableListOf<Seen>()
        val body = """{"floors": [{"id": "ground", "name": "Ground floor", "outline": [[0, 0], [30, 0], [30, 40], [0, 40]],
            "rooms": [{"name": "Kitchen", "x": 8, "y": 10.5}], "stairs": [[15, 18], {"x": 4, "y": 5, "direction": "down", "facing": "w", "flight": "cellar"}], "walls": [[0, 13, 14, 13], [1, 2]],
            "doors": [{"name": "Back door", "from": [12, 0], "to": [15, 0], "swing": -1, "sensor": "0x01"}, {"from": [1], "to": [2, 0]}]},
            {"id": "attic", "outline": [[0, 0]]}]}"""
        val plan = api(respondWith = body, seen = seen).getFloorPlan("http://192.168.68.65:8971", "device-1", "secret-1").getOrThrow()
        assertEquals("http://192.168.68.65:8787/floorplan?device=device-1", seen.single().url)
        assertEquals(
            FloorPlan(
                listOf(
                    PlanFloor(
                        "ground",
                        "Ground floor",
                        listOf(PlanPoint(0f, 0f), PlanPoint(30f, 0f), PlanPoint(30f, 40f), PlanPoint(0f, 40f)),
                        listOf(PlanRoom("Kitchen", PlanPoint(8f, 10.5f))),
                        listOf(PlanStairs(PlanPoint(15f, 18f)), PlanStairs(PlanPoint(4f, 5f), StairDirection.DOWN, facingDegrees = 270, flight = "cellar")),
                        listOf(PlanWall(PlanPoint(0f, 13f), PlanPoint(14f, 13f))),
                        listOf(PlanDoor(PlanPoint(12f, 0f), PlanPoint(15f, 0f), swing = -1, name = "Back door", sensorId = "0x01")),
                    ),
                ),
            ),
            plan,
        )
    }

    @Test
    fun aBoxWithNoPlanAnswers404() = runTest {
        assertEquals(null, api(status = HttpStatusCode.NotFound, respondWith = """{"detail": "No floor plan on this box"}""").getFloorPlan("http://192.168.68.65:8971").getOrThrow())
    }

    @Test
    fun aDoorIsPlacedWithAPutAndTakenOffWithADelete() = runTest {
        val seen = mutableListOf<Seen>()
        val placed = api(respondWith = body.replace("\"notify\": \"always\"}", "\"notify\": \"always\", \"spot\": {\"floor\": \"ground\", \"x\": 12.5, \"y\": 39}}"), seen = seen)
            .setDoorSpot("http://192.168.68.65:8971", "0x282c02bfffe6aa01", DoorSpot("ground", 12.5f, 39f)).getOrThrow()
        assertEquals(HttpMethod.Put, seen.single().method)
        assertEquals("http://192.168.68.65:8787/doors/0x282c02bfffe6aa01/spot", seen.single().url)
        assertEquals("""{"floor":"ground","x":12.5,"y":39.0}""", seen.single().body)
        assertEquals(DoorSpot("ground", 12.5f, 39f), placed.doors[0].spot)

        seen.clear()
        api(respondWith = body, seen = seen).setDoorSpot("http://192.168.68.65:8971", "0x282c02bfffe6aa01", null).getOrThrow()
        assertEquals(HttpMethod.Delete, seen.single().method)
    }
}
