package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow

/** Where a radar loop's pictures come from, which decides how far they can be zoomed and who is credited. */
enum class RadarSource {
    /** NOAA's MRMS mosaic of the US radars, and the HRRR model's forecast of it, served as tiles by the Iowa Environmental Mesonet. */
    US_MRMS,

    /** RainViewer's worldwide composite: the past two hours, coarser, and no forecast. */
    RAINVIEWER,
}

/**
 * One picture of a radar loop: what the radars saw at [epochSeconds], or with [forecast] what a
 * model expects them to see then. [tileUrl] is a web-mercator tile address with `{z}`, `{x}`
 * and `{y}` to fill in; tiles exist up to [maxZoom] and are stretched beyond it.
 */
@Immutable
data class RadarFrame(val epochSeconds: Long, val forecast: Boolean, val tileUrl: String, val maxZoom: Int) {
    fun url(z: Int, x: Int, y: Int): String = tileUrl.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}

/** A radar loop, oldest frame first; [latestObserved] is the index of the newest one that was actually seen. */
@Immutable
data class RadarTimeline(val source: RadarSource, val frames: List<RadarFrame>) {
    val latestObserved: Int get() = frames.indexOfLast { !it.forecast }.coerceAtLeast(0)
}

/**
 * Forecasts, air quality, alerts, place names and radar from public services that need no key
 * (see docs/weather.md for which and why). Everything a screen asks for more than once is
 * cached; a forecast is also kept on the device so the app opens on the last one it had.
 */
interface WeatherRepository {
    /** The forecast for [place], from the network unless one newer than [maxAgeSeconds] is in hand. */
    suspend fun report(place: Place, maxAgeSeconds: Long = 600): Result<WeatherReport>

    /** The last forecast fetched for [place], however old, or null: what to show while a new one loads. */
    suspend fun lastReport(place: Place): WeatherReport?

    /** Cities whose name starts with [query]. */
    suspend fun searchPlaces(query: String): Result<List<Place>>

    /** What the place at these coordinates is called ("Portland" and "OR"), or null where nobody could say. */
    suspend fun nameOf(latitude: Double, longitude: Double): Pair<String, String>?

    /** The radar loop covering the place at these coordinates. */
    suspend fun radar(latitude: Double, longitude: Double): Result<RadarTimeline>
}

/** The cities the household follows beside wherever the phone is, in the order they were arranged. */
interface WeatherPlacesRepository {
    fun observe(): Flow<List<Place>>

    suspend fun add(place: Place)

    suspend fun remove(id: String)

    /** Moves the place [id] to [toIndex] in the list. */
    suspend fun move(id: String, toIndex: Int)
}

interface WeatherPreferencesRepository {
    fun observe(): Flow<WeatherPreferences>

    suspend fun update(change: (WeatherPreferences) -> WeatherPreferences)
}

/** Which weather notifications have gone out, so none is sent twice. */
interface WeatherNoticeLedger {
    /** Every key sent and when, with anything older than a few days forgotten. */
    suspend fun sent(nowEpochSeconds: Long): Map<String, Long>

    suspend fun record(key: String, atEpochSeconds: Long)
}
