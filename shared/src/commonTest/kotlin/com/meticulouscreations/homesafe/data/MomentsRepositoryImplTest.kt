package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MomentsRepositoryImplTest {

    /** Verbatim from `GET /api/events` on Frigate 0.17.2 (the manual test event), plus one in-progress car. */
    private val eventsJson = """[
      {"id":"1788401732.596325-eaak48","label":"person","sub_label":"test","camera":"hikvision_1",
       "start_time":1788401727.596325,"end_time":1788401742.596325,"false_positive":null,"zones":[],
       "thumbnail":null,"has_clip":true,"has_snapshot":true,"retain_indefinitely":false,"plus_id":null,
       "model_hash":null,"detector_type":null,"model_type":null,"data":{"type":"api","score":0,"top_score":0}},
      {"id":"1788401800.1-abc","label":"car","sub_label":null,"camera":"amcrest_1",
       "start_time":1788401800.1,"end_time":null,"has_clip":false,"has_snapshot":false,"zones":["driveway"],
       "data":{"type":"object","score":0.71,"top_score":0.88}}
    ]"""

    private class FakeConnection(url: String?) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection = MutableStateFlow(
            url?.let { ActiveConnection(serverUrl = it, localUrl = null, route = ConnectionRoute.TAILSCALE) },
        )

        /** A route flip: the same server — its Tailscale URL is its identity — answering at its LAN address now. */
        fun flipToLocalNetwork(localUrl: String) {
            activeConnection.update { it?.copy(localUrl = localUrl, route = ConnectionRoute.LOCAL_NETWORK) }
            currentServerUrl.value = activeConnection.value?.activeUrl
        }

        /** The session gone: no address to ask, and no server to ask it of. */
        fun disconnect() {
            activeConnection.value = null
            currentServerUrl.value = null
        }

        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    /** Pinned so a test can place its detections relative to "now" — see the in-view tests. */
    private class FakeClock(var nowEpochSeconds: Long = 1_789_400_000) : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(nowEpochSeconds)
    }

    private class Harness(
        scope: TestScope,
        url: String? = SERVER_URL,
        failEvents: Boolean = false,
        val clock: FakeClock = FakeClock(),
        /** Shared between two harnesses to stand in for what a previous launch left on the device. */
        val momentsDao: InMemoryMomentsDao = InMemoryMomentsDao(),
    ) {
        /** Whether the server is answering at all right now; a test can take it away mid-run. */
        var offline: Boolean = failEvents

        /** When set, every request waits on it before anything else happens: a server that accepts connections but is still coming up. */
        var stalled: CompletableDeferred<Unit>? = null

        // What the engine saw, as flows of immutable lists rather than mutable lists. A poll resumes
        // on whatever thread Ktor finished its request on — [eventually] spends real time off the
        // test dispatcher, so that is genuinely another thread — while the test body reads what has
        // been recorded so far. Appending to a MutableList from one thread while another iterates it
        // is a data race, and iOS caught it as a ConcurrentModificationException inside `distinct()`.
        // Every read below is a snapshot, so no reader can see the list change under it.
        private val _hosts = MutableStateFlow<List<String>>(emptyList())
        val hosts: List<String> get() = _hosts.value

        /** Every `/api/events` request's query string, in order: what the feed asked the server for. */
        private val _eventQueries = MutableStateFlow<List<String>>(emptyList())
        val eventQueries: List<String> get() = _eventQueries.value
        val engine = MockEngine { req ->
            stalled?.await()
            _hosts.update { it + req.url.host }
            // Recorded whether or not the server answers: an ask is an ask.
            if (req.url.encodedPath.endsWith("/api/events")) _eventQueries.update { it + req.url.encodedQuery }
            when {
                offline -> respond("boom", HttpStatusCode.InternalServerError)

                req.url.encodedPath.endsWith("/api/events") -> {
                    val body = eventsFor?.invoke(req.url.parameters["before"]?.toDouble()) ?: events
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                req.url.encodedPath.endsWith("/api/config") && config != null -> respond(config!!, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpCookies)
            install(HttpTimeout)
        }
        val connection = FakeConnection(url)
        val repo = MomentsRepositoryImpl(FrigateApiClient(client), connection, momentsDao, clock, scope.backgroundScope)
        companion object {
            lateinit var events: String

            /** `/api/config`, or null to 404 it the way a test that isn't about zones expects. */
            var config: String? = null

            /** When set, answers `/api/events` by its `before` (null for none) instead of with [events]. */
            var eventsFor: ((before: Double?) -> String)? = null
        }
    }

    /** [count] one-person detections, one a second, newest first from [newestStart] — the shape of a full page. */
    private fun personsJson(newestStart: Long, count: Int): String = personsJson((0 until count).map { newestStart - it })

    /** One-person detections at exactly [starts], in the order given — for a page with a hole where one used to be. */
    private fun personsJson(starts: List<Long>, camera: String = "hikvision_1"): String = starts.joinToString(",", "[", "]") { start ->
        """{"id":"e$start","label":"person","sub_label":null,"camera":"$camera","start_time":$start.0,"end_time":${start + 5}.0,
            "has_clip":true,"has_snapshot":false,"zones":[],"data":{"type":"object","score":0.9,"top_score":0.9}}"""
    }

    /**
     * [count] cars driving down Front Yard's street, one a second, newest first from [newestStart]:
     * the evening traffic (2026-09-16) that filled a whole page with detections [configJson]'s
     * birds-only street rejects. The path is the real street car's from [zonedEventsJson].
     */
    private fun streetCarsJson(newestStart: Long, count: Int): String = (0 until count).joinToString(",", "[", "]") {
        val start = newestStart - it
        """{"id":"car$start","label":"car","sub_label":null,"camera":"hikvision_1","start_time":$start.0,"end_time":${start + 20}.0,
            "has_clip":true,"has_snapshot":false,"zones":[],
            "data":{"type":"object","score":0.71,"top_score":0.78,"box":[0.65,0.27,0.14,0.1],
                    "path_data":[[[0.6219,0.2861],$start.5],[[0.7734,0.3222],${start + 10}.0],[[0.725,0.3667],${start + 19}.0]]}}"""
    }

    /**
     * [count] re-detections of the car parked at Front Yard's curb, one a second, newest first
     * from [newestStart]: each a new object with a two-point path around one spot, which
     * [com.meticulouscreations.homesafe.domain.model.mergeVehicleVisits] drops as a car that
     * never went anywhere. The box is the real parked Tesla's from [parkedCarJson].
     */
    private fun parkedJitterJson(newestStart: Long, count: Int): String = (0 until count).joinToString(",", "[", "]") {
        val start = newestStart - it
        """{"id":"parked$start","label":"car","sub_label":null,"camera":"hikvision_1","start_time":$start.0,"end_time":${start + 1}.0,
            "has_clip":true,"has_snapshot":false,"zones":[],
            "data":{"type":"object","score":0.8,"top_score":0.8,"box":[0.75,0.34,0.18,0.21],
                    "path_data":[[[0.84,0.54],$start.2],[[0.85,0.40],$start.8]]}}"""
    }

    private suspend fun MomentsRepositoryImpl.paging() = observePaging().first()

    /**
     * The page requests, the polls left out: [eventually] advances virtual time past the poll
     * interval every round, so the head is re-read any number of times along the way.
     */
    private val Harness.pageQueries: List<String> get() = eventQueries.filter { "before=" in it }

    /** Front Yard's real zones (2026-09-06): the street wants only birds, the sidewalk wants everything. */
    private val configJson = """{"cameras":{"hikvision_1":{"zones":{
      "street":{"coordinates":"0.311,0.113,0.994,0.33,1.0,0.607,0.301,0.287","objects":["bird"],"friendly_name":"Street"},
      "sidewalk":{"coordinates":"1.0,0.6,0.998,0.75,0.331,0.41,0.44,0.353,0.704,0.46","objects":[]}
    }},"amcrest_1":{}}}"""

    /** Real path_data shapes: a car that stayed on the street, a person Frigate never tagged whose path crosses the sidewalk, and the amcrest car. */
    private val zonedEventsJson = """[
      {"id":"street-car","label":"car","sub_label":null,"camera":"hikvision_1","start_time":1788726566.38,"end_time":null,
       "has_clip":true,"has_snapshot":false,"zones":[],
       "data":{"type":"object","score":0.71,"top_score":0.78,"box":[0.6546875,0.2666,0.1421875,0.1],
               "path_data":[[[0.6219,0.2861],1788726569.34],[[0.7734,0.3222],1788726586.76],[[0.725,0.3667],1788726611.56]]}},
      {"id":"walker","label":"person","sub_label":null,"camera":"hikvision_1","start_time":1788726500.0,"end_time":1788726520.0,
       "has_clip":true,"has_snapshot":false,"zones":[],
       "data":{"type":"object","score":0.9,"top_score":0.92,"path_data":[[[0.55,0.44],1788726501.0],[[0.6,0.45],1788726510.0]]}},
      {"id":"1788401800.1-abc","label":"car","sub_label":null,"camera":"amcrest_1",
       "start_time":1788401800.1,"end_time":null,"has_clip":false,"has_snapshot":false,"zones":["driveway"],
       "data":{"type":"object","score":0.71,"top_score":0.88}}
    ]"""

    /**
     * The real shape of a parked car's day (Front Yard, 2026-09-07): Sarah's Tesla pulling up to
     * the curb, then two sightings of it sitting there, each a jittery path around one spot ended
     * by a passer-by stealing the tracker, plus one genuine drive-through of the same car later.
     * Newest first, like the API.
     */
    private val parkedCarJson = """[
      {"id":"drive","label":"car","sub_label":"sarahs_tesla","camera":"hikvision_1","start_time":1788832052.0,"end_time":1788832056.0,
       "has_clip":true,"has_snapshot":false,"zones":[],
       "data":{"type":"object","score":0.8,"top_score":0.83,"sub_label_score":0.91,"box":[0.62,0.24,0.14,0.10],
               "path_data":[[[0.10,0.30],1788832052.2],[[0.20,0.30],1788832052.4],[[0.30,0.30],1788832052.6],[[0.40,0.30],1788832052.8],[[0.50,0.30],1788832053.0],
                            [[0.60,0.30],1788832053.2],[[0.70,0.30],1788832053.4],[[0.80,0.30],1788832053.6],[[0.90,0.30],1788832053.8],[[0.95,0.30],1788832054.0]]}},
      {"id":"third","label":"car","sub_label":"sarahs_tesla","camera":"hikvision_1","start_time":1788802458.9,"end_time":null,
       "has_clip":true,"has_snapshot":false,"zones":[],
       "data":{"type":"object","score":0.8,"top_score":0.85,"sub_label_score":0.997,"box":[0.75,0.33,0.18,0.21],
               "path_data":[[[0.84,0.54],1788802460.0],[[0.85,0.40],1788802461.0],[[0.84,0.54],1788802560.0],[[0.85,0.40],1788802561.0]]}},
      {"id":"second","label":"car","sub_label":"andrews_tesla","camera":"hikvision_1","start_time":1788799948.1,"end_time":1788802465.0,
       "has_clip":true,"has_snapshot":false,"zones":[],
       "data":{"type":"object","score":0.8,"top_score":0.80,"sub_label_score":0.94,"box":[0.75,0.34,0.18,0.19],
               "path_data":[[[0.84,0.54],1788800030.0],[[0.88,0.44],1788800034.0],[[0.84,0.54],1788800035.0],[[0.87,0.39],1788800049.0],[[0.59,0.34],1788802461.0]]}},
      {"id":"first","label":"car","sub_label":"sarahs_tesla","camera":"hikvision_1","start_time":1788786387.8,"end_time":1788799377.0,
       "has_clip":true,"has_snapshot":false,"zones":[],
       "data":{"type":"object","score":0.8,"top_score":0.78,"sub_label_score":0.97,"box":[0.75,0.34,0.18,0.21],
               "path_data":[[[0.30,0.30],1788786418.0],[[0.45,0.35],1788786419.0],[[0.60,0.42],1788786420.0],[[0.75,0.48],1788786421.0],[[0.84,0.53],1788786422.0],[[0.84,0.53],1788799365.0]]}}
    ]"""

    /**
     * Real and virtual time enough for anything the repository was going to do to have happened.
     * What [eventually] is for asserting that something starts being true, this is for asserting
     * that something goes on being true — a feed that must *not* empty has to be given every
     * chance to empty first, including the real time a cancelled fetch takes to unwind.
     */
    private suspend fun TestScope.settle() {
        repeat(5) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(25) }
        }
        advanceUntilIdle()
    }

    private suspend fun TestScope.eventually(what: String, cond: suspend () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (cond()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("Timed out waiting for $what")
    }

    /**
     * Lets exactly [seconds] of virtual time pass, a second at a time, with real time between for
     * the mock engine's thread to answer whatever that second set off. Unlike [eventually], which
     * skips ahead to whatever is scheduled next, this is for asserting *when* the repository asks.
     */
    private suspend fun TestScope.passVirtual(seconds: Int) {
        repeat(seconds) {
            advanceTimeBy(1_000)
            repeat(3) {
                runCurrent()
                withContext(Dispatchers.Default) { delay(10) }
            }
        }
    }

    @Test
    fun mapsRealFrigateEventsNewestFirstAndSurfacesInProgress() = runTest {
        Harness.events = eventsJson
        val h = Harness(this)
        backgroundScope.launch { h.repo.observeMoments().collect {} }   // keep the poller subscribed while we wait
        var list = h.repo.observeMoments().first()
        eventually("events to load") {
            list = h.repo.observeMoments().first()
            list.size == 2
        }

        // The feed sorts by start, newest first, whatever order the payload arrived in — this
        // fixture lists the older person first, and the car that started later leads the feed.
        val car = list[0]
        val person = list[1]
        assertEquals("1788401732.596325-eaak48", person.id)
        assertEquals("hikvision_1", person.cameraName)
        assertEquals(MomentCategory.PEOPLE, person.category)
        assertEquals("test", person.subLabel)
        assertEquals(15.0, person.durationSeconds!!, 0.001)
        assertEquals(true, person.hasClip)

        assertEquals(MomentCategory.VEHICLES, car.category)
        assertEquals(true, car.isInProgress)
        assertNull(car.durationSeconds)
        assertEquals(0.88, car.topScore!!, 0.001)
        assertNull(car.box, "no box in the payload, no box on the moment")
        assertEquals(1, car.sightings)
        assertNull(h.repo.observeError().first())
    }

    @Test
    fun stillRedetectionsOfARecognisedCarFoldIntoOneCard() = runTest {
        Harness.events = parkedCarJson
        Harness.config = configJson
        try {
            val h = Harness(this)
            backgroundScope.launch { h.repo.observeMoments().collect {} }
            var list = h.repo.observeMoments().first()
            eventually("events to load") {
                list = h.repo.observeMoments().first()
                list.isNotEmpty()
            }

            // The drive-through is its own card; the arrival and the two curb sightings are one, still in progress.
            assertEquals(listOf("drive", "first"), list.map { it.id })
            val parked = list[1]
            assertEquals(3, parked.sightings)
            assertEquals(true, parked.isInProgress, "the latest sighting hadn't ended")
            assertEquals("sarahs_tesla", parked.subLabel, "the surest name across the sightings")
            assertEquals(0.997, parked.subLabelScore!!, 0.0001)
            assertEquals(0.75, parked.box!!.x, 0.001)
            assertEquals(1, list[0].sightings)
            assertEquals(0.91, list[0].subLabelScore!!, 0.001)
        } finally {
            Harness.config = null
        }
    }

    @Test
    fun zonesDecideWhichDetectionsBelongInTheFeedAndWhereTheyWere() = runTest {
        Harness.events = zonedEventsJson
        Harness.config = configJson
        try {
            val h = Harness(this)
            backgroundScope.launch { h.repo.observeMoments().collect {} }
            var list = h.repo.observeMoments().first()
            eventually("events to load") {
                list = h.repo.observeMoments().first()
                list.isNotEmpty()
            }

            // The street-only car is gone: the only zone it crossed wants birds. The untagged
            // walker is placed on the sidewalk from their path; the amcrest car (no zones drawn
            // on that camera) is untouched, Frigate's driveway tag and all.
            assertEquals(listOf("walker", "1788401800.1-abc"), list.map { it.id })
            assertEquals(listOf("sidewalk"), list[0].zones)
            assertEquals(listOf("driveway"), list[1].zones)
            assertEquals(2, list[0].pathPoints.size)
        } finally {
            Harness.config = null
        }
    }

    @Test
    fun clipStreamHitsTheEventVodEndpointWithTheSessionCookie() = runTest {
        Harness.events = eventsJson
        val h = Harness(this)
        val s = h.repo.getClipStream("1788401732.596325-eaak48")
        assertEquals("http://192.168.68.55:8971/vod/event/1788401732.596325-eaak48/index.m3u8", s.url)
        // No login happened in this test, so no cookie — the map is simply empty rather than a null header.
        assertEquals(emptyMap(), s.headers)
    }

    @Test
    fun serverErrorIsSurfacedNotSwallowed() = runTest {
        Harness.events = eventsJson
        val h = Harness(this, failEvents = true)
        backgroundScope.launch { h.repo.observeMoments().collect {} }   // keep the poller subscribed while we wait
        var err: String? = null
        h.repo.observeMoments().first()
        eventually("error to surface") {
            err = h.repo.observeError().first()
            err != null
        }
        assertNotNull(err)
        assertEquals(emptyList(), h.repo.observeMoments().first())
    }

    @Test
    fun theNextPageStartsBeforeTheOldestDetectionAndSitsBelowTheHead() = runTest {
        // A full page of 100 from the top; below it the server has two more, then nothing.
        Harness.eventsFor = { before -> if (before == null) personsJson(2000, 100) else personsJson(1900, 2) }
        try {
            val h = Harness(this)
            backgroundScope.launch { h.repo.observeMoments().collect {} }
            var list = h.repo.observeMoments().first()
            eventually("the first page") {
                list = h.repo.observeMoments().first()
                list.size == 100
            }
            assertEquals(true, h.repo.paging().hasOlder, "a full page means there may be more")
            assertEquals(emptyList(), h.pageQueries, "the live feed is read from the top, with no cursor")

            h.repo.loadOlder()

            list = h.repo.observeMoments().first()
            assertEquals(102, list.size)
            assertEquals(listOf("e1900", "e1899"), list.takeLast(2).map { it.id })
            // The cursor is the oldest detection fetched, rendered the way every Frigate timestamp is.
            assertEquals(listOf("limit=100&before=1901.000"), h.pageQueries)
            val paging = h.repo.paging()
            assertEquals(false, paging.hasOlder, "a short page is the end of what the server has")
            assertEquals(false, paging.loadingOlder)

            // Nothing older: asking again doesn't even go to the server.
            h.repo.loadOlder()
            assertEquals(1, h.pageQueries.size)
        } finally {
            Harness.eventsFor = null
        }
    }

    @Test
    fun openingTheFeedAtAnEarlierInstantStartsOverBelowIt() = runTest {
        Harness.eventsFor = { before ->
            when (before) {
                null -> personsJson(2000, 100)
                1500.0 -> personsJson(1499, 1)
                else -> fail("unexpected before=$before")
            }
        }
        try {
            val h = Harness(this)
            backgroundScope.launch { h.repo.observeMoments().collect {} }
            var list = h.repo.observeMoments().first()
            eventually("the live feed") {
                list = h.repo.observeMoments().first()
                list.size == 100
            }

            h.repo.showBefore(1500.0)
            eventually("the window to move") {
                list = h.repo.observeMoments().first()
                list.map { it.id } == listOf("e1499")
            }
            assertEquals("limit=100&before=1500.000", h.pageQueries.last())
            val paging = h.repo.paging()
            assertEquals(1500.0, paging.beforeEpochSeconds)
            assertEquals(false, paging.hasOlder)

            // Back to now: the live page again, and the window's edge gone with it.
            h.repo.showBefore(null)
            eventually("the live feed again") {
                list = h.repo.observeMoments().first()
                list.size == 100
            }
            assertNull(h.repo.paging().beforeEpochSeconds)
            assertEquals(true, h.repo.paging().hasOlder)
        } finally {
            Harness.eventsFor = null
        }
    }

    @Test
    fun narrowingToACameraAsksTheServerForItsMomentsAloneAndPagesWithinIt() = runTest {
        Harness.eventsFor = { _ -> personsJson(2000, 100) }
        try {
            val h = Harness(this)
            backgroundScope.launch { h.repo.observeMoments().collect {} }
            eventually("the live feed") { h.repo.observeMoments().first().size == 100 }
            assertEquals("limit=100", h.eventQueries.first(), "every camera asks for no camera at all")

            h.repo.showCamera("amcrest_1")
            eventually("the narrowed head") { h.eventQueries.last() == "limit=100&cameras=amcrest_1" }
            eventually("the narrowed feed") { h.repo.observeMoments().first().size == 100 }

            h.repo.loadOlder()
            assertEquals("limit=100&before=1901.000&cameras=amcrest_1", h.pageQueries.last(), "the next page stays on the camera")

            h.repo.showCamera(null)
            eventually("every camera again") { h.eventQueries.last() == "limit=100" }
        } finally {
            Harness.eventsFor = null
        }
    }

    @Test
    fun aCamerasRecentMomentsAreItsOwnFromNowWhereverTheFeedIs() = runTest {
        Harness.events = personsJson(2000, 25)
        val h = Harness(this)
        // The Moments feed is off looking at another camera's past; the recent strip doesn't follow it.
        h.repo.showBefore(1500.0)
        h.repo.showCamera("amcrest_1")

        var recent = emptyList<MomentEvent>()
        backgroundScope.launch { h.repo.observeRecentMoments("hikvision_1", limit = 3).collect { recent = it } }
        eventually("recent moments") { recent.isNotEmpty() }

        assertEquals(listOf("e2000", "e1999", "e1998"), recent.map { it.id })
        // Polled like the feed ([eventually] runs virtual time on), but every poll is the same question.
        assertEquals(listOf("limit=100&cameras=hikvision_1"), h.eventQueries.distinct())
    }

    @Test
    fun aParkedCarsRedetectionsDoNotEmptyACamerasRecentActivity() = runTest {
        // Front Yard, 2026-09-22: a full page of the parked car's re-detections, the evening's people below it.
        Harness.eventsFor = { before ->
            when (before) {
                null -> parkedJitterJson(2000, 100)
                1901.0 -> personsJson((1900L downTo 1881L).toList())
                else -> fail("unexpected before=$before")
            }
        }
        try {
            val h = Harness(this)
            // Null until the strip says anything, so "nothing yet" can't pass for "nothing".
            var recent: List<MomentEvent>? = null
            backgroundScope.launch { h.repo.observeRecentMoments("hikvision_1", limit = 3).collect { recent = it } }
            eventually("the people below the parked car") { !recent.isNullOrEmpty() }

            assertEquals(listOf("e1900", "e1899", "e1898"), recent!!.map { it.id })
            assertEquals(
                listOf("limit=100&cameras=hikvision_1", "limit=100&before=1901.000&cameras=hikvision_1"),
                h.eventQueries.distinct(),
                "one page down, and no further once three moments survived",
            )
        } finally {
            Harness.eventsFor = null
        }
    }

    @Test
    fun aCameraThatOnlySeesItsParkedCarSaysSoAfterAFewPages() = runTest {
        Harness.eventsFor = { before -> parkedJitterJson(before?.toLong()?.minus(1) ?: 2000, 100) }
        try {
            val h = Harness(this)
            var recent: List<MomentEvent>? = null
            backgroundScope.launch { h.repo.observeRecentMoments("hikvision_1", limit = 3).collect { recent = it } }
            eventually("the first answer") { recent != null }
            settle()

            // An answer (nothing to show) rather than a strip that never stops loading, and a bounded walk to get it.
            assertEquals(emptyList(), recent)
            assertEquals(5, h.eventQueries.distinct().size, "a few pages per poll, not the server's whole history")
        } finally {
            Harness.eventsFor = null
        }
    }

    @Test
    fun aCamerasRecentMomentsReachBackAsFarAsTheTimelineShows() = runTest {
        val clock = FakeClock()
        val now = clock.nowEpochSeconds
        // A person every second from ten seconds ago; the timeline shows the last 150 seconds.
        Harness.eventsFor = { before -> personsJson(before?.toLong()?.minus(1) ?: (now - 10), 100) }
        try {
            val h = Harness(this, clock = clock)
            var recent: List<MomentEvent>? = null
            backgroundScope.launch { h.repo.observeRecentMoments("hikvision_1", limit = 3, lookbackSeconds = 150.0).collect { recent = it } }
            eventually("the lookback's moments") { (recent?.size ?: 0) > 3 }

            assertEquals((now - 10 downTo now - 150).map { "e$it" }, recent!!.map { it.id }, "everything since the cutoff, and nothing older")
            assertEquals(2, h.eventQueries.distinct().size, "the second page already reached past the cutoff")
        } finally {
            Harness.eventsFor = null
        }
    }

    @Test
    fun aCamerasRecentMomentsSayNothingUntilTheyKnow() = runTest {
        Harness.events = personsJson(2000, 3)
        val h = Harness(this)
        val serverUp = CompletableDeferred<Unit>()
        h.stalled = serverUp
        var emissions = 0
        var recent: List<MomentEvent>? = null
        backgroundScope.launch {
            h.repo.observeRecentMoments("hikvision_1", limit = 3).collect {
                emissions++
                recent = it
            }
        }
        settle()
        // Nothing cached and nothing answered: not an empty list, which the screen would read as "no detections".
        assertEquals(0, emissions)

        serverUp.complete(Unit)
        eventually("the server's answer") { recent != null }
        assertEquals(listOf("e2000", "e1999", "e1998"), recent!!.map { it.id })
    }

    @Test
    fun aShortFirstPageIsTheWholeFeed() = runTest {
        Harness.events = eventsJson
        val h = Harness(this)
        backgroundScope.launch { h.repo.observeMoments().collect {} }
        eventually("events to load") { h.repo.observeMoments().first().size == 2 }
        assertEquals(false, h.repo.paging().hasOlder)

        h.repo.loadOlder()
        assertEquals(emptyList(), h.pageQueries, "nothing older to ask for")
    }

    @Test
    fun theInViewStripKeepsTheParkedCarTheFeedThrowsAway() = runTest {
        Harness.events = parkedCarJson
        // A couple of minutes after the sighting that is still in progress: the Tesla is there now.
        val h = Harness(this, clock = FakeClock(1_788_802_600))

        var inView = emptyList<StationaryObject>()
        backgroundScope.launch { h.repo.observeStationaryObjects().collect { inView = it } }
        eventually("the in-view strip") { inView.isNotEmpty() }

        val tesla = inView.single()
        assertEquals("third", tesla.thumbnailEventId, "the newest sighting's crop: a picture of the car where it is now")
        assertEquals("sarahs_tesla", tesla.subLabel, "the surest of the sightings names it")
        assertEquals(1_788_786_387.8, tesla.firstSeenEpochSeconds, "the stay is anchored to the arrival, hours earlier")
        assertEquals(3, tesla.sightings, "the arrival and the two curb sightings, as one car")
        assertEquals(true, tesla.seenRecently, "the latest sighting hadn't ended")
        assertEquals(true, tesla.sinceIsKnown, "a page short of the limit means the fetch really did reach back twelve hours")
        assertEquals(1, h.eventQueries.distinct().count { "after=" in it }, "one question per poll: every camera, from now")
    }

    @Test
    fun aCarOutOnTheStreetIsNotParkedInTheYard() = runTest {
        Harness.events = zonedEventsJson
        Harness.config = configJson
        try {
            val h = Harness(this, clock = FakeClock(1_788_726_700))

            // Null until the first poll answers, so an empty strip can't be mistaken for one that never ran.
            var inView: List<StationaryObject>? = null
            backgroundScope.launch { h.repo.observeStationaryObjects().collect { inView = it } }
            eventually("the first in-view poll") { inView != null }

            // The street car the zones reject, and the amcrest car with no box to place it by:
            // neither is something the app can say is parked in the yard.
            assertEquals(emptyList(), inView)
        } finally {
            Harness.config = null
        }
    }

    @Test
    fun theInViewStripOpensOnTheCarsTheDeviceKept() = runTest {
        Harness.events = parkedCarJson
        val kept = InMemoryMomentsDao()
        val first = Harness(this, clock = FakeClock(1_788_802_600), momentsDao = kept)
        var firstInView = emptyList<StationaryObject>()
        backgroundScope.launch { first.repo.observeStationaryObjects().collect { firstInView = it } }
        eventually("the first launch's strip") { firstInView.isNotEmpty() }
        eventually("the sightings to reach the device") { kept.page(SERVER_URL, null, null, limit = 10).size == 4 }

        // Launched again with the server out of reach: the Tesla is still on the strip, from the
        // sightings the last launch filed, rather than the blank the strip used to be until a poll landed.
        val relaunch = Harness(this, failEvents = true, clock = FakeClock(1_788_802_600), momentsDao = kept)
        var inView = emptyList<StationaryObject>()
        backgroundScope.launch { relaunch.repo.observeStationaryObjects().collect { inView = it } }
        eventually("the strip the device kept") { inView.isNotEmpty() }

        val tesla = inView.single()
        assertEquals("third", tesla.thumbnailEventId)
        assertEquals("sarahs_tesla", tesla.subLabel)
        assertEquals(3, tesla.sightings, "folded off the cache exactly as off the server")
        assertEquals(true, tesla.seenRecently, "the cache can't know an in-progress sighting has since ended; the first fetch will")
        assertEquals(false, tesla.sinceIsKnown, "the stay starts at the cache's oldest row: the device may not have watched it arrive")
    }

    @Test
    fun theServerReplacesWhatTheCacheShowed() = runTest {
        Harness.events = parkedCarJson
        val kept = InMemoryMomentsDao()
        // What a previous launch left behind: a neighbour's car Frigate has since purged, still in progress as far as the device knows.
        kept.insertAll(
            listOf(
                MomentEventEntity(
                    serverUrl = SERVER_URL, id = "stale", cameraName = "hikvision_1", label = "car", subLabel = "ron_judys_mercedes",
                    startEpochSeconds = 1_788_800_000.0, endEpochSeconds = null, topScore = 0.8, hasClip = true, hasSnapshot = false,
                    zones = "", pathPoints = "0.3,0.7;0.31,0.7", boxX = 0.2, boxY = 0.6, boxW = 0.2, boxH = 0.2, subLabelScore = 0.9,
                ),
            ),
        )
        val h = Harness(this, clock = FakeClock(1_788_802_600), momentsDao = kept)

        // Every list the strip has shown, by name, so the order the cache and the server arrived in is on record.
        val shown = MutableStateFlow<List<List<String?>>>(emptyList())
        backgroundScope.launch { h.repo.observeStationaryObjects().collect { list -> shown.update { it + listOf(list.map { s -> s.subLabel }) } } }
        eventually("the server's answer") { shown.value.lastOrNull() == listOf("sarahs_tesla") }

        assertEquals(listOf("ron_judys_mercedes"), shown.value.first(), "the cache painted first, before the fetch was answered")
        eventually("the purged car to leave the cache") { kept.page(SERVER_URL, null, null, limit = 10).none { it.id == "stale" } }
    }

    @Test
    fun aStaleCarBeforeTheOldestFetchedSightingIsStillPruned() = runTest {
        // A short page: the server answers with fewer than PAGE_SIZE, so its own oldest sighting
        // ("first", starting 1788786387.8) is well inside the 12h window the app actually asked
        // for — the fetch answered for the whole window back to lookbackStart, not merely back to
        // the sighting it happened to return.
        Harness.events = parkedCarJson
        val kept = InMemoryMomentsDao()
        // Parked hours before any sighting the fetch returns, but still inside the lookback window:
        // exactly the gap a prune keyed on the raw oldest event would leave untouched.
        kept.insertAll(
            listOf(
                MomentEventEntity(
                    serverUrl = SERVER_URL, id = "gone", cameraName = "hikvision_1", label = "car", subLabel = "old_neighbour_car",
                    startEpochSeconds = 1_788_770_000.0, endEpochSeconds = 1_788_770_100.0, topScore = 0.8, hasClip = true, hasSnapshot = false,
                    zones = "", pathPoints = "0.3,0.7;0.31,0.7", boxX = 0.2, boxY = 0.6, boxW = 0.2, boxH = 0.2, subLabelScore = 0.9,
                ),
            ),
        )
        val h = Harness(this, clock = FakeClock(1_788_802_600), momentsDao = kept)
        backgroundScope.launch { h.repo.observeStationaryObjects().collect {} }

        eventually("the gap car to be pruned by the server's answer") {
            kept.page(SERVER_URL, null, null, limit = 10).none { it.id == "gone" }
        }
    }

    @Test
    fun aNewLaunchOpensOnTheMomentsTheDeviceKept() = runTest {
        Harness.events = eventsJson
        val kept = InMemoryMomentsDao()
        val first = Harness(this, momentsDao = kept)
        backgroundScope.launch { first.repo.observeMoments().collect {} }
        eventually("the first launch's feed") { first.repo.observeMoments().first().size == 2 }
        eventually("the moments to reach the device") { kept.page(SERVER_URL, null, null, limit = 10).size == 2 }

        // Launched again with the server out of reach: the feed is what the last launch left behind,
        // in the order it was in, rather than the blank page it used to be.
        val relaunch = Harness(this, failEvents = true, momentsDao = kept)
        backgroundScope.launch { relaunch.repo.observeMoments().collect {} }
        var ids = emptyList<String>()
        eventually("the feed the device kept") {
            ids = relaunch.repo.observeMoments().first().map { it.id }
            ids.isNotEmpty()
        }
        assertEquals(listOf("1788401800.1-abc", "1788401732.596325-eaak48"), ids)
    }

    @Test
    fun aRelaunchAgainstAServerStillBootingShowsWhatTheDeviceKeptWhileItWaits() = runTest {
        Harness.events = eventsJson
        val kept = InMemoryMomentsDao()
        val first = Harness(this, momentsDao = kept)
        backgroundScope.launch { first.repo.observeMoments().collect {} }
        eventually("the moments to reach the device") { kept.page(SERVER_URL, null, null, limit = 10).size == 2 }

        // Opened again after a power cut: the server accepts the connection but is still coming
        // up, so nothing it is asked comes back yet. The feed is what the device kept, at once,
        // and not an error — nothing has gone wrong, the answer is merely on its way.
        val relaunch = Harness(this, momentsDao = kept)
        val serverUp = CompletableDeferred<Unit>()
        relaunch.stalled = serverUp
        backgroundScope.launch { relaunch.repo.observeMoments().collect {} }
        var ids = emptyList<String>()
        eventually("the feed the device kept") {
            ids = relaunch.repo.observeMoments().first().map { it.id }
            ids.isNotEmpty()
        }
        assertEquals(listOf("1788401800.1-abc", "1788401732.596325-eaak48"), ids)
        assertEquals(emptyList(), relaunch.eventQueries, "the server hasn't answered anything yet")
        assertNull(relaunch.repo.observeError().first(), "waiting is not an error")

        // The server finishes booting, with the night's detections: the fetch that was waiting lands and has the last word.
        Harness.events = personsJson(1_788_500_000, 3)
        serverUp.complete(Unit)
        eventually("the server's feed") { relaunch.repo.observeMoments().first().size == 3 }
    }

    @Test
    fun comingBackToAServerThatIsNotAnsweringKeepsTheFeedAndAsksAgainWithinSeconds() = runTest {
        Harness.events = eventsJson
        val h = Harness(this)
        val watcher = backgroundScope.launch { h.repo.observeMoments().collect {} }
        eventually("the feed") { h.repo.observeMoments().first().size == 2 }

        // The app goes to the background for the night: nothing is looking, so the poll stops.
        watcher.cancel()
        advanceUntilIdle()
        val asksBeforeReturn = h.eventQueries.size

        // Meanwhile the server was rebooted, and it is still coming up when the app returns.
        h.offline = true
        val sizes = MutableStateFlow<List<Int>>(emptyList())
        backgroundScope.launch { h.repo.observeMoments().collect { list -> sizes.update { it + list.size } } }
        passVirtual(3)
        assertTrue(h.eventQueries.size > asksBeforeReturn, "the returning feed asked the server")
        assertEquals(2, h.repo.observeMoments().first().size, "the feed is what it was, not blank")
        assertEquals(emptyList(), sizes.value.filter { it == 0 }, "never blank on the way back")
        assertNotNull(h.repo.observeError().first(), "but it says the server didn't answer")

        // A server that didn't answer is asked again in seconds (5s, then 15s), not a whole poll later.
        val asksAfterReturn = h.eventQueries.size
        passVirtual(25)
        assertTrue(h.eventQueries.size - asksAfterReturn >= 2, "asked again ${h.eventQueries.size - asksAfterReturn} times in 25s")

        // It comes up: the feed catches up with the night's detections on the next retry.
        Harness.events = personsJson(1_788_500_000, 3)
        h.offline = false
        eventually("the feed to catch up") { h.repo.observeMoments().first().size == 3 }
        assertNull(h.repo.observeError().first())
    }

    @Test
    fun aRouteFlipAsksTheNewAddressWithoutEmptyingTheFeed() = runTest {
        Harness.events = eventsJson
        val h = Harness(this)
        // Every size the feed has ever had, so a blank frame in the middle can't hide behind the end state.
        val sizes = MutableStateFlow<List<Int>>(emptyList())
        backgroundScope.launch { h.repo.observeMoments().collect { list -> sizes.update { it + list.size } } }
        eventually("the feed") { h.repo.observeMoments().first().size == 2 }

        h.connection.flipToLocalNetwork("http://192.168.68.99:8971")
        eventually("the new address to be asked") { "192.168.68.99" in h.hosts }
        settle()

        assertEquals(2, h.repo.observeMoments().first().size)
        val afterItFilled = sizes.value.dropWhile { it == 0 }
        assertEquals(emptyList(), afterItFilled.filter { it == 0 }, "the same server at another address is not a new feed")
    }

    @Test
    fun losingTheConnectionLeavesTheMomentsOnScreen() = runTest {
        Harness.events = eventsJson
        val h = Harness(this)
        val sizes = MutableStateFlow<List<Int>>(emptyList())
        backgroundScope.launch { h.repo.observeMoments().collect { list -> sizes.update { it + list.size } } }
        eventually("the feed") { h.repo.observeMoments().first().size == 2 }

        h.connection.disconnect()
        settle()

        assertEquals(2, h.repo.observeMoments().first().size, "a dropped session is not news that nothing happened")
        assertEquals(emptyList(), sizes.value.dropWhile { it == 0 }.filter { it == 0 }, "the feed never went blank")
    }

    @Test
    fun aDetectionTheServerNoLongerHasLeavesTheDeviceToo() = runTest {
        Harness.events = personsJson(2000, 3)
        val kept = InMemoryMomentsDao()
        val h = Harness(this, momentsDao = kept)
        backgroundScope.launch { h.repo.observeMoments().collect {} }
        eventually("the feed") { h.repo.observeMoments().first().size == 3 }

        eventually("all three to reach the device") { kept.page(SERVER_URL, null, null, limit = 10).size == 3 }

        // Frigate's retention takes the middle one; the next poll comes back without it.
        Harness.events = personsJson(listOf(2000L, 1998L))
        eventually("the shorter feed") { h.repo.observeMoments().first().size == 2 }

        var cached = emptyList<String>()
        eventually("the device to let go of it too") {
            cached = kept.page(SERVER_URL, null, null, limit = 10).map { it.id }
            cached.size == 2
        }
        assertEquals(listOf("e2000", "e1998"), cached, "a purged detection can't sit on the device waiting for the next launch")
    }

    @Test
    fun theNextPageDownComesOffTheDeviceWhenTheServerCannotAnswer() = runTest {
        Harness.eventsFor = { before -> if (before == null) personsJson(2000, 100) else personsJson(1900, 2) }
        try {
            val kept = InMemoryMomentsDao()
            val h = Harness(this, momentsDao = kept)
            backgroundScope.launch { h.repo.observeMoments().collect {} }
            eventually("the first page") { h.repo.observeMoments().first().size == 100 }
            h.repo.loadOlder()
            assertEquals(102, h.repo.observeMoments().first().size)
            eventually("both pages to reach the device") { kept.page(SERVER_URL, null, null, limit = 200).size == 102 }

            // Launched again with nothing to reach: the head comes off the device, and so does the page below it.
            val relaunch = Harness(this, failEvents = true, momentsDao = kept)
            backgroundScope.launch { relaunch.repo.observeMoments().collect {} }
            eventually("the head the device kept") { relaunch.repo.observeMoments().first().size == 100 }
            assertEquals(true, relaunch.repo.paging().hasOlder, "a full page off the device may have more below it")

            relaunch.repo.loadOlder()
            assertEquals(102, relaunch.repo.observeMoments().first().size, "the page below came off the device as well")
        } finally {
            Harness.eventsFor = null
        }
    }

    @Test
    fun aPageTheZonesEmptyReachesDownForMomentsToShow() = runTest {
        // Two full pages of street traffic the zones reject, then the evening's real moments below them.
        Harness.config = configJson
        Harness.eventsFor = { before ->
            when (before) {
                null -> streetCarsJson(2000, 100)
                1901.0 -> streetCarsJson(1900, 100)
                1801.0 -> personsJson((1800L downTo 1781L).toList(), camera = "amcrest_1")
                else -> fail("unexpected before=$before")
            }
        }
        try {
            val h = Harness(this)
            backgroundScope.launch { h.repo.observeMoments().collect {} }
            var list = h.repo.observeMoments().first()
            eventually("the moments below the traffic") {
                list = h.repo.observeMoments().first()
                list.isNotEmpty()
            }

            // Nobody tapped "Look further back": the feed went down for them, and stopped at the end.
            assertEquals((1800L downTo 1781L).map { "e$it" }, list.map { it.id })
            assertEquals(listOf("limit=100&before=1901.000", "limit=100&before=1801.000"), h.pageQueries)
            assertEquals(false, h.repo.paging().hasOlder)
            assertEquals(false, h.repo.paging().loadingOlder)
        } finally {
            Harness.eventsFor = null
            Harness.config = null
        }
    }

    @Test
    fun aCameraThatOnlySeesTheStreetStopsReachingDownAfterAFewPages() = runTest {
        Harness.config = configJson
        Harness.eventsFor = { before -> streetCarsJson(before?.toLong()?.minus(1) ?: 2000, 100) }
        try {
            val h = Harness(this)
            backgroundScope.launch { h.repo.observeMoments().collect {} }
            eventually("the feed to page down") { h.pageQueries.size == 5 }
            settle()

            // Still empty, and still offering more, but the walk down is left to the reader now —
            // however many polls have come and gone meanwhile.
            assertEquals(5, h.pageQueries.size, "a bounded walk, not the server's whole history")
            assertEquals(emptyList(), h.repo.observeMoments().first())
            assertEquals(true, h.repo.paging().hasOlder)

            h.repo.loadOlder()
            assertEquals(6, h.pageQueries.size, "asking by hand still goes further")
        } finally {
            Harness.eventsFor = null
            Harness.config = null
        }
    }

    @Test
    fun disconnectedYieldsEmptyWithoutAnyRequest() = runTest {
        Harness.events = eventsJson
        val h = Harness(this, url = null)
        assertEquals(emptyList(), h.repo.observeMoments().first())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), h.hosts)
    }
}

/** The server the tests sign in to: its identity, and the address it answers at until a route flip moves it. */
private const val SERVER_URL = "http://192.168.68.55:8971"
