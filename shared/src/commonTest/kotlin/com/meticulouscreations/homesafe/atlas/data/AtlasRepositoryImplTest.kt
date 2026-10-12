package com.meticulouscreations.homesafe.atlas.data

import com.meticulouscreations.homesafe.atlas.domain.AtlasProblem
import com.meticulouscreations.homesafe.atlas.domain.AtlasSetupProblem
import com.meticulouscreations.homesafe.atlas.domain.PlaceKind
import com.meticulouscreations.homesafe.atlas.domain.Polyline
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.data.InMemoryAtlasDao
import com.meticulouscreations.homesafe.finance.FakeServerConnection
import com.meticulouscreations.homesafe.weather.data.MutableClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtlasRepositoryImplTest {
    private val start = 1_700_028_000L // 2023-11-15 06:00 UTC

    /** The relay: a manifest, each year's body and the places, as `relay.py` writes them, and how often each was asked for. */
    private inner class Relay {
        val years = LinkedHashMap<Int, Pair<String, String>>()
        var placesVersion = 1
        var places = """[{"id":"ChIJhome","lat":40.0,"lon":-89.0,"kind":"home","name":"7 Mill Road","city":"Fairview","region":"IL","country":"US"}]"""
        var manifestStatus = HttpStatusCode.OK
        var failYear: Int? = null
        var drive = """{"configured":true,"service_account":"relay@example.iam.gserviceaccount.com","checked_at":1700000000.5,"error":null}"""
        val asked = ArrayList<String>()

        fun year(year: Int, etag: String, at: Long = start, cafe: String = "ChIJcafe") {
            years[year] = etag to """
                {"year":$year,"places":["$cafe","ChIJhome"],
                 "visits":{"started":[$at,${at + 7200}],"ended":[${at + 3600},${at + 9000}],"offset":[-360,-360],"lat":[40.0,40.031],"lon":[-89.0,-89.041],"place":[1,0],"kind":["home","other"],"p":[0.9,0.5]},
                 "moves":{"started":[${at + 3600}],"ended":[${at + 7200}],"offset":[-360],"mode":["cycle"],"lat":[40.0],"lon":[-89.0],"to_lat":[40.031],"to_lon":[-89.041],"metres":[5200.5]},
                 "paths":{"started":[${at + 3600}],"offset":[-360],"points":["${Polyline.encode(doubleArrayOf(40.0, -89.0, 40.031, -89.041)).replace("\\", "\\\\")}"],"times":["${Polyline.encodeNumbers(intArrayOf(0, 3600))}"],"count":[2]}}
            """.trimIndent()
        }

        val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                asked += "${request.method.value} $path"
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    path == "/timeline" || path == "/timeline/sync" -> respond(
                        """{"years":[${years.entries.joinToString(",") { """{"year":${it.key},"etag":"${it.value.first}","visits":2,"moves":1,"points":2,"bytes":300}""" }}],
                            "places":{"version":$placesVersion,"total":2,"named":1,"gone":0},"file":{"name":"Timeline.json","ingested_at":1700000100.0},
                            "drive":$drive,"naming":{"configured":true,"error":null,"asked_this_month":2,"monthly_cap":4000},"syncing":false}""",
                        manifestStatus,
                        json,
                    )

                    path.startsWith("/timeline/year/") -> {
                        val year = path.substringAfterLast('/').toInt()
                        if (year == failYear) respond("", HttpStatusCode.BadGateway) else respond(years.getValue(year).second, HttpStatusCode.OK, json)
                    }

                    path == "/timeline/places" -> respond("""{"version":$placesVersion,"places":$places}""", HttpStatusCode.OK, json)

                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout)
        }
    }

    private val relay = Relay()
    private val dao = InMemoryAtlasDao()
    private val connection = FakeServerConnection("https://box.example:8971")
    private val clock = MutableClock(1_760_000_000)
    private val repository = AtlasRepositoryImpl(AtlasRelayApi(relay.client), dao, connection, clock)

    @Test
    fun aFirstRefreshFetchesEveryYearAndThePlaces() = runTest {
        relay.year(2023, "a")
        relay.year(2024, "b", at = start + 60 * 86_400)
        repository.refresh()

        val history = assertNotNull(repository.history.value)
        assertEquals(4, history.visits.size)
        assertEquals(2, history.moves.size)
        assertEquals(TravelMode.CYCLE, history.moves.first().mode)
        assertEquals(5200.5, history.moves.first().meters)
        // A visit's place comes through the year's own list, and its name from the places.
        assertEquals(listOf("ChIJhome", "ChIJcafe"), history.visits.take(2).map { it.placeKey })
        assertEquals("7 Mill Road", history.place("ChIJhome").name)
        assertEquals(PlaceKind.HOME, history.place("ChIJhome").kind)
        assertNull(history.place("ChIJcafe").name)
        assertEquals(listOf("GET /timeline", "GET /timeline/year/2023", "GET /timeline/year/2024", "GET /timeline/places"), relay.asked)

        val sync = repository.sync.value
        assertFalse(sync.syncing)
        assertNull(sync.problem)
        assertEquals(clock.seconds, sync.checkedAtEpochSeconds)
        assertEquals("relay@example.iam.gserviceaccount.com", sync.server?.serviceAccount)
        assertEquals("Timeline.json", sync.server?.fileName)
        assertEquals(1, sync.server?.placesNamed)

        val tracks = repository.tracks()
        assertEquals(2, tracks.size)
        assertEquals(2, tracks.first().size)
        assertEquals(start + 7200, tracks.first().end)
    }

    @Test
    fun onlyAYearWhoseEtagMovedIsFetchedAgain() = runTest {
        relay.year(2023, "a")
        relay.year(2024, "b", at = start + 60 * 86_400)
        repository.refresh()
        relay.asked.clear()

        repository.refresh()
        assertEquals(listOf("GET /timeline"), relay.asked)

        // The café corrected in Maps to the bakery next door.
        relay.year(2023, "a2", cafe = "ChIJbakery")
        relay.asked.clear()
        repository.refresh()
        assertEquals(listOf("GET /timeline", "GET /timeline/year/2023", "GET /timeline/places"), relay.asked)
        assertEquals(listOf("ChIJhome", "ChIJbakery", "ChIJhome", "ChIJcafe"), repository.history.value?.visits?.map { it.placeKey })
        assertEquals(4, dao.visits().size)
    }

    @Test
    fun newNamesAreFetchedWhenTheRelayHasNamedMore() = runTest {
        relay.year(2023, "a")
        repository.refresh()
        relay.asked.clear()
        relay.placesVersion = 2
        relay.places = """[{"id":"ChIJhome","lat":40.0,"lon":-89.0,"kind":"home","name":"7 Mill Road"},{"id":"ChIJcafe","lat":40.031,"lon":-89.041,"kind":"other","name":"Kettle and Crumb","city":"Fairview","region":"IL","country":"US"}]"""
        repository.refresh()
        assertEquals(listOf("GET /timeline", "GET /timeline/places"), relay.asked)
        assertEquals("Kettle and Crumb", repository.history.value?.place("ChIJcafe")?.name)
    }

    @Test
    fun aYearTheRelayNoLongerHasGoes() = runTest {
        relay.year(2023, "a")
        relay.year(2024, "b", at = start + 60 * 86_400)
        repository.refresh()
        relay.years.remove(2024)
        repository.refresh()
        assertEquals(2, repository.history.value?.visits?.size)
        assertEquals(listOf(2023), dao.years().map { it.year })
    }

    @Test
    fun aFetchCutShortKeepsTheYearsThatArrived() = runTest {
        relay.year(2023, "a")
        relay.year(2024, "b", at = start + 60 * 86_400)
        relay.failYear = 2024
        repository.refresh()
        assertEquals(2, repository.history.value?.visits?.size)
        assertEquals(AtlasProblem.OTHER, repository.sync.value.problem)
        assertFalse(repository.sync.value.syncing)

        // The next refresh picks up where it left off.
        relay.failYear = null
        relay.asked.clear()
        repository.refresh()
        assertEquals(listOf("GET /timeline", "GET /timeline/year/2024", "GET /timeline/places"), relay.asked)
        assertEquals(4, repository.history.value?.visits?.size)
        assertNull(repository.sync.value.problem)
    }

    @Test
    fun withNoServerItOpensOnWhatTheDeviceKept() = runTest {
        relay.year(2023, "a")
        repository.refresh()
        relay.asked.clear()

        val later = AtlasRepositoryImpl(AtlasRelayApi(relay.client), dao, FakeServerConnection(null), clock)
        later.refresh()
        assertEquals(2, later.history.value?.visits?.size)
        assertEquals(AtlasProblem.SIGNED_OUT, later.sync.value.problem)
        assertTrue(relay.asked.isEmpty())
        assertEquals(clock.seconds, later.sync.value.checkedAtEpochSeconds)
    }

    @Test
    fun anAccountTheRelayRefusesLosesTheCopy() = runTest {
        relay.year(2023, "a")
        repository.refresh()
        relay.manifestStatus = HttpStatusCode.Forbidden
        repository.refresh()
        assertEquals(AtlasProblem.NOT_ALLOWED, repository.sync.value.problem)
        assertEquals(true, repository.history.value?.isEmpty)
        assertTrue(dao.visits().isEmpty() && dao.years().isEmpty())
        assertTrue(repository.tracks().isEmpty())
    }

    @Test
    fun anotherServersHistoryIsNotAddedTo() = runTest {
        relay.year(2023, "a")
        repository.refresh()
        connection.signIn("https://other.example:8971")
        relay.years.clear()
        relay.year(2025, "z", at = start + 500 * 86_400)
        repository.refresh()
        assertEquals(listOf(2025), dao.years().map { it.year })
        assertEquals(2, repository.history.value?.visits?.size)
        assertEquals("https://other.example:8971", dao.state()?.owner)
    }

    @Test
    fun aRelayFromBeforeTheFeatureSaysSo() = runTest {
        relay.manifestStatus = HttpStatusCode.NotFound
        repository.refresh()
        assertEquals(AtlasProblem.RELAY_OUTDATED, repository.sync.value.problem)
        assertEquals(true, repository.history.value?.isEmpty)
    }

    @Test
    fun askingDriveIsAPostAndTheSetupStepComesBack() = runTest {
        relay.drive = """{"configured":true,"service_account":"relay@example.iam.gserviceaccount.com","error":"not_shared","message":"File not found: folder."}"""
        repository.refresh(askDrive = true)
        assertEquals(HttpMethod.Post.value + " /timeline/sync", relay.asked.first())
        assertEquals(AtlasSetupProblem.NOT_SHARED, repository.sync.value.server?.driveProblem)
        assertEquals("File not found: folder.", repository.sync.value.server?.driveMessage)
    }
}
