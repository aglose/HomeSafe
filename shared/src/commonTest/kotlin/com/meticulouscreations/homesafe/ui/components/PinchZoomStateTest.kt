package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
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
