package com.meticulouscreations.homesafe.weather.ui.radar

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.data.RadarDecoder
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import com.meticulouscreations.homesafe.weather.domain.PlacedTile
import com.meticulouscreations.homesafe.weather.domain.RadarFrame
import com.meticulouscreations.homesafe.weather.domain.RadarTimeline
import com.meticulouscreations.homesafe.weather.ui.LocalWeatherShaders
import com.meticulouscreations.homesafe.weather.ui.shader.MAP_NIGHT_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.RADAR_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.rememberWeatherShaderClock
import com.meticulouscreations.homesafe.weather.ui.shader.weatherShaderOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** OpenStreetMap's standard tiles: free to use with attribution and a cache, which [MapTileSource] keeps. */
internal fun baseTileUrl(z: Int, x: Int, y: Int): String = "https://tile.openstreetmap.org/$z/$x/$y.png"

/** How wide a 256-pixel tile is drawn, in dp: a little under its nominal size, which keeps the map's lettering from going soft on a dense screen. */
internal const val MAP_TILE_DP = 256f * 0.62f

/** Where a radar map is looking. Held by the screen, so the map can be told to go somewhere. */
@Stable
internal class RadarMapState(camera: MapCamera) {
    var camera by mutableStateOf(camera)
}

/**
 * A map with the radar on it, drawn from tiles in three layers:
 *
 * 1. the base map, turned to night by [MAP_NIGHT_SHADER];
 * 2. the radar loop's frame at [position] (a whole number is a frame; anything between is the
 *    one fading into the next), coloured and smoothed by [RADAR_SHADER];
 * 3. a mark where the place is, pulsing.
 *
 * Drag to pan, pinch or double-tap to zoom, unless [interactive] is off (the small map on the
 * Today tab). Tiles are asked of [tiles] as the view moves; with [wholeLoop] every frame is
 * fetched for the area in view, nearest first, so the loop plays without stalling, and without
 * it only the frame on show is.
 *
 * [position] is read while drawing, so playing the loop redraws and never recomposes.
 */
