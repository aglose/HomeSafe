package com.meticulouscreations.homesafe.ui.components

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the timeline lens's glass goes relative to the playhead it magnifies. The picture is
 * checked by eye; what is pinned here is the promise behind it: however the glass's spring lags
 * or overshoots, and wherever the playhead is, the playhead line stays in the clear middle of the
 * glass, never near or past its rim.
 */
class LiquidLensTest {

    /** A 1000 px timeline with the glass kept 10 px inside its edges. */
    private val width = 1_000f
    private val inset = 10f

    /** The held bubble's half-width, and the most the glass may sit off the playhead with it. */
    private val held = 150f
    private val heldReach = held * PLAYHEAD_REACH

    @Test
    fun settledOnThePlayheadTheGlassIsCentredOnIt() {
        assertEquals(500f, lensCenterX(sprungX = 500f, playheadX = 500f, halfWidth = held, inset = inset, width = width))
    }

    @Test
    fun aSmallLagIsLeftToTheSpring() {
        assertEquals(480f, lensCenterX(sprungX = 480f, playheadX = 500f, halfWidth = held, inset = inset, width = width))
    }

    @Test
    fun aLongLagIsCutShortSoThePlayheadStaysInTheMiddleOfTheGlass() {
        // Trailing a fast drag by 100 px: the glass is pulled up to within its reach of the line.
        assertEquals(500f - heldReach, lensCenterX(sprungX = 400f, playheadX = 500f, halfWidth = held, inset = inset, width = width))
        // And an overshoot the other way is held back just the same.
        assertEquals(500f + heldReach, lensCenterX(sprungX = 620f, playheadX = 500f, halfWidth = held, inset = inset, width = width))
    }

    @Test
    fun awayFromTheEndsTheGlassStaysInsideTheCard() {
        // The playhead near the left end but the glass still whole: the card's edge moves it right, within reach.
        val center = lensCenterX(sprungX = 120f, playheadX = 120f, halfWidth = held, inset = inset, width = width)
        assertEquals(held + inset, center)
        assertTrue(abs(center - 120f) <= heldReach)
    }

    @Test
    fun atTheLiveEdgeTheGlassHangsOffTheCardRatherThanLetGoOfThePlayhead() {
        val center = lensCenterX(sprungX = width, playheadX = width, halfWidth = held, inset = inset, width = width)
        assertEquals(width - heldReach, center)
        assertTrue(center + held > width, "the glass should reach past the card's edge, but ends at ${center + held}")
    }

    @Test
    fun whereverTheSpringAndThePlayheadAreThePlayheadIsInTheMiddleOfTheGlass() {
        for (halfWidth in listOf(13f, 40f, held)) {
            val reach = halfWidth * PLAYHEAD_REACH
            for (playhead in 0..1_000 step 25) {
                for (sprung in -200..1_200 step 25) {
                    val center = lensCenterX(sprung.toFloat(), playhead.toFloat(), halfWidth, inset, width)
                    assertTrue(
                        abs(center - playhead) <= reach + 0.001f,
                        "halfWidth $halfWidth, playhead $playhead, spring at $sprung: glass centred at $center, off the line by more than $reach",
                    )
                }
            }
        }
    }
}
