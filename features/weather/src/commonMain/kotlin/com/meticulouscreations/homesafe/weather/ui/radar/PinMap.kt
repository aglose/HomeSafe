package com.meticulouscreations.homesafe.weather.ui.radar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import kotlin.math.roundToInt

/** A place on a [PinMap]: where it is, and the [key] the map tells it apart by. */
@Immutable
data class MapPin(val key: String, val latitude: Double, val longitude: Double)

/**
 * A street map with the caller's pins on it, for a screen outside the weather app (the
 * household's cars): the radar map with no radar, on whichever base map is picked in Settings.
 * Each pin is drawn by [pin], centred on its place, and follows the map as it is dragged and
 * pinched.
 *
 * The map shows every pin, as close in as they allow (no closer than a street, [SPOT_ZOOM]), or,
 * with [focus] set to a pin's key, that one pin at [SPOT_ZOOM]. It moves there when [focus]
 * changes or a pin comes or goes, not when a pin only moves, so a finger looking around isn't
 * pulled back by a fresh reading.
 */
@Composable
fun PinMap(
    pins: List<MapPin>,
    tiles: MapTileSource,
    modifier: Modifier = Modifier,
    focus: String? = null,
    animated: Boolean = true,
    pin: @Composable (MapPin) -> Unit,
) {
    val density = LocalDensity.current.density
    val tilePx = MAP_TILE_DP * density
    val marginPx = PIN_MARGIN.value * density
    // Measured before the first frame, so the map opens already fitted rather than jumping there.
    BoxWithConstraints(modifier) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 0f
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else 0f
        val view = { shown: List<MapPin> ->
            shown.firstOrNull { it.key == focus }?.let { MapCamera.at(it.latitude, it.longitude, SPOT_ZOOM) }
                ?: MapCamera.fitting(shown.map { it.latitude to it.longitude }, width, height, tilePx, SPOT_ZOOM, marginPx)
        }
        val state = remember { RadarMapState(view(pins) ?: MapCamera.at(0.0, 0.0, MapCamera.MIN_ZOOM)) }
        val currentPins by rememberUpdatedState(pins)
        PinMapContent(state, pins, tiles, focus, animated, tilePx, pin)
        LaunchedEffect(focus, pins.map { it.key }.toSet(), width, height, tilePx) {
            val target = view(currentPins) ?: return@LaunchedEffect
            if (target == state.camera) return@LaunchedEffect
            flyTo(state, target, animated)
        }
    }
}

/** Moves [state]'s camera to [target]: flown there, or simply there when nothing animates. */
private suspend fun flyTo(state: RadarMapState, target: MapCamera, animated: Boolean) {
    if (!animated) {
        state.camera = target
        return
    }
    val start = state.camera
    animate(0f, 1f, animationSpec = tween(450, easing = FastOutSlowInEasing)) { t, _ ->
        state.camera = MapCamera(
            centerX = start.centerX + (target.centerX - start.centerX) * t,
            centerY = start.centerY + (target.centerY - start.centerY) * t,
            zoom = start.zoom + (target.zoom - start.zoom) * t,
        )
    }
}

@Composable
private fun PinMapContent(
    state: RadarMapState,
    pins: List<MapPin>,
    tiles: MapTileSource,
    focus: String?,
    animated: Boolean,
    tilePx: Float,
    pin: @Composable (MapPin) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        RadarMap(
            state = state,
            timeline = null,
            position = { 0f },
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            wholeLoop = false,
            animated = animated,
            maxZoom = SPOT_MAX_ZOOM,
        )
        // The pin in focus last, so it is drawn over any it sits close to.
        pins.sortedBy { it.key == focus }.forEach { place ->
            key(place.key) {
                Box(
                    // Placed while laying out, from the camera: dragging the map moves the pins without recomposing them.
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val (dx, dy) = state.camera.project(place.latitude, place.longitude, tilePx)
                            placeable.place(
                                (constraints.maxWidth / 2f + dx - placeable.width / 2f).roundToInt(),
                                (constraints.maxHeight / 2f + dy - placeable.height / 2f).roundToInt(),
                            )
                        }
                    },
                ) {
                    pin(place)
                }
            }
        }
    }
}

/** How far in from the map's edges [PinMap] keeps the pins when it fits them all. */
private val PIN_MARGIN = 56.dp
