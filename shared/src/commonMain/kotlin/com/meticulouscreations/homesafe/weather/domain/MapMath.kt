package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The web's map projection (EPSG:3857, "slippy map" tiles): the world is a square, [x] and [y]
 * run 0–1 across it from the north-west corner, and at zoom `z` it is cut into 2^z by 2^z tiles.
 */
object WebMercator {
    /** Beyond this the projection runs off to infinity; tiles stop here. */
    const val MAX_LATITUDE = 85.0511

    fun x(longitude: Double): Double = (longitude + 180.0) / 360.0

    fun y(latitude: Double): Double {
        val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180.0
        return (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0
    }

    fun longitude(x: Double): Double = x * 360.0 - 180.0

    fun latitude(y: Double): Double = atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI

    /** Kilometres one screen pixel spans at [latitude] when a tile is drawn [tilePx] wide at [zoom]. */
    fun kmPerPixel(latitude: Double, zoom: Double, tilePx: Double): Double =
        40_075.017 * cos(latitude * PI / 180.0) / (2.0.pow(zoom) * tilePx)
}

/** One map tile and where it lands on screen: its top-left corner and its side, in pixels. */
@Immutable
data class PlacedTile(val z: Int, val x: Int, val y: Int, val left: Float, val top: Float, val size: Float)

/**
 * Where a map is looking: the point at the middle of the view ([centerX], [centerY], in
 * [WebMercator]'s 0–1 world) and how far in ([zoom], continuous: 7.5 is halfway between tile
 * levels 7 and 8). A value; panning and zooming make a new one.
 */
@Immutable
data class MapCamera(val centerX: Double, val centerY: Double, val zoom: Double) {

    /** World units per screen pixel, with tiles drawn [tilePx] wide at a whole zoom. */
    private fun worldPerPixel(tilePx: Float): Double = 1.0 / (2.0.pow(zoom) * tilePx)

    /** Dragged by ([dx], [dy]) pixels: the map follows the finger, so the centre moves the other way. */
    fun panned(dx: Float, dy: Float, tilePx: Float): MapCamera {
        val k = worldPerPixel(tilePx)
        return copy(centerX = wrap(centerX - dx * k), centerY = (centerY - dy * k).coerceIn(0.02, 0.98))
    }

    /**
     * Zoomed by [factor] about the screen point ([focusX], [focusY]) (measured from the view's
     * centre), which stays under the fingers.
     */
    fun zoomed(factor: Float, focusX: Float, focusY: Float, tilePx: Float, minZoom: Double = MIN_ZOOM, maxZoom: Double = MAX_ZOOM): MapCamera {
        val target = (zoom + ln(factor.toDouble()) / ln(2.0)).coerceIn(minZoom, maxZoom)
        if (target == zoom) return this
        val before = worldPerPixel(tilePx)
        val after = 1.0 / (2.0.pow(target) * tilePx)
        return copy(
            centerX = wrap(centerX + focusX * (before - after)),
            centerY = (centerY + focusY * (before - after)).coerceIn(0.02, 0.98),
            zoom = target,
        )
    }

    /** The screen position of a place, in pixels from the view's centre. */
    fun project(latitude: Double, longitude: Double, tilePx: Float): Pair<Float, Float> {
        val k = worldPerPixel(tilePx)
        var dx = WebMercator.x(longitude) - centerX
        // The short way round the world.
        if (dx > 0.5) dx -= 1.0
        if (dx < -0.5) dx += 1.0
        return (dx / k).toFloat() to ((WebMercator.y(latitude) - centerY) / k).toFloat()
    }

    /**
     * The tiles of level [level] that cover a [width] by [height] pixel view, each with where to
     * draw it. [level] may be coarser than [zoom] (radar is drawn from a level or two out and
     * stretched) or the level itself.
     */
    fun tiles(width: Float, height: Float, tilePx: Float, level: Int): List<PlacedTile> {
        if (width <= 0f || height <= 0f) return emptyList()
        val count = 1 shl level
        val size = (tilePx * 2.0.pow(zoom - level)).toFloat()
        // The view's top-left corner, in tiles of this level.
        val originX = centerX * count - width / 2.0 / size
        val originY = centerY * count - height / 2.0 / size
        val firstX = floor(originX).toInt()
        val firstY = floor(originY).toInt().coerceAtLeast(0)
        val lastX = floor(originX + width / size).toInt()
        val lastY = floor(originY + height / size).toInt().coerceAtMost(count - 1)
        val placed = ArrayList<PlacedTile>()
        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                placed += PlacedTile(
                    z = level,
                    x = ((tx % count) + count) % count,
                    y = ty,
                    left = ((tx - originX) * size).toFloat(),
                    top = ((ty - originY) * size).toFloat(),
                    size = size,
                )
            }
        }
        return placed
    }

    /** The whole tile level this view is drawn from. */
    val level: Int get() = floor(zoom + 0.0001).toInt().coerceIn(0, 19)

    companion object {
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 11.0

        fun at(latitude: Double, longitude: Double, zoom: Double): MapCamera = MapCamera(WebMercator.x(longitude), WebMercator.y(latitude), zoom)

        private fun wrap(x: Double): Double = x - floor(x)
    }
}
