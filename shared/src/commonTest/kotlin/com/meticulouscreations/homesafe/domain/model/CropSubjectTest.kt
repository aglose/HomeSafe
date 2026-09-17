package com.meticulouscreations.homesafe.domain.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [CropSubject.boxInCrop] finds the box a crop was cut from and redoes Frigate's
 * `calculate_region(frame.shape, *box, max(w, h), 1.0)` cut. Each case is worked by hand from that
 * function, so a drift from Frigate's arithmetic shows up as a frame around the wrong car.
 */
class CropSubjectTest {

    private val nowhere = MaskPoint(0.5, 0.5)

    /** A box given in hikvision_1 (640x360) pixels. */
    private fun px(left: Double, top: Double, width: Double, height: Double, at: Double? = null) =
        SeenBox(x = left / 640, y = top / 360, width = width / 640, height = height / 360, epochSeconds = at)

    private fun subject(vararg boxes: SeenBox, captured: Double? = null, bottomCentre: MaskPoint = nowhere) =
        CropSubject(640, 360, boxes.toList(), captured, bottomCentre)

    private fun assertBox(expected: CropBox, actual: CropBox?) {
        actual ?: fail("expected a box, got none")
        val close = listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .all { (e, a) -> abs(e - a) < 0.001 }
        assertTrue(close && expected.exact == actual.exact, "expected $expected, got $actual")
    }

    /**
     * A parked car on hikvision_1, from the real server on 2026-09-16: event box 80x41 px, crop
     * 80x80. The sizes match, so it's that frame; wide, so it spans the crop and the question is
     * only how tall a band it is.
     */
    @Test
    fun aCropTheSizeOfAKeptBoxIsFramedByThatBoxExactly() {
        val parked = subject(SeenBox(0.390625, 0.25833333333333336, 0.125, 0.11388888888888889, epochSeconds = null))
        // Centre (290, 113.5); region offset x int(250) = 250, y int(73.5) = 73.
        assertBox(CropBox(left = 0.0, top = 0.25, right = 1.0, bottom = 0.7625, exact = true), parked.boxInCrop(80, 80))
    }

    @Test
    fun aCropNoKeptBoxMatchesBorrowsTheShapeAndTakesThePositionFromThePath() {
        // The car was closer when this crop was taken: 120 px against the 80 px box. Same shape,
        // scaled up, standing where the path says: bottom-centre (291, 135).
        val closer = subject(
            SeenBox(0.390625, 0.25833333333333336, 0.125, 0.11388888888888889, epochSeconds = null),
            bottomCentre = MaskPoint(0.4547, 0.375),
        )
        // Box 120x61.5, centre y 135 - 30.75 = 104.25, y offset int(44.25) = 44, box top 73.5.
        assertBox(CropBox(left = 0.0, top = 0.2458, right = 1.0, bottom = 0.7583, exact = false), closer.boxInCrop(120, 120))
    }

    /** From the real server: a street car whose best frame (103 px) was taken far from where it parked (37 px). */
    @Test
    fun theKeptBoxIsTheOneWhoseSizeMatchesNotTheBestFrame() {
        val best = px(513.0, 111.0, 103.0, 53.0)
        val parked = px(603.25, 117.25, 37.0, 31.0, at = 951.0)
        val car = subject(best, parked, captured = 952.0)
        // 37x31 at (603.25, 117.25): offset x int(603.25) = 603, just inside the frame; y int(114.25) = 114.
        assertBox(CropBox(left = 0.25 / 37, top = 3.25 / 37, right = 1.0, bottom = 34.25 / 37, exact = true), car.boxInCrop(37, 37))
    }

    @Test
    fun amongBoxesOfAboutTheRightSizeTheNearestInTimeWins() {
        val parked = px(603.0, 117.0, 37.0, 31.0, at = 951.0)
        val movedOff = px(604.25, 118.25, 36.0, 20.0, at = 1128.0)
        val car = subject(parked, movedOff, captured = 1120.0)
        // 36 px matches movedOff outright; parked is 3 % off and nearly three minutes away.
        // Offset x 604, y int(110.25) = 110.
        assertBox(CropBox(left = 0.25 / 36, top = 8.25 / 36, right = 1.0, bottom = 28.25 / 36, exact = true), car.boxInCrop(36, 36))
    }

    @Test
    fun aTallObjectAgainstTheLeftEdgeIsNotCentred() {
        // 40x100 px against x = 0: the 100 px square can't centre on it, so it's pinned at 0.
        val person = subject(px(0.0, 100.25, 40.0, 100.0))
        assertBox(CropBox(left = 0.0, top = 0.0025, right = 0.4, bottom = 1.0), person.boxInCrop(100, 100))
    }

    @Test
    fun aCarAtTheTopIsPushedDownIntoTheCrop() {
        // 160x60 px touching the top: the square would start at y = -50, so Frigate starts it at 0.
        val car = subject(px(240.0, 0.0, 160.0, 60.0))
        assertBox(CropBox(left = 0.0, top = 0.0, right = 1.0, bottom = 0.375), car.boxInCrop(160, 160))
    }

    @Test
    fun aCarAtTheBottomComesOutShortRatherThanPushedUp() {
        // Frigate clamps against the YUV buffer (540 px tall here), so a 160 px square starting at
        // y = 249 isn't moved; slicing the 360 px picture just stops at its bottom: 160x111.
        val car = subject(px(240.0, 299.75, 160.0, 60.0))
        assertBox(CropBox(left = 0.0, top = 50.75 / 111, right = 1.0, bottom = 110.75 / 111), car.boxInCrop(160, 111))
    }

    @Test
    fun nothingToPlaceGivesNoBox() {
        assertNull(subject().boxInCrop(80, 80), "no kept boxes")
        assertNull(subject(px(0.0, 0.0, 0.0, 0.0)).boxInCrop(80, 80), "a box with no size")
        assertNull(subject(px(240.0, 0.0, 160.0, 60.0)).boxInCrop(0, 0), "an image with no size")
        assertEquals(true, subject(px(240.0, 0.0, 160.0, 60.0)).boxInCrop(160, 160)?.exact)
    }
}
