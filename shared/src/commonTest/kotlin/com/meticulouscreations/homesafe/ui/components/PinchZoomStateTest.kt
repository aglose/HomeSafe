package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.meticulouscreations.homesafe.domain.model.CameraCrop
import com.meticulouscreations.homesafe.domain.model.VideoFit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A 16:9 video (1000x562) letterboxed in a portrait viewport (1000x2000), the camera detail
 * screen's shape once zooming has taken over the scroll area.
 */
class PinchZoomStateTest {

    private fun letterboxed() = PinchZoomState().apply {
        viewportSize = IntSize(1000, 2000)
        contentSize = IntSize(1000, 562)
    }

    private val centre = Offset(500f, 1000f)

    @Test
    fun contentShorterThanViewportStaysCentredVertically() {
        val state = letterboxed()
        // 2x: the video is 1124 tall, still inside 2000, so a vertical drag must not move it.
        state.transformBy(zoomChange = 2f, pan = Offset(0f, 300f), centroid = centre)
        assertEquals(2f, state.scale)
        assertEquals(0f, state.offset.y)
    }

    @Test
    fun horizontalPanIsBoundedByTheScaledWidth() {
        val state = letterboxed()
        state.transformBy(zoomChange = 2f, pan = Offset(-5000f, 0f), centroid = centre)
        // Scaled width 2000 in a 1000 viewport: the centre may move at most 500 either way.
        assertEquals(-500f, state.offset.x)
    }

    @Test
    fun verticalPanOpensUpOnceTheContentOutgrowsTheViewport() {
        val state = letterboxed()
        // 4x: 2248 tall in 2000, so 124 of slack each way.
        state.transformBy(zoomChange = 4f, pan = Offset(0f, 5000f), centroid = centre)
        assertEquals(124f, state.offset.y)
    }

    @Test
    fun aGrowingViewportReclampsThePan() {
        val state = PinchZoomState().apply {
            viewportSize = IntSize(1000, 562)
            contentSize = IntSize(1000, 562)
        }
        // Inside its own strip a 3x video can be dragged 562 down...
        state.transformBy(zoomChange = 3f, pan = Offset(0f, 5000f), centroid = Offset(500f, 281f))
        assertEquals(562f, state.offset.y)
        // ...but when the strip grows into a 2000-tall viewport only (1686 - 2000)/2 < 0 remains: centred.
        state.viewportSize = IntSize(1000, 2000)
        assertEquals(0f, state.offset.y)
    }

    @Test
    fun zoomingAboutAPointKeepsItUnderTheFingers() {
        val state = letterboxed()
        val finger = Offset(800f, 1000f)
        state.transformBy(zoomChange = 1.5f, pan = Offset.Zero, centroid = finger)
        // The content point that was 300 right of centre is now 450 right of centre, so the
        // content shifts 150 left to keep it under the finger (well within the 250 allowed).
        assertEquals(-150f, state.offset.x)
        assertTrue(state.isZoomed)
    }

    @Test
    fun scaleNeverDropsBelowOneOrAboveMax() {
        val state = letterboxed()
        state.transformBy(zoomChange = 0.5f, pan = Offset.Zero, centroid = centre)
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
        state.transformBy(zoomChange = 100f, pan = Offset.Zero, centroid = centre)
        assertEquals(PinchZoomState.DEFAULT_MAX_SCALE, state.scale)
    }

    @Test
    fun aDragDuringTheZoomAnimationSurvivesIt() = runTest {
        val state = letterboxed()
        val job = launch {
            withContext(FrameClock) { state.animateZoomTo(PinchZoomState.DOUBLE_TAP_SCALE, centre) }
        }
        // Part way in, the video is wide enough to be dragged 100 left.
        advanceTimeBy(150)
        assertTrue(state.scale > 1.5f, "expected to be well into the zoom, was ${state.scale}")
        state.panBy(Offset(-100f, 0f))
        advanceUntilIdle()
        job.join()

        assertEquals(PinchZoomState.DOUBLE_TAP_SCALE, state.scale)
        // Zooming about the centre targets no offset: all that's left is the drag.
        assertEquals(-100f, state.offset.x, 0.01f)
    }

    @Test
    fun aDragIsBoundedLikeAnyPan() {
        val state = letterboxed()
        state.transformBy(zoomChange = 2f, pan = Offset.Zero, centroid = centre)
        state.panBy(Offset(5000f, 0f))
        assertEquals(500f, state.offset.x)
    }

    /** A 16:9 strip at rest on a crop: twice as close, a quarter of the video's width right of centre. */
    private fun restingOnACrop() = PinchZoomState().apply {
        viewportSize = IntSize(1000, 562)
        contentSize = IntSize(1000, 562)
        restOn(ZoomRest(scale = 2f, shift = Offset(0.25f, 0f)))
    }

    @Test
    fun contentAtRestOnACropShowsItWithoutCountingAsZoomed() {
        val state = restingOnACrop()
        assertEquals(2f, state.scale)
        assertEquals(Offset(250f, 0f), state.offset)
        assertFalse(state.isZoomed, "the crop is where the page opens, not a zoom to undo")
    }

    @Test
    fun pinchingOutFromACropReachesTheWholePicture() {
        val state = restingOnACrop()
        state.transformBy(zoomChange = 0.4f, pan = Offset.Zero, centroid = Offset(500f, 281f))
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
        assertTrue(state.isZoomed, "off the rest, so the page treats it as zoomed")
    }

