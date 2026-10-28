package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.atlas.AtlasPaths
import com.meticulouscreations.homesafe.atlas.domain.Move
import com.meticulouscreations.homesafe.atlas.domain.Track
import com.meticulouscreations.homesafe.atlas.domain.VisitRole
import com.meticulouscreations.homesafe.atlas.ui.shader.ATLAS_HEAT_SHADER
import com.meticulouscreations.homesafe.atlas.ui.shader.ATLAS_NIGHT_SHADER
import com.meticulouscreations.homesafe.maps.BaseMap
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.data.requestUntilArrived
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import com.meticulouscreations.homesafe.weather.domain.PlacedTile
import com.meticulouscreations.homesafe.weather.domain.WebMercator
import com.meticulouscreations.homesafe.weather.ui.radar.MAP_TILE_DP
import com.meticulouscreations.homesafe.weather.ui.radar.MapCreditLabel
import com.meticulouscreations.homesafe.weather.ui.radar.followCopyright
import com.meticulouscreations.homesafe.weather.ui.radar.mapCredit
import com.meticulouscreations.homesafe.weather.ui.shader.MAP_NIGHT_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.rememberWeatherShaderClock
import com.meticulouscreations.homesafe.weather.ui.shader.weatherShaderOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

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

/**
 * Where an atlas map is looking. Held by the screen, so the map can be told to go somewhere
 * ([show]) and so that where it was left is where it is found. Everything that moves the map by
 * itself (a glide, a zoom from a button or a double tap, the run-on after a flick) goes through
 * here, one at a time, and a finger put down stops it ([halt]).
 */
@Stable
class AtlasMapState(camera: MapCamera = MapCamera(0.25, 0.38, 3.5)) {
    var camera by mutableStateOf(camera)

    /** Somewhere the map has been asked to show and hasn't yet turned to: it needs its own size first. */
    var pending by mutableStateOf<MapBounds?>(null)
        private set
    var pendingGlide by mutableStateOf(false)
        private set

    /** Where the map is travelling to by itself, while it is: what is wanted there can be got ready before it lands. */
    var destination by mutableStateOf<MapCamera?>(null)
        private set

    /** What the screen last opened the map on, for one that sends it somewhere once and not again each time it comes back. */
    var openedOn: Any? = null

    /** Google's copyright line for the view, while the base map is Google's (see `followCopyright`). */
    var copyright by mutableStateOf<String?>(null)

    private var motion: Job? = null

    /** Turns the map to hold [bounds] with room round it; with [glide], by travelling there. */
    fun show(bounds: MapBounds, glide: Boolean = false) {
        pending = bounds
        pendingGlide = glide
    }

    fun shown() {
        pending = null
    }

    /** Stops the map where it is: a finger has it now. */
    fun halt() {
        motion?.cancel()
        motion = null
        destination = null
    }

