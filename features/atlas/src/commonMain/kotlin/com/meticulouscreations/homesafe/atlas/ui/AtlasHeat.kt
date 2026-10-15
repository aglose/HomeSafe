package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.meticulouscreations.homesafe.atlas.AtlasPaths
import com.meticulouscreations.homesafe.atlas.domain.HeatRaster
import com.meticulouscreations.homesafe.weather.data.imageBitmapOf
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** How wide a path's glow is drawn before its bloom, in dp. */
internal const val GLOW_DP = 4.6f

/** A picture of the glow is this many of its own pixels a side. */
internal const val HEAT_TILE = 256

/** The glow is drawn afresh this many times for each doubling of the map, so what is on screen is never stretched by more than a tenth. */
private const val HEAT_STEPS = 4

/** The squares are drawn a few at a time, not on every thread there is: the history being worked out and the map's tiles being read want some too. */
private val HeatThreads = Dispatchers.Default.limitedParallelism(4)

/** A flight's arc: a fine line this far either side of its middle, on a faint band this far either side, and not drawn at all when shorter than the last. In dp. */
private const val FLIGHT_LINE_DP = 0.6f
private const val FLIGHT_BAND_DP = 2f
private const val FLIGHT_LEAST_DP = 12f

/**
 * Screen pixels to one of the glow's own. It is a soft thing: on a dense screen half the pixels
 * each way say all it has to say, in a quarter of the work.
 */
internal fun heatResolution(density: Float): Float = if (density >= 2f) 0.5f else 1f

/** What a glow is a picture of: change any of it and every square is drawn again. */
private data class HeatSource(
    val paths: AtlasPaths,
    val years: Int,
    val tilePx: Float,
    val glowPx: Float,
    val resolution: Float,
    /** The flights to draw over the glow, as `HeatRaster.arcs` takes them, or null for none; and the screen's pixels to a dp, for how fine to draw them. */
    val flights: DoubleArray?,
    val dpPx: Float,
) {
    /** The bloom's reach, in the picture's pixels. */
    val spread: Float get() = glowPx * 0.62f * resolution
    val apron: Int get() = ceil(spread * HeatRaster.FAR_RING).toInt() + 2

    /** The picture's pixels to the whole world at [step]. */
    fun scale(step: Int): Double = tilePx * resolution * 2.0.pow(step.toDouble() / HEAT_STEPS)
}

/**
 * The glow of an atlas map, kept as squares the way the base map is kept as tiles: each drawn
 * once, off the main thread ([HeatRaster]), and after that only put down. A drag asks for the
 * squares coming in at the edge; a pinch stretches the ones it has until the next step's are
 * ready, a few frames on.
 *
 * Far out a square is drawn from the counted squares of the paths (`AtlasDensity`), close in from
 * the paths themselves, as the map always chose. The flights' arcs are drawn into the same
 * squares, in a channel of their own. Used from the main thread; only the drawing of a
 * square leaves it. With [immediate] a square is drawn there and then instead, for a preview or a
 * test that takes one look and has no later.
 */
@Stable
internal class AtlasHeat(private val scope: CoroutineScope, private val immediate: Boolean = false) {
    private class Tile(val image: ImageBitmap?, val generation: Int) {
        var drawnAt = 0
    }

    private val tiles = HashMap<Long, Tile>()
    private val jobs = HashMap<Long, Job>()
    private val rasters = ArrayList<HeatRaster>()
    private val fields = Mutex()
    private var spare: HeatRaster? = null
    private var source: HeatSource? = null
    private var generation = 0
    private var frame = 0

    /** The last step whose squares covered the whole view: what is stretched while another's are on their way. */
    private var settled = NO_STEP
    private var heading = NO_STEP

    /** The step and the squares in view that were last asked for. */
    private var asked = IntArray(0)

    /** Goes up as each square arrives, for the map to draw again. */
    var arrivals by mutableIntStateOf(0)
        private set

    private fun step(zoom: Double): Int = (zoom * HEAT_STEPS).roundToInt()

    private fun key(step: Int, x: Int, y: Int): Long = (step.toLong() shl 48) or ((x.toLong() and 0xFFFFFF) shl 24) or (y.toLong() and 0xFFFFFF)

    /** The squares of [step] that cover a [width] by [height] view from [camera], [more] further on every side: calls [each] with their columns and rows. */
    private inline fun covering(camera: MapCamera, step: Int, width: Float, height: Float, tilePx: Float, scale: Double, more: Int, each: (fromX: Int, toX: Int, fromY: Int, toY: Int) -> Unit) {
        val side = HEAT_TILE / scale
        val perPixel = 1.0 / (tilePx * 2.0.pow(camera.zoom))
        val last = ceil(1.0 / side).toInt() - 1
        val fromX = (floor((camera.centerX - width / 2 * perPixel) / side).toInt() - more).coerceAtLeast(0)
        val toX = (floor((camera.centerX + width / 2 * perPixel) / side).toInt() + more).coerceAtMost(last)
        val fromY = (floor((camera.centerY - height / 2 * perPixel) / side).toInt() - more).coerceAtLeast(0)
        val toY = (floor((camera.centerY + height / 2 * perPixel) / side).toInt() + more).coerceAtMost(last)
        if (fromX <= toX && fromY <= toY) each(fromX, toX, fromY, toY)
    }