@Composable
internal fun RadarMap(
    state: RadarMapState,
    timeline: RadarTimeline?,
    position: () -> Float,
    tiles: MapTileSource,
    modifier: Modifier = Modifier,
    markerLatitude: Double? = null,
    markerLongitude: Double? = null,
    interactive: Boolean = true,
    wholeLoop: Boolean = true,
    strength: Float = 0.92f,
    animated: Boolean = true,
) {
    val density = LocalDensity.current.density
    val tilePx = MAP_TILE_DP * density
    val shaded = LocalWeatherShaders.current
    val night = remember(shaded) { if (shaded) weatherShaderOrNull(MAP_NIGHT_SHADER) else null }
    val radar = remember(shaded) { if (shaded) weatherShaderOrNull(RADAR_SHADER) else null }
    val arrivals by tiles.arrivals.collectAsStateWithLifecycle()
    val clock = rememberWeatherShaderClock(running = animated)
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val framePosition by rememberUpdatedState(position)

    // Ask for what's in view whenever the view moves on to other tiles, or the loop does.
    LaunchedEffect(state, tiles, timeline, tilePx, wholeLoop) {
        snapshotFlow {
            val camera = state.camera
            val count = 1 shl camera.level
            // Half-tile steps: often enough to stay ahead of a drag, not every pixel of one.
            listOf(camera.level, floor(camera.centerX * count * 2).toInt(), floor(camera.centerY * count * 2).toInt(), viewport.width, viewport.height, framePosition().roundToInt())
        }.collectLatest { key ->
            val camera = state.camera
            val width = viewport.width.toFloat()
            val height = viewport.height.toFloat()
            if (width <= 0f || height <= 0f) return@collectLatest
            val base = camera.tiles(width, height, tilePx, camera.level).map { baseTileUrl(it.z, it.x, it.y) }
            val frames = timeline?.frames
            if (frames != null && wholeLoop && frames.isNotEmpty()) {
                // The loop is only smooth if every frame's tiles can be in memory at once.
                val perFrame = camera.tiles(width, height, tilePx, radarLevel(camera, frames.first())).size
                tiles.reserve(base.size + perFrame * frames.size)
            }
            tiles.request(base, scope, keep = true)
            if (frames == null) return@collectLatest
            val current = key.last()
            // The frame on screen first, then outward from it in both directions.
            frames.indices.sortedBy { abs(it - current) }.take(if (wholeLoop) frames.size else 1).forEach { i ->
                val frame = frames[i]
                val urls = camera.tiles(width, height, tilePx, radarLevel(camera, frame)).map { frame.url(it.z, it.x, it.y) }
                tiles.request(urls, scope, keep = false, radar = RadarDecoder.tableFor(timeline.source, frame.forecast))
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
                        .pointerInput(state, tilePx) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                state.camera = state.camera
                                    .zoomed(zoom, centroid.x - size.width / 2f, centroid.y - size.height / 2f, tilePx)
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
                                            state.camera = start.zoomed(2f.pow(t), focusX, focusY, tilePx)
                                        }
                                    }
                                },
                            )
                        }
                },
            ),
    ) {
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                if (night != null) {
                    night.setUniform("dim", if (timeline != null) 1f else 0f)
                    renderEffect = night.renderEffect()
                }
            },
        ) {
            // Read so a tile's arrival redraws the map.
            arrivals
            // The map's own colour for open land, so a tile still on its way is a gap in the detail, not a hole.
            drawRect(Color(0xFFF2EFE9))
            val camera = state.camera
            camera.tiles(size.width, size.height, tilePx, camera.level).forEach { tile ->
                drawTile(tiles, tile, ::baseTileUrl, ancestors = 4, quality = FilterQuality.Medium)
            }
        }

        if (timeline != null && timeline.frames.isNotEmpty()) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    val camera = state.camera
                    val at = position().coerceIn(0f, timeline.frames.lastIndex.toFloat())
                    val from = timeline.frames[floor(at).toInt()]
                    val to = timeline.frames[ceil(at).toInt()]
                    val blend = at - floor(at)
                    if (radar != null) {
                        // A tile's texel on screen, in pixels: the smoothing reaches about one of them.
                        val texel = tilePx * 2f.pow((camera.zoom - radarLevel(camera, from)).toFloat()) / 256f
                        radar.setUniform("size", size.width, size.height)
                        radar.setUniform("time", clock.floatValue)
                        radar.setUniform("unit", density)
                        radar.setUniform("blur", (texel * 0.8f).coerceIn(1f, 18f))
                        radar.setUniform("forecast", (if (from.forecast) 1f - blend else 0f) + (if (to.forecast) blend else 0f))
                        radar.setUniform("strength", strength)
                        renderEffect = radar.renderEffect()
                    }
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            ) {
                arrivals
                val camera = state.camera
                val at = position().coerceIn(0f, timeline.frames.lastIndex.toFloat())
                val a = floor(at).toInt()
                val b = ceil(at).toInt()
                val blend = at - a
                val from = timeline.frames[a]
                val to = timeline.frames[b]
                // A frame fades into the next only once the next has arrived; until then it stays whole,
                // or the rain would thin toward nothing and jump back at each step of a first play.
                val fading = b != a && blend > 0f && hasFrame(tiles, camera, tilePx, to)
                val drewFrom = drawFrame(tiles, camera, tilePx, from, alpha = if (fading) 1f - blend else 1f, plus = false)
                // Added, not laid over: the layer then holds exactly the strength between the two.
                val drewTo = if (fading) drawFrame(tiles, camera, tilePx, to, alpha = if (drewFrom) blend else 1f, plus = true) else false
                // Nothing of either yet: the nearest frame that has arrived stands in.
                if (!drewFrom && !drewTo) {
                    timeline.frames.indices.sortedBy { abs(it - a) }.firstOrNull { i ->
                        drawFrame(tiles, camera, tilePx, timeline.frames[i], alpha = 1f, plus = false)
                    }
                }
            }
        }

        if (markerLatitude != null && markerLongitude != null) {
            Canvas(Modifier.fillMaxSize()) {
                val (dx, dy) = state.camera.project(markerLatitude, markerLongitude, tilePx)
                val center = Offset(size.width / 2f + dx, size.height / 2f + dy)
                val phase = if (animated) (clock.floatValue % 2.6f) / 2.6f else 0.35f
                val reach = 26.dp.toPx()
                drawCircle(Color.White, radius = 6.dp.toPx() + reach * phase, center = center, alpha = 0.55f * (1f - phase), style = Stroke(1.5.dp.toPx()))
                drawCircle(Color(0x66000000), radius = 8.dp.toPx(), center = center)
                drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
                drawCircle(Color(0xFF2F8BFF), radius = 4.dp.toPx(), center = center)
            }
        }
    }
}