    private fun move(scope: CoroutineScope, target: MapCamera?, block: suspend () -> Unit) {
        halt()
        destination = target
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } finally {
                // Only its own: a move that took over has said where it is going.
                if (motion === job) {
                    motion = null
                    destination = null
                }
            }
        }
        if (job.isActive) motion = job else destination = null
    }

    /** Travels to [target] over [millis]: by default straight there, or along whatever [at] gives for each share of the way. */
    fun travel(scope: CoroutineScope, target: MapCamera, millis: Int = GLIDE_MS, easing: Easing = FastOutSlowInEasing, at: ((Float) -> MapCamera)? = null) {
        val from = camera
        move(scope, target) {
            animate(0f, 1f, animationSpec = tween(millis, easing = easing)) { t, _ ->
                camera = at?.invoke(t) ?: MapCamera(from.centerX + (target.centerX - from.centerX) * t, from.centerY + (target.centerY - from.centerY) * t, from.zoom + (target.zoom - from.zoom) * t)
            }
            camera = target
        }
    }

    /** Zooms by [factor] about a point of the screen ([focusX], [focusY], from the view's middle), which stays where it is throughout. */
    fun zoomBy(scope: CoroutineScope, factor: Float, focusX: Float, focusY: Float, tilePx: Float) {
        // From where a zoom still under way was heading, so two quick taps are two steps.
        val start = destination ?: camera
        val target = start.zoomed(factor, focusX, focusY, tilePx, ATLAS_MIN_ZOOM, ATLAS_MAX_ZOOM)
        if (target == start) return
        val from = camera
        val whole = target.zoom - from.zoom
        travel(scope, target, ZOOM_MS, LinearOutSlowInEasing) { t ->
            if (from === start) from.zoomed(2f.pow((whole * t).toFloat()), focusX, focusY, tilePx, ATLAS_MIN_ZOOM, ATLAS_MAX_ZOOM) else MapCamera(from.centerX + (target.centerX - from.centerX) * t, from.centerY + (target.centerY - from.centerY) * t, from.zoom + whole * t)
        }
    }

    /** Lets the map run on from a flick at [velocity] pixels a second, slowing as a list does. */
    fun fling(scope: CoroutineScope, velocity: Offset, tilePx: Float, decay: DecayAnimationSpec<Offset>) {
        move(scope, null) {
            var last = Offset.Zero
            AnimationState(Offset.VectorConverter, Offset.Zero, velocity).animateDecay(decay) {
                val step = value - last
                last = value
                camera = camera.panned(step.x, step.y, tilePx)
            }
        }
    }

    private companion object {
        const val GLIDE_MS = 700
        const val ZOOM_MS = 280
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

/** A flick slower than this, in dp a second, is a drag that stopped. */
private const val MIN_FLING_DP = 160f

/** The map's own light (the night under it, the shimmer of its glow) moves this often: slow things, which a faster step would only draw more times. */
internal const val ATLAS_FRAME_SECONDS = 1f / 30f

/** The places on a map, worked out once for drawing them every frame: where in the world, how large, and their names laid out. */
private class MarkerShapes(val markers: List<MapMarker>, val radius: FloatArray, val labels: Array<TextLayoutResult?>) {
    val x = DoubleArray(markers.size) { WebMercator.x(markers[it].longitude) }
    val y = DoubleArray(markers.size) { WebMercator.y(markers[it].latitude) }

    /** Those with names, the weightiest first: each is written only where it doesn't sit on one already written. */
    val named: IntArray = markers.indices.filter { labels[it] != null }.sortedByDescending { markers[it].weight }.toIntArray()
}

/** How far east of the camera's middle [x] is, the short way round the world. */
private fun MapCamera.east(x: Double): Double {
    val dx = x - centerX
    return when {
        dx > 0.5 -> dx - 1.0
        dx < -0.5 -> dx + 1.0
        else -> dx
    }
}

/**
 * The atlas map: the base map (OpenStreetMap's or Google's, as [MapTileSource.baseMap] says) turned to night, with everywhere the paths go glowing on it, the
 * brighter the more often gone ([paths], and of those only the years in [years]); the places
 * stopped at ([markers]); flights as arcs ([flights]); and, when a day or a trip is being looked
 * at, its own paths picked out ([highlight]) as far as the moment [head] says, with a point of
 * light at the head of them.
 *
 * The glow is a picture of how often, made off the screen and kept ([AtlasHeat]): far out from
 * the counted squares (`AtlasDensity`), close in, where the finest squares would show as squares,
 * from the paths themselves. A shader makes light of it. So a frame of this map, however much
 * history is on it, is some tiles and some pictures put down, and the map follows the finger.
 * A Google map wears its credit in a corner unless [showCredit] is off, for a screen that
 * credits it in a line of its own (from [AtlasMapState.copyright]).
 */
@Composable
fun AtlasMap(
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
    showCredit: Boolean = true,
) {
    val density = LocalDensity.current
    val tilePx = with(density) { MAP_TILE_DP.dp.toPx() }
    val glowPx = with(density) { GLOW_DP.dp.toPx() }
    val resolution = heatResolution(density.density)
    val colors = AtlasTheme.colors
    val shaded = LocalAtlasShaders.current
    val night = remember(shaded) { if (shaded) weatherShaderOrNull(ATLAS_NIGHT_SHADER) else null }
    val mapTone = remember(shaded) { if (shaded) weatherShaderOrNull(MAP_NIGHT_SHADER) else null }
    val heat = remember(shaded) { if (shaded) weatherShaderOrNull(ATLAS_HEAT_SHADER) else null }
    // Without the shader the glow is the paths' own colour, as strong as the picture says.
    val plainGlow = remember(heat, colors) {
        if (heat != null) {
            null
        } else {
            val trail = colors.trail
            ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, trail.red * 255f, 0f, 0f, 0f, 0f, trail.green * 255f, 0f, 0f, 0f, 0f, trail.blue * 255f, 1f, 0f, 0f, 0f, 0f)))
        }
    }
    val arrivals by tiles.arrivals.collectAsStateWithLifecycle()
    val baseMap by tiles.baseMap.collectAsStateWithLifecycle()
    val clock = rememberWeatherShaderClock(running = animated, frameSeconds = ATLAS_FRAME_SECONDS)
    val scope = rememberCoroutineScope()
    // A preview or a test takes one look: the glow is drawn for it there and then.
    val immediate = LocalInspectionMode.current || !shaded
    val glow = remember(scope, immediate) { AtlasHeat(scope, immediate) }
    val decay = rememberSplineBasedDecay<Offset>()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val headAt by rememberUpdatedState(head)
    val tapMarker by rememberUpdatedState(onTapMarker)
    val labels = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = AtlasTheme.type.label
    val shapes = remember(markers, labelStyle, density) {
        MarkerShapes(
            markers,
            FloatArray(markers.size) { with(density) { (3.5f + 6.5f * markers[it].weight).dp.toPx() } },
            Array(markers.size) { index -> markers[index].label?.let { labels.measure(it, labelStyle.copy(color = Color.White), maxLines = 1) } },
        )
    }
    val shownShapes by rememberUpdatedState(shapes)
    val halos = remember(colors) { VisitRole.entries.map { Brush.radialGradient(listOf(colors.role(it).copy(alpha = 0.45f), Color.Transparent), center = Offset.Zero, radius = 1f) } }
    val headGlow = remember(colors) { Brush.radialGradient(listOf(colors.trail.copy(alpha = 0.55f), Color.Transparent), center = Offset.Zero, radius = 1f) }
    // Each flight's two ends in the map's world, in fours.
    val flightEnds = remember(flights) {
        DoubleArray(flights.size * 4).also { ends ->
            flights.forEachIndexed { i, flight ->
                ends[4 * i] = WebMercator.x(flight.longitude)
                ends[4 * i + 1] = WebMercator.y(flight.latitude)
                ends[4 * i + 2] = WebMercator.x(flight.toLongitude)
                ends[4 * i + 3] = WebMercator.y(flight.toLatitude)
            }
        }
    }
    // Where there is a glow and a shader for it, the flights are drawn into its pictures and coloured by it.
    val flightsInGlow = paths != null && heat != null
    val arcs = remember { Path() }
    val route = remember { Path() }
    // Made again when the base map changes: its addresses are another provider's, or another session's.
    val tileUrls = remember(baseMap) { HashMap<Long, String>() }

    // Somewhere the screen asked to be shown, once the map knows how big it is.
    LaunchedEffect(state, state.pending, viewport, tilePx) {
        val bounds = state.pending ?: return@LaunchedEffect
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val target = cameraFor(bounds, viewport.width.toFloat(), viewport.height.toFloat(), tilePx)
        if (state.pendingGlide) {
            state.travel(scope, target)
        } else {
            state.halt()
            state.camera = target
        }
        state.shown()
    }

    // Ask for the tiles in view whenever the view moves on to others. A glide asks for those where
    // it will land, not for every level it passes through on the way.
    LaunchedEffect(state, tiles, tilePx, baseMap) {
        val map = baseMap ?: return@LaunchedEffect
        snapshotFlow {
            val camera = state.destination ?: state.camera
            val count = 1 shl camera.level
            listOf(camera.level, floor(camera.centerX * count * 2).toInt(), floor(camera.centerY * count * 2).toInt(), viewport.width, viewport.height)
        }.collectLatest {
            val width = viewport.width.toFloat()
            val height = viewport.height.toFloat()
            if (width <= 0f || height <= 0f) return@collectLatest
            val camera = state.destination ?: state.camera
            // Asked again while any is missing: one a slow network dropped would stay a hole until the view moved.
            tiles.requestUntilArrived(camera.tiles(width, height, tilePx, camera.level).map { map.url(it.z, it.x, it.y) }, scope, keep = map.keep)
        }
    }

    LaunchedEffect(state, tiles, baseMap, viewport, tilePx) {
        tiles.followCopyright(baseMap, { state.camera }, viewport, tilePx) { state.copyright = it }
    }

    // And for the glow's pictures of it.
    if (paths != null) {
        LaunchedEffect(state, glow, paths, years, tilePx, glowPx, resolution, flightEnds, flightsInGlow) {
            snapshotFlow { Triple(state.camera, state.destination, viewport) }.collect { (camera, destination, size) ->
                glow.want(paths, years, camera, destination, size.width.toFloat(), size.height.toFloat(), tilePx, glowPx, resolution, flightEnds.takeIf { flightsInGlow }, density.density)
            }
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
                        .pointerInput(state, tilePx, decay) { detectMapGestures(state, tilePx, scope, decay) }
                        .pointerInput(state, tilePx) {
                            detectTapGestures(
                                onDoubleTap = { at -> state.zoomBy(scope, 2f, at.x - size.width / 2f, at.y - size.height / 2f, tilePx) },
                                onTap = { at ->
                                    val tap = tapMarker ?: return@detectTapGestures
                                    val reach = 28.dp.toPx()
                                    val shown = shownShapes
                                    val camera = state.camera
                                    val scale = 2.0.pow(camera.zoom) * tilePx
                                    var nearest = -1
                                    var nearestAway = reach
                                    for (i in shown.markers.indices) {
                                        val x = (camera.east(shown.x[i]) * scale).toFloat() + size.width / 2f
                                        val y = ((shown.y[i] - camera.centerY) * scale).toFloat() + size.height / 2f
                                        val away = (Offset(x, y) - at).getDistance()
                                        if (away <= nearestAway) {
                                            nearestAway = away
                                            nearest = i
                                        }
                                    }
                                    if (nearest >= 0) tap(shown.markers[nearest])
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
            val map = baseMap ?: return@Canvas
            if (tileUrls.size > 2048) tileUrls.clear()
            camera.tiles(size.width, size.height, tilePx, camera.level).forEach { tile ->
                drawTile(tiles, map, tileUrls, tile, ancestors = 5)
            }
        }

        if (paths != null) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    if (heat != null) {
                        heat.setUniform("time", clock.floatValue)
                        heat.setUniform("air", colors.air)
                        renderEffect = heat.renderEffect()
                    }
                    // The pictures replace what is under them, which must be nothing but each other.
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            ) {
                with(glow) { drawHeat(state.camera, tilePx, plainGlow) }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val camera = state.camera
            val scale = 2.0.pow(camera.zoom) * tilePx
            val centreX = size.width / 2f
            val centreY = size.height / 2f

            if (flightEnds.isNotEmpty() && !flightsInGlow) {
                arcs.rewind()
                val least = 12.dp.toPx()
                for (i in 0 until flightEnds.size / 4) {
                    val ax = (camera.east(flightEnds[4 * i]) * scale).toFloat() + centreX
                    val ay = ((flightEnds[4 * i + 1] - camera.centerY) * scale).toFloat() + centreY
                    val bx = (camera.east(flightEnds[4 * i + 2]) * scale).toFloat() + centreX
                    val by = ((flightEnds[4 * i + 3] - camera.centerY) * scale).toFloat() + centreY
                    val span = Offset(bx - ax, by - ay).getDistance()
                    if (span < least) continue
                    // A shallow arc, bowed toward the pole as a great circle is on this projection.
                    val bow = span * 0.18f
                    // Wholly off one side of the view, bow and all.
                    if (max(ax, bx) < 0f || min(ax, bx) > size.width || max(ay, by) < 0f || min(ay, by) - bow > size.height) continue
                    arcs.moveTo(ax, ay)
                    arcs.quadraticTo((ax + bx) / 2f, (ay + by) / 2f - bow, bx, by)
                }
                drawPath(arcs, colors.air, alpha = 0.16f, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(arcs, colors.air, alpha = 0.7f, style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
            }

            if (highlight.isNotEmpty()) {
                val until = headAt()
                route.rewind()
                var lastX = Float.NaN
                var lastY = Float.NaN
                highlight.forEach { track ->
                    var pen = false
                    for (i in 0 until track.size) {
                        val at = track.start + track.seconds[i]
                        val x = ((track.x(i) - camera.centerX) * scale).toFloat() + centreX
                        val y = ((track.y(i) - camera.centerY) * scale).toFloat() + centreY
                        if (at > until) {
                            // Part-way along the stretch the moment falls in.
                            if (i > 0 && pen) {
                                val before = track.start + track.seconds[i - 1]
                                val t = ((until - before).toFloat() / (at - before).coerceAtLeast(1)).coerceIn(0f, 1f)
                                lastX += (x - lastX) * t
                                lastY += (y - lastY) * t
                                route.lineTo(lastX, lastY)
                            }
                            break
                        }
                        if (!pen) route.moveTo(x, y) else route.lineTo(x, y)
                        pen = true
                        lastX = x
                        lastY = y
                    }
                }
                drawPath(route, colors.trail, alpha = 0.28f, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(route, colors.trail, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(route, Color.White, alpha = 0.85f, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (until != Long.MAX_VALUE && !lastX.isNaN()) {
                    val pulse = if (animated) 0.5f + 0.5f * sin(clock.floatValue * 5f) else 0.5f
                    val reach = (20 + 6 * pulse).dp.toPx()
                    withTransform({
                        translate(lastX, lastY)
                        scale(reach, reach, Offset.Zero)
                    }) { drawCircle(headGlow, 1f, Offset.Zero) }
                    drawCircle(Color.White, 4.5.dp.toPx(), Offset(lastX, lastY))
                }
            }

            // Every halo, then every disc: one kind of thing at a time is one piece of work for the
            // graphics chip, where a halo and three discs for each place in turn is four apiece.
            val rim = 1.5.dp.toPx()
            for (pass in 0..1) {
                for (i in shapes.markers.indices) {
                    val x = (camera.east(shapes.x[i]) * scale).toFloat() + centreX
                    val y = ((shapes.y[i] - camera.centerY) * scale).toFloat() + centreY
                    if (x < -40f || y < -40f || x > size.width + 40f || y > size.height + 40f) continue
                    val role = shapes.markers[i].role
                    val radius = shapes.radius[i]
                    if (pass == 0) {
                        withTransform({
                            translate(x, y)
                            scale(radius * 3.2f, radius * 3.2f, Offset.Zero)
                        }) { drawCircle(halos[role.ordinal], 1f, Offset.Zero) }
                    } else {
                        val at = Offset(x, y)
                        drawCircle(colors.background, radius + rim, at)
                        drawCircle(colors.role(role), radius, at)
                        drawCircle(Color.White, radius * 0.38f, at, alpha = 0.9f)
                    }
                }
            }
            if (shapes.named.isNotEmpty()) {
                val taken = ArrayList<Rect>(shapes.named.size)
                val pad = 4.dp.toPx()
                val gap = 8.dp.toPx()
                val corner = CornerRadius(8.dp.toPx())
                val plate = colors.background.copy(alpha = 0.72f)
                for (i in shapes.named) {
                    val laid = shapes.labels[i] ?: continue
                    val x = (camera.east(shapes.x[i]) * scale).toFloat() + centreX
                    val y = ((shapes.y[i] - camera.centerY) * scale).toFloat() + centreY
                    if (x < 0f || y < 0f || x > size.width || y > size.height) continue
                    val topLeft = Offset(x + shapes.radius[i] + gap, y - laid.size.height / 2f)
                    val box = Rect(topLeft - Offset(pad, pad / 2), Size(laid.size.width + pad * 2, laid.size.height + pad))
                    if (box.right > size.width || taken.any { it.overlaps(box) }) continue
                    taken += box
                    drawRoundRect(plate, box.topLeft, box.size, corner)
                    drawText(laid, topLeft = topLeft)
                }
            }
        }

        if (showCredit && baseMap is BaseMap.Google) {
            MapCreditLabel(mapCredit(baseMap, state.copyright), Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
    }
}

/**
 * One finger drags the map and two pinch it, as `detectTransformGestures` has it, and a drag let
 * go of at speed runs on ([AtlasMapState.fling]). Putting a finger down stops the map wherever it
 * was going by itself.
 */
private suspend fun PointerInputScope.detectMapGestures(state: AtlasMapState, tilePx: Float, scope: CoroutineScope, decay: DecayAnimationSpec<Offset>) {
    val tracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.halt()
        tracker.resetTracking()
        tracker.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        var zoom = 1f
        var pan = Offset.Zero
        var moving = false
        var pinched = false
        var over: Boolean
        do {
            val event = awaitPointerEvent()
            over = event.changes.any { it.isConsumed }
            if (!over) {
                val zoomed = event.calculateZoom()
                val panned = event.calculatePan()
                if (event.changes.count { it.pressed } > 1) pinched = true
                if (!moving) {
                    zoom *= zoomed
                    pan += panned
                    if (abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false) > slop || pan.getDistance() > slop) moving = true
                }
                if (moving) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoomed != 1f || panned != Offset.Zero) {
                        state.camera = state.camera
                            .zoomed(zoomed, centroid.x - size.width / 2f, centroid.y - size.height / 2f, tilePx, ATLAS_MIN_ZOOM, ATLAS_MAX_ZOOM)
                            .panned(panned.x, panned.y, tilePx)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
            }
        } while (!over && event.changes.any { it.pressed })
        // Two fingers lifting are never quite together, and the one left behind would throw the map.
        if (moving && !pinched && !over) {
            val velocity = tracker.calculateVelocity()
            val speed = Offset(velocity.x, velocity.y)
            if (speed.getDistance() > MIN_FLING_DP.dp.toPx()) state.fling(scope, speed, tilePx, decay)
        }
    }
}

/** A whole, round number of degrees for the graticule: the one whose squares come out nearest [wanted] pixels at [pixelsPerDegree]. */
internal fun graticuleDegrees(pixelsPerDegree: Double, wanted: Float): Double =
    doubleArrayOf(45.0, 30.0, 15.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.25, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002, 0.001).minBy { kotlin.math.abs(it * pixelsPerDegree - wanted) }

/**
 * Draws the picture for [tile], or failing that the right quarter (or sixteenth…) of the nearest
 * of its [ancestors] that has arrived, so a map zooming in sharpens rather than blanks. The same
 * as the radar map's. [urls] is where the tiles' addresses on [map] are kept.
 */
private fun DrawScope.drawTile(tiles: MapTileSource, map: BaseMap, urls: HashMap<Long, String>, tile: PlacedTile, ancestors: Int) {
    var z = tile.z
    var x = tile.x
    var y = tile.y
    var up = 0
    while (true) {
        // The address of a tile is asked for on every frame it is drawn in: made once and kept.
        val image = tiles.image(urls.getOrPut((z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()) { map.url(z, x, y) })
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
