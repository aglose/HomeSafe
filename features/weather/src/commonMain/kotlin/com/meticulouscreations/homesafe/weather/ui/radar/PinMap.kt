package com.meticulouscreations.homesafe.weather.ui.radar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** A place on a [PinMap]: where it is, and the [key] the map tells it apart by. */
@Immutable
data class MapPin(val key: String, val latitude: Double, val longitude: Double)

/**
 * A line drawn on a [PinMap] under its pins, e.g. along a street's curb: through [points]
 * (latitude to longitude), [width] wide, and moved [offsetMetres] to its left going the way the
 * points run (negative to the right), never less than [minOffset] from them however far out the
 * map is, so two lines down one street's two curbs stay apart.
 */
@Immutable
data class MapLine(
    val points: List<Pair<Double, Double>>,
    val color: Color,
    val width: Dp = 4.dp,
    val offsetMetres: Double = 0.0,
    val minOffset: Dp = 0.dp,
)

/**
 * A street map with the caller's pins on it, for a screen outside the weather app (the
 * household's cars): the radar map with no radar, on whichever base map is picked in Settings.
 * Each pin is drawn by [pin], centred on its place, and follows the map as it is dragged and
 * pinched, over the [lines], which do the same.
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
    lines: List<MapLine> = emptyList(),
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
                ?: MapCamera.fitting(shown.map { it.latitude to it.longitude }, width, height, tilePx, SPOT_ZOOM, marginPx, minZoom = PIN_MIN_ZOOM)
        }
        val state = remember { RadarMapState(view(pins) ?: MapCamera.at(0.0, 0.0, PIN_MIN_ZOOM)) }
        val currentPins by rememberUpdatedState(pins)
        PinMapContent(state, pins, lines, tiles, focus, animated, tilePx, pin)
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
    lines: List<MapLine>,
    tiles: MapTileSource,
    focus: String?,
    animated: Boolean,
    tilePx: Float,
    pin: @Composable (MapPin) -> Unit,
) {
    // The pins are clipped with the map: one panned out of view mustn't draw over the page around it.
    Box(Modifier.fillMaxSize().clipToBounds()) {
        RadarMap(
            state = state,
            timeline = null,
            position = { 0f },
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            wholeLoop = false,
            animated = animated,
            minZoom = PIN_MIN_ZOOM,
            maxZoom = SPOT_MAX_ZOOM,
        )
        if (lines.isNotEmpty()) {
            // Read from the camera while drawing: dragging the map redraws the lines without recomposing.
            Canvas(Modifier.fillMaxSize()) { lines.forEach { drawMapLine(it, state.camera, tilePx) } }
        }
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

/** [line] where [camera] has it, each point moved off it along the line's normal there (the left one going its way, for a positive offset). */
private fun DrawScope.drawMapLine(line: MapLine, camera: MapCamera, tilePx: Float) {
    if (line.points.size < 2) return
    val centre = Offset(size.width / 2f, size.height / 2f)
    val points = line.points.map { (latitude, longitude) -> camera.project(latitude, longitude, tilePx).let { (dx, dy) -> centre + Offset(dx, dy) } }
    // Web Mercator stretches the world by 1 / cos(latitude): metres to pixels where the line is.
    val pixelsPerMetre = 2.0.pow(camera.zoom) * tilePx / (EARTH_CIRCUMFERENCE_METRES * cos(line.points[0].first * PI / 180))
    val offset = max(line.minOffset.toPx(), (abs(line.offsetMetres) * pixelsPerMetre).toFloat()) * if (line.offsetMetres < 0) -1f else 1f
    val path = Path()
    points.forEachIndexed { i, point ->
        val along = points[minOf(i + 1, points.lastIndex)] - points[maxOf(i - 1, 0)]
        val length = along.getDistance()
        // Screen y runs down: the left of (x, y) is (y, -x).
        val moved = if (length > 0f) point + Offset(along.y, -along.x) * (offset / length) else point
        if (i == 0) path.moveTo(moved.x, moved.y) else path.lineTo(moved.x, moved.y)
    }
    drawPath(path, line.color, style = Stroke(width = line.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Round the equator, as Web Mercator's world is. */
private const val EARTH_CIRCUMFERENCE_METRES = 40_075_016.686

/** As far out as [PinMap] goes: the whole world across a phone, for cars a continent apart. */
private const val PIN_MIN_ZOOM = 0.0

/** How far in from the map's edges [PinMap] keeps the pins when it fits them all. */
private val PIN_MARGIN = 56.dp