    /**
     * Says what the map is showing, so the squares for it are drawn: those in view first, from the
     * middle out, then a ring beyond them for a drag to move on to. [destination] is where the map
     * is travelling to by itself: the squares drawn while it travels are the ones for where it
     * lands, to be there when it does.
     */
    fun want(
        paths: AtlasPaths,
        years: Int,
        camera: MapCamera,
        destination: MapCamera?,
        width: Float,
        height: Float,
        tilePx: Float,
        glowPx: Float,
        resolution: Float,
        flights: DoubleArray? = null,
        dpPx: Float = 1f,
    ) {
        if (width <= 0f || height <= 0f) return
        val wanted = HeatSource(paths, years, tilePx, glowPx, resolution, flights?.takeIf { it.isNotEmpty() }, dpPx)
        val view = destination ?: camera
        val viewStep = step(view.zoom)
        var fromX = 0
        var toX = -1
        var fromY = 0
        var toY = -1
        covering(view, viewStep, width, height, tilePx, wanted.scale(viewStep), more = 0) { a, b, c, d ->
            fromX = a
            toX = b
            fromY = c
            toY = d
        }
        // Asked on every frame of a drag, and most frames show the squares the last one did.
        val asking = intArrayOf(viewStep, fromX, toX, fromY, toY, if (destination != null) 1 else 0)
        if (wanted == source && asking.contentEquals(asked)) return
        asked = asking
        if (wanted != source) {
            source = wanted
            generation++
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            // What is on screen stays until its replacement comes, rather than a frame of nothing;
            // what isn't would only be the old picture turning up later.
            tiles.entries.removeAll { it.value.drawnAt < frame }
        }
        val keys = LinkedHashSet<Long>()
        val order = ArrayList<Long>()

        fun add(from: MapCamera, more: Int) {
            val step = step(from.zoom)
            covering(from, step, width, height, tilePx, wanted.scale(step), more) { west, east, north, south ->
                val middleX = (west + east) / 2f
                val middleY = (north + south) / 2f
                order.clear()
                for (y in north..south) for (x in west..east) order += key(step, x, y)
                order.sortBy { abs((it shr 24 and 0xFFFFFF) - middleX) + abs((it and 0xFFFFFF) - middleY) }
                keys += order
            }
        }
        heading = destination?.let { step(it.zoom) } ?: NO_STEP
        if (destination != null) {
            // On its way somewhere: every step it passes through would be gone before it was drawn.
            add(destination, more = 0)
        } else {
            add(camera, more = 0)
            add(camera, more = 1)
        }

        jobs.entries.removeAll { (key, job) -> (key !in keys).also { if (it) job.cancel() } }
        for (key in keys) {
            if (tiles[key]?.generation == generation || jobs[key]?.isActive == true) continue
            val step = (key shr 48).toInt()
            val x = (key shr 24 and 0xFFFFFF).toInt()
            val y = (key and 0xFFFFFF).toInt()
            if (immediate) {
                val raster = spare?.takeIf { it.apron == wanted.apron } ?: HeatRaster(HEAT_TILE, wanted.apron).also { spare = it }
                arrived(key, draw(wanted, step, x, y, raster)?.let { imageBitmapOf(it, HEAT_TILE, HEAT_TILE) })
                continue
            }
            val made = generation
            lateinit var job: Job
            job = scope.launch {
                try {
                    val image = withContext(HeatThreads) {
                        ensureActive()
                        // A field to draw in, taken when a thread is free to draw and given back after: as many as there are threads, not as there are squares waiting.
                        val raster = fields.withLock { rasters.removeLastOrNull() }?.takeIf { it.apron == wanted.apron } ?: HeatRaster(HEAT_TILE, wanted.apron)
                        try {
                            draw(wanted, step, x, y, raster)?.let { imageBitmapOf(it, HEAT_TILE, HEAT_TILE) }
                        } finally {
                            withContext(NonCancellable) { fields.withLock { rasters += raster } }
                        }
                    }
                    if (made == generation) arrived(key, image)
                } finally {
                    if (jobs[key] === job) jobs.remove(key)
                }
            }
            if (job.isActive) jobs[key] = job
        }
    }

