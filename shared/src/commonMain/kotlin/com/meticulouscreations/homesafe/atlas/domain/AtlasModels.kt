package com.meticulouscreations.homesafe.atlas.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.weather.domain.WebMercator
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How a stretch of the way was travelled, as the relay names it ([key]; `relay/relay.py`, TIMELINE_MODES). */
enum class TravelMode(val key: String) {
    WALK("walk"),
    RUN("run"),
    CYCLE("cycle"),
    DRIVE("drive"),
    MOTORCYCLE("motorcycle"),
    BUS("bus"),
    TRAIN("train"),
    SUBWAY("subway"),
    TRAM("tram"),
    FLY("fly"),
    FERRY("ferry"),
    BOAT("boat"),
    SKI("ski"),
    OTHER("other"),
    ;

    /** Under one's own power. */
    val active: Boolean get() = this == WALK || this == RUN || this == CYCLE || this == SKI

    companion object {
        fun of(key: String): TravelMode = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** What a place is to the person whose history this is. */
enum class PlaceKind(val key: String) {
    HOME("home"),
    WORK("work"),
    OTHER("other"),
    ;

    companion object {
        fun of(key: String): PlaceKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * A stay somewhere. Times are epoch seconds; [offsetMinutes] is how far ahead of UTC the clock on
 * the wall was there, so [startDay] is the day it was for the person, wherever they were.
 * [placeKey] names the place: Google's id for it, or for a stay Google matched to nothing, where
 * it was to about a hundred metres ([Place.keyAt]).
 */
@Immutable
data class Visit(
    val start: Long,
    val end: Long,
    val offsetMinutes: Int,
    val latitude: Double,
    val longitude: Double,
    val placeKey: String,
    val kind: PlaceKind,
) {
    val seconds: Long get() = end - start
    val startDay: Int get() = localDay(start, offsetMinutes)

    /** The last day any of it fell on. */
    val endDay: Int get() = localDay(maxOf(start, end - 1), offsetMinutes)
}

/** Getting from one place to another. */
@Immutable
data class Move(
    val start: Long,
    val end: Long,
    val offsetMinutes: Int,
    val mode: TravelMode,
    val latitude: Double,
    val longitude: Double,
    val toLatitude: Double,
    val toLongitude: Double,
    val meters: Double,
) {
    val seconds: Long get() = end - start
    val startDay: Int get() = localDay(start, offsetMinutes)
}

/** Somewhere that was visited. [name] and what follows it are Google's, null until the relay has asked (or for a place Google has no id for). */
@Immutable
data class Place(
    val key: String,
    val latitude: Double,
    val longitude: Double,
    val kind: PlaceKind = PlaceKind.OTHER,
    val name: String? = null,
    val address: String? = null,
    val type: String? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
) {
    companion object {
        /** The key of a stay with no place id: its position to three decimal places, about a hundred metres. */
        fun keyAt(latitude: Double, longitude: Double): String = "@${(latitude * 1000).roundToInt()},${(longitude * 1000).roundToInt()}"
    }
}

/**
 * The points along a stretch of the way, ready to draw: positions in [WebMercator]'s 0–1 world,
 * each as its distance from ([originX], [originY]) so that a float holds it to well under a metre
 * ([xy] is x0, y0, x1, y1…), and [seconds] after [start] for each. The box round them is kept for
 * telling quickly whether any of it is in view.
 */
@Immutable
class Track(
    val start: Long,
    val offsetMinutes: Int,
    val originX: Double,
    val originY: Double,
    val xy: FloatArray,
    val seconds: IntArray,
) {
    val size: Int get() = seconds.size
    val end: Long get() = start + (seconds.lastOrNull() ?: 0)
    val minX: Double
    val maxX: Double
    val minY: Double
    val maxY: Double

    init {
        var x0 = 0f
        var x1 = 0f
        var y0 = 0f
        var y1 = 0f
        for (i in 0 until size) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        minX = originX + x0
        maxX = originX + x1
        minY = originY + y0
        maxY = originY + y1
    }

    fun x(index: Int): Double = originX + xy[2 * index]

    fun y(index: Int): Double = originY + xy[2 * index + 1]

    companion object {
        /** From the relay's packing: an encoded polyline, and each point's seconds after [start] packed the same way. */
        fun decode(start: Long, offsetMinutes: Int, points: String, times: String): Track {
            val degrees = Polyline.decode(points)
            val count = degrees.size / 2
            val seconds = Polyline.decodeNumbers(times).let { read -> IntArray(count) { read.getOrElse(it) { read.lastOrNull() ?: 0 } } }
            if (count == 0) return Track(start, offsetMinutes, 0.0, 0.0, FloatArray(0), seconds)
            val originX = WebMercator.x(degrees[1])
            val originY = WebMercator.y(degrees[0])
            val xy = FloatArray(count * 2)
            for (i in 0 until count) {
                xy[2 * i] = (WebMercator.x(degrees[2 * i + 1]) - originX).toFloat()
                xy[2 * i + 1] = (WebMercator.y(degrees[2 * i]) - originY).toFloat()
            }
            return Track(start, offsetMinutes, originX, originY, xy, seconds)
        }
    }
}

/** The day, counted from 1970-01-01, that [epochSeconds] fell on where the clock was [offsetMinutes] ahead of UTC. */
fun localDay(epochSeconds: Long, offsetMinutes: Int): Int = (epochSeconds + offsetMinutes * 60L).floorDiv(SECONDS_PER_DAY).toInt()

/** Minutes past midnight on that clock. */
fun localMinute(epochSeconds: Long, offsetMinutes: Int): Int = ((epochSeconds + offsetMinutes * 60L).mod(SECONDS_PER_DAY) / 60).toInt()

/** 0 for Monday to 6 for Sunday. 1970-01-01 was a Thursday. */
fun weekdayOf(day: Int): Int = (day + 3).mod(7)

const val SECONDS_PER_DAY = 86_400L

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Metres between two places as the crow flies. */
fun metersBetween(latitude: Double, longitude: Double, toLatitude: Double, toLongitude: Double): Double {
    val lat1 = latitude * PI / 180
    val lat2 = toLatitude * PI / 180
    val h = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin((toLongitude - longitude) * PI / 360).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
