package com.meticulouscreations.homesafe.ui.components

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bright spot's travel across [BufferingDots]. Only the alpha ramp is testable without a
 * frame clock, but it is the whole animation: the composable just plays a phase into it.
 */
class BufferingDotsTest {

    private fun alphaOf(phase: Float) = List(DOT_COUNT) { bufferingDotAlpha(phase, it) }

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < 1e-4f, "$message: expected $expected but was $actual")
    }

    @Test
    fun theDotThePhaseSitsOnIsFullyOpaque() {
        repeat(DOT_COUNT) { index ->
            assertClose(1f, bufferingDotAlpha(index.toFloat(), index), "dot $index at its own phase")
        }
    }

    @Test
    fun exactlyOneDotIsBrightestAtAnyMoment() {
        // Sampled off the dot centres too, where two neighbours share the light.
        for (step in 0 until 30) {
            val phase = step / 10f
            val alphas = alphaOf(phase)
            val brightest = alphas.max()
            assertTrue(alphas.all { it <= brightest + 1e-4f }, "phase $phase should have a single peak: $alphas")
        }
    }

    @Test
    fun noDotEverGoesOut() {
        // The three keep their shape as a group; the dimmest is faint, not absent.
        for (step in 0 until 30) {
            val phase = step / 10f
            alphaOf(phase).forEach { alpha ->
                assertTrue(alpha > 0f, "phase $phase should leave every dot visible")
                assertTrue(alpha <= 1f, "phase $phase should never exceed full opacity")
            }
        }
    }

    @Test
    fun theSpotWrapsTheShortWayFromTheLastDotToTheFirst() {
        // Just before the cycle ends the spot is between dot 2 and dot 0 — so those two are the
        // bright pair, not dot 0 and dot 1 as a non-wrapping ramp would give.
        val alphas = alphaOf(2.5f)
        assertTrue(alphas[2] > alphas[1], "dot 2 should still be bright at phase 2.5: $alphas")
        assertTrue(alphas[0] > alphas[1], "dot 0 should be coming up at phase 2.5: $alphas")
        assertClose(alphas[0], alphas[2], "the spot is exactly between dots 2 and 0")
    }

    @Test
    fun theCycleEndLandsBackOnTheFirstDot() {
        assertEquals(alphaOf(0f), alphaOf(DOT_COUNT.toFloat()))
    }
}
