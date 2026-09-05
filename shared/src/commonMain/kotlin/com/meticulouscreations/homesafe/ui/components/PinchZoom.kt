package com.meticulouscreations.homesafe.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Scale and pan for a pinch-zoomable surface, kept within bounds: never smaller than the
 * surface itself, never larger than [maxScale], and never panned far enough to show an empty
 * edge. Apply with [Modifier.pinchZoomContent] on the thing that should grow, and drive it with
 * [Modifier.pinchZoomGestures] on whatever receives the touches.
 */
@Stable
class PinchZoomState(private val maxScale: Float = DEFAULT_MAX_SCALE) {
    var scale by mutableFloatStateOf(1f)
        private set

    /** Translation of the scaled content's centre from the surface's centre, in pixels. */
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > 1f

    /** One increment of a pinch: zoom about [centroid] (surface-local) by [zoomChange], then drag by [pan]. */
    fun transformBy(zoomChange: Float, pan: Offset, centroid: Offset, size: IntSize) {
        val newScale = (scale * zoomChange).coerceIn(1f, maxScale)
        val focal = centroid - size.center
        // Keep the content point under the fingers where it is, then let the drag move it.
        val newOffset = focal + pan - (focal - offset) * (newScale / scale)
        scale = newScale
        offset = newOffset.clampedFor(newScale, size)
    }

    suspend fun animateReset() = animateTo(1f, Offset.Zero)

    /** Zooms to [targetScale] with the content under [centroid] staying put — a double-tap zoom. */
    suspend fun animateZoomTo(targetScale: Float, centroid: Offset, size: IntSize) {
        val newScale = targetScale.coerceIn(1f, maxScale)
        val focal = centroid - size.center
        val newOffset = (focal - (focal - offset) * (newScale / scale)).clampedFor(newScale, size)
        animateTo(newScale, newOffset)
    }

    private suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
        val fromScale = scale
        val fromOffset = offset
        animate(0f, 1f) { t, _ ->
            scale = fromScale + (targetScale - fromScale) * t
            offset = fromOffset + (targetOffset - fromOffset) * t
        }
    }

    private fun Offset.clampedFor(scale: Float, size: IntSize): Offset {
        val maxX = (scale - 1f) * size.width / 2f
        val maxY = (scale - 1f) * size.height / 2f
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }

    private val IntSize.center: Offset get() = Offset(width / 2f, height / 2f)

    companion object {
        const val DEFAULT_MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

@Composable
fun rememberPinchZoomState(maxScale: Float = PinchZoomState.DEFAULT_MAX_SCALE): PinchZoomState = remember { PinchZoomState(maxScale) }

/** Scales and pans this node by [state], about its centre. The parent should clip. */
fun Modifier.pinchZoomContent(state: PinchZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}

/**
 * Pinch to zoom, and — only once zoomed in — one-finger drag to pan. At 1x a single-finger drag
 * is deliberately left alone so an enclosing scroll container still scrolls over the surface.
 * Taps are never consumed, so tap handlers on descendants keep working.
 */
fun Modifier.pinchZoomGestures(state: PinchZoomState): Modifier = pointerInput(state) { detectPinchZoom(state) }

private suspend fun PointerInputScope.detectPinchZoom(state: PinchZoomState) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                    val panMotion = pan.getDistance()
                    if (zoomMotion > touchSlop || (panMotion > touchSlop && state.isZoomed)) pastTouchSlop = true
                }

                if (pastTouchSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        state.transformBy(zoomChange, panChange, centroid, size)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}
