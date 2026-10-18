package com.meticulouscreations.homesafe.ui.screens

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
}
