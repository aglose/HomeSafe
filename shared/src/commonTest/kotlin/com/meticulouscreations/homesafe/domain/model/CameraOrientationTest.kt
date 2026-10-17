package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraOrientationTest {

    @Test
    fun quarterTurnsGoRoundAndKeepTheTilt() {
        val tilted = CameraOrientation(quarterTurns = 0, tiltDegrees = 7f)
        assertEquals(CameraOrientation(3, 7f), tilted.turned(clockwise = false))
        assertEquals(CameraOrientation(0, 7f), tilted.turned(clockwise = false).turned(clockwise = true))
        assertEquals(CameraOrientation(0, 0f), CameraOrientation(3).turned(clockwise = true))
        assertEquals(277f, tilted.turned(clockwise = false).degrees)
    }

    @Test
    fun theTiltStaysWithinItsReach() {
        assertEquals(45f, CameraOrientation().tilted(80f).tiltDegrees)
        assertEquals(-45f, CameraOrientation().tilted(-80f).tiltDegrees)
    }
}
