package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.finance.data.suspendRunCatching
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.data.WEATHER_CLIENT
import com.meticulouscreations.homesafe.weather.data.WeatherServiceException
import com.meticulouscreations.homesafe.weather.data.coarse
import com.meticulouscreations.homesafe.weather.data.weatherJson
import com.meticulouscreations.homesafe.weather.ski.domain.ChainControl
import com.meticulouscreations.homesafe.weather.ski.domain.ChainLevel
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDrive
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDriveSource
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecastDay
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecastHour
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPoint
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortFacts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPage
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_chains_unavailable
import homesafe.shared.generated.resources.ski_error_drive
import homesafe.shared.generated.resources.ski_error_forecast
import homesafe.shared.generated.resources.ski_error_report
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Instant

/**
 * OnTheSnow's pages: what the resorts report, their listed prices, and each one's details. See
 * [NextFlightRows] for how these are read, and docs/weather.md for why a web page: nobody offers
 * resort reports as a service without a commercial contract.
 */
@Inject
class OnTheSnowApi(@Named(WEATHER_CLIENT) private val httpClient: HttpClient) {
    /** Every resort in a region or on a pass's list, which are pages of the same shape. */
    suspend fun reports(regionSlug: String): Result<List<SkiReport>> = suspendRunCatching {
        val html = page("$BASE/$regionSlug/skireport")
        withContext(Dispatchers.Default) { OnTheSnowPages.reports(html) }.ifEmpty { throw failed(0) }
    }

    suspend fun prices(regionSlug: String): Result<Map<String, SkiTicketPrice>> = suspendRunCatching {
        val html = page("$BASE/$regionSlug/lift-tickets")
        withContext(Dispatchers.Default) { OnTheSnowPages.prices(html) }
    }

    suspend fun resortPage(ref: SkiResortRef, nowEpochSeconds: Long): Result<SkiResortPage> = suspendRunCatching {
        val html = page("$BASE/${ref.regionSlug}/${ref.slug}/skireport")
        withContext(Dispatchers.Default) { OnTheSnowPages.resortPage(html, ref.slug, ref.regionSlug, nowEpochSeconds) } ?: throw failed(0)
    }

    private suspend fun page(url: String): String {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) throw failed(response.status.value)
        return response.bodyAsText()
    }

    private fun failed(status: Int) = WeatherServiceException(UiText.of(Res.string.ski_error_report), status)

    companion object {
        const val BASE = "https://www.onthesnow.com"
    }
}

/**
 * The mountain's forecast from Open-Meteo, read at the summit's and the base's own heights, and
 * from several weather models at once so the Ski tab can show how far they agree: a storm a week
 * off is a range, and one figure would claim more than anybody knows.
 */
@Inject
class SkiForecastApi(@Named(WEATHER_CLIENT) private val httpClient: HttpClient) {
    suspend fun forecast(facts: SkiResortFacts, nowEpochSeconds: Long): Result<SkiForecast> = suspendRunCatching {
        coroutineScope {
            val summit = async { summit(facts) }
            val base = async { facts.baseElevationM?.let { elevation -> suspendRunCatching { base(facts, elevation) }.getOrNull() } }
            val top = summit.await()
            val bottom = base.await()
            withContext(Dispatchers.Default) { top.toForecast(bottom, nowEpochSeconds) } ?: throw failed(0)
        }
    }

    private suspend fun summit(facts: SkiResortFacts): MountainDto {
        val response = httpClient.get("$BASE/v1/forecast") {
            parameter("latitude", coarse(facts.latitude))
            parameter("longitude", coarse(facts.longitude))
            facts.summitElevationM?.let { parameter("elevation", it.roundToInt()) }
            parameter("daily", SUMMIT_DAILY)
            parameter("hourly", SUMMIT_HOURLY)
            parameter("models", MODELS.joinToString(","))
            parameter("past_days", PAST_DAYS)
            parameter("forecast_days", FORECAST_DAYS)
            parameter("timezone", "auto")
            parameter("timeformat", "unixtime")
        }
        if (!response.status.isSuccess()) throw failed(response.status.value)
        return response.decode()
    }