/** The tile level a frame is drawn from: one out from the map's (radar is coarser than a map and wants fewer, larger tiles), and no finer than the frame has. */
internal fun radarLevel(camera: MapCamera, frame: RadarFrame): Int = (camera.level - 1).coerceIn(2, frame.maxZoom)

/** Whether any of [frame]'s tiles for this view has arrived, at its own level (a coarser stand-in doesn't count). */
private fun DrawScope.hasFrame(tiles: MapTileSource, camera: MapCamera, tilePx: Float, frame: RadarFrame): Boolean =
    camera.tiles(size.width, size.height, tilePx, radarLevel(camera, frame)).any { tiles.image(frame.url(it.z, it.x, it.y)) != null }

/**
 * Draws [frame]'s tiles over the view; false if none of them has arrived. A tile not here yet
 * is stood in for by the coarser one it is part of, so zooming in a level doesn't blank the rain.
 */
private fun DrawScope.drawFrame(tiles: MapTileSource, camera: MapCamera, tilePx: Float, frame: RadarFrame, alpha: Float, plus: Boolean): Boolean {
    var drew = false
    camera.tiles(size.width, size.height, tilePx, radarLevel(camera, frame)).forEach { tile ->
        if (drawTile(tiles, tile, frame::url, ancestors = 2, quality = FilterQuality.Low, alpha = alpha, blendMode = if (plus) BlendMode.Plus else BlendMode.SrcOver)) drew = true
    }
    return drew
}

/**
 * Draws the picture for [tile], or failing that the right quarter (or sixteenth…) of the
 * nearest of its [ancestors] that has arrived, so a map zooming in sharpens rather than blanks.
 */
private fun DrawScope.drawTile(
    tiles: MapTileSource,
    tile: PlacedTile,
    url: (Int, Int, Int) -> String,
    ancestors: Int,
    quality: FilterQuality,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
): Boolean {
    var z = tile.z
    var x = tile.x
    var y = tile.y
    var up = 0
    while (true) {
        val image = tiles.image(url(z, x, y))
        if (image != null) {
            val span = 1 shl up
            val srcSize = IntSize(image.width / span, image.height / span)
            val srcOffset = IntOffset((tile.x - (x shl up)) * srcSize.width, (tile.y - (y shl up)) * srcSize.height)
            // Whole pixels, each edge rounded the same way from either side of it: neighbours share
            // the edge exactly, with no gap between them and no column drawn twice (which, where
            // tiles are added together, would count the rain there double).
            val left = tile.left.roundToInt()
            val top = tile.top.roundToInt()
            val dstSize = IntSize((tile.left + tile.size).roundToInt() - left, (tile.top + tile.size).roundToInt() - top)
            if (dstSize.width <= 0 || dstSize.height <= 0) return true
            drawImage(image, srcOffset, srcSize, IntOffset(left, top), dstSize, alpha = alpha, blendMode = blendMode, filterQuality = quality)
            return true
        }
        if (up >= ancestors || z == 0) return false
        z--
        x = x shr 1
        y = y shr 1
        up++
    }
}
