package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
