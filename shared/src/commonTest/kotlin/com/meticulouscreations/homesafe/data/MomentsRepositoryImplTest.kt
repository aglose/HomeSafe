package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
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
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
    }

    private class Harness(scope: TestScope, url: String? = "http://192.168.68.55:8971", failEvents: Boolean = false) {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { req ->
            hosts += req.url.host
            when {
                failEvents -> respond("boom", HttpStatusCode.InternalServerError)
                req.url.encodedPath.endsWith("/api/events") -> respond(events, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
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
        val repo = MomentsRepositoryImpl(FrigateApiClient(client), connection, scope.backgroundScope)
        companion object {
            lateinit var events: String

            /** `/api/config`, or null to 404 it the way a test that isn't about zones expects. */
            var config: String? = null
        }
    }

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
     * The real shape of a parked car's day (Front Yard, 2026-09-07): three sightings of Sarah's
     * Tesla at the curb, each a jittery path around one spot ended by a passer-by stealing the
     * tracker, plus one genuine drive-through of the same car later. Newest first, like the API.
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
               "path_data":[[[0.84,0.53],1788786418.0],[[0.84,0.53],1788786418.5],[[0.83,0.37],1788788897.0],[[0.83,0.54],1788788897.5],[[0.72,0.50],1788799364.0],[[0.87,0.57],1788799365.0]]}}
    ]"""

    private suspend fun TestScope.eventually(what: String, cond: suspend () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (cond()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("Timed out waiting for $what")
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

            // The drive-through is its own card; the three curb sightings are one, still in progress.
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
    fun disconnectedYieldsEmptyWithoutAnyRequest() = runTest {
        Harness.events = eventsJson
        val h = Harness(this, url = null)
        assertEquals(emptyList(), h.repo.observeMoments().first())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), h.hosts)
    }
}
