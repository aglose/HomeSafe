package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.CarCheck
import com.meticulouscreations.homesafe.domain.model.CarFix
import com.meticulouscreations.homesafe.domain.model.CarProfile
import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import com.meticulouscreations.homesafe.domain.model.ParkingRule
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

    @Test
    fun eachTeslaComesWithWhereItWasLastSeenAndItsSpotAtACurb() = runTest {
        val (api, seen) = api(
            respondWith = """{"streets":[],"cars":[
                {"car":"andrews_tesla","name":"Andrew's Tesla","street":"Elm St","side":"north","lat":37.77004,"lng":-122.25732,"heading":270,
                 "since":1793851200.5,"move_by":1793890800,"rule":{"kind":"no_parking","days":["thu"],"start":"08:00","end":"11:00","note":"Street sweeping"},
                 "window":{"from":1793890800,"until":1793901600},
                 "last":{"lat":37.77004,"lng":-122.25732,"heading":270,"at":1793851200,"moving":false,"home":true},"asleep":true},
                {"car":"sarahs_car","name":"Sarah's Tesla","street":null,"side":null,"lat":null,"lng":null,"since":null,"move_by":null,"rule":null,"window":null,
                 "last":{"lat":37.78,"lng":-122.27,"heading":10,"at":1793854800,"moving":true,"home":false},"asleep":false},
                {"car":"new_car","name":"New Car","last":null,"asleep":false}]}""",
        )
        val cars = api.getCars("http://100.99.163.71:5000").getOrThrow()
        assertEquals("http://100.99.163.71:8787/parking", seen.urls.single())
        val (andrew, sarah, new) = cars
        val spot = andrew.parked!!
        assertEquals(listOf("Elm St", "Street sweeping"), listOf(spot.street, spot.note))
        assertEquals(ParkingAlert.Side.NORTH, spot.side)
        assertEquals(listOf(1_793_851_200L, 1_793_890_800L, 1_793_890_800L, 1_793_901_600L), listOf(spot.sinceEpochSeconds, spot.moveByEpochSeconds, spot.fromEpochSeconds, spot.untilEpochSeconds))
        assertNull(spot.limitMinutes)
        assertTrue(andrew.asleep)
        assertEquals(CarFix(37.78, -122.27, 10f, 1_793_854_800, moving = true, home = false), sarah.lastSeen)
        assertNull(sarah.parked)
        assertEquals(37.78, sarah.latitude)
        assertNull(new.lastSeen)
        assertNull(new.latitude, "never seen: no pin")
    }

    @Test
    fun aTimeLimitIsMinutesNotAWindow() = runTest {
        val (api, _) = api(
            respondWith = """{"cars":[{"car":"andrews_tesla","name":"Andrew's Tesla","street":"Oak St","side":null,"lat":1.0,"lng":2.0,
                "move_by":1793890800,"rule":{"kind":"limit","minutes":120},"window":{"from":1793880000,"until":1793916000}}]}""",
        )
        val spot = api.getCars("http://frigate:5000").getOrThrow().single().parked!!
        assertEquals(120, spot.limitMinutes)
        assertNull(spot.fromEpochSeconds)
        assertNull(spot.side)
    }

    @Test
    fun eachWatchedStreetComesWithTheSignsAlongItsCurbs() = runTest {
        val (api, _) = api(
            respondWith = """{"cars":[],"streets":[
                {"street":"Central Ave","points":3,"home_side":"right","width_m":16,"line":[[37.7639,-122.2423],[37.7600,-122.2350],[37.7549,-122.2273]],"sides":[
                  {"side":"north","offset":"left","rules":[{"kind":"no_parking","days":["thu"],"weeks":[1,3],"start":"08:00","end":"11:00","note":"Street sweeping",
                    "next":{"from":1793894400,"until":1793905200},"lifted":false}]},
                  {"side":"south","offset":"right","rules":[{"kind":"limit","minutes":120,"days":["Monday","tue","WED","thu","fri","sometimes"],"weeks":["2",-1,9],
                    "months":[4,5,13],"start":"8","end":"18:30","permit":"B","next":null,"lifted":true},
                    {"kind":"no_parking","days":null,"weeks":null,"months":null,"start":null,"end":null,"note":null,"permit":null,"next":null}]}]},
                {"street":"Nowhere St","line":[[37.76,-122.23]],"sides":[]}]}""",
        )
        val street = api.getParking("http://frigate:5000").getOrThrow().streets.single()
        assertEquals(listOf("Central Ave", 16.0, 3), listOf(street.street, street.widthMetres, street.line.size))
        val (north, south) = street.curbs
        assertEquals(ParkingAlert.Side.NORTH to true, north.side to north.left)
        assertEquals(
            ParkingRule(
                limitMinutes = null, days = listOf(3), weeks = listOf(1, 3), months = emptyList(), startMinutes = 480, endMinutes = 660, note = "Street sweeping",
                permit = null, lifted = false, nextFromEpochSeconds = 1_793_894_400, nextUntilEpochSeconds = 1_793_905_200,
            ),
            north.rules.single(),
        )
        val (limit, always) = south.rules
        assertEquals(listOf(emptyList<Int>(), 0, 1440), listOf(always.days + always.weeks + always.months, always.startMinutes, always.endMinutes), "nulls are as good as left out")
        assertEquals(ParkingAlert.Side.SOUTH to false, south.side to south.left)
        assertEquals(listOf(listOf(0, 1, 2, 3, 4), listOf(2, -1), listOf(4, 5)), listOf(limit.days, limit.weeks, limit.months), "read as loosely as the relay does; nonsense left out")
        assertEquals(listOf(120, 480, 1110), listOf(limit.limitMinutes, limit.startMinutes, limit.endMinutes))
        assertEquals("B" to true, limit.permit to limit.lifted)
        assertNull(limit.nextFromEpochSeconds)
        assertFalse(limit.on(1_793_894_400), "no window coming")
        assertTrue(north.rules.single().on(1_793_894_400 + 60), "sweeping now")
    }

    @Test
    fun aRelayFromBeforeParkingHasNoCars() = runTest {
        val (api, _) = api(HttpStatusCode.NotFound, respondWith = """{"detail":"Not Found"}""")
        assertEquals(emptyList(), api.getCars("http://frigate:5000").getOrThrow())
    }
}
