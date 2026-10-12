package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.atlas.AtlasPaths
import com.meticulouscreations.homesafe.atlas.domain.AtlasDensity
import com.meticulouscreations.homesafe.atlas.domain.Move
import com.meticulouscreations.homesafe.atlas.domain.Track
import com.meticulouscreations.homesafe.atlas.domain.VisitRole
import com.meticulouscreations.homesafe.atlas.ui.shader.ATLAS_HEAT_SHADER
import com.meticulouscreations.homesafe.atlas.ui.shader.ATLAS_NIGHT_SHADER
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import com.meticulouscreations.homesafe.weather.domain.PlacedTile
import com.meticulouscreations.homesafe.weather.domain.WebMercator
import com.meticulouscreations.homesafe.weather.ui.radar.MAP_TILE_DP
import com.meticulouscreations.homesafe.weather.ui.radar.baseTileUrl
import com.meticulouscreations.homesafe.weather.ui.shader.MAP_NIGHT_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.rememberWeatherShaderClock
import com.meticulouscreations.homesafe.weather.ui.shader.weatherShaderOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal const val ATLAS_MIN_ZOOM = 2.5
internal const val ATLAS_MAX_ZOOM = 17.0

/**
 * A box on the Earth, for telling a map what to show: from [west] eastward to [east]. An [east]
 * less than [west] is a box that runs over the date line (from 170 to -170 is twenty degrees
 * wide, not three hundred and forty).
 */
@Immutable
data class MapBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    companion object {
        /**
         * The smallest box round some places, or null for none. [points] is latitude then
         * longitude. Round the globe the smallest box is the one that leaves out the widest
         * stretch of longitude with nothing in it, which for Fiji and Samoa is not the one
         * through Greenwich.
         */
        fun of(points: List<Pair<Double, Double>>): MapBounds? {
            if (points.isEmpty()) return null
            val longitudes = points.map { it.second }.sorted()
            var west = longitudes.first()
            var east = longitudes.last()
            var widestGap = 360.0 - (east - west)
            for (i in 1 until longitudes.size) {
                val gap = longitudes[i] - longitudes[i - 1]
                if (gap > widestGap) {
                    widestGap = gap
                    west = longitudes[i]
                    east = longitudes[i - 1]
                }
            }
            return MapBounds(points.minOf { it.first }, west, points.maxOf { it.first }, east)
        }
    }
}

/** A place drawn on the map. [weight] is 0–1: how large it is drawn against the others. */
@Immutable
data class MapMarker(val key: String, val latitude: Double, val longitude: Double, val role: VisitRole, val weight: Float, val label: String? = null)

/** Where an atlas map is looking. Held by the screen, so the map can be told to go somewhere ([show]). */
@Stable
internal class AtlasMapState(camera: MapCamera = MapCamera(0.25, 0.38, 3.5)) {
    var camera by mutableStateOf(camera)

    /** Somewhere the map has been asked to show and hasn't yet turned to: it needs its own size first. */
    var pending by mutableStateOf<MapBounds?>(null)
        private set
    var pendingGlide by mutableStateOf(false)
        private set

    /** Turns the map to hold [bounds] with room round it; with [glide], by travelling there. */
    fun show(bounds: MapBounds, glide: Boolean = false) {
        pending = bounds
        pendingGlide = glide
    }

    fun shown() {
        pending = null
    }
}

/** The camera that holds [bounds] in a [width] by [height] view with a margin round it. */
internal fun cameraFor(bounds: MapBounds, width: Float, height: Float, tilePx: Float, maxZoom: Double = 15.5): MapCamera {
    val x0 = WebMercator.x(bounds.west)
    val x1 = WebMercator.x(bounds.east)
    val y0 = WebMercator.y(bounds.north)
    val y1 = WebMercator.y(bounds.south)
    // Eastward from the west edge, the short way or over the date line.
    val spanX = max((x1 - x0).mod(1.0), 1e-7)
    val spanY = max(y1 - y0, 1e-7)
    // A world of 1 is tilePx * 2^zoom pixels: the zoom at which the box fills 70% of the view.
    val zoom = min(ln(width * 0.7 / (spanX * tilePx)) / ln(2.0), ln(height * 0.7 / (spanY * tilePx)) / ln(2.0))
    return MapCamera((x0 + spanX / 2).mod(1.0), (y0 + y1) / 2, zoom.coerceIn(ATLAS_MIN_ZOOM, maxZoom))
}

// Points to draw, grown as needed and reused from one frame to the next.
private class PointBuffer {
    var values = FloatArray(1024)
    var size = 0

