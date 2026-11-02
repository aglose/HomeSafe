package com.meticulouscreations.homesafe.car3d

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Where the viewer stands round the car. Reading it in a snapshot observes every change. */
@Stable
class CarOrbitState(azimuth: Float = START_AZIMUTH, elevation: Float = START_ELEVATION, zoom: Float = 1f) {
    var azimuth by mutableFloatStateOf(azimuth)
    var elevation by mutableFloatStateOf(elevation)
    var zoom by mutableFloatStateOf(zoom)

    internal val camera: CarCamera get() = CarCamera(azimuth, elevation, zoom)

    fun turn(degreesAround: Float, degreesUp: Float) {
        azimuth += degreesAround
        elevation = (elevation + degreesUp).coerceIn(MIN_ELEVATION, MAX_ELEVATION)
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    companion object {
        const val START_AZIMUTH = -38f
        const val START_ELEVATION = 11f
        private const val MIN_ELEVATION = 1.5f
        private const val MAX_ELEVATION = 78f
        private const val MIN_ZOOM = 0.8f
        private const val MAX_ZOOM = 3.6f
    }
}

@Composable
fun rememberCarOrbitState(): CarOrbitState = remember { CarOrbitState() }

/** One finger or the mouse turns the car and it coasts on after; two pinch; a wheel zooms. */
internal fun Modifier.carOrbitGestures(state: CarOrbitState): Modifier = pointerInput(state) {
    coroutineScope {
        val coast = Animatable(0f)
        launch {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll) {
                        val scroll = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (scroll != 0f) state.zoomBy(if (scroll > 0) 1f / WHEEL_ZOOM else WHEEL_ZOOM)
                    }
                }
            }
        }
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            launch { coast.stop() }
            val tracker = VelocityTracker()
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    // The lift counts as a sample, so a pause before it leaves no speed to coast on.
                    event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    break
                }
                val pan = event.calculatePan()
                val zoom = event.calculateZoom()
                if (zoom != 1f) state.zoomBy(zoom)
                if (pressed.size == 1) {
                    val perPixel = DEGREES_PER_PIXEL / (density * state.zoom)
                    state.turn(-pan.x * perPixel, pan.y * perPixel * VERTICAL_SHARE)
                    tracker.addPosition(pressed[0].uptimeMillis, pressed[0].position)
                } else {
                    tracker.resetTracking()
                }
                event.changes.forEach { it.consume() }
            }
            val velocity = tracker.calculateVelocity().x
            val perPixel = DEGREES_PER_PIXEL / (density * state.zoom)
            launch {
                coast.snapTo(0f)
                var last = 0f
                coast.animateDecay(-velocity * perPixel, exponentialDecay(frictionMultiplier = COAST_FRICTION)) {
                    state.turn(value - last, 0f)
                    last = value
                }
            }
        }
    }
}

private const val DEGREES_PER_PIXEL = 0.42f
private const val VERTICAL_SHARE = 0.55f
private const val WHEEL_ZOOM = 1.06f
private const val COAST_FRICTION = 2.4f
