package com.meticulouscreations.homesafe.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AlertMediaStagesTest {

    @Test
    fun aDetectionJustStartedWaitsOutTheRestOfItsWindow() {
        assertEquals(17_000L, previewWaitMs(startEpochSeconds = 1_000.0, nowEpochSeconds = 1_005.0, windowSeconds = 22.0))
    }

    @Test
    fun anOldDetectionIsAskedForAtOnce() {
        assertEquals(0L, previewWaitMs(startEpochSeconds = 1_000.0, nowEpochSeconds = 1_100.0, windowSeconds = 22.0))
    }

    @Test
    fun aStartAheadOfThisPhonesClockNeverWaitsLongerThanTheWindow() {
        assertEquals(22_000L, previewWaitMs(startEpochSeconds = 1_060.0, nowEpochSeconds = 1_000.0, windowSeconds = 22.0))
    }
}