    private fun arrived(key: Long, image: ImageBitmap?) {
        tiles[key] = Tile(image, generation).also { it.drawnAt = frame }
        if (tiles.size > MAX_TILES) {
            // The ones longest undrawn go; a third at a time, so this is not done for every arrival.
            tiles.entries.sortedBy { it.value.drawnAt }.take(MAX_TILES / 3).forEach { tiles.remove(it.key) }
        }
        arrivals++
    }

    private fun draw(source: HeatSource, step: Int, x: Int, y: Int, raster: HeatRaster): IntArray? {
        val scale = source.scale(step)
        val side = HEAT_TILE / scale
        raster.clear()
        val level = source.paths.density.levelFor(step.toDouble() / HEAT_STEPS, source.tilePx, source.glowPx / 2f)
        if (level != null) {
            raster.dots(level, source.years, x * side, y * side, scale, source.glowPx / 2f * source.resolution)
        } else {
            raster.lines(source.paths.tracks, source.paths.trackYears, source.years, x * side, y * side, scale, source.glowPx * 0.35f * source.resolution)
        }
        source.flights?.let {
            val px = source.dpPx * source.resolution
            raster.arcs(it, x * side, y * side, scale, core = FLIGHT_LINE_DP * px, wide = FLIGHT_BAND_DP * px, least = FLIGHT_LEAST_DP * px)
        }
        return raster.picture(source.spread)
    }

    /**
     * Puts the glow down for [camera]. Each square replaces whatever is under it, so one that is
     * newer or nearer the map's own scale covers the stretched one it stands in for. [filter]
     * colours it where there is no shader to.
     */
    fun DrawScope.drawHeat(camera: MapCamera, tilePx: Float, filter: ColorFilter?) {
        arrivals
        val source = source ?: return
        frame++
        val step = step(camera.zoom)
        var whole = true
        covering(camera, step, size.width, size.height, tilePx, source.scale(step), more = 0) { fromX, toX, fromY, toY ->
            for (y in fromY..toY) for (x in fromX..toX) if (key(step, x, y) !in tiles) whole = false
        }
        if (!whole) {
            // What there is of other steps, the coarser beneath the finer, under what has come of this one.
            val a = minOf(settled, heading)
            val b = maxOf(settled, heading)
            if (a != step && standsIn(a, step)) drawStep(source, a, camera, tilePx, filter, clear = false)
            if (b != a && b != step && standsIn(b, step)) drawStep(source, b, camera, tilePx, filter, clear = false)
        } else {
            settled = step
        }
        drawStep(source, step, camera, tilePx, filter, clear = !whole)
    }

    /**
     * Whether the squares of [other] are worth drawing in place of those of [step]: near enough to
     * it in scale. From much further out one bright pixel would be a blot across the screen, and
     * from much further in there would be thousands of squares to put down.
     */
    private fun standsIn(other: Int, step: Int): Boolean = other != NO_STEP && abs(other - step) <= MAX_STRETCH_STEPS

    private fun DrawScope.drawStep(source: HeatSource, step: Int, camera: MapCamera, tilePx: Float, filter: ColorFilter?, clear: Boolean) {
        val scale = source.scale(step)
        val side = HEAT_TILE / scale
        val perWorld = tilePx * 2.0.pow(camera.zoom)
        covering(camera, step, size.width, size.height, tilePx, scale, more = 0) { fromX, toX, fromY, toY ->
            for (y in fromY..toY) {
                val top = ((y * side - camera.centerY) * perWorld + size.height / 2).roundToInt()
                val bottom = (((y + 1) * side - camera.centerY) * perWorld + size.height / 2).roundToInt()
                for (x in fromX..toX) {
                    val tile = tiles[key(step, x, y)] ?: continue
                    tile.drawnAt = frame
                    val left = ((x * side - camera.centerX) * perWorld + size.width / 2).roundToInt()
                    val right = (((x + 1) * side - camera.centerX) * perWorld + size.width / 2).roundToInt()
                    if (right <= left || bottom <= top) continue
                    val image = tile.image
                    if (image != null) {
                        drawImage(image, IntOffset.Zero, IntSize(HEAT_TILE, HEAT_TILE), IntOffset(left, top), IntSize(right - left, bottom - top), colorFilter = filter, blendMode = BlendMode.Src, filterQuality = FilterQuality.Low)
                    } else if (clear) {
                        // Nothing here at this step, which has to be said over whatever another step drew.
                        drawRect(Color.Black, Offset(left.toFloat(), top.toFloat()), Size((right - left).toFloat(), (bottom - top).toFloat()), blendMode = BlendMode.Clear)
                    }
                }
            }
        }
    }

    private companion object {
        const val NO_STEP = Int.MIN_VALUE

        /** Two and a half doublings: as far as a square is stretched or shrunk to stand in for another. */
        const val MAX_STRETCH_STEPS = HEAT_STEPS * 5 / 2

        /** A quarter megabyte each where there is anything in them, and most of a map has nothing. */
        const val MAX_TILES = 144
    }
}
