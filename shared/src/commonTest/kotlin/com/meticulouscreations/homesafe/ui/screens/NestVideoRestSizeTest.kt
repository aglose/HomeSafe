package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Nest camera page's video box at rest, before a zoom grows the viewport around it. */
class NestVideoRestSizeTest {

    @Test
    fun aWidePictureIsAsWideAsThePage() {
        assertEquals(IntSize(1080, 608), nestVideoRestSize(width = 1080, available = 1800, boxAspect = 16f / 9f))
    }

    @Test
    fun aTallPictureIsAsTallAsThePageLeavesRoomForAndNarrower() {
        // The doorbell's 3:4, shown whole: 1440 tall at full width, with 1200 to spare.
        assertEquals(IntSize(900, 1200), nestVideoRestSize(width = 1080, available = 1200, boxAspect = 3f / 4f))
    }

    @Test
    fun noRoomLeftIsNoHeightRatherThanANegativeOne() {
        assertEquals(IntSize(0, 0), nestVideoRestSize(width = 1080, available = -40, boxAspect = 3f / 4f))
    }
}