    @Test
    fun panningFromACropReachesPastItsEdge() {
        val state = restingOnACrop()
        state.transformBy(zoomChange = 1.01f, pan = Offset(5000f, 0f), centroid = Offset(500f, 281f))
        // At about 2x the picture's left edge is 510 left of the strip's middle, past the crop's 250.
        assertTrue(state.offset.x > 500f, "dragged to the picture's own edge, was ${state.offset.x}")
    }

    @Test
    fun theViewportGrowingUnderContentAtRestKeepsItAtRest() {
        val state = restingOnACrop()
        state.viewportSize = IntSize(1000, 2000)
        assertFalse(state.isZoomed)
        assertEquals(Offset(250f, 0f), state.offset)
    }

    @Test
    fun aNewRestMovesContentAtRestButNotContentZoomedElsewhere() {
        val state = restingOnACrop()
        state.restOn(ZoomRest(scale = 3f))
        assertEquals(3f, state.scale)
        assertEquals(Offset.Zero, state.offset)

        state.transformBy(zoomChange = 0.5f, pan = Offset.Zero, centroid = Offset(500f, 281f))
        state.restOn(ZoomRest.NONE)
        assertEquals(1.5f, state.scale, "left where the fingers put it")
        assertTrue(state.isZoomed)
    }

    @Test
    fun resettingGoesBackToTheCropNotToTheWholePicture() = runTest {
        val state = restingOnACrop()
        state.transformBy(zoomChange = 0.5f, pan = Offset.Zero, centroid = Offset(500f, 281f))
        withContext(FrameClock) { state.animateReset() }
        assertEquals(2f, state.scale)
        assertEquals(Offset(250f, 0f), state.offset)
        assertFalse(state.isZoomed)
    }

    @Test
    fun aPinchLetGoNearTheCropSettlesOntoIt() = runTest {
        val state = restingOnACrop()
        state.transformBy(zoomChange = 1.05f, pan = Offset(-30f, 0f), centroid = Offset(500f, 281f))
        withContext(FrameClock) { state.settleNearRest() }
        assertFalse(state.isZoomed)

        state.transformBy(zoomChange = 0.6f, pan = Offset.Zero, centroid = Offset(500f, 281f))
        withContext(FrameClock) { state.settleNearRest() }
        assertEquals(1.2f, state.scale, 0.001f, "carried well past it: stays")
    }

    @Test
    fun aDoubleTapFromACropZoomsCloserThanIt() = runTest {
        val state = restingOnACrop()
        withContext(FrameClock) { state.animateZoomIn(Offset(500f, 281f)) }
        assertEquals(PinchZoomState.DEFAULT_MAX_SCALE, state.scale, "2.5 times the crop's 2x, as far as the zoom goes")
    }

    @Test
    fun aDoubleTapOnACropAlreadyAsCloseAsItGoesShowsTheWholePicture() = runTest {
        val state = restingOnACrop()
        state.restOn(ZoomRest(scale = PinchZoomState.DEFAULT_MAX_SCALE))
        withContext(FrameClock) { state.animateZoomIn(Offset(500f, 281f)) }
        assertEquals(1f, state.scale)
    }

    @Test
    fun aFillCropRestsWhereItWasSavedThoughThePictureSpillsPastTheFrame() {
        // A 4:3 Nest picture filling a 1600x900 frame is 1600x1200, a 150 px band cut off above
        // and below. Twice as close, low down: its middle 0.8 of the way down the picture.
        val crop = CameraCrop(zoom = 2f, centerY = 0.8f)
        val picture = pictureInBox(boxAspect = 16f / 9f, aspect = 4f / 3f, fit = VideoFit.Fill, onItsSide = false)
        val state = PinchZoomState().apply {
            viewportSize = IntSize(1600, 900)
            contentSize = IntSize(1600, 900)
            restOn(cropAsZoomRest(crop, boxAspect = 16f / 9f, aspect = 4f / 3f, fit = VideoFit.Fill, onItsSide = false), picture)
        }
        // (0.5 - 0.8) x 2 x 1200: the picture slid 720 px up, past what the frame alone allows.
        assertEquals(-720f, state.offset.y, 0.5f)
        assertFalse(state.isZoomed)

        state.panBy(Offset(0f, -5000f))
        assertEquals(-750f, state.offset.y, 0.5f, "a drag stops at the picture's bottom edge, (2400 - 900) / 2 up")
    }

    @Test
    fun aPictureBetweenBarsIsNotPannedIntoThem() {
        // A 4:3 picture shown whole in a 16:9 strip is 0.75 of its width: at 2x, 1500 of 1000 wide.
        val state = PinchZoomState().apply {
            viewportSize = IntSize(1000, 562)
            contentSize = IntSize(1000, 562)
            restOn(ZoomRest.NONE, pictureInBox(boxAspect = 1000f / 562f, aspect = 4f / 3f, fit = VideoFit.Fit, onItsSide = false))
        }
        state.transformBy(zoomChange = 2f, pan = Offset(5000f, 0f), centroid = Offset(500f, 281f))
        assertEquals(250f, state.offset.x, 1f)
    }

    /** Frames every 16ms of the test's virtual time. */
    private object FrameClock : MonotonicFrameClock {
        private var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16)
            nanos += 16_000_000L
            return onFrame(nanos)
        }
    }
}
