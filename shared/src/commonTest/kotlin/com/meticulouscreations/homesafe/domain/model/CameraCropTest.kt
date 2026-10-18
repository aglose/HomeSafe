package com.meticulouscreations.homesafe.domain.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraCropTest {

    private fun assertNear(expected: Float, actual: Float, message: String? = null) =
        assertTrue(abs(expected - actual) < 1e-4f, "${message ?: ""} expected $expected, was $actual")

    @Test
    fun zoomingAboutTheMiddleKeepsTheMiddle() {
        val crop = CameraCrop.WHOLE.transformed(factor = 2f)
        assertEquals(CameraCrop(zoom = 2f, centerX = 0.5f, centerY = 0.5f), crop)
    }

    @Test
    fun zoomingAboutAPointKeepsThatPointUnderTheFingers() {
        // Fingers three quarters of the way across: at 2x that point of the picture is still there.
        val crop = CameraCrop.WHOLE.transformed(factor = 2f, focusX = 0.75f, focusY = 0.5f)
        val pictureUnderFingers = crop.centerX + (0.75f - 0.5f) / crop.zoom
        assertNear(0.75f, pictureUnderFingers)
    }

    @Test
    fun aDragMovesThePictureWithTheFinger() {
        // Dragging right by a tenth of the frame at 2x shows more of the picture's left.
        val crop = CameraCrop(zoom = 2f).transformed(factor = 1f, panX = 0.1f)
        assertNear(0.45f, crop.centerX)
        assertNear(0.5f, crop.centerY)
    }

    @Test
    fun theFrameNeverLeavesThePicture() {
        val dragged = CameraCrop(zoom = 2f).transformed(factor = 1f, panX = 5f, panY = -5f)
        assertNear(0.25f, dragged.centerX, "half the frame (a quarter of the picture at 2x) from the left edge")
        assertNear(0.75f, dragged.centerY)

        val zoomedOut = CameraCrop(zoom = 3f, centerX = 0.1f, centerY = 0.9f).zoomedTo(1f)
        assertEquals(CameraCrop.WHOLE, zoomedOut, "back to 1x the only place for the middle is the middle")
    }

    @Test
    fun theZoomStaysWithinItsReach() {
        assertEquals(1f, CameraCrop(zoom = 1.5f).transformed(factor = 0.1f).zoom)
        assertEquals(CameraCrop.MAX_ZOOM, CameraCrop(zoom = 4f).transformed(factor = 10f).zoom)
    }

    @Test
    fun aFramingIsUntouchedOnlyUprightAndWhole() {
        assertTrue(CameraFraming.UNTOUCHED.isUntouched)
        assertTrue(!CameraFraming(crop = CameraCrop(zoom = 1.2f)).isUntouched)
        assertTrue(!CameraFraming(orientation = CameraOrientation(tiltDegrees = 0.1f)).isUntouched)
    }

    // A 4:3 picture shown whole in a 16:9 frame: the frame reaches 4/3 of the way across it.
    private val letterboxed = 4f / 3f

    @Test
    fun aPictureNarrowerThanTheFrameStaysCentredUntilZoomedPastIt() {
        val crop = CameraCrop(zoom = 1.2f, centerX = 0.3f, centerY = 0.5f).clamped(spanX = letterboxed, spanY = 1f)
        assertNear(0.5f, crop.centerX, "at 1.2x the picture is still narrower than the frame")
    }

    @Test
    fun panningALetterboxedPictureStopsAtItsEdgeNotTheFrames() {
        // At 2x the frame shows 2/3 of the picture's width: its middle can go no nearer an edge than 1/3.
        val crop = CameraCrop(zoom = 2f).transformed(factor = 1f, panX = 5f, spanX = letterboxed, spanY = 1f)
        assertNear(1f / 3f, crop.centerX)
    }

    @Test
    fun theSameCropIsTheSamePointOfThePictureInAnyFrame() {
        // The crop is in fractions of the picture, so a page shaped like the picture centres where the 16:9 editor did.
        val crop = CameraCrop(zoom = 2f, centerX = 0.6f, centerY = 0.5f)
        assertNear(0.6f, crop.clamped(spanX = letterboxed, spanY = 1f).centerX)
        assertNear(0.6f, crop.clamped().centerX)
    }

    @Test
    fun zoomingAboutTheFingersInALetterboxedFrameKeepsThePictureUnderThem() {
        // Fingers at 3/4 across the 16:9 frame are over the picture at 0.5 + 0.25 * 4/3 = 5/6 of its width.
        val crop = CameraCrop.WHOLE.transformed(factor = 3f, focusX = 0.75f, spanX = letterboxed, spanY = 1f)
        assertNear(5f / 6f, crop.centerX + (0.75f - 0.5f) * letterboxed / crop.zoom)
    }
}