    private suspend fun base(facts: SkiResortFacts, elevation: Double): MountainDto {
        val response = httpClient.get("$BASE/v1/forecast") {
            parameter("latitude", coarse(facts.latitude))
            parameter("longitude", coarse(facts.longitude))
            parameter("elevation", elevation.roundToInt())
            parameter("daily", BASE_DAILY)
            parameter("past_days", PAST_DAYS)
            parameter("forecast_days", FORECAST_DAYS)
            parameter("timezone", "auto")
            parameter("timeformat", "unixtime")
        }
        if (!response.status.isSuccess()) throw failed(response.status.value)
        return response.decode()
    }

    private fun failed(status: Int) = WeatherServiceException(UiText.of(Res.string.ski_error_forecast), status)

    companion object {
        private const val BASE = "https://api.open-meteo.com"

        /** The blend Open-Meteo thinks best for the place, then the European and American globals, which are the disagreement worth seeing. */
        internal const val BEST_MODEL = "best_match"
        private val MODELS = listOf(BEST_MODEL, "ecmwf_ifs025", "gfs_seamless")
        const val PAST_DAYS = 3
        const val FORECAST_DAYS = 10
        private const val SUMMIT_DAILY = "weather_code,temperature_2m_max,temperature_2m_min,snowfall_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset"
        private const val SUMMIT_HOURLY = "temperature_2m,snowfall,weather_code,wind_speed_10m,wind_gusts_10m,visibility,freezing_level_height,is_day"
        private const val BASE_DAILY = "temperature_2m_max,temperature_2m_min,rain_sum,showers_sum,snowfall_sum"
    }
}

/**
 * The answer to asking for several models at once: each field is named for its model
 * (`snowfall_sum_best_match`), or plainly when only one was asked for. So it's read by name.
 */
@Serializable
internal class MountainDto(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val timezone: String = "",
    val daily: JsonObject? = null,
    val hourly: JsonObject? = null,
) {
    fun toForecast(base: MountainDto?, fetchedAt: Long): SkiForecast? {
        val days = daily ?: return null
        val times = days.longs("time")
        if (times.isEmpty()) return null
        val baseDays = base?.daily
        val baseTimes = baseDays?.longs("time").orEmpty()
        return SkiForecast(
            fetchedAtEpochSeconds = fetchedAt,
            utcOffsetSeconds = utcOffsetSeconds,
            timeZoneId = timezone,
            days = times.indices.mapNotNull { i ->
                val high = days.best("temperature_2m_max", i) ?: return@mapNotNull null
                val low = days.best("temperature_2m_min", i) ?: return@mapNotNull null
                val spread = days.allModels("snowfall_sum", i)
                val snow = days.best("snowfall_sum", i) ?: spread.firstOrNull() ?: 0.0
                val b = baseTimes.indexOf(times[i]).takeIf { it >= 0 }
                SkiForecastDay(
                    epochSeconds = times[i],
                    weatherCode = days.best("weather_code", i)?.roundToInt() ?: 3,
                    snowCm = snow,
                    snowLowCm = spread.minOrNull()?.let { min(it, snow) } ?: snow,
                    snowHighCm = spread.maxOrNull()?.let { max(it, snow) } ?: snow,
                    summitHighC = high,
                    summitLowC = low,
                    baseHighC = b?.let { baseDays?.plain("temperature_2m_max", it) },
                    baseLowC = b?.let { baseDays?.plain("temperature_2m_min", it) },
                    baseRainMm = b?.let { baseDays?.plain("rain_sum", it)?.plus(baseDays.plain("showers_sum", it) ?: 0.0) },
                    baseSnowCm = b?.let { baseDays?.plain("snowfall_sum", it) },
                    precipitationProbability = days.best("precipitation_probability_max", i)?.roundToInt(),
                    windMaxKmh = days.best("wind_speed_10m_max", i) ?: 0.0,
                    gustMaxKmh = days.best("wind_gusts_10m_max", i) ?: 0.0,
                    sunriseEpochSeconds = days.best("sunrise", i)?.toLong()?.takeIf { it > 0 },
                    sunsetEpochSeconds = days.best("sunset", i)?.toLong()?.takeIf { it > 0 },
                )
            },
            hours = hourly?.let { h ->
                h.longs("time").indices.mapNotNull { i ->
                    val temperature = h.best("temperature_2m", i) ?: return@mapNotNull null
                    SkiForecastHour(
                        epochSeconds = h.longs("time")[i],
                        temperatureC = temperature,
                        // As everywhere with this service: an amount is stamped at the end of the hour it fell in.
                        snowCm = h.best("snowfall", i + 1) ?: 0.0,
                        weatherCode = h.best("weather_code", i)?.roundToInt() ?: 3,
                        windKmh = h.best("wind_speed_10m", i) ?: 0.0,
                        gustKmh = h.best("wind_gusts_10m", i) ?: 0.0,
                        visibilityM = h.best("visibility", i),
                        freezingLevelM = h.best("freezing_level_height", i),
                        isDay = (h.best("is_day", i) ?: 1.0) != 0.0,
                    )
                }
            }.orEmpty(),
        )
    }
}