    fun add(x: Float, y: Float) {
        if (size + 2 > values.size) values = values.copyOf(values.size * 2)
        values[size++] = x
        values[size++] = y
    }

    fun take(): FloatArray = values.copyOf(size).also { size = 0 }
}

private const val HEAT_STEPS = 8

/** How wide a path's glow is drawn before the shader blooms it, in dp. */
private const val GLOW_DP = 4.6f

/**
 * The atlas map: OpenStreetMap turned to night, with everywhere the paths go glowing on it, the
 * brighter the more often gone ([paths], and of those only the years in [years]); the places
 * stopped at ([markers]); flights as arcs ([flights]); and, when a day or a trip is being looked
 * at, its own paths picked out ([highlight]) as far as the moment [head] says, with a point of
 * light at the head of them.
 *
 * Far out the glow is drawn from the counted squares ([AtlasDensity]), some thousands of dots that
 * run together into lines; close in, where the finest squares would show as squares, from the
 * paths in view themselves. Either way what is drawn is a picture of how often, in greys, and a
 * shader makes light of it.
 */
@Composable
internal fun AtlasMap(
    state: AtlasMapState,
    tiles: MapTileSource,
    modifier: Modifier = Modifier,
    paths: AtlasPaths? = null,
    years: Int = -1,
    markers: List<MapMarker> = emptyList(),
    flights: List<Move> = emptyList(),
    highlight: List<Track> = emptyList(),
    head: () -> Long = { Long.MAX_VALUE },
    interactive: Boolean = true,
    animated: Boolean = true,
    onTapMarker: ((MapMarker) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val tilePx = with(density) { MAP_TILE_DP.dp.toPx() }
    val colors = AtlasTheme.colors
    val shaded = LocalAtlasShaders.current
    val night = remember(shaded) { if (shaded) weatherShaderOrNull(ATLAS_NIGHT_SHADER) else null }
    val mapTone = remember(shaded) { if (shaded) weatherShaderOrNull(MAP_NIGHT_SHADER) else null }
    val heat = remember(shaded) { if (shaded) weatherShaderOrNull(ATLAS_HEAT_SHADER) else null }
    val arrivals by tiles.arrivals.collectAsStateWithLifecycle()
    val clock = rememberWeatherShaderClock(running = animated)
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val headAt by rememberUpdatedState(head)
    val tapMarker by rememberUpdatedState(onTapMarker)
    val shownMarkers by rememberUpdatedState(markers)
    val labels = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = AtlasTheme.type.label
    val buffers = remember { Array(HEAT_STEPS) { PointBuffer() } }
    val lines = remember { PointBuffer() }
    val paint = remember {
        Paint().apply {
            color = Color.White
            style = PaintingStyle.Stroke
            strokeCap = StrokeCap.Round
            blendMode = BlendMode.Plus
            isAntiAlias = true
        }
    }

    // Somewhere the screen asked to be shown, once the map knows how big it is.
    LaunchedEffect(state, state.pending, viewport, tilePx) {
        val bounds = state.pending ?: return@LaunchedEffect
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val target = cameraFor(bounds, viewport.width.toFloat(), viewport.height.toFloat(), tilePx)
        if (state.pendingGlide) {
            val from = state.camera
            animate(0f, 1f, animationSpec = tween(700, easing = FastOutSlowInEasing)) { t, _ ->
                state.camera = MapCamera(from.centerX + (target.centerX - from.centerX) * t, from.centerY + (target.centerY - from.centerY) * t, from.zoom + (target.zoom - from.zoom) * t)
            }
        } else {
            state.camera = target
        }
        state.shown()
    }

    // Ask for the tiles in view whenever the view moves on to others.
    LaunchedEffect(state, tiles, tilePx) {
        snapshotFlow {
            val camera = state.camera
            val count = 1 shl camera.level
            listOf(camera.level, floor(camera.centerX * count * 2).toInt(), floor(camera.centerY * count * 2).toInt(), viewport.width, viewport.height)
        }.collectLatest {
            val width = viewport.width.toFloat()
            val height = viewport.height.toFloat()
            if (width <= 0f || height <= 0f) return@collectLatest
            val camera = state.camera
            tiles.request(camera.tiles(width, height, tilePx, camera.level).map { baseTileUrl(it.z, it.x, it.y) }, scope, keep = true)
        }
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .then(
                if (!interactive) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(state, tilePx) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                state.camera = state.camera
                                    .zoomed(zoom, centroid.x - size.width / 2f, centroid.y - size.height / 2f, tilePx, ATLAS_MIN_ZOOM, ATLAS_MAX_ZOOM)
                                    .panned(pan.x, pan.y, tilePx)
                            }
                        }
                        .pointerInput(state, tilePx) {
                            detectTapGestures(
                                onDoubleTap = { at ->
                                    val start = state.camera
                                    val focusX = at.x - size.width / 2f
                                    val focusY = at.y - size.height / 2f
                                    scope.launch {
                                        animate(0f, 1f, animationSpec = tween(260)) { t, _ ->
                                            state.camera = start.zoomed(2f.pow(t), focusX, focusY, tilePx, ATLAS_MIN_ZOOM, ATLAS_MAX_ZOOM)
                                        }
                                    }
                                },
                                onTap = { at ->
                                    val tap = tapMarker ?: return@detectTapGestures
                                    val reach = 28.dp.toPx()
                                    shownMarkers
                                        .map { marker ->
                                            val (dx, dy) = state.camera.project(marker.latitude, marker.longitude, tilePx)
                                            marker to (Offset(size.width / 2f + dx, size.height / 2f + dy) - at).getDistance()
                                        }
                                        .filter { it.second <= reach }
                                        .minByOrNull { it.second }
                                        ?.let { tap(it.first) }
                                },
                            )
                        }
                },
            ),
    ) {
        // Night, and the lines of the globe: all there is where no tile has come.
        Canvas(Modifier.fillMaxSize()) {
            val camera = state.camera
            if (night != null) {
                val scale = 2.0.pow(camera.zoom) * tilePx
                // A graticule square is a whole number of degrees that lands near 60dp on screen.
                val degrees = graticuleDegrees(scale / 360.0, 60.dp.toPx())
                val cell = (scale * degrees / 360.0).toFloat()
                night.setUniform("size", size.width, size.height)
                night.setUniform("cell", cell)
                // Kept small, so a float holds it: only where the view sits within a square matters.
                night.setUniform("origin", ((camera.centerX * scale - size.width / 2.0).mod(cell * 4.0)).toFloat(), ((camera.centerY * scale - size.height / 2.0).mod(cell * 4.0)).toFloat())
                night.setUniform("time", clock.floatValue)
                drawRect(night.brush())
            } else {
                drawRect(colors.background)
            }
        }

        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                if (mapTone != null) {
                    mapTone.setUniform("dim", 1f)
                    renderEffect = mapTone.renderEffect()
                }
                alpha = 0.82f
            },
        ) {
            // Read so a tile's arrival redraws the map.
            arrivals
            val camera = state.camera
            camera.tiles(size.width, size.height, tilePx, camera.level).forEach { tile ->
                drawTile(tiles, tile, ancestors = 5)
            }
        }

        if (paths != null) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    if (heat != null) {
                        heat.setUniform("size", size.width, size.height)
                        heat.setUniform("spread", GLOW_DP.dp.toPx() * 0.62f)
                        heat.setUniform("time", clock.floatValue)
                        renderEffect = heat.renderEffect()
                    }
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            ) {
                val camera = state.camera
                val scale = 2.0.pow(camera.zoom) * tilePx
                val halfWidth = size.width / 2.0 / scale
                val halfHeight = size.height / 2.0 / scale
                val glow = GLOW_DP.dp.toPx()
                // For the shader the layer is a picture of strengths: black for nowhere, lighter
                // the more often gone. Without it the paths are drawn as they are, in their own colour.
                if (heat != null) drawRect(Color.Black)
                drawIntoCanvas { canvas ->
                    val level = paths.density.levelFor(camera.zoom, tilePx, glow / 2f)
                    if (level != null) {
                        level.forEachIn(camera.centerX - halfWidth, camera.centerY - halfHeight, camera.centerX + halfWidth, camera.centerY + halfHeight) { i ->
                            if (level.years[i] and years != 0) {
                                // Brightness that climbs fast at first and then levels: once and twice differ, a thousand and two thousand don't.
                                val bright = 1f - exp(-1.6f * level.weight[i] / level.typicalWeight)
                                buffers[(bright * HEAT_STEPS).toInt().coerceIn(0, HEAT_STEPS - 1)]
                                    .add(((level.x[i] - camera.centerX) * scale + size.width / 2).toFloat(), ((level.y[i] - camera.centerY) * scale + size.height / 2).toFloat())
                            }
                        }
                        paint.strokeWidth = glow
                        // The stronger of two dots where they overlap, not their sum: squares side by
                        // side along a road are one road, and each already says how often it was driven.
                        paint.blendMode = if (heat != null) BlendMode.Lighten else BlendMode.SrcOver
                        buffers.forEachIndexed { step, buffer ->
                            if (buffer.size > 0) {
                                val strength = 0.16f + 0.84f * (step + 0.5f) / HEAT_STEPS
                                paint.color = if (heat != null) Color(strength, strength, strength) else colors.trail.copy(alpha = strength)
                                canvas.drawRawPoints(PointMode.Points, buffer.take(), paint)
                            }
                        }
                    } else {
                        val west = camera.centerX - halfWidth
                        val east = camera.centerX + halfWidth
                        val north = camera.centerY - halfHeight
                        val south = camera.centerY + halfHeight
                        paths.tracks.forEachIndexed { index, track ->
                            if (track.maxX < west || track.minX > east || track.maxY < north || track.minY > south || paths.trackYears[index] and years == 0) return@forEachIndexed
                            addSegments(track, camera, scale, lines, Long.MAX_VALUE)
                        }
                        // Here each line is one journey, and journeys along the same road do add up.
                        paint.strokeWidth = glow * 0.7f
                        paint.blendMode = BlendMode.Plus
                        paint.color = if (heat != null) Color(0.2f, 0.2f, 0.2f) else colors.trail.copy(alpha = 0.26f)
                        if (lines.size > 0) canvas.drawRawPoints(PointMode.Lines, lines.take(), paint)
                    }
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val camera = state.camera
            val scale = 2.0.pow(camera.zoom) * tilePx
            val centre = Offset(size.width / 2f, size.height / 2f)

            flights.forEach { flight ->
                val (ax, ay) = camera.project(flight.latitude, flight.longitude, tilePx)
                val (bx, by) = camera.project(flight.toLatitude, flight.toLongitude, tilePx)
                val a = centre + Offset(ax, ay)
                val b = centre + Offset(bx, by)
                val span = (b - a).getDistance()
                if (span < 12.dp.toPx()) return@forEach
                // A shallow arc, bowed toward the pole as a great circle is on this projection.
                val arc = Path().apply {
                    moveTo(a.x, a.y)
                    quadraticTo((a.x + b.x) / 2f, (a.y + b.y) / 2f - span * 0.18f, b.x, b.y)
                }
                drawPath(arc, colors.air, alpha = 0.16f, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(arc, colors.air, alpha = 0.7f, style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
            }

            if (highlight.isNotEmpty()) {
                val until = headAt()
                val path = Path()
                var last: Offset? = null
                highlight.forEach { track ->
                    var pen = false
                    for (i in 0 until track.size) {
                        val at = track.start + track.seconds[i]
                        val point = Offset(((track.x(i) - camera.centerX) * scale).toFloat(), ((track.y(i) - camera.centerY) * scale).toFloat()) + centre
                        if (at > until) {
                            // Part-way along the stretch the moment falls in.
                            if (i > 0 && pen) {
                                val before = track.start + track.seconds[i - 1]
                                val t = ((until - before).toFloat() / (at - before).coerceAtLeast(1)).coerceIn(0f, 1f)
                                val from = Offset(((track.x(i - 1) - camera.centerX) * scale).toFloat(), ((track.y(i - 1) - camera.centerY) * scale).toFloat()) + centre
                                val part = from + (point - from) * t
                                path.lineTo(part.x, part.y)
                                last = part
                            }
                            break
                        }
                        if (!pen) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        pen = true
                        last = point
                    }
                }
                drawPath(path, colors.trail, alpha = 0.28f, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, colors.trail, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, Color.White, alpha = 0.85f, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (until != Long.MAX_VALUE) {
                    last?.let { at ->
                        val pulse = if (animated) 0.5f + 0.5f * kotlin.math.sin(clock.floatValue * 5f) else 0.5f
                        drawCircle(Brush.radialGradient(listOf(colors.trail.copy(alpha = 0.55f), Color.Transparent), at, (20 + 6 * pulse).dp.toPx()), (20 + 6 * pulse).dp.toPx(), at)
                        drawCircle(Color.White, 4.5.dp.toPx(), at)
                    }
                }
            }

            markers.forEach { marker ->
                val (dx, dy) = camera.project(marker.latitude, marker.longitude, tilePx)
                val at = centre + Offset(dx, dy)
                if (at.x < -40f || at.y < -40f || at.x > size.width + 40f || at.y > size.height + 40f) return@forEach
                val tint = colors.role(marker.role)
                val radius = (3.5f + 6.5f * marker.weight).dp.toPx()
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent), at, radius * 3.2f), radius * 3.2f, at)
                drawCircle(colors.background, radius + 1.5.dp.toPx(), at)
                drawCircle(tint, radius, at)
                drawCircle(Color.White, radius * 0.38f, at, alpha = 0.9f)
            }
            // Names, the weightiest first, each only where it doesn't sit on one already written.
            val taken = ArrayList<androidx.compose.ui.geometry.Rect>()
            markers.sortedByDescending { it.weight }.forEach { marker ->
                val label = marker.label ?: return@forEach
                val (dx, dy) = camera.project(marker.latitude, marker.longitude, tilePx)
                val at = centre + Offset(dx, dy)
                if (at.x < 0f || at.y < 0f || at.x > size.width || at.y > size.height) return@forEach
                val laid = labels.measure(label, labelStyle.copy(color = Color.White), maxLines = 1)
                val pad = 4.dp.toPx()
                val topLeft = Offset(at.x + (3.5f + 6.5f * marker.weight).dp.toPx() + 8.dp.toPx(), at.y - laid.size.height / 2f)
                val box = androidx.compose.ui.geometry.Rect(topLeft - Offset(pad, pad / 2), androidx.compose.ui.geometry.Size(laid.size.width + pad * 2, laid.size.height + pad))
                if (box.right > size.width || taken.any { it.overlaps(box) }) return@forEach
                taken += box
                drawRoundRect(colors.background.copy(alpha = 0.72f), box.topLeft, box.size, androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                drawText(laid, topLeft = topLeft)
            }
        }
    }
}

