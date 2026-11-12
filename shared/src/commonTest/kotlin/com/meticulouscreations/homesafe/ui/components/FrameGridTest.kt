package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameGridTest {

    /** The frames, of a display drawing [hz] a second for one second, in which a clock started at frame [startFrame] moves on. */
    private fun ticks(hz: Int, startFrame: Int, stepSeconds: Float): Set<Int> {
        val period = 1_000_000_000L / hz
        var last = frameGridSlot(startFrame * period, stepSeconds)
        return (startFrame + 1 until hz).filterTo(mutableSetOf()) { frame ->
            val slot = frameGridSlot(frame * period, stepSeconds)
            (slot != last).also { last = slot }
        }
    }

    @Test
    fun twoClocksStartedOutOfStepMoveInTheSameFrames() {
        val early = ticks(hz = 60, startFrame = 0, stepSeconds = 1f / 30f)
        val late = ticks(hz = 60, startFrame = 7, stepSeconds = 1f / 30f)
        assertEquals(early.filter { it > 7 }.toSet(), late)
    }

    @Test
    fun aThirtyASecondClockMovesOnEveryOtherFrameAtSixtyAndEveryFourthAtOneTwenty() {
        assertEquals(29, ticks(hz = 60, startFrame = 0, stepSeconds = 1f / 30f).size)
        assertEquals(29, ticks(hz = 120, startFrame = 0, stepSeconds = 1f / 30f).size)
    }

    @Test
    fun aStepOfNothingIsEveryFrame() {
        assertEquals(59, ticks(hz = 60, startFrame = 0, stepSeconds = 0f).size)
    }

    /** A display drawing [hz] frames a second on the test's clock (whole milliseconds, so [hz] divides 1000), counting the frames asked for. */
    private class Display(private val scope: TestScope, private val hz: Int) : MonotonicFrameClock {
        var asked = 0

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            asked++
            val period = 1_000L / hz
            val now = scope.testScheduler.currentTime
            delay((now / period + 1) * period - now)
            return onFrame(scope.testScheduler.currentTime * 1_000_000)
        }
    }

    /** The frames at which a clock stepping thirty times a second moves on, on [display], from [startMillis] in, into [moved]. */
    private fun TestScope.stepping(display: Display, moved: MutableList<Long>, startMillis: Long = 0) = launch(display) {
        delay(startMillis)
        var last = 0L
        while (true) {
            last = awaitNextGridFrame(1f / 30f, last)
            moved += last
        }
    }

    /** The frames at which a clock stepping thirty times a second moved on in one second of [display]. */
    private fun TestScope.steps(display: Display): List<Long> {
        val moved = mutableListOf<Long>()
        val job = stepping(display, moved)
        testScheduler.advanceTimeBy(1_000)
        job.cancel()
        return moved
    }

    @Test
    fun aSteppedClockAsksForAFrameOrTwoAStepRatherThanEveryFrame() = runTest {
        val display = Display(this, hz = 125)
        val moved = steps(display)
        assertTrue(moved.size in 29..31, "about thirty steps in a second: ${moved.size}")
        assertTrue(display.asked < 80, "a display drawing 125 a second was asked for ${display.asked} of them")
    }

    @Test
    fun twoSteppedClocksStartedOutOfStepMoveOnTheSameFrames() = runTest {
        val first = mutableListOf<Long>()
        val second = mutableListOf<Long>()
        val one = stepping(Display(this, hz = 125), first)
        val other = stepping(Display(this, hz = 125), second, startMillis = 13)
        testScheduler.advanceTimeBy(1_000)
        one.cancel()
        other.cancel()
        // The second's first frame is wherever it happened to start; every step after that is the first's.
        assertEquals(first.filter { it > second.first() }.take(20), second.drop(1).take(20))
    }

    @Test
    fun aBreathRisesToOneAndFallsBackOverItsPeriod() {
        assertEquals(0f, breathAt(0, 2_000), 1e-4f)
        assertEquals(1f, breathAt(1_000_000_000L, 2_000), 1e-4f)
        assertEquals(0f, breathAt(2_000_000_000L, 2_000), 1e-4f)
        assertEquals(0.25f, cycleAt(500_000_000L, 2_000), 1e-4f)
    }
}