private fun JsonObject.longs(key: String): List<Long> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }

private fun JsonObject.plain(key: String, index: Int): Double? = ((this[key] as? JsonArray)?.getOrNull(index) as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.best(key: String, index: Int): Double? = plain("${key}_${SkiForecastApi.BEST_MODEL}", index) ?: plain(key, index)

/** What each of the models says, the blend among them. */
private fun JsonObject.allModels(key: String, index: Int): List<Double> =
    keys.filter { it.startsWith("${key}_") }.mapNotNull { plain(it, index) }

private suspend inline fun <reified T> HttpResponse.decode(): T {
    val text = bodyAsText()
    return withContext(Dispatchers.Default) { weatherJson.decodeFromString(text) }
}

/**
 * How long the road is. With a Google Maps key in the household's vault that's Google's routes,
 * which know the traffic, at any hour asked about. Without one, or with a key that isn't allowed
 * routes, it's OSRM's public server over OpenStreetMap: free and keyless, but it knows nothing of
 * traffic, and asks only that it isn't leaned on. Where the drive starts is told to a hundredth
 * of a degree, about a kilometre, as with the weather: never the house itself.
 */
@Inject
class SkiRoutesApi(@Named(WEATHER_CLIENT) private val httpClient: HttpClient) {
    /**
     * Google's answer. A failure is [RoutesRefused] if the key isn't allowed routes; any other is this
     * drive's or this moment's, and says nothing about the next one.
     */
    suspend fun withTraffic(from: SkiPoint, to: SkiPoint, departEpochSeconds: Long?, key: String): Result<SkiDrive> = suspendRunCatching {
        val response = httpClient.post(GOOGLE) {
            contentType(ContentType.Application.Json)
            header("X-Goog-Api-Key", key)
            header("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline")
            setBody(
                buildJsonObject {
                    putJsonObject("origin") { put("location", latLng(from)) }
                    putJsonObject("destination") { put("location", latLng(to)) }
                    put("travelMode", "DRIVE")
                    put("routingPreference", "TRAFFIC_AWARE")
                    if (departEpochSeconds != null) put("departureTime", Instant.fromEpochSeconds(departEpochSeconds).toString())
                }.toString(),
            )
        }
        if (!response.status.isSuccess()) {
            val status = response.status
            // A key Google doesn't recognize at all comes back as a bad request.
            if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden || (status == HttpStatusCode.BadRequest && "API_KEY_INVALID" in response.bodyAsText())) throw RoutesRefused
            throw noDrive(status.value)
        }
        val route = response.decode<GoogleRoutesDto>().routes.firstOrNull() ?: throw noDrive(response.status.value)
        val seconds = route.duration?.removeSuffix("s")?.toLongOrNull() ?: throw noDrive(response.status.value)
        SkiDrive(seconds, route.distanceMeters ?: 0.0, SkiDriveSource.TRAFFIC, route.polyline?.encodedPolyline.orEmpty(), departEpochSeconds)
    }

    private fun noDrive(status: Int) = WeatherServiceException(UiText.of(Res.string.ski_error_drive), status)

    private fun latLng(point: SkiPoint) = buildJsonObject {
        putJsonObject("latLng") {
            put("latitude", coarse(point.latitude).toDouble())
            put("longitude", coarse(point.longitude).toDouble())
        }
    }

    suspend fun freeFlow(from: SkiPoint, to: SkiPoint): Result<SkiDrive> = suspendRunCatching {
        val response = httpClient.get("$OSRM/${coarse(from.longitude)},${coarse(from.latitude)};${coarse(to.longitude)},${coarse(to.latitude)}") {
            parameter("overview", "full")
            parameter("geometries", "polyline")
        }
        if (!response.status.isSuccess()) throw noDrive(response.status.value)
        val route = response.decode<OsrmDto>().routes.firstOrNull() ?: throw noDrive(response.status.value)
        SkiDrive(route.duration.roundToInt().toLong(), route.distance, SkiDriveSource.FREE_FLOW, route.geometry)
    }

    private companion object {
        const val GOOGLE = "https://routes.googleapis.com/directions/v2:computeRoutes"
        const val OSRM = "https://router.project-osrm.org/route/v1/driving"
    }
}