/** The stretches of [track] that are one journey, up to [until], as pairs of points for `PointMode.Lines`. */
private fun DrawScope.addSegments(track: Track, camera: MapCamera, scale: Double, into: PointBuffer, until: Long) {
    var previousX = 0f
    var previousY = 0f
    for (i in 0 until track.size) {
        if (track.start + track.seconds[i] > until) break
        val x = ((track.x(i) - camera.centerX) * scale + size.width / 2).toFloat()
        val y = ((track.y(i) - camera.centerY) * scale + size.height / 2).toFloat()
        // A gap of a hundred and fifty kilometres between fixes is a flight or a hole in the record, not a road.
        if (i > 0 && kotlin.math.abs(track.xy[2 * i] - track.xy[2 * i - 2]) < JUMP && kotlin.math.abs(track.xy[2 * i + 1] - track.xy[2 * i - 1]) < JUMP) {
            into.add(previousX, previousY)
            into.add(x, y)
        }
        previousX = x
        previousY = y
    }
}

private const val JUMP = (150.0 / 40_075.0).toFloat()

/** A whole, round number of degrees for the graticule: the one whose squares come out nearest [wanted] pixels at [pixelsPerDegree]. */
internal fun graticuleDegrees(pixelsPerDegree: Double, wanted: Float): Double =
    doubleArrayOf(45.0, 30.0, 15.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.25, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002, 0.001).minBy { kotlin.math.abs(it * pixelsPerDegree - wanted) }

