package com.meticulouscreations.homesafe.fitness.live

import com.meticulouscreations.homesafe.fitness.RestTimer
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.text.TextLoader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** [LiveWorkouts] (the process's workout and its rest) and [LiveWorkoutPresenter] (what goes to the platform's surface). */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveWorkoutsTest {
    private val chest = LiveWorkout(workoutId = 1, focus = WorkoutFocus.CHEST, startedAtEpochMillis = 1_000)
    private val rest = RestTimer(endsAtEpochMillis = 200_000, totalSeconds = 120, exerciseId = "chest/bench_press")

    @Test
    fun theWorkoutIsShownWithItsRest() {
        val hub = LiveWorkouts()
        hub.show(chest)
        assertNull(hub.workout.value?.rest)
        hub.startRest(rest)
        assertEquals(chest.copy(rest = rest), hub.workout.value)
        // The view model's picture never carries a rest of its own: the hub's stands.
        hub.show(chest.copy(sets = 4, rest = null))
        assertEquals(rest, hub.workout.value?.rest)
        assertEquals(4, hub.workout.value?.sets)
    }

    @Test
    fun skippingAndAdjustingActOnTheOneRest() {
        val hub = LiveWorkouts()
        hub.show(chest)
        hub.adjustRest(15)
        assertNull(hub.rest.value)
        hub.startRest(rest)
        hub.adjustRest(15)
        assertEquals(RestTimer(215_000, 135, rest.exerciseId), hub.rest.value)
        hub.adjustRest(-10_000)
        assertEquals(1, hub.rest.value?.totalSeconds)
        hub.skipRest()
        assertNull(hub.workout.value?.rest)
        assertNotNull(hub.workout.value)
    }

    @Test
    fun aRestOutsideAWorkoutIsKeptButAnotherWorkoutsRestIsNot() {
        val hub = LiveWorkouts()
        // A set logged from an exercise's page with no workout open still rests.
        hub.startRest(rest)
        hub.show(null)
        assertEquals(rest, hub.rest.value)
        assertNull(hub.workout.value)

        hub.show(chest)
        assertEquals(rest, hub.workout.value?.rest)
        hub.show(chest.copy(workoutId = 2))
        assertNull(hub.rest.value)
    }

    @Test
    fun aRestThatRanOutLongEnoughAgoIsForgotten() {
        val hub = LiveWorkouts()
        hub.startRest(rest)
        hub.forgetRestOverBefore(rest.endsAtEpochMillis - 1)
        assertEquals(rest, hub.rest.value)
        hub.forgetRestOverBefore(rest.endsAtEpochMillis)
        assertNull(hub.rest.value)
    }

    // ---- The presenter ----------------------------------------------------------------------------

    private class RecordingSurface(override val keepsAppRunning: Boolean = false) : LiveWorkoutSurface {
        val shown = mutableListOf<Pair<LiveWorkout, LiveWorkoutWords>>()
        var ended = 0

        override fun show(workout: LiveWorkout, words: LiveWorkoutWords) {
            shown += workout to words
        }

        override fun end() {
            ended++
        }
    }

    /** The test's virtual time, as a clock. */
    private fun TestScope.clock() = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
    }

    private val keys = TextLoader { it.toString() }

    @Test
    fun theSurfaceIsShownTheWorkoutInWordsAndToldWhenItEnds() = runTest {
        val hub = LiveWorkouts()
        val surface = RecordingSurface()
        val job = LiveWorkoutPresenter(hub, surface, clock(), keys).start(backgroundScope)
        runCurrent()
        // Nothing open: nothing shown, and nothing to end.
        assertTrue(surface.shown.isEmpty())
        assertEquals(0, surface.ended)

        hub.show(chest.copy(startedAtEpochMillis = 0))
        runCurrent()
        assertEquals(chest.copy(startedAtEpochMillis = 0), surface.shown.single().first)
        assertTrue(surface.shown.single().second.title.contains("fitness_workout_title"))

        hub.startRest(rest)
        runCurrent()
        assertEquals(rest, surface.shown.last().first.rest)

        hub.show(null)
        runCurrent()
        assertEquals(1, surface.ended)
        job.cancel()
    }

    @Test
    fun aRestThatRanOutIsLetGoOfAfterFiveMinutesWithNoScreenToDoIt() = runTest {
        val hub = LiveWorkouts()
        LiveWorkoutPresenter(hub, RecordingSurface(), clock(), keys).start(backgroundScope)
        hub.show(chest.copy(startedAtEpochMillis = 0))
        hub.startRest(RestTimer(endsAtEpochMillis = 60_000, totalSeconds = 60, exerciseId = "chest/bench_press"))
        runCurrent()
        advanceTimeBy(60_000 + LiveWorkoutPresenter.REST_LINGER_MILLIS - 1)
        assertNotNull(hub.rest.value)
        advanceTimeBy(2)
        assertNull(hub.rest.value)
    }

    @Test
    fun aWorkoutLeftOpenPastWhenItCountsIsTakenOffTheSurface() = runTest {
        val hub = LiveWorkouts()
        val surface = RecordingSurface()
        LiveWorkoutPresenter(hub, surface, clock(), keys).start(backgroundScope)
        hub.show(chest.copy(startedAtEpochMillis = 0))
        runCurrent()
        advanceTimeBy(chest.copy(startedAtEpochMillis = 0).staleAtEpochMillis + 1)
        assertEquals(1, surface.ended)
    }

    @Test
    fun aWorkoutNobodyTouchesIsTakenOffASurfaceThatKeepsTheAppRunning_andComesBackWithTheApp() = runTest {
        val hub = LiveWorkouts()
        val surface = RecordingSurface(keepsAppRunning = true)
        LiveWorkoutPresenter(hub, surface, clock(), keys).start(backgroundScope)
        val open = chest.copy(startedAtEpochMillis = 0)
        hub.show(open)
        runCurrent()
        assertTrue(hub.keepsAppRunning)

        // The heart reads on, every second: that is nobody doing anything, and puts nothing off.
        repeat(10) { minute ->
            advanceTimeBy(LiveWorkoutPresenter.IDLE_MILLIS / 10)
            hub.show(open.copy(heart = LiveHeart(100 + minute)))
            runCurrent()
        }
        assertEquals(1, surface.ended)
        assertEquals(false, hub.keepsAppRunning, "the heart sensor's link is let go of with it")
        val shownByThen = surface.shown.size
        hub.show(open.copy(heart = LiveHeart(140)))
        runCurrent()
        assertEquals(shownByThen, surface.shown.size, "a reading that arrives afterwards doesn't bring it back")

        // Opened again, it is the workout in progress as before, and stays while the app is in front.
        hub.show(open.copy(onScreen = true))
        runCurrent()
        assertEquals(shownByThen + 1, surface.shown.size)
        advanceTimeBy(LiveWorkoutPresenter.IDLE_MILLIS * 2)
        assertEquals(1, surface.ended)
    }

    @Test
    fun aSetLoggedStartsTheHourAgain() = runTest {
        val hub = LiveWorkouts()
        val surface = RecordingSurface(keepsAppRunning = true)
        LiveWorkoutPresenter(hub, surface, clock(), keys).start(backgroundScope)
        val open = chest.copy(startedAtEpochMillis = 0)
        hub.show(open)
        runCurrent()
        advanceTimeBy(LiveWorkoutPresenter.IDLE_MILLIS - 1_000)
        hub.show(open.copy(sets = 1))
        runCurrent()
        advanceTimeBy(LiveWorkoutPresenter.IDLE_MILLIS - 1_000)
        assertEquals(0, surface.ended)
        advanceTimeBy(2_000)
        assertEquals(1, surface.ended)
    }

    @Test
    fun aSurfaceThatDoesNotKeepTheAppRunningShowsAnUntouchedWorkoutForAsLongAsItCounts() = runTest {
        val hub = LiveWorkouts()
        val surface = RecordingSurface()
        LiveWorkoutPresenter(hub, surface, clock(), keys).start(backgroundScope)
        hub.show(chest.copy(startedAtEpochMillis = 0))
        runCurrent()
        advanceTimeBy(LiveWorkoutPresenter.IDLE_MILLIS * 3)
        assertEquals(0, surface.ended)
    }
}
