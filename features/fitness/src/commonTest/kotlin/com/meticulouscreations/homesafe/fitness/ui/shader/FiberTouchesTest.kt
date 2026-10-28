package com.meticulouscreations.homesafe.fitness.ui.shader

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

/** A fibre card's marks are emptied once they have settled, so its shader can pass them by. */
class FiberTouchesTest {

    private fun FiberTouches.made(slot: Int) = marks[slot * 4 + 2]

    @Test
    fun aLiftedMarkIsEmptiedOnceItsSwirlAndRingHaveFaded() {
        val touches = FiberTouches()
        touches.tick(10f)
        touches.down(1L, Offset(40f, 20f))
        touches.tick(11f)
        touches.up(1L)

        touches.tick(14.5f)
        assertEquals(10f, touches.made(0))

        touches.tick(15.5f)
        assertEquals(-1f, touches.made(0))
        assertEquals(-1f, touches.marks[3])
        assertEquals(0f, touches.splash[0])
    }

    @Test
    fun aMarkWhoseFingerIsStillDownIsKeptHoweverOld() {
        val touches = FiberTouches()
        touches.tick(10f)
        touches.down(1L, Offset(40f, 20f))

        touches.tick(100f)

        assertEquals(10f, touches.made(0))
        assertEquals(-1f, touches.marks[3])
    }

    @Test
    fun aLiftedMarkMadeBeforeTheClockStartedOverIsEmptied() {
        val touches = FiberTouches()
        touches.tick(599f)
        touches.down(1L, Offset(40f, 20f))
        touches.up(1L)

        touches.tick(1f)

        assertEquals(-1f, touches.made(0))
    }
}
