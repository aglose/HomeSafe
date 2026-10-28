package com.meticulouscreations.homesafe.fitness.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A run worked out from what the GPS gave: distance, moving time, pace, splits and route. All positions made up. */
class RunTrackerTest {

    private val start = 1_700_000_000_000L

    /** Meters north of the start as a latitude, near enough. */
    private fun north(meters: Double) = 45.0 + meters / 111_195.0

    private fun fix(seconds: Long, meters: Double, accuracy: Double = 5.0, east: Double = 0.0) =
        RunEvent.of(GpsFix(start + seconds * 1_000, north(meters), -93.0 + east / 78_710.0, accuracy))

    /** A steady run at [metersPerSecond], a position every second, for [seconds]. */
    private fun steady(metersPerSecond: Double, seconds: Long, from: Long = 0, fromMeters: Double = 0.0) =
        (0..seconds).map { fix(from + it, fromMeters + it * metersPerSecond) }

    private fun near(expected: Double, actual: Double, within: Double) = assertTrue(abs(expected - actual) <= within, "expected about $expected, was $actual")

    @Test
    fun aSteadyRunAddsUp() {
        // Ten minutes at 3 m/s is 1,800 m: an 8:56 mile.
        val tracker = RunTracker.replay(start, steady(3.0, 600))
        near(1_800.0, tracker.meters, 2.0)
        assertEquals(600_000, tracker.movingMillisAt(start + 600_000))
        val pace = tracker.paceSecondsAt(start + 600_000)
        near(536.0, pace?.toDouble() ?: 0.0, 5.0)
        assertEquals(GpsSignal.GOOD, tracker.signalAt(start + 600_000))
    }

    @Test
    fun standingStillAddsNothingThoughTheGpsWanders() {
        // A minute of positions scattered a few meters about one spot.
        val wander = (0..60L).map { fix(it, if (it % 2 == 0L) 0.0 else 2.5, east = if (it % 3 == 0L) 1.5 else 0.0) }
        assertEquals(0.0, RunTracker.replay(start, wander).meters)
    }

    @Test
    fun aPositionTooUnsureToUseIsPassedOver() {
        val events = steady(3.0, 10) + fix(11, 400.0, accuracy = 80.0) + fix(12, 36.0)
        near(36.0, RunTracker.replay(start, events).meters, 1.0)
    }

    @Test
    fun aJumpIsPassedOverUntilItStands() {
        // 300 m in a second is no runner.
        val jumped = steady(3.0, 10) + fix(11, 330.0) + fix(12, 36.0)
        near(36.0, RunTracker.replay(start, jumped).meters, 1.0)
        // Where nothing better comes for half a minute, it is started again from, with nothing added for the leap.
        val stood = steady(3.0, 10) + fix(45, 630.0) + fix(46, 633.0)
        near(33.0, RunTracker.replay(start, stood).meters, 1.0)
    }

    @Test
    fun aJumpThatStaysAWhileAddsNothingGoingOrComingBack() {
        // Standing still, with the GPS 100 m off for a quarter of a minute: from far enough on, 100 m
        // is no longer too fast to have run since the last good position, but it is still the jump.
        val events = (0..30L).map { fix(it, if (it in 1..15) 100.0 else 0.0) }
        assertEquals(0.0, RunTracker.replay(start, events).meters)
    }

    @Test
    fun aPauseStopsTheClockAndWhatIsCoveredMeanwhile() {
        val events = steady(3.0, 100) +
            RunEvent(RunEventKind.PAUSE, start + 100_000) +
            // Walked across the road while paused.
            steady(1.5, 60, from = 101, fromMeters = 300.0) +
            RunEvent(RunEventKind.RESUME, start + 200_000) +
            steady(3.0, 100, from = 200, fromMeters = 400.0)
        val tracker = RunTracker.replay(start, events)
        near(600.0, tracker.meters, 2.0)
        assertEquals(200_000, tracker.movingMillisAt(start + 300_000))
        assertTrue(tracker.distance.none { it.startEpochSeconds in (start / 1_000 + 101)..(start / 1_000 + 199) })
    }

