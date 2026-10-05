package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The arithmetic behind two landscape layouts, at the window sizes they are for: how tall the
 * camera's player is, and how many camera cards Home puts side by side. Sizes are in pixels at a
 * density of 1, so they read as dp.
 */
class LandscapeLayoutTest {

    // ---- The camera's player ----

    @Test
    fun fullScreenThePlayerIsExactlyTheWindowsHeightThoughItsStripWouldBeTaller() {
        // A phone on its side is wider than 16:9: a strip 915 across is 515 tall, in a window of 411.
        assertEquals(411, playerSurfaceHeight(width = 915, viewportHeight = 411, bottomInsetPx = 0, takeover = 1f, fillViewport = true))
        assertEquals(360, playerSurfaceHeight(width = 640, viewportHeight = 360, bottomInsetPx = 0, takeover = 1f, fillViewport = true))
    }

    @Test
    fun fullScreenInAWindowTallerThanTheStripThePlayerStillFillsIt() {
        assertEquals(768, playerSurfaceHeight(width = 1024, viewportHeight = 768, bottomInsetPx = 0, takeover = 1f, fillViewport = true))
    }

    @Test
    fun onThePageThePlayerIsItsStripAndGrowsToTheViewportWhenPinchedLarger() {
        // 411 across is 231 tall at 16:9.
        assertEquals(231, playerSurfaceHeight(width = 411, viewportHeight = 800, bottomInsetPx = 136, takeover = 0f, fillViewport = false))
        assertEquals(664, playerSurfaceHeight(width = 411, viewportHeight = 800, bottomInsetPx = 136, takeover = 1f, fillViewport = false))
    }

    @Test
    fun onThePageThePlayerIsNeverShorterThanItsStrip() {
        // A scrolling page: a strip taller than the viewport is scrolled, not squeezed.
        assertEquals(576, playerSurfaceHeight(width = 1024, viewportHeight = 400, bottomInsetPx = 136, takeover = 1f, fillViewport = false))
    }

    @Test
    fun beforeTheViewportIsMeasuredThereIsOnlyTheStripToGoBy() {
        assertEquals(515, playerSurfaceHeight(width = 915, viewportHeight = 0, bottomInsetPx = 0, takeover = 1f, fillViewport = true))
    }

    // ---- Home's camera grid ----

    private fun columns(available: Int, onItsSide: Boolean = false): List<Int> =
        with(CameraGridCells(twoAbreastWhenTheyFit = onItsSide)) { Density(1f).calculateCrossAxisCellSizes(availableSize = available, spacing = 16) }

    @Test
    fun aPhoneHeldUprightShowsOneCameraPerRow() {
        assertEquals(listOf(363), columns(363))
        assertEquals(listOf(312), columns(312))
    }

    @Test
    fun aPhoneOnItsSideShowsTwoAbreast() {
        // 915 long, less the side nav and the gutters.
        assertEquals(listOf(374, 373), columns(763, onItsSide = true))
    }

    @Test
    fun theSmallestPhoneOnItsSideStillShowsTwoAbreast() {
        // 640 long, less the 104 of side nav and 48 of gutters: 488, which two 300dp cards don't fit.
        assertEquals(listOf(236, 236), columns(488, onItsSide = true))
        // The same width upright (a small tablet, a split screen) keeps full-size cards.
        assertEquals(listOf(488), columns(488, onItsSide = false))
    }

    @Test
    fun aWindowTooNarrowForTwoUsableCardsKeepsOneEvenOnItsSide() {
        assertEquals(listOf(400), columns(400, onItsSide = true))
    }

    @Test
    fun aWideWindowStopsAtThreeColumns() {
        assertEquals(3, columns(976).size)
        // Room for four 300dp cards (4 x 300 + 3 x 16 = 1248): still three.
        assertEquals(listOf(411, 411, 410), columns(1264))
        assertEquals(3, columns(2000, onItsSide = true).size)
    }

    @Test
    fun theColumnsAndTheGapsBetweenThemAddUpToTheWidth() {
        for (available in listOf(363, 488, 763, 977, 1264)) {
            val cells = columns(available, onItsSide = true)
            assertEquals(available, cells.sum() + 16 * (cells.size - 1), "at $available")
        }
    }
}