/** The key isn't allowed routes (it may only be allowed map tiles): no use asking with it again. */
internal object RoutesRefused : RuntimeException("Routes refused")

@Serializable
internal class GoogleRoutesDto(val routes: List<GoogleRouteDto> = emptyList())

@Serializable
internal class GoogleRouteDto(val duration: String? = null, val distanceMeters: Double? = null, val polyline: GooglePolylineDto? = null)

@Serializable
internal class GooglePolylineDto(val encodedPolyline: String? = null)

@Serializable
internal class OsrmDto(val routes: List<OsrmRouteDto> = emptyList())

@Serializable
internal class OsrmRouteDto(val duration: Double = 0.0, val distance: Double = 0.0, val geometry: String = "")

/**
 * Chain controls on California's highways, from Caltrans's open data, one file per district. They
 * list every checkpoint in the district whether anything is in force or not, so only the
 * districts a road runs through are fetched.
 */
@Inject
class CaltransChainApi(@Named(WEATHER_CLIENT) private val httpClient: HttpClient) {
    /** The districts a road inside [box] could run through. */
    fun districtsAround(box: GeoBox): List<Int> = DISTRICTS.filter { it.box.overlaps(box) }.map { it.number }

    /** What's in force in one district. It throws if the file can't be read: that isn't the same as nothing in force. */
    suspend fun district(number: Int): List<ChainControl> {
        val padded = number.toString().padStart(2, '0')
        val response = httpClient.get("https://cwwp2.dot.ca.gov/data/d$number/cc/ccStatusD$padded.json")
        if (!response.status.isSuccess()) throw WeatherServiceException(UiText.of(Res.string.ski_chains_unavailable), response.status.value)
        return response.decode<ChainFileDto>().data.mapNotNull { it.cc?.toControl() }
    }

    private class District(val number: Int, val box: GeoBox)

    private companion object {
        /** Each district's extent, roughly and generously: enough to tell which files a road could be in. */
        val DISTRICTS = listOf(
            District(1, GeoBox(38.2, 42.1, -124.6, -122.3)),
            District(2, GeoBox(39.4, 42.1, -123.8, -119.9)),
            District(3, GeoBox(38.3, 40.2, -122.5, -119.8)),
            District(6, GeoBox(34.7, 37.8, -121.3, -117.9)),
            District(7, GeoBox(33.6, 35.0, -119.6, -117.5)),
            District(8, GeoBox(33.4, 35.9, -118.1, -114.1)),
            District(9, GeoBox(34.8, 38.8, -119.8, -117.0)),
            District(10, GeoBox(36.9, 39.0, -121.8, -119.1)),
            District(11, GeoBox(32.5, 33.6, -117.7, -114.4)),
        )
    }
}