    @Test
    fun whilePausedThereIsNoPace() {
        val tracker = RunTracker.replay(start, steady(3.0, 60) + RunEvent(RunEventKind.PAUSE, start + 60_000))
        assertNull(tracker.paceSecondsAt(start + 61_000))
        assertTrue(tracker.paused)
    }

    @Test
    fun theGpsIsSearchingThenGoodThenLost() {
        val tracker = RunTracker(start)
        assertEquals(GpsSignal.SEARCHING, tracker.signalAt(start + 5_000))
        tracker.apply(fix(5, 0.0))
        assertEquals(GpsSignal.GOOD, tracker.signalAt(start + 6_000))
        assertEquals(GpsSignal.LOST, tracker.signalAt(start + 5_000 + RunTracker.LOST_AFTER_MILLIS + 1))
    }

    @Test
    fun twoMilesSplitIntoTwo() {
        // A 9:00 mile and then an 8:00 one.
        val first = METERS_PER_MILE / 540
        val second = METERS_PER_MILE / 480
        val events = steady(first, 540) + steady(second, 480, from = 541, fromMeters = METERS_PER_MILE + first)
        val tracker = RunTracker.replay(start, events)
        val run = Runs.of(tracker.finished("a", start + 1_021_000, 0))
        val splits = Runs.splits(run, tracker.track())
        assertEquals(listOf(true, true), splits.map { it.whole })
        near(540.0, splits[0].seconds.toDouble(), 6.0)
        near(480.0, splits[1].seconds.toDouble(), 6.0)
    }

    @Test
    fun aMileIsTimedWithoutThePauseInIt() {
        // A 9:00 mile with two minutes stood at a crossing halfway through it.
        val speed = METERS_PER_MILE / 540
        val events = steady(speed, 270) +
            RunEvent(RunEventKind.PAUSE, start + 270_000) +
            RunEvent(RunEventKind.RESUME, start + 390_000) +
            steady(speed, 300, from = 390, fromMeters = 270 * speed)
        val tracker = RunTracker.replay(start, events)
        val run = Runs.of(tracker.finished("a", start + 690_000, 0))
        val splits = Runs.splits(run, tracker.track())
        near(540.0, splits[0].seconds.toDouble(), 6.0)
    }

    @Test
    fun theRouteKeepsAPointEveryFewMeters() {
        val tracker = RunTracker.replay(start, steady(3.0, 100))
        // 300 m with a point every 5 m or more: one a second at 3 m/s is too close, so every other one.
        assertTrue(tracker.route.size in 50..61, "${tracker.route.size} points")
    }

    @Test
    fun theSameEventsGiveTheSameRun() {
        val events = steady(2.8, 300) + RunEvent(RunEventKind.PAUSE, start + 300_000) + RunEvent(RunEventKind.RESUME, start + 330_000) + steady(2.8, 120, from = 330, fromMeters = 840.0)
        val once = RunTracker.replay(start, events)
        // As the recorder took them, one at a time.
        val again = RunTracker(start)
        events.forEach(again::apply)
        assertEquals(once.meters, again.meters)
        assertEquals(once.distance, again.distance)
        assertEquals(once.route, again.route)
    }

    @Test
    fun aRunRecordedHereIsARunWhateverItsPace() {
        val tracker = RunTracker.replay(start, steady(1.2, 1_200))
        val walked = tracker.finished("app-1", start + 1_200_000, 0)
        val board = Runs.board(emptyList(), todayEpochDay = start / 86_400_000, offsetAt = { 0 }, recorded = listOf(walked))
        assertEquals(listOf("app-1"), board.runs.map { it.id })
        assertTrue(board.notRuns.isEmpty())
    }
}
