package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.UptimeCheckKind
import com.meticulouscreations.homesafe.domain.model.UptimeOutage
import com.meticulouscreations.homesafe.domain.model.UptimeState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The relay's uptime record, as its `relay.py` answers `/status/data`. */
class PushRelayApiUptimeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, respondWith: String, seen: MutableList<String> = mutableListOf()): PushRelayApi {
        val engine = MockEngine { req ->
            seen += req.url.toString()
            respond(respondWith, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PushRelayApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } })
    }

    // What the relay answered for an hour in twelve spans, 2026-10-04, trimmed to three checks.
    private val body = """{"since": 1791144000.0, "until": 1791147600.0, "bucket_seconds": 300.0, "sample_seconds": 60, "recording_since": 1791100000,
        "checks": [
          {"key": "server", "states": "uuuuuuuuuuuu", "up_fraction": 1.0, "down_seconds": 0, "up": true, "name": "Server running"},
          {"key": "internet", "states": "uuuudxuuuuun", "up_fraction": 0.9, "down_seconds": 360, "up": false, "name": "Internet"},
          {"key": "zigbee", "states": "nnnnnnnnnnnn", "up_fraction": null, "down_seconds": 0, "up": null, "name": "Zigbee"}],
        "devices": [{"name": "pixel-10-pro-xl", "os": "android", "online": false, "last_seen": 1791091637.1, "states": "xxxxxxxxxxxx"},
                    {"name": "iphone-15-pro", "os": "", "online": null, "last_seen": null, "states": "uuuuuuuuuuuu"}],
        "outages": [{"check": "internet", "start": 1791145260, "end": null, "seconds": 2340}, {"check": "server", "start": 1791144300, "end": 1791144600, "seconds": 300}]}"""

    @Test
    fun theRecordIsReadFromTheRelayOnWhicheverAddressTheAppIsUsing() = runTest {
        val seen = mutableListOf<String>()
        val uptime = api(respondWith = body, seen = seen).getUptime("http://192.168.68.65:8971", hours = 24).getOrThrow()
        assertEquals("http://192.168.68.65:8787/status/data?hours=24", seen.single(), "the home network's address, the relay's port")
        assertEquals(1_791_144_000L, uptime.sinceEpochSeconds)
        assertEquals(1_791_147_600L, uptime.untilEpochSeconds)
        assertEquals(1_791_100_000L, uptime.recordingSinceEpochSeconds)
        assertEquals(1_791_144_000L + 5 * 300, uptime.bucketStart(5))
    }

    @Test
    fun eachCheckCarriesAStatePerSpan() = runTest {
        val uptime = api(respondWith = body).getUptime("http://100.99.163.71:8971", hours = 1).getOrThrow()
        val internet = uptime.checks.single { it.key == "internet" }
        assertEquals(UptimeCheckKind.Internet, internet.kind)
        assertEquals(
            List(4) { UptimeState.Up } + UptimeState.Partial + UptimeState.Down + List(5) { UptimeState.Up } + UptimeState.NotMeasured,
            internet.states,
        )
        assertEquals(0.9, internet.upFraction)
        assertEquals(360L, internet.downSeconds)
        assertEquals(false, internet.isUpNow)
        assertEquals(listOf("internet"), uptime.downNow.map { it.key })
    }

    @Test
    fun aCheckThisBuildHasNoWordsForKeepsTheRelaysName() = runTest {
        val zigbee = api(respondWith = body).getUptime("http://frigate:8971", hours = 1).getOrThrow().checks.single { it.key == "zigbee" }
        assertNull(zigbee.kind)
        assertEquals("Zigbee", zigbee.serverName)
        assertNull(zigbee.upFraction)
        assertNull(zigbee.isUpNow)
    }

    @Test
    fun devicesAndOutagesComeThrough() = runTest {
        val uptime = api(respondWith = body).getUptime("http://frigate:8971", hours = 1).getOrThrow()
        val pixel = uptime.devices.first()
        assertEquals("pixel-10-pro-xl", pixel.name)
        assertEquals(false, pixel.isOnline)
        assertEquals(1_791_091_637L, pixel.lastSeenEpochSeconds)
        assertTrue(pixel.states.all { it == UptimeState.Down })
        assertNull(uptime.devices.last().isOnline)
        assertNull(uptime.devices.last().lastSeenEpochSeconds)
        assertEquals(
            listOf(UptimeOutage("internet", 1_791_145_260L, null, 2_340), UptimeOutage("server", 1_791_144_300L, 1_791_144_600L, 300)),
            uptime.outages,
        )
    }

    @Test
    fun aRelayFromBeforeTheRecordFailsWithItsStatus() = runTest {
        val failure = api(status = HttpStatusCode.NotFound, respondWith = """{"detail":"Not Found"}""").getUptime("http://frigate:8971", hours = 24).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("404"), failure?.message)
    }

    @Test
    fun anEmptyRecordIsStillARecord() = runTest {
        val uptime = api(respondWith = """{"since": 1.0, "until": 3601.0, "bucket_seconds": 300.0, "recording_since": null}""").getUptime("http://frigate:8971", hours = 1).getOrThrow()
        assertTrue(uptime.checks.isEmpty() && uptime.devices.isEmpty() && uptime.outages.isEmpty())
        assertNull(uptime.recordingSinceEpochSeconds)
    }
}