/**
 * Draws the picture for [tile], or failing that the right quarter (or sixteenth…) of the nearest
 * of its [ancestors] that has arrived, so a map zooming in sharpens rather than blanks. The same
 * as the radar map's.
 */
private fun DrawScope.drawTile(tiles: MapTileSource, tile: PlacedTile, ancestors: Int) {
    var z = tile.z
    var x = tile.x
    var y = tile.y
    var up = 0
    while (true) {
        val image = tiles.image(baseTileUrl(z, x, y))
        if (image != null) {
            val span = 1 shl up
            val srcSize = IntSize(image.width / span, image.height / span)
            val srcOffset = IntOffset((tile.x - (x shl up)) * srcSize.width, (tile.y - (y shl up)) * srcSize.height)
            val left = tile.left.roundToInt()
            val top = tile.top.roundToInt()
            val dstSize = IntSize((tile.left + tile.size).roundToInt() - left, (tile.top + tile.size).roundToInt() - top)
            if (dstSize.width <= 0 || dstSize.height <= 0 || srcSize.width <= 0 || srcSize.height <= 0) return
            drawImage(image, srcOffset, srcSize, IntOffset(left, top), dstSize, filterQuality = FilterQuality.Medium)
            return
        }
        if (up >= ancestors || z == 0) return
        z--
        x = x shr 1
        y = y shr 1
        up++
    }
}
