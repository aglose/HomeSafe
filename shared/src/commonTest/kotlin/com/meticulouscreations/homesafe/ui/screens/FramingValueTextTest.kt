package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.VideoFit
import kotlin.test.Test
import kotlin.test.assertEquals

class FramingValueTextTest {

    @Test
    fun theAngleIsSignedToATenth() {
        assertEquals("+2.4", signedTenths(2.44f))
        assertEquals("-0.5", signedTenths(-0.46f))
        assertEquals("0.0", signedTenths(0.04f))
        assertEquals("0.0", signedTenths(-0.04f), "no minus on a level picture")
        assertEquals("-44.0", signedTenths(-44f))
    }

    @Test
    fun theZoomIsToATenth() {
        assertEquals("1.0", tenths(1f))
        assertEquals("2.5", tenths(2.46f))
        assertEquals("5.0", tenths(5f))
    }

    @Test
    fun theFrameReachesAcrossALetterboxedPicture() {
        val spans = frameSpans(aspect = 4f / 3f, fit = VideoFit.Fit, onItsSide = false)
        assertEquals(4f / 3f, spans.x, 0.01f)
        assertEquals(1f, spans.y, 0.01f)
        assertEquals(FrameSpans(1f, 1f), frameSpans(aspect = null, fit = null, onItsSide = false), "a stretched picture is the frame")
    }
}
