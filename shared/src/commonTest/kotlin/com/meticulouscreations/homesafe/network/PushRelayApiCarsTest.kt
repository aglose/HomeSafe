package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.CarCheck
import com.meticulouscreations.homesafe.domain.model.CarProfile
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The relay's car profiles and car checks, as its `relay.py` answers them. */
class PushRelayApiCarsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class Seen(val urls: MutableList<String> = mutableListOf(), val bodies: MutableList<String> = mutableListOf())

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, respondWith: String): Pair<PushRelayApi, Seen> {
        val seen = Seen()
        val engine = MockEngine { req ->
            seen.urls += req.url.toString()
            seen.bodies += (req.body as? TextContent)?.text.orEmpty()
            respond(respondWith, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PushRelayApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }) to seen
    }

    @Test
    fun profilesComeWithTheMakesAndColoursTheModelCanAnswer() = runTest {
        val (api, seen) = api(
            respondWith = """{"profiles":[{"name":"andrews_tesla","display_name":"Andrew's Tesla","make":"tesla","model":"Model Y","colour":"blue","plate":null}],
                "makes":["tesla","toyota"],"colours":["white","blue"]}""",
        )
        val profiles = api.getCarProfiles("http://100.99.163.71:5000").getOrThrow()
        assertEquals("http://100.99.163.71:8787/cars/profiles", seen.urls.single())
        assertEquals(listOf(CarProfile("andrews_tesla", make = "tesla", model = "Model Y", colour = "blue")), profiles.profiles)
        assertEquals(listOf("tesla", "toyota"), profiles.makes)
        assertEquals(listOf("white", "blue"), profiles.colours)
    }

    @Test
    fun savingPutsTheProfileUnderItsCategory() = runTest {
        val (api, seen) = api(respondWith = """{"name":"sarahs_car","make":"tesla","model":"Model Y","colour":"red","plate":"9XYZ789"}""")
        val saved = api.saveCarProfile("http://frigate:5000", CarProfile("sarahs_car", "tesla", "Model Y", "red", "9XYZ789")).getOrThrow()
        assertEquals("http://frigate:8787/cars/profiles/sarahs_car", seen.urls.single())
        assertTrue(""""plate":"9XYZ789"""" in seen.bodies.single(), seen.bodies.single())
        assertEquals("9XYZ789", saved.plate)
    }

    @Test
    fun checksAreKeyedByEvent() = runTest {
        val (api, seen) = api(
            respondWith = """{"checks":{"1790636195.070977-unrrq2":{"verdict":"clear","classifier":"andrews_tesla","name":null,"verified":null,
                "saw":{"colour":"black","make":"tesla","model":"","body":"suv"},"plate_read":false,"filed":"unverified"}}}""",
        )
        val checks = api.getCarChecks("http://frigate:5000", listOf("1790636195.070977-unrrq2", "1790636224.095431-75pnj4")).getOrThrow()
        assertTrue("events=1790636195.070977-unrrq2%2C1790636224.095431-75pnj4" in seen.urls.single(), seen.urls.single())
        assertEquals(
            mapOf("1790636195.070977-unrrq2" to CarCheck("clear", "andrews_tesla", null, null, "black", "tesla", "")),
            checks,
        )
    }

    @Test
    fun forgettingDeletesTheProfileUnderItsCategory() = runTest {
        val (api, seen) = api(respondWith = """{"ok":true}""")
        assertTrue(api.deleteCarProfile("http://frigate:5000", "sarahs_car").isSuccess)
        assertEquals("http://frigate:8787/cars/profiles/sarahs_car", seen.urls.single())
    }

    @Test
    fun noEventsAsksNothingAnOlderRelayHasNoChecksAndAnErrorFails() = runTest {
        val (quiet, seen) = api(respondWith = "{}")
        assertEquals(emptyMap(), quiet.getCarChecks("http://frigate:5000", emptyList()).getOrThrow())
        assertTrue(seen.urls.isEmpty())
        val (old, _) = api(HttpStatusCode.NotFound, """{"detail":"Not Found"}""")
        assertNull(old.getCarChecks("http://frigate:5000", listOf("1790636195.070977-unrrq2")).getOrThrow(), "no such route: an older relay")
        val (down, _) = api(HttpStatusCode.BadGateway, "")
        assertTrue(down.getCarChecks("http://frigate:5000", listOf("1790636195.070977-unrrq2")).isFailure, "a relay that couldn't say this time")
    }
}
