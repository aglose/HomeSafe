package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.SleepStageKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is said to the relay and heard back, as `/fitness/health` in `relay/relay.py` has it. */
class HealthRelayApiImplTest {
    private var seen: HttpRequestData? = null

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, answer: String): HealthRelayApi {
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
            seen = request
            respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HealthRelayApiImpl(HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    @Test
    fun aSnapshotIsPutOnTheRelaysPort() = runTest {
        val answer = api(answer = """{"tracks":{"r1":"print"}}""").send(URL, HealthSendRequest("phone", HealthSnapshot(at = 5, granted = listOf("STEPS"), steps = mapOf(100L to 2L)), mapOf("r1" to "print")))
        assertEquals(mapOf("r1" to "print"), answer.tracks)
        val request = seen!!
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/fitness/health", request.url.encodedPath)
        assertEquals(8787, request.url.port)
        val sent = (request.body as TextContent).text
        assertTrue("\"device\":\"phone\"" in sent, sent)
        assertTrue("\"100\":2" in sent, sent)
    }

    @Test
    fun aTrackIsPutByItsId() = runTest {
        assertTrue(api(answer = """{"kept":true}""").sendTrack(URL, HealthTrackRequest("r1", "print", HealthTrackRows(route = listOf(RoutePointRow(1.0, 2.0))))))
        assertEquals("/fitness/health/track", seen!!.url.encodedPath)
        assertEquals(HttpMethod.Put, seen!!.method)
    }

    @Test
    fun clearingSaysWhichInstallAsks() = runTest {
        api(answer = """{"cleared":true}""").clear(URL, "phone")
        assertEquals(HttpMethod.Delete, seen!!.method)
        assertEquals("phone", seen!!.url.parameters["device"])
    }

    @Test
    fun noSnapshotIsNull() = runTest {
        assertNull(api(answer = """{"snapshot":null}""").read(URL))
    }

    @Test
    fun aTrackIsAskedForByIdInTheQuery() = runTest {
        val track = api(answer = """{"track":{"heart":[{"t":1,"v":150.0}]}}""").readTrack(URL, "a id/with odd bits")
        assertEquals(listOf(SampleRow(1, 150.0)), track?.heart)
        assertEquals("a id/with odd bits", seen!!.url.parameters["id"])
        assertEquals("/fitness/health/track", seen!!.url.encodedPath)
    }

    @Test
    fun noTrackIsNull() = runTest {
        assertNull(api(answer = """{"track":null}""").readTrack(URL, "r1"))
    }

    @Test
    fun aSessionThatHasLapsedIsThrown() = runTest {
        assertFailsWith<IllegalStateException> { api(HttpStatusCode.Unauthorized, "{}").read(URL) }
    }

    @Test
    fun whatALaterVersionWroteIsReadAsFarAsItCanBe() = runTest {
        val snapshot = api(
            answer = """{"snapshot":{"at":5,"granted":["STEPS","SOMETHING_NEW"],"new":1,"sleep":[{"start":1,"end":2,"stages":[{"start":1,"end":2,"kind":"DREAMING"}]}],"runs":[{"id":"r","start":1,"end":2,"route":"BY_POST"}]}}""",
        ).read(URL)!!.toReadings()
        assertEquals(setOf(HealthKind.STEPS), snapshot.granted)
        assertEquals(SleepStageKind.ASLEEP, snapshot.sleep.single().stages.single().kind)
        assertEquals("r", snapshot.runs.single().id)
    }

    private companion object {
        const val URL = "http://frigate.test"
    }
}
