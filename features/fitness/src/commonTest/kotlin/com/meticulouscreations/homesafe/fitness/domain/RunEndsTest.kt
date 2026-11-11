package com.meticulouscreations.homesafe.fitness.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a run left recording was really over, from its positions, the heart and a band's session. All of it made up. */
class RunEndsTest {

    private val start = 1_700_000_000_000L

    private fun fix(seconds: Long, meters: Double, accuracy: Double = 5.0) =
        RunEvent.of(GpsFix(start + seconds * 1_000, 45.0 + meters / 111_195.0, -93.0, accuracy))

    /** Going at [metersPerSecond] from [from] for [seconds], a position a second, beginning [fromMeters] along. */
    private fun going(metersPerSecond: Double, from: Long, seconds: Long, fromMeters: Double) = (0..seconds).map { fix(from + it, fromMeters + it * metersPerSecond) }

    /** Standing at [meters] along from [from] for [seconds], the GPS wandering a couple of meters about. */
    private fun standing(from: Long, seconds: Long, meters: Double) = (1..seconds).map { fix(from + it, meters + if (it % 2 == 0L) 0.0 else 2.0) }

    private fun heart(from: Long, to: Long, bpm: Double) = (from until to step 5).map { HealthSample(start / 1_000 + it, bpm) }

    private fun at(seconds: Long) = start + seconds * 1_000

    private fun assertOverAt(seconds: Long, over: Long?, within: Long = 15) {
        assertNotNull(over)
        assertTrue(abs(over - at(seconds)) <= within * 1_000, "expected about ${at(seconds)}, was $over")
    }

    @Test
    fun aRunEndedWhenItWasOverStands() {
        // Twenty minutes, and a minute getting the phone out.
        val events = going(3.0, 0, 1_200, 0.0) + standing(1_200, 60, 3_600.0)
        assertNull(RunEnds.inferred(start, at(1_260), events))
    }

    @Test
    fun aRunLeftRecordingEndsWhereTheRunningStopped() {
        // Twenty minutes of running, then the phone on the kitchen counter for two hours.
        val events = going(3.0, 0, 1_200, 0.0) + standing(1_200, 7_200, 3_600.0)
        assertOverAt(1_200, RunEnds.inferred(start, at(8_400), events))
    }

    @Test
    fun aStopAtALightIsPartOfTheRun() {
        val events = going(3.0, 0, 600, 0.0) + standing(600, 180, 1_800.0) + going(3.0, 780, 600, 1_800.0) + standing(1_380, 1_800, 3_600.0)
        assertOverAt(1_380, RunEnds.inferred(start, at(3_180), events))
    }

    @Test
    fun runningAgainAfterALongStopIsStillTheRun() {
        // Ten minutes, ten stretching, ten more: all one run, and then forgotten.
        val events = going(3.0, 0, 600, 0.0) + standing(600, 600, 1_800.0) + going(3.0, 1_200, 600, 1_800.0) + standing(1_800, 1_800, 3_600.0)
        assertOverAt(1_800, RunEnds.inferred(start, at(3_600), events))
    }

    @Test
    fun aDashLongAfterIsNotTheRun() {
        // Half a minute's trot to the car, a quarter of an hour after stopping.
        val events = going(3.0, 0, 1_200, 0.0) + standing(1_200, 900, 3_600.0) + going(3.0, 2_100, 30, 3_600.0) + standing(2_130, 900, 3_690.0)
        assertOverAt(1_200, RunEnds.inferred(start, at(3_030), events))
    }

    @Test
    fun theWalkHomeIsNotTheRun() {
        // Slowing to a walk is the end of the run, for two minutes; the quarter of an hour after isn't.
        val events = going(3.0, 0, 1_200, 0.0) + going(1.3, 1_200, 900, 3_600.0)
        assertOverAt(1_200 + RunEnds.SLOWING_MILLIS / 1_000, RunEnds.inferred(start, at(2_100), events))
    }

