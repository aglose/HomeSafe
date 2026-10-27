package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.data.InMemoryFitnessDao
import com.meticulouscreations.homesafe.fitness.FitnessTestData.DAY
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOW
import com.meticulouscreations.homesafe.fitness.data.FitnessRepositoryImpl
import com.meticulouscreations.homesafe.fitness.domain.DistanceSpan
import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.METERS_PER_MILE
import com.meticulouscreations.homesafe.fitness.domain.RouteAccess
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import com.meticulouscreations.homesafe.weather.data.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The view model's reading of a health store that does as the test says: when it is read, what
 * is asked of the user, and what becomes of what comes back. Everything in the store is dated
 * within an hour or two of noon UTC, so that it is the same local day wherever the test runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val everything = HealthKind.entries.toSet()

    /** The local day the view model takes [NOW] to be in. */
    private val today = (NOW + localUtcOffsetSeconds(NOW.toDouble())).floorDiv(DAY)

    private class Harness(available: HealthAvailability) {
        val source = FakeHealthSource(available)
        val repository = FitnessRepositoryImpl(InMemoryFitnessDao())
        val clock = MutableClock(NOW)
        val vm = HealthViewModel(source, repository, clock)
        val state get() = vm.uiState.value
    }

    private fun runHealthTest(available: HealthAvailability = HealthAvailability.AVAILABLE, body: suspend TestScope.(Harness) -> Unit) =
        runTest(dispatcher) { body(Harness(available)) }

    /** A three-mile run that began two hours ago, with a track fine enough to split. */
    private val run = RunRecord("r1", NOW - 7_200, NOW - 5_400, meters = 3 * METERS_PER_MILE, activeSeconds = 1_800, averageBpm = 150, route = RouteAccess.ASK)
    private val track = RunTrack(
        heart = (0 until 1_800 step 10).map { HealthSample(run.startEpochSeconds + it, 150.0) },
        distance = (0 until 30).map { DistanceSpan(run.startEpochSeconds + it * 60L, run.startEpochSeconds + (it + 1) * 60L, METERS_PER_MILE / 10) },
    )

    /** A store with a week of steps, a month of nights and readings, and the run. */
    private fun fill(source: FakeHealthSource) {
        source.steps = mapOf(today to 4_200L, today - 1 to 8_000L, today - 2 to 6_000L)
        source.sleeps = (0L..20L).map { SleepSession(NOW - 3_600 - it * DAY - 7 * 3_600, NOW - 3_600 - it * DAY) }
        source.resting = (0L..20L).map { HealthSample(NOW - 3_600 - it * DAY, 55.0) }
        source.variability = (0L..20L).map { HealthSample(NOW - 3_600 - it * DAY, 50.0) }
        source.runRecords = listOf(run)
        source.tracks = mapOf(run.id to track)
    }

    private fun TestScope.open(h: Harness) {
        h.vm.setActive(true)
        runCurrent()
    }

    // ---- Access -------------------------------------------------------------------------------

    @Test
    fun whereThereIsNoStoreNothingIsReadOrShown() = runHealthTest(HealthAvailability.UNSUPPORTED) { h ->
        open(h)
        assertEquals(HealthAccess.UNSUPPORTED, h.state.access)
        assertFalse(h.state.supported)
        assertEquals(emptyList(), h.source.reads)
    }

    @Test
    fun aStoreThatIsNotInstalledIsToBeGot() = runHealthTest(HealthAvailability.MISSING) { h ->
        assertEquals(HealthAccess.MISSING, h.state.access)
        open(h)
        assertEquals(HealthAccess.MISSING, h.state.access)
        h.vm.connect()
        runCurrent()
        // Nothing to ask for yet: the button goes to where the store is got.
        assertEquals(1, h.source.managed)
        assertEquals(0, h.source.asked)
    }

    @Test
    fun beforeTheStoreIsAskedItIsNotTakenToBeOff() = runHealthTest { h ->
        h.source.allowed = everything
        // A view model is made afresh with each activity: it mustn't offer to connect what may be connected.
        assertEquals(HealthAccess.CHECKING, h.state.access)
        assertTrue(h.state.supported)
        assertFalse(h.state.loaded)
    }

    @Test
    fun aConnectedStoreIsOnWhileItIsStillBeingRead() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        val held = CompletableDeferred<Unit>()
        h.source.runsHeld = held
        open(h)
        // The card reads "reading", not "connect", and the runs door waits for its runs.
        assertEquals(HealthAccess.ON, h.state.access)
        assertFalse(h.state.loaded)
        assertFalse(h.state.runsShared)
        held.complete(Unit)
        runCurrent()
        assertTrue(h.state.loaded)
        assertTrue(h.state.runsShared)
        assertEquals(everything, h.state.granted)
    }

    @Test
    fun aStoreThatCantSayWhatIsAllowedDoesNotTurnTheCardOff() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        h.source.grantsFailing = true
        h.clock.seconds += 120
        open(h)
        assertEquals(HealthAccess.ON, h.state.access)
        assertTrue(h.state.loaded)
        assertTrue(h.state.failed)
        assertEquals(4_200L, h.state.body.steps?.today)
        // Back again, it is read afresh.
        h.source.grantsFailing = false
        h.clock.seconds += 120
        open(h)
        assertFalse(h.state.failed)
    }

    @Test
    fun beforeTheUserHasAllowedAnythingNothingIsRead() = runHealthTest { h ->
        fill(h.source)
        open(h)
        assertEquals(HealthAccess.OFF, h.state.access)
        assertFalse(h.state.loaded)
        assertEquals(emptyList(), h.source.reads)
        // Opening the app is not asking: only the button does that.
        assertEquals(0, h.source.asked)
    }

    @Test
    fun connectingAsksAndThenReads() = runHealthTest { h ->
        fill(h.source)
        h.source.grantsWhenAsked = everything
        open(h)
        h.vm.connect()
        runCurrent()
        assertEquals(1, h.source.asked)
        assertEquals(HealthAccess.ON, h.state.access)
        assertEquals(everything, h.state.granted)
        assertTrue(h.state.loaded)
        assertFalse(h.state.refused)
        assertEquals(4_200L, h.state.body.steps?.today)
        assertNotNull(h.state.body.sleep?.lastNight)
        assertEquals(75, h.state.body.readiness?.score)
        assertEquals(listOf("r1"), h.state.runs.runs.map { it.id })
        assertTrue(h.state.bodyShared)
        assertTrue(h.state.runsShared)
        assertEquals(NOW, h.state.nowEpochSeconds)
    }

    @Test
    fun oneRefusalIsAnAnswerAndNotTheEndOfAsking() = runHealthTest { h ->
        h.source.grantsWhenAsked = emptySet()
        open(h)
        h.vm.connect()
        runCurrent()
        assertEquals(1, h.source.asked)
        assertEquals(HealthAccess.OFF, h.state.access)
        // The sheet came up and was turned down. It will come up again.
        assertFalse(h.state.refused)
    }

    @Test
    fun whenAskingHasComeToNothingTwiceItPointsAtTheStoresOwnPage() = runHealthTest { h ->
        // After two refusals Android no longer shows the sheet, and the request just comes back.
        h.source.grantsWhenAsked = null
        open(h)
        h.vm.connect()
        runCurrent()
        h.vm.connect()
        runCurrent()
        assertEquals(2, h.source.asked)
        assertEquals(HealthAccess.OFF, h.state.access)
        assertTrue(h.state.refused)
        // Allowed over there after all: there is nothing left to point at.
        h.source.allowed = setOf(HealthKind.STEPS)
        h.clock.seconds += 120
        open(h)
        assertFalse(h.state.refused)
    }

    @Test
    fun aRefusalThenAnAllowingStartsTheCountAgain() = runHealthTest { h ->
        h.source.grantsWhenAsked = emptySet()
        open(h)
        h.vm.connect()
        runCurrent()
        h.source.grantsWhenAsked = setOf(HealthKind.STEPS)
        h.vm.connect()
        runCurrent()
        assertEquals(HealthAccess.ON, h.state.access)
        assertFalse(h.state.refused)
    }

    @Test
    fun allowingOnlySomeIsNotARefusal() = runHealthTest { h ->
        fill(h.source)
        h.source.grantsWhenAsked = setOf(HealthKind.STEPS)
        open(h)
        h.vm.connect()
        runCurrent()
        assertEquals(HealthAccess.ON, h.state.access)
        assertFalse(h.state.refused)
    }

    @Test
    fun onlyWhatIsAllowedIsRead() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = setOf(HealthKind.STEPS, HealthKind.EXERCISE)
        open(h)
        assertEquals(listOf("steps", "runs"), h.source.reads)
        assertNotNull(h.state.body.steps)
        // Not shared, which is not the same as nothing recorded.
        assertNull(h.state.body.sleep)
        assertNull(h.state.body.readiness)
        assertFalse(h.state.body.readinessShared)
        assertTrue(h.state.runsShared)
    }

    @Test
    fun withOnlyRunsSharedThereIsNothingForTheBodyCard() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = setOf(HealthKind.EXERCISE, HealthKind.DISTANCE)
        open(h)
        assertFalse(h.state.bodyShared)
        assertTrue(h.state.runsShared)
    }

    @Test
    fun accessTakenAwayClearsWhatWasShown() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        assertTrue(h.state.loaded)
        h.source.allowed = emptySet()
        h.clock.seconds += 120
        open(h)
        assertEquals(HealthAccess.OFF, h.state.access)
        assertFalse(h.state.loaded)
        assertNull(h.state.body.steps)
        assertEquals(emptyList(), h.state.runs.runs)
    }

    // ---- When it is read ----------------------------------------------------------------------

    @Test
    fun comingBackWithinAMinuteDoesNotReadAgain() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        val once = h.source.reads.size
        h.vm.setActive(false)
        h.clock.seconds += 30
        open(h)
        assertEquals(once, h.source.reads.size)
        h.clock.seconds += 31
        open(h)
        assertEquals(once * 2, h.source.reads.size)
    }

    @Test
    fun comingBackFromTheStoresOwnPageReadsAtOnce() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = setOf(HealthKind.STEPS)
        open(h)
        assertNull(h.state.body.sleep)
        h.vm.manage()
        assertEquals(1, h.source.managed)
        // Sleep was switched on over there.
        h.source.allowed = setOf(HealthKind.STEPS, HealthKind.SLEEP)
        open(h)
        assertNotNull(h.state.body.sleep)
    }

    @Test
    fun aStoreThatCannotBeReachedLeavesWhatWasThere() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        h.source.failing = setOf(HealthKind.STEPS)
        h.source.steps = mapOf(today to 9_999L)
        h.clock.seconds += 120
        open(h)
        assertTrue(h.state.failed)
        assertEquals(4_200L, h.state.body.steps?.today)
        // And once it is back, what is there now.
        h.source.failing = emptySet()
        h.clock.seconds += 120
        open(h)
        assertFalse(h.state.failed)
        assertEquals(9_999L, h.state.body.steps?.today)
    }

    @Test
    fun whatIsNoLongerSharedGoesEvenWhenAnotherReadFails() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        assertNotNull(h.state.body.sleep)
        assertNotNull(h.state.body.readiness)
        // Sleep and the heart's readings are switched off in the store, and the steps can't be reached.
        h.source.allowed = everything - setOf(HealthKind.SLEEP, HealthKind.HEART_RATE_VARIABILITY, HealthKind.RESTING_HEART_RATE)
        h.source.failing = setOf(HealthKind.STEPS)
        h.clock.seconds += 120
        open(h)
        assertTrue(h.state.failed)
        // What couldn't be read is as it was; what was taken away is gone.
        assertEquals(4_200L, h.state.body.steps?.today)
        assertNull(h.state.body.sleep)
        assertNull(h.state.body.readiness)
        assertFalse(h.state.body.readinessShared)
        assertTrue(h.state.partlyShared)
    }

    @Test
    fun runsThatCannotBeReadStayAsTheyWere() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        h.source.failing = setOf(HealthKind.EXERCISE)
        h.clock.seconds += 120
        open(h)
        assertTrue(h.state.failed)
        assertEquals(listOf("r1"), h.state.runs.runs.map { it.id })
    }

    @Test
    fun aFirstReadThatPartlyFailsShowsWhatItCould() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        h.source.failing = setOf(HealthKind.SLEEP)
        open(h)
        assertTrue(h.state.loaded)
        assertTrue(h.state.failed)
        assertEquals(4_200L, h.state.body.steps?.today)
        assertNull(h.state.body.sleep)
    }

    // ---- A run --------------------------------------------------------------------------------

    @Test
    fun openingARunReadsItsTrack() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        assertNull(h.state.detail)
        h.vm.openRun("r1")
        runCurrent()
        val detail = assertNotNull(h.state.detail)
        assertEquals("r1", detail.runId)
        assertEquals(listOf(600L, 600L, 600L), detail.splits.map { it.seconds })
        assertEquals(180, detail.heart.size)
        // No zones have been set, so there is no time in them.
        assertNull(detail.summary)
        assertEquals(1, h.source.reads.count { it == "track r1" })
        // Opened again, it is already there.
        h.vm.openRun("r1")
        runCurrent()
        assertEquals(1, h.source.reads.count { it == "track r1" })
    }

    @Test
    fun aRunIsDrawnInTheZonesThatAreSet() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        h.repository.saveHeartProfile(HeartProfile(maxBpm = 190))
        open(h)
        h.vm.openRun("r1")
        runCurrent()
        // 150 under a maximum of 190 is zone 3, the whole half hour.
        assertEquals(listOf(0L, 0L, 1_800_000L, 0L, 0L), h.state.detail?.summary?.zoneMillis)
        // The zones change while it is open: it is drawn again in the new ones. 150 under 160 is zone 5.
        h.repository.saveHeartProfile(HeartProfile(maxBpm = 160))
        runCurrent()
        assertEquals(listOf(0L, 0L, 0L, 0L, 1_800_000L), h.state.detail?.summary?.zoneMillis)
    }

    @Test
    fun anOpenRunLosesWhatStopsBeingShared() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        h.source.routes = mapOf("r1" to listOf(RoutePoint(45.0, -93.0), RoutePoint(45.01, -93.01)))
        open(h)
        h.vm.openRun("r1")
        runCurrent()
        h.vm.askForRoute()
        runCurrent()
        assertEquals(180, h.state.detail?.heart?.size)
        // The heart rate is switched off in the store while the run is open.
        h.source.allowed = everything - HealthKind.HEART_RATE
        h.clock.seconds += 120
        open(h)
        val detail = assertNotNull(h.state.detail)
        assertEquals(emptyList(), detail.heart)
        // The distance is still shared, so the miles are still there, and so is the route that was handed over.
        assertEquals(3, detail.splits.size)
        assertEquals(2, detail.route.size)
        assertEquals(2, h.source.reads.count { it == "track r1" })
        // And switched back on, it is read again.
        h.source.allowed = everything
        h.clock.seconds += 120
        open(h)
        assertEquals(180, h.state.detail?.heart?.size)
    }

    @Test
    fun withTheSamePermissionsAnOpenRunIsNotReadAgain() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        h.vm.openRun("r1")
        runCurrent()
        h.clock.seconds += 120
        open(h)
        assertEquals(1, h.source.reads.count { it == "track r1" })
        assertNotNull(h.state.detail)
    }

    @Test
    fun aRunWhoseTrackCannotBeReadStillOpens() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        h.source.failing = setOf(HealthKind.EXERCISE)
        h.vm.openRun("r1")
        runCurrent()
        val detail = assertNotNull(h.state.detail)
        assertEquals(emptyList(), detail.splits)
        assertEquals(emptyList(), detail.heart)
    }

    @Test
    fun aRunThatIsNotThereIsNotOpened() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        h.vm.openRun("nobody")
        runCurrent()
        assertNull(h.state.detail)
    }

    @Test
    fun aRouteIsAskedForAndDrawn() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        val route = listOf(RoutePoint(45.0, -93.0), RoutePoint(45.01, -93.01))
        h.source.routes = mapOf("r1" to route)
        open(h)
        h.vm.openRun("r1")
        runCurrent()
        assertEquals(emptyList(), h.state.detail?.route)
        h.vm.askForRoute()
        runCurrent()
        assertEquals(route, h.state.detail?.route)
        assertTrue("route r1" in h.source.reads)
    }

    @Test
    fun aRouteTheUserKeepsBackChangesNothing() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        h.vm.openRun("r1")
        runCurrent()
        val before = h.state.detail
        h.vm.askForRoute()
        runCurrent()
        assertEquals(before, h.state.detail)
    }

    @Test
    fun aRunDeletedInTheStoreClosesWhenItIsNextRead() = runHealthTest { h ->
        fill(h.source)
        h.source.allowed = everything
        open(h)
        h.vm.openRun("r1")
        runCurrent()
        assertNotNull(h.state.detail)
        h.source.runRecords = emptyList()
        h.clock.seconds += 120
        open(h)
        assertNull(h.state.detail)
        assertEquals(emptyList(), h.state.runs.runs)
    }
}
