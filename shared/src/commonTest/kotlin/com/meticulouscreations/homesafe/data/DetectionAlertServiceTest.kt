package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.PresenceSource
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionAlertServiceTest {

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

    private class FakeSettings(initial: AlertSettings) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override fun observeSettings(): Flow<AlertSettings> = state
        override suspend fun updateSettings(settings: AlertSettings) {
            state.value = settings
        }
    }

    private class FakePresence : PresenceRepository {
        override val presence = MutableStateFlow(HouseholdPresence.EMPTY)
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun setThisDeviceAway(away: Boolean, source: PresenceSource, dwellSeconds: Int) = Result.success(Unit)
        override suspend fun setHome(home: HomeLocation?) = Result.success(Unit)

        fun everyoneAway(away: Boolean) {
            presence.value = HouseholdPresence(
                devices = listOf(PresenceDevice("Pixel", "android", away, 1.0, isThisDevice = true), PresenceDevice("iPhone", "ios", away, 1.0)),
                everyoneAway = away,
            )
        }
    }

    private class FakeNotifier(override val isSupported: Boolean = true) : AlertNotifier {
        val posted = mutableListOf<AlertNotification>()
        override suspend fun permissionStatus() = NotificationPermission.GRANTED
        override suspend fun requestPermission() = true
        override fun openSystemSettings() = Unit
        override fun notify(notification: AlertNotification) {
            posted += notification
        }
    }

    /** A fake Frigate whose `/api/events` honours `after` the way the real one does (start_time strictly after). */
    private class Harness(scope: TestScope, settings: AlertSettings, now: Double = 1_000_000.0, supported: Boolean = true) {
        val events = mutableListOf<Triple<String, String, Double>>() // id, label, start

        /** Zones an event passed through, by id; absent means none. */
        val zonesById = mutableMapOf<String, List<String>>()

        /** A recognised face (or plate) Frigate attached, by id; absent means none yet. */
        val subLabelById = mutableMapOf<String, String>()

        /** When an event ended, by id; absent means still in progress. */
        val endedById = mutableMapOf<String, Double>()

        /** Where the object went (Frigate's `path_data` points), by id; absent means no path. */
        val pathById = mutableMapOf<String, List<Pair<Double, Double>>>()

        /** The best frame's box `[x, y, w, h]`, by id; absent means Frigate sent none. */
        val boxById = mutableMapOf<String, List<Double>>()

        /** The camera, by id; absent means the Front Door. */
        val cameraById = mutableMapOf<String, String>()

        /** `/api/config`, or null to 404 it (no zones drawn anywhere). */
        var config: String? = null
        val afters = mutableListOf<String>()
        private val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/api/events") -> {
                    val after = req.url.parameters["after"]!!.also { afters += it }.toDouble()
                    val body = events.filter { it.third > after }.sortedByDescending { it.third }.joinToString(",", "[", "]") { (id, label, start) ->
                        val zones = zonesById[id].orEmpty().joinToString(",", "[", "]") { "\"$it\"" }
                        val subLabel = subLabelById[id]?.let { "\"$it\"" } ?: "null"
                        val end = endedById[id]?.toString() ?: "null"
                        val path = pathById[id].orEmpty().joinToString(",", "[", "]") { (x, y) -> "[[$x,$y],$start]" }
                        val box = boxById[id]?.joinToString(",", ",\"box\":[", "]").orEmpty()
                        val camera = cameraById[id] ?: "amcrest_1"
                        """{"id":"$id","label":"$label","sub_label":$subLabel,"camera":"$camera","start_time":$start,"end_time":$end,"has_clip":false,"has_snapshot":false,"zones":$zones,"data":{"type":"object","path_data":$path$box}}"""
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                req.url.encodedPath.contains("/thumbnail.jpg") -> respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))

                req.url.encodedPath.endsWith("/api/config") && config != null -> respond(config!!, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        private val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val connection = FakeConnection("http://192.168.68.55:8971")
        val settingsRepo = FakeSettings(settings)
        val presenceRepo = FakePresence()
        val notifier = FakeNotifier(supported)
        var clockNow = now
        val service = DetectionAlertService(
            apiClient = FrigateApiClient(client),
            connectionRepository = connection,
            settingsRepository = settingsRepo,
            presenceRepository = presenceRepo,
            notifier = notifier,
            scope = scope.backgroundScope,
            clock = { clockNow },
            pollIntervalMs = 100,
        )
    }

    private suspend fun TestScope.eventually(what: String, cond: suspend () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (cond()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("Timed out waiting for $what")
    }

    private suspend fun TestScope.settle() {
        repeat(8) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(25) }
        }
    }

    private val on = AlertSettings(pushNotificationsEnabled = true)
    private val anywhere = AlertZone("amcrest_1", null)

    @Test
    fun onlyDetectionsAfterSwitchingOnNotifyAndEachOnlyOnce() = runTest {
        val h = Harness(this, on)
        h.events += Triple("old", "person", 999_990.0)   // before the baseline: history, not news
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }
        assertEquals("1000000.000", h.afters.first(), "the baseline is the moment polling started")
        assertTrue(h.notifier.posted.isEmpty())

        h.events += Triple("fresh", "person", 1_000_005.0)
        eventually("the new detection") { h.notifier.posted.size == 1 }
        val posted = h.notifier.posted.single()
        assertEquals("fresh", posted.id)
        assertEquals("Person detected", posted.title)
        assertTrue(posted.body.startsWith("Front Door · "), posted.body)
        assertEquals(listOf<Byte>(1, 2, 3), posted.thumbnail!!.toList())

        settle()
        assertEquals(1, h.notifier.posted.size, "later polls must not repeat it")
    }

    @Test
    fun zoneRulesFilterWithoutRestartingTheBaseline() = runTest {
        val h = Harness(this, on)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.events += Triple("dog", "dog", 1_000_001.0)   // animals are off by default
        settle()
        assertTrue(h.notifier.posted.isEmpty(), "an unwanted category is silent")

        h.settingsRepo.state.value = on.withCategory(anywhere, MomentCategory.ANIMALS, true)
        h.events += Triple("dog2", "dog", 1_000_002.0)
        eventually("the second dog") { h.notifier.posted.map { it.id } == listOf("dog2") }
        assertTrue(h.notifier.posted.none { it.id == "dog" }, "flipping a rule doesn't replay what was skipped")
    }

    @Test
    fun aZoneThatDoesNotWantTheLabelSilencesItInsteadOfCountingAsAnywhereElse() = runTest {
        // Every category on, everywhere: only the zone's own object filter can keep a car quiet.
        val h = Harness(this, on.withCategory(anywhere, MomentCategory.ANIMALS, true))
        h.config = """{"cameras":{"amcrest_1":{"zones":{
          "street":{"coordinates":"0.0,0.0,1.0,0.0,1.0,0.5,0.0,0.5","objects":["bird"]},
          "lawn":{"coordinates":"0.0,0.5,1.0,0.5,1.0,1.0,0.0,1.0","objects":[]}
        }}}}"""
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.pathById["street-car"] = listOf(0.5 to 0.2, 0.7 to 0.3)   // Frigate leaves its zones empty: not a bird
        h.events += Triple("street-car", "car", 1_000_001.0)
        settle()
        assertTrue(h.notifier.posted.isEmpty(), "a car on the birds-only street is not 'anywhere else'")

        h.pathById["lawn-car"] = listOf(0.5 to 0.2, 0.5 to 0.8, 0.5 to 0.9)
        h.events += Triple("lawn-car", "car", 1_000_002.0)
        eventually("the lawn car") { h.notifier.posted.map { it.id } == listOf("lawn-car") }
        assertEquals("Car on the lawn", h.notifier.posted.single().title, "placed from its path, not from Frigate's empty tag list")

        h.events += Triple("stray", "cat", 1_000_003.0)   // no path, no zones: on a camera with zones, that's noise
        settle()
        assertEquals(listOf("lawn-car"), h.notifier.posted.map { it.id }, "an unrecognised object outside every zone is silent")

        h.subLabelById["tesla"] = "sarahs_tesla"   // recognised: shows wherever it is, street included
        h.pathById["tesla"] = listOf(0.5 to 0.2)
        h.events += Triple("tesla", "car", 1_000_004.0)
        eventually("the recognised car") { h.notifier.posted.map { it.id } == listOf("lawn-car", "tesla") }
        assertEquals("Sarah's Tesla on the street", h.notifier.posted.last().title)
    }

    @Test
    fun aDetectionIsJudgedByTheZonesItPassedThrough() = runTest {
        val driveway = AlertZone("amcrest_1", "driveway")
        val h = Harness(this, on.withCategory(driveway, MomentCategory.PEOPLE, false))
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.zonesById["walker"] = listOf("driveway")
        h.events += Triple("walker", "person", 1_000_001.0)
        settle()
        assertTrue(h.notifier.posted.isEmpty(), "people are muted in the driveway")

        h.events += Triple("passerby", "person", 1_000_002.0)   // no zone: the camera's "anywhere else", still on
        eventually("the passer-by") { h.notifier.posted.map { it.id } == listOf("passerby") }

        h.zonesById["car"] = listOf("driveway")
        h.events += Triple("car", "car", 1_000_003.0)   // vehicles in the driveway were never turned off
        eventually("the car") { h.notifier.posted.map { it.id } == listOf("passerby", "car") }
    }

    @Test
    fun switchingOffStopsPollingAndOnAgainStartsFresh() = runTest {
        val h = Harness(this, on)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.settingsRepo.state.value = on.copy(pushNotificationsEnabled = false)
        settle()
        val pollsWhenOff = h.afters.size
        settle()
        assertEquals(pollsWhenOff, h.afters.size, "no requests while off")

        h.events += Triple("while_off", "person", 1_000_010.0)
        h.clockNow = 1_000_020.0
        h.settingsRepo.state.value = on
        eventually("polling resumes") { h.afters.size > pollsWhenOff }
        settle()
        assertTrue(h.notifier.posted.isEmpty(), "what happened while off is history, not a burst on re-enable")
        assertEquals("1000020.000", h.afters.last())
    }

    @Test
    fun whenEveryoneIsAwayAnyPersonNotifiesUrgentlyDespiteZoneRules() = runTest {
        val driveway = AlertZone("amcrest_1", "driveway")
        val h = Harness(this, on.withCategory(driveway, MomentCategory.PEOPLE, false))
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.zonesById["muted"] = listOf("driveway")
        h.events += Triple("muted", "person", 1_000_001.0)
        settle()
        assertTrue(h.notifier.posted.isEmpty(), "at home, the driveway rule holds")

        h.presenceRepo.everyoneAway(true)
        h.zonesById["intruder"] = listOf("driveway")
        h.events += Triple("intruder", "person", 1_000_002.0)
        eventually("the escalated person") { h.notifier.posted.map { it.id } == listOf("intruder") }
        val urgent = h.notifier.posted.single()
        assertTrue(urgent.urgent, "away mode posts on the loud channel")
        assertEquals("Away: Person in the driveway", urgent.title)

        h.events += Triple("dog", "dog", 1_000_003.0)   // animals stay off: away mode is about people
        h.zonesById["car"] = listOf("driveway")
        h.events += Triple("car", "car", 1_000_004.0)   // vehicles in the driveway follow the normal rules, not escalated
        eventually("the car") { h.notifier.posted.map { it.id } == listOf("intruder", "car") }
        assertFalse(h.notifier.posted.last().urgent)
        assertEquals("Car in the driveway", h.notifier.posted.last().title)

        h.presenceRepo.everyoneAway(false)
        h.zonesById["home_again"] = listOf("driveway")
        h.events += Triple("home_again", "person", 1_000_005.0)
        settle()
        assertEquals(listOf("intruder", "car"), h.notifier.posted.map { it.id }, "back home, the driveway rule holds again")
        assertTrue(h.presenceRepo.presence.subscriptionCount.value > 0, "the poller keeps presence collected so it stays fresh")
    }

    /** A driveway spot and the jittery path a parked car produces there. */
    private val spot = listOf(0.25, 0.40, 0.19, 0.16)
    private val parkedPath = listOf(0.35 to 0.57, 0.31 to 0.66, 0.35 to 0.57, 0.31 to 0.66)
    private val driveThrough = List(20) { 0.05 + it * 0.0474 to 0.57 }

    @Test
    fun aCarNotifiesOncePerVisitAndItsRedetectionsStayQuiet() = runTest {
        val h = Harness(this, on)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.boxById["parked"] = spot
        h.pathById["parked"] = parkedPath
        h.events += Triple("parked", "car", 1_000_001.0)
        eventually("the parked car") { h.notifier.posted.map { it.id } == listOf("parked") }

        // Forty minutes and then ninety: the tracker was stolen and Frigate saw the car anew, at the same spot.
        h.clockNow = 1_002_401.0
        h.boxById["again"] = spot
        h.pathById["again"] = parkedPath
        h.events += Triple("again", "car", 1_002_401.0)
        settle()
        h.clockNow = 1_005_401.0
        h.boxById["and-again"] = spot
        h.pathById["and-again"] = parkedPath
        h.events += Triple("and-again", "car", 1_005_401.0)
        settle()
        assertEquals(listOf("parked"), h.notifier.posted.map { it.id }, "a still car where one was already reported is the same car")

        // Pulling out a minute later is the tail of the same visit, not a second alert.
        h.boxById["leaving"] = spot
        h.pathById["leaving"] = driveThrough
        h.events += Triple("leaving", "car", 1_005_460.0)
        settle()
        assertEquals(listOf("parked"), h.notifier.posted.map { it.id }, "a move inside the visit window is the same visit")

        // Half an hour on, a car moving into that spot is a new arrival worth hearing about.
        h.clockNow = 1_007_300.0
        h.boxById["returning"] = spot
        h.pathById["returning"] = driveThrough
        h.events += Triple("returning", "car", 1_007_300.0)
        eventually("the return") { h.notifier.posted.map { it.id } == listOf("parked", "returning") }

        h.clockNow = 1_007_300.0 + 6 * 3600 + 1   // the memory has expired: a car here now is news again
        h.boxById["next-day"] = spot
        h.pathById["next-day"] = parkedPath
        h.events += Triple("next-day", "car", h.clockNow)
        eventually("the next day's car") { h.notifier.posted.map { it.id } == listOf("parked", "returning", "next-day") }
    }

    @Test
    fun aStillCarAtAnotherSpotOrOnAnotherCameraStillNotifies() = runTest {
        val h = Harness(this, on)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.boxById["parked"] = spot
        h.pathById["parked"] = parkedPath
        h.events += Triple("parked", "car", 1_000_001.0)
        eventually("the parked car") { h.notifier.posted.map { it.id } == listOf("parked") }

        h.boxById["elsewhere"] = listOf(0.60, 0.30, 0.12, 0.10)
        h.pathById["elsewhere"] = listOf(0.66 to 0.40, 0.66 to 0.41)
        h.events += Triple("elsewhere", "car", 1_000_060.0)
        eventually("the car at another spot") { h.notifier.posted.map { it.id } == listOf("parked", "elsewhere") }

        h.boxById["front-yard"] = spot
        h.pathById["front-yard"] = parkedPath
        h.cameraById["front-yard"] = "hikvision_1"
        h.events += Triple("front-yard", "car", 1_000_120.0)
        eventually("the car on the other camera") { h.notifier.posted.map { it.id } == listOf("parked", "elsewhere", "front-yard") }
        assertTrue(h.notifier.posted.last().body.startsWith("Front Yard · "), h.notifier.posted.last().body)
    }

    @Test
    fun unsupportedPlatformNeverPolls() = runTest {
        val h = Harness(this, on, supported = false)
        h.service.start()
        settle()
        assertTrue(h.afters.isEmpty())
    }

    @Test
    fun aNamedPersonNotifiesByNameWhenEveryoneIsWanted() = runTest {
        val h = Harness(this, on)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.subLabelById["andrew"] = "andrew"
        h.events += Triple("andrew", "person", 1_000_001.0)
        eventually("Andrew") { h.notifier.posted.size == 1 }
        assertEquals("Andrew detected", h.notifier.posted.single().title)
        assertTrue(h.notifier.posted.single().body.endsWith(" · Andrew"), h.notifier.posted.single().body)
    }

    @Test
    fun onlyStrangersSkipsRecognisedPeopleAndPostsStrangersOnce() = runTest {
        val h = Harness(this, on.copy(quietFamiliarPeople = true))
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.subLabelById["andrew"] = "andrew"
        h.events += Triple("andrew", "person", 1_000_001.0)
        h.endedById["stranger"] = 1_000_008.0   // already gone: nothing more to wait for
        h.events += Triple("stranger", "person", 1_000_002.0)
        eventually("the stranger") { h.notifier.posted.map { it.id } == listOf("stranger") }
        settle()
        assertEquals(listOf("stranger"), h.notifier.posted.map { it.id }, "Andrew never notifies, the stranger only once")
    }

    @Test
    fun anAnonymousPersonIsHeldUntilTheirFaceIsNamedOrTheGraceRunsOut() = runTest {
        val h = Harness(this, on.copy(quietFamiliarPeople = true), now = 1_000_000.0)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.clockNow = 1_000_003.0
        h.events += Triple("visitor", "person", 1_000_001.0)   // still in frame, face not yet placed
        h.events += Triple("walker", "person", 1_000_002.0)
        settle()
        assertTrue(h.notifier.posted.isEmpty(), "held while recognition may still catch up")

        h.subLabelById["visitor"] = "sarah"                   // recognised a few seconds in: family, stays quiet
        settle()                                               // (the fake server reads the map on its own thread; let a poll see it before the clock moves)
        assertTrue(h.notifier.posted.isEmpty(), "a recognised visitor is decided quietly")
        h.clockNow = 1_000_030.0                               // past the grace for the walker
        eventually("the walker") { h.notifier.posted.map { it.id } == listOf("walker") }
        settle()
        assertEquals(listOf("walker"), h.notifier.posted.map { it.id }, "the recognised visitor never notifies; the walker only once")
    }

    @Test
    fun withOnlyStrangersOffNobodyIsHeld() = runTest {
        val h = Harness(this, on, now = 1_000_000.0)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.clockNow = 1_000_003.0
        h.events += Triple("visitor", "person", 1_000_001.0)
        eventually("the visitor") { h.notifier.posted.map { it.id } == listOf("visitor") }
    }
}