@Serializable
internal class ChainFileDto(val data: List<ChainEntryDto> = emptyList())

@Serializable
internal class ChainEntryDto(val cc: ChainPointDto? = null)

@Serializable
internal class ChainPointDto(val location: ChainLocationDto? = null, val inService: String? = null, val statusData: ChainStatusDto? = null) {
    fun toControl(): ChainControl? {
        if (inService != "true") return null
        val level = when (statusData?.status?.trim()?.uppercase()) {
            "R-1", "R1" -> ChainLevel.R1
            "R-2", "R2" -> ChainLevel.R2
            "R-3", "R3" -> ChainLevel.R3
            else -> return null
        }
        val where = location ?: return null
        return ChainControl(
            level = level,
            route = where.route.orEmpty(),
            place = where.locationName?.takeIf { it.isNotBlank() } ?: where.nearbyPlace.orEmpty(),
            direction = where.direction.orEmpty(),
            latitude = where.latitude?.toDoubleOrNull() ?: return null,
            longitude = where.longitude?.toDoubleOrNull() ?: return null,
            description = statusData.statusDescription.orEmpty().trim(),
        )
    }
}

@Serializable
internal class ChainLocationDto(
    val locationName: String? = null,
    val nearbyPlace: String? = null,
    val route: String? = null,
    val direction: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
)

@Serializable
internal class ChainStatusDto(val status: String? = null, val statusDescription: String? = null)

/** A rectangle of the map. */
data class GeoBox(val south: Double, val north: Double, val west: Double, val east: Double) {
    fun overlaps(other: GeoBox): Boolean = south <= other.north && north >= other.south && west <= other.east && east >= other.west
}

/** The road's points from a polyline as Google and OSRM both encode them: five decimal places, each a difference from the last. */
internal fun decodePolyline(encoded: String): List<SkiPoint> {
    val points = ArrayList<SkiPoint>(encoded.length / 4)
    var index = 0
    var latitude = 0
    var longitude = 0
    fun next(): Int? {
        var result = 0
        var shift = 0
        while (true) {
            if (index >= encoded.length) return null
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return if (result and 1 != 0) (result shr 1).inv() else result shr 1
    }
    while (index < encoded.length) {
        latitude += next() ?: break
        longitude += next() ?: break
        points += SkiPoint(latitude / 1e5, longitude / 1e5)
    }
    return points
}

/** The rectangle round a road. */
internal fun boxAround(points: List<SkiPoint>): GeoBox? {
    if (points.isEmpty()) return null
    return GeoBox(points.minOf { it.latitude }, points.maxOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.longitude })
}

/** Whether a road comes within [meters] of a place, near enough to be on it: a checkpoint's mapped position is only roughly on its highway. */
internal fun passesNear(road: List<SkiPoint>, latitude: Double, longitude: Double, meters: Double = CHAIN_MATCH_METERS): Boolean {
    if (road.size < 2) return false
    val metersPerDegreeLatitude = 111_320.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(latitude * PI / 180)
    fun x(p: SkiPoint) = (p.longitude - longitude) * metersPerDegreeLongitude
    fun y(p: SkiPoint) = (p.latitude - latitude) * metersPerDegreeLatitude
    for (i in 0 until road.lastIndex) {
        val ax = x(road[i])
        val ay = y(road[i])
        val bx = x(road[i + 1])
        val by = y(road[i + 1])
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        if (sqrt(px * px + py * py) <= meters) return true
    }
    return false
}

private const val CHAIN_MATCH_METERS = 1_500.0
