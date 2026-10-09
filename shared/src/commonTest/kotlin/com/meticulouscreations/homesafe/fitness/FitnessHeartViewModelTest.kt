package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.data.InMemoryFitnessDao
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOW
import com.meticulouscreations.homesafe.fitness.data.FitnessRepositoryImpl
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartRecovery
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.domain.ZoneTracker
import com.meticulouscreations.homesafe.weather.data.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * The view model's side of the heart rate, over the real repository and a monitor that does as
 * it is told: when the sensor is listened to, what a reading becomes on screen, and what a
 * workout's heart adds up to. The zones throughout are a maximum of 190: 95, 114, 133, 152, 171.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FitnessHeartViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Harness(supported: Boolean = true) {
        val repository = FitnessRepositoryImpl(InMemoryFitnessDao())
        val monitor = FakeHeartRateMonitor(supported)
        val clock = MutableClock(NOW)
        val vm = FitnessViewModel(repository, monitor, clock)

        /** The time of the last reading, moved on by each one. */
        var at = NOW * 1000

        val heart get() = vm.heart.value

        /** A reading [afterMillis] on from the last. */
        fun read(bpm: Int?, afterMillis: Long = 1_000) {
            at += afterMillis
            monitor.read(bpm, at)
        }
    }

    private fun runHeartTest(supported: Boolean = true, body: suspend TestScope.(Harness) -> Unit) = runTest(dispatcher) {
        val h = Harness(supported)
        try {
            body(h)
        } finally {
            // Stops the minute tick, which would otherwise keep a finished test's clock turning for ever.
            h.vm.setActive(false)
        }
    }

    private val benchId = Exercise.idFor(BodyPart.CHEST, "Bench press")

    /** The band chosen, the zones set, and the app on screen. */
    private suspend fun TestScope.ready(h: Harness) {
        h.repository.saveHeartSensor(TEST_BAND)
        h.repository.saveHeartProfile(HeartProfile(maxBpm = 190))
        h.vm.setActive(true)
        runCurrent()
    }

    /** As [ready], with a chest workout open and a bench press to log. */
    private suspend fun TestScope.working(h: Harness): Long {
        ready(h)
        h.vm.addExercise("Bench press", BodyPart.CHEST)
        h.vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()
        return assertNotNull(h.vm.uiState.value.workout).workout.id
    }

    // ---- When the sensor is listened to -------------------------------------------------------

    @Test
    fun whereThereIsNoBluetoothTheHeartIsUnsupportedAndNothingIsAsked() = runHeartTest(supported = false) { h ->
        ready(h)
        assertFalse(h.heart.supported)
        assertFalse(h.heart.wanted)
        assertEquals(emptyList(), h.monitor.calls.filter { it != "stop" })
    }

    @Test
    fun withNoSensorChosenNothingIsListenedFor() = runHeartTest { h ->
        h.vm.setActive(true)
        runCurrent()
        assertTrue(h.heart.supported)
        assertFalse(h.heart.wanted)
        assertEquals(emptyList(), h.monitor.calls)
    }

    @Test
    fun theChosenSensorIsFollowedOnceTheAppItselfIsOnScreenWithoutAsking() = runHeartTest { h ->
        ready(h)
        assertEquals(listOf("follow ${TEST_BAND.address}"), h.monitor.calls)
        assertTrue(h.heart.wanted)
        assertEquals(TEST_BAND, h.heart.settings.sensor)
    }

    @Test
    fun theDrawersCardAloneDoesNotWakeTheSensor() = runHeartTest { h ->
        h.repository.saveHeartSensor(TEST_BAND)
        h.vm.setActive(true, full = false)
        runCurrent()
        assertEquals(emptyList(), h.monitor.calls)
    }

    @Test
    fun leavingTheScreenLetsTheSensorGoAndComingBackFindsItAgain() = runHeartTest { h ->
        ready(h)
        h.vm.setActive(false)
        runCurrent()
        assertEquals("stop", h.monitor.calls.last())
        assertEquals(HeartSensorState.Off, h.heart.sensor)

        h.vm.setActive(true)
        runCurrent()
        assertEquals(listOf("follow ${TEST_BAND.address}", "stop", "follow ${TEST_BAND.address}"), h.monitor.calls)
    }

    @Test
    fun beingToldItIsOnScreenAgainDoesNotStartOver() = runHeartTest { h ->
        ready(h)
        h.vm.setActive(true)
        h.vm.setActive(true)
        runCurrent()
        assertEquals(1, h.monitor.calls.size)
    }

    // ---- Choosing a sensor --------------------------------------------------------------------

    @Test
    fun connectWithNoSensorLooksForSomeToChooseFrom() = runHeartTest { h ->
        h.vm.setActive(true)
        runCurrent()
        h.vm.connectHeart()
        runCurrent()
        assertEquals(listOf("search"), h.monitor.calls)
        assertEquals(HeartSensorState.Searching(), h.heart.sensor)
        assertTrue(h.heart.wanted)
    }

    @Test
    fun choosingASensorRemembersItAndFollowsItAskingForBluetooth() = runHeartTest { h ->
        h.vm.setActive(true)
        runCurrent()
        h.vm.connectHeart()
        h.vm.chooseHeartSensor(TEST_BAND)
        runCurrent()
        assertEquals(listOf("search", "follow ${TEST_BAND.address} ask"), h.monitor.calls)
        assertEquals(TEST_BAND, h.repository.heartSettings.first().sensor)
        assertEquals(TEST_BAND, h.heart.settings.sensor)
    }

    @Test
    fun connectWithASensorChosenGoesAfterItAgainAsking() = runHeartTest { h ->
        ready(h)
        h.vm.connectHeart()
        runCurrent()
        assertEquals("follow ${TEST_BAND.address} ask", h.monitor.calls.last())
    }

    @Test
    fun forgettingTheSensorStopsAndStaysStopped() = runHeartTest { h ->
        ready(h)
        h.vm.forgetHeartSensor()
        runCurrent()
        assertEquals("stop", h.monitor.calls.last())
        assertNull(h.repository.heartSettings.first().sensor)
        assertFalse(h.heart.wanted)
        // The zones are kept: only the sensor was forgotten.
        assertEquals(190, h.repository.heartSettings.first().profile.maxBpm)

        h.vm.setActive(true)
        runCurrent()
        assertEquals("stop", h.monitor.calls.last())
    }

    @Test
    fun leavingTheListWithoutChoosingStopsLooking() = runHeartTest { h ->
        h.vm.setActive(true)
        runCurrent()
        h.vm.connectHeart()
        h.vm.stopHeartSearch()
        runCurrent()
        assertEquals(listOf("search", "stop"), h.monitor.calls)
        // And with nothing being looked for, there is nothing to stop.
        h.vm.stopHeartSearch()
        assertEquals(2, h.monitor.calls.size)
    }

    @Test
    fun aSensorThatCameBackAtAnotherAddressIsRememberedThereWithoutStartingOver() = runHeartTest { h ->
        ready(h)
        val moved = TEST_BAND.copy(address = "AA:BB:CC:00:00:99")
        h.monitor.read(70, h.at, sensor = moved)
        runCurrent()
        assertEquals(moved, h.repository.heartSettings.first().sensor)
        assertEquals(1, h.monitor.calls.size)
        assertEquals(70, h.heart.bpm)
    }

    // ---- The zones ----------------------------------------------------------------------------

    @Test
    fun theZonesComeFromWhatWasSavedAndFollowIt() = runHeartTest { h ->
        h.vm.setActive(true)
        runCurrent()
        assertNull(h.heart.bounds)

        h.vm.saveHeartProfile(HeartProfile(age = 40))
        runCurrent()
        assertEquals(180, h.heart.bounds?.maxBpm)
        assertEquals(true, h.heart.bounds?.estimated)

        h.vm.saveHeartProfile(HeartProfile(maxBpm = 190, age = 40))
        runCurrent()
        assertEquals(listOf(95, 114, 133, 152, 171), h.heart.bounds?.floors)
    }

    @Test
    fun savingTheZonesLeavesTheSensorAlone() = runHeartTest { h ->
        ready(h)
        h.vm.saveHeartProfile(HeartProfile(maxBpm = 180, restingBpm = 60))
        runCurrent()
        assertEquals(TEST_BAND, h.repository.heartSettings.first().sensor)
        assertEquals(1, h.monitor.calls.size)
    }

    @Test
    fun aReadingShowsWithItsZone() = runHeartTest { h ->
        ready(h)
        h.read(140)
        runCurrent()
        assertEquals(140, h.heart.bpm)
        assertEquals(HeartZone.MODERATE, h.heart.zone)

        h.read(80)
        runCurrent()
        assertEquals(80, h.heart.bpm)
        // Still settling: a zone is only left once the readings have stayed out of it.
        assertEquals(HeartZone.MODERATE, h.heart.zone)
    }

    @Test
    fun withNoZonesSetAReadingIsJustANumber() = runHeartTest { h ->
        h.repository.saveHeartSensor(TEST_BAND)
        h.vm.setActive(true)
        runCurrent()
        h.read(140)
        runCurrent()
        assertEquals(140, h.heart.bpm)
        assertNull(h.heart.zone)
        assertNull(h.heart.bounds)
    }

    @Test
    fun aLinkedSensorWithNothingToSayShowsNoReading() = runHeartTest { h ->
        ready(h)
        h.read(140)
        runCurrent()
        h.read(null)
        runCurrent()
        assertNull(h.heart.bpm)
        assertNull(h.heart.zone)
        assertTrue(h.heart.sensor is HeartSensorState.Connected)
    }

    // ---- Noticing a change of zone ------------------------------------------------------------

    @Test
    fun aZoneTheHeartSettlesIntoDuringAWorkoutIsNoticedOnce() = runHeartTest { h ->
        working(h)
        h.read(120)
        runCurrent()
        assertNull(h.heart.notice)

        // Up into zone 3, and staying there.
        h.read(140)
        runCurrent()
        assertNull(h.heart.notice)
        h.read(142, afterMillis = ZoneTracker.HOLD_MILLIS)
        runCurrent()
        val notice = assertNotNull(h.heart.notice)
        assertEquals(HeartZone.LIGHT, notice.from)
        assertEquals(HeartZone.MODERATE, notice.to)
        assertTrue(notice.rising)
        assertEquals(HeartZone.MODERATE, h.heart.zone)

        // More of the same says nothing new.
        h.read(144)
        runCurrent()
        assertEquals(notice, h.heart.notice)

        h.vm.dismissZoneNotice()
        assertNull(h.heart.notice)
    }

    @Test
    fun comingDownIsNoticedAsFalling() = runHeartTest { h ->
        working(h)
        h.read(160)
        runCurrent()
        h.read(120)
        runCurrent()
        h.read(118, afterMillis = ZoneTracker.HOLD_MILLIS)
        runCurrent()
        val notice = assertNotNull(h.heart.notice)
        assertEquals(HeartZone.HARD, notice.from)
        assertEquals(HeartZone.LIGHT, notice.to)
        assertFalse(notice.rising)
    }

    @Test
    fun eachNoticeIsToldFromTheLast() = runHeartTest { h ->
        working(h)
        h.read(120)
        runCurrent()
        h.read(140)
        runCurrent()
        h.read(140, afterMillis = ZoneTracker.HOLD_MILLIS)
        runCurrent()
        val first = assertNotNull(h.heart.notice)
        h.read(160)
        runCurrent()
        h.read(160, afterMillis = ZoneTracker.HOLD_MILLIS)
        runCurrent()
        val second = assertNotNull(h.heart.notice)
        assertTrue(second.token != first.token)
        assertEquals(HeartZone.HARD, second.to)
    }

    @Test
    fun outsideAWorkoutAChangeOfZoneIsShownButNotAnnounced() = runHeartTest { h ->
        ready(h)
        h.read(120)
        runCurrent()
        assertEquals(HeartZone.LIGHT, h.heart.zone)
        h.read(140)
        runCurrent()
        h.read(140, afterMillis = ZoneTracker.HOLD_MILLIS)
        runCurrent()
        assertEquals(HeartZone.MODERATE, h.heart.zone)
        assertNull(h.heart.notice)
    }

    @Test
    fun afterTheLinkWasAwayTheFirstReadingIsTakenAsItComes() = runHeartTest { h ->
        working(h)
        h.read(120)
        runCurrent()
        h.monitor.state.value = HeartSensorState.Lost(TEST_BAND)
        runCurrent()
        assertNull(h.heart.bpm)
        h.read(160, afterMillis = 60_000)
        runCurrent()
        assertEquals(HeartZone.HARD, h.heart.zone)
        assertNull(h.heart.notice)
    }

    // ---- A workout's time in zone -------------------------------------------------------------

    @Test
    fun whileAWorkoutIsOpenEachReadingStandsForTheSecondSinceTheLast() = runHeartTest { h ->
        working(h)
        // A turn each: readings that land together reach the view model as the last of them only.
        for (bpm in listOf(120, 122, 140, 141, 80)) {
            h.read(bpm)
            runCurrent()
        }
        val summary = assertNotNull(h.heart.summary)
        // The first reading has no time behind it; the four after it a second each.
        assertEquals(1_000, summary.millisIn(HeartZone.LIGHT))
        assertEquals(2_000, summary.millisIn(HeartZone.MODERATE))
        assertEquals(1_000, summary.belowMillis)
        assertEquals(141, summary.peakBpm)
    }

    @Test
    fun timeTheSensorWasAwayIsNotCounted() = runHeartTest { h ->
        working(h)
        h.read(120)
        runCurrent()
        h.read(120)
        runCurrent()
        h.read(null)
        runCurrent()
        h.read(120, afterMillis = 3_000)
        runCurrent()
        h.read(120, afterMillis = 90_000)
        runCurrent()
        h.read(120)
        runCurrent()
        assertEquals(2_000, h.heart.summary?.totalMillis)
    }

    @Test
    fun withNoWorkoutOpenNothingIsAddedUp() = runHeartTest { h ->
        ready(h)
        repeat(5) {
            h.read(130)
            runCurrent()
        }
        assertNull(h.heart.summary)
        assertEquals(emptyMap(), h.repository.heartSummaries.first())
    }

    @Test
    fun aWorkoutsHeartIsWrittenDownAsItGoesSoAKilledAppLosesLittle() = runHeartTest { h ->
        val id = working(h)
        repeat(29) {
            h.read(130)
            runCurrent()
        }
        assertEquals(emptyMap(), h.repository.heartSummaries.first())
        repeat(3) {
            h.read(130)
            runCurrent()
        }
        assertEquals(30_000, h.repository.heartSummaries.first()[id]?.totalMillis)
    }

    @Test
    fun leavingTheScreenWritesDownWhatThereIs() = runHeartTest { h ->
        val id = working(h)
        repeat(4) {
            h.read(130)
            runCurrent()
        }
        h.vm.setActive(false)
        runCurrent()
        assertEquals(3_000, h.repository.heartSummaries.first()[id]?.totalMillis)
    }

    @Test
    fun aWorkoutCarriesOnFromWhatWasSavedOfItsHeart() = runHeartTest { h ->
        h.repository.saveExercises(listOf(FitnessTestData.exercise()))
        val open = h.repository.startWorkout(WorkoutFocus.CHEST, NOW - 600)
        h.repository.saveHeartSummary(open.id, HeartSummary(zoneMillis = listOf(0L, 240_000L, 0L, 0L, 0L), bpmMillis = 240_000L * 120, peakBpm = 128))
        ready(h)
        h.read(140)
        runCurrent()
        h.read(140)
        runCurrent()
        val summary = assertNotNull(h.heart.summary)
        assertEquals(240_000, summary.millisIn(HeartZone.LIGHT))
        assertEquals(1_000, summary.millisIn(HeartZone.MODERATE))
        assertEquals(140, summary.peakBpm)
    }

    @Test
    fun finishingAWorkoutKeepsItsHeartAndOffersItAsTheLast() = runHeartTest { h ->
        val id = working(h)
        h.vm.logSet(benchId, 135.0, 8)
        runCurrent()
        repeat(6) {
            h.read(150)
            runCurrent()
        }
        h.vm.finishWorkout()
        runCurrent()
        assertNull(h.vm.uiState.value.workout)
        assertEquals(5_000, h.repository.heartSummaries.first()[id]?.totalMillis)
        val last = assertNotNull(h.heart.last)
        assertEquals(id, last.workout.id)
        assertEquals(150, last.summary.averageBpm)
        assertNull(h.heart.summary)
    }

    @Test
    fun readingsThatArriveAsAWorkoutClosesAreNotAddedToIt() = runHeartTest { h ->
        val id = working(h)
        h.vm.logSet(benchId, 135.0, 8)
        runCurrent()
        repeat(3) {
            h.read(150)
            runCurrent()
        }
        h.vm.finishWorkout()
        // Before the log has said the workout is closed.
        h.read(150)
        h.read(150)
        runCurrent()
        h.vm.setActive(false)
        runCurrent()
        assertEquals(2_000, h.repository.heartSummaries.first()[id]?.totalMillis)
    }

    @Test
    fun aWorkoutCancelledEmptyTakesItsHeartWithIt() = runHeartTest { h ->
        working(h)
        repeat(40) {
            h.read(120)
            runCurrent()
        }
        assertEquals(1, h.repository.heartSummaries.first().size)
        h.vm.finishWorkout()
        runCurrent()
        assertEquals(emptyMap(), h.repository.heartSummaries.first())
        assertNull(h.heart.last)
    }

    @Test
    fun theLastHeartIsTheMostRecentFinishedWorkoutThatHasOne() = runHeartTest { h ->
        h.repository.saveExercises(listOf(FitnessTestData.exercise()))
        val summary = HeartSummary(zoneMillis = listOf(0L, 60_000L, 0L, 0L, 0L), bpmMillis = 60_000L * 120, peakBpm = 130)
        val older = h.repository.startWorkout(WorkoutFocus.CHEST, NOW - 3 * 86_400)
        h.repository.addSets(listOf(SetDraft(benchId, 135.0, 8, NOW - 3 * 86_400 + 60, workoutId = older.id)))
        h.repository.saveHeartSummary(older.id, summary)
        h.repository.finishWorkout(older.id, NOW - 3 * 86_400 + 3_000)
        // A later one the sensor wasn't on for.
        val newer = h.repository.startWorkout(WorkoutFocus.BACK, NOW - 86_400)
        h.repository.addSets(listOf(SetDraft(benchId, 135.0, 8, NOW - 86_400 + 60, workoutId = newer.id)))
        h.repository.finishWorkout(newer.id, NOW - 86_400 + 3_000)

        h.vm.setActive(true)
        runCurrent()
        assertEquals(older.id, h.heart.last?.workout?.id)
        assertEquals(summary, h.heart.last?.summary)
    }

    // ---- Recovery during a rest ---------------------------------------------------------------

    @Test
    fun duringARestTheHeartIsShownComingDownFromItsPeak() = runHeartTest { h ->
        working(h)
        h.read(150)
        runCurrent()
        assertNull(h.heart.recovery)

        h.vm.logSet(benchId, 135.0, 8)
        runCurrent()
        assertNotNull(h.vm.uiState.value.rest)
        // It climbs a little after the set before it turns.
        h.read(156)
        runCurrent()
        assertEquals(HeartRecovery(156, 156), h.heart.recovery)
        h.read(131)
        runCurrent()
        assertEquals(HeartRecovery(156, 131), h.heart.recovery)
        assertEquals(25, h.heart.recovery?.drop)
    }

    @Test
    fun skippingTheRestEndsTheRecoveryAndTheNextRestStartsItsOwn() = runHeartTest { h ->
        working(h)
        h.vm.logSet(benchId, 135.0, 8)
        runCurrent()
        h.read(160)
        runCurrent()
        h.vm.skipRest()
        h.read(150)
        runCurrent()
        assertNull(h.heart.recovery)

        h.vm.logSet(benchId, 135.0, 7)
        runCurrent()
        h.read(140)
        runCurrent()
        assertEquals(HeartRecovery(150, 140), h.heart.recovery)
    }
}