    @Test
    fun aWalkRecordedAsOneIsKeptToWhereTheWalkingStopped() {
        // Never a jog's pace: judged by its own, so the walk itself stays.
        val events = going(1.4, 0, 1_800, 0.0) + standing(1_800, 1_200, 2_520.0)
        assertOverAt(1_800, RunEnds.inferred(start, at(3_000), events))
        assertNull(RunEnds.inferred(start, at(1_800), going(1.4, 0, 1_800, 0.0)))
    }

    @Test
    fun aRunPausedAndForgottenEndsAtThePause() {
        val events = going(3.0, 0, 1_200, 0.0) + RunEvent(RunEventKind.PAUSE, at(1_201)) + going(3.0, 1_202, 600, 3_600.0)
        assertOverAt(1_200, RunEnds.inferred(start, at(1_802), events), within = 30)
    }

    @Test
    fun aRideHomeWithTheHeartSettledIsNotTheRun() {
        // Stopped, and straight into slow traffic at a runner's speed: only the heart says it isn't one.
        val events = going(3.0, 0, 1_200, 0.0) + going(5.0, 1_200, 900, 3_600.0)
        assertNull(RunEnds.inferred(start, at(2_100), events))
        val settledHeart = heart(0, 1_200, 165.0) + heart(1_200, 2_100, 95.0)
        assertOverAt(1_200, RunEnds.inferred(start, at(2_100), events, settledHeart), within = 30)
        // A band that says the run went on outweighs a heart that read low.
        assertNull(RunEnds.inferred(start, at(2_100), events, settledHeart, listOf(start / 1_000..start / 1_000 + 2_100)))
    }

    @Test
    fun withNoHeartTheBandsSessionSaysWhenTheRideBegan() {
        val events = going(3.0, 0, 1_200, 0.0) + going(5.0, 1_200, 900, 3_600.0)
        val worn = listOf(start / 1_000 + 5..start / 1_000 + 1_190)
        // The band is given two minutes' grace.
        assertOverAt(1_200 + RunEnds.WORN_GRACE_SECONDS, RunEnds.inferred(start, at(2_100), events, worn = worn), within = 31)
    }

    @Test
    fun whereTheGpsWasLostTheBandAndTheHeartSay() {
        // Ten minutes with positions, then none at all: the run went on to twenty by the band, and was left recording to fifty.
        val events = going(3.0, 0, 600, 0.0)
        assertOverAt(600, RunEnds.inferred(start, at(3_000), events), within = 30)
        val worn = listOf(start / 1_000..start / 1_000 + 1_200)
        assertOverAt(1_200 + RunEnds.WORN_GRACE_SECONDS, RunEnds.inferred(start, at(3_000), events, worn = worn), within = 31)
        val through = heart(0, 1_500, 160.0) + heart(1_500, 3_000, 90.0)
        assertOverAt(1_500, RunEnds.inferred(start, at(3_000), events, through), within = 31)
    }

    @Test
    fun aRunThatNeverWentAnywhereIsLeftAlone() {
        assertNull(RunEnds.inferred(start, at(3_600), standing(0, 3_600, 0.0)))
    }

    @Test
    fun aSettledRunIsAddedUpAgainToItsEndAndStandsAfterThat() {
        val events = going(3.0, 0, 1_200, 0.0) + standing(1_200, 600, 3_600.0) + going(1.3, 1_800, 300, 3_600.0)
        val left = RunTracker.replay(start, events).finished("run", at(2_100), -28_800)
        assertEquals(2_100_000, left.movingMillis)
        val settled = assertNotNull(RunEnds.settled(left, events))
        assertEquals("run", settled.id)
        assertEquals(-28_800, settled.utcOffsetSeconds)
        assertOverAt(1_200, settled.endEpochMillis)
        assertEquals(settled.endEpochMillis - start, settled.movingMillis)
        assertEquals(3_600.0, settled.meters, 5.0)
        // Looked at again, with everything recorded after its end still there, it stands.
        assertNull(RunEnds.settled(settled, events))
    }
}
