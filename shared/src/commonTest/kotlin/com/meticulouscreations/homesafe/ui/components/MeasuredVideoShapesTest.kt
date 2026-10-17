package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeasuredVideoShapesTest {

    @Test
    fun aMeasuredSizeIsRememberedAsItsShape() {
        MeasuredVideoShapes.record("test_doorbell", 960, 1280)
        assertEquals(0.75f, MeasuredVideoShapes["test_doorbell"])
    }

    @Test
    fun aSizeTheEngineCouldNotGiveIsNotAShape() {
        MeasuredVideoShapes.record("test_unknown", null, 1080)
        MeasuredVideoShapes.record("test_unknown", 0, 0)
        assertNull(MeasuredVideoShapes["test_unknown"], "stats before the first frame have no size; the video keeps stretching until one comes")
    }
}
