package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.finance.data.suspendRunCatching
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.domain.AirQuality
import com.meticulouscreations.homesafe.weather.domain.AlertSeverity
import com.meticulouscreations.homesafe.weather.domain.CurrentConditions
import com.meticulouscreations.homesafe.weather.domain.DayForecast
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.RadarFrame
import com.meticulouscreations.homesafe.weather.domain.RadarSource
import com.meticulouscreations.homesafe.weather.domain.RadarTimeline
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_error_forecast_answered
import homesafe.shared.generated.resources.weather_error_no_forecast
import homesafe.shared.generated.resources.weather_error_radar
import homesafe.shared.generated.resources.weather_error_search_answered
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * The client for the weather services (Open-Meteo, the US National Weather Service, the radar
 * and map tile hosts). Its own instance, as the finance app's is: Frigate's session cookies
 * never ride along to a third party.
 */
@ContributesTo(AppScope::class)
interface WeatherDataProviders {
    @Named(WEATHER_CLIENT)
    @SingleIn(AppScope::class)
    @Provides
    fun provideWeatherHttpClient(): HttpClient = HttpClient {
        install(ContentNegotiation) { json(weatherJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            // The National Weather Service refuses requests that don't say who is asking, and
            // OpenStreetMap's tile servers block a library's default. (A browser sends its own.)
            header(HttpHeaders.UserAgent, WEATHER_USER_AGENT)
        }
    }
}

const val WEATHER_CLIENT = "weather"
internal const val WEATHER_USER_AGENT = "(HomeSafe, https://github.com/aglose/HomeSafe)"

internal val weatherJson = Json {
    ignoreUnknownKeys = true
    // A value absent from the answer, or sent as null, is read as the field's default.
    explicitNulls = false
    isLenient = true
}

/**
 * Reads the body as JSON whatever the server called it: these are public files and feeds, and
 * some are served as plain text.
 */
private suspend inline fun <reified T> HttpResponse.decode(): T = weatherJson.decodeFromString(bodyAsText())

/** A weather service answered, but not with a forecast. [status] is its HTTP status, for callers that care which. */
class WeatherServiceException(text: UiText, val status: Int) : LocalizedException(text, technical = "HTTP $status")

/**
 * Open-Meteo: forecasts from the national weather models, air quality from Copernicus, and a
 * city search over GeoNames, all without a key (https://open-meteo.com, CC BY 4.0). Asked for in
 * metric always; the screen converts.
 */
@Inject
class OpenMeteoApi(@Named(WEATHER_CLIENT) private val httpClient: HttpClient) {

    /**
     * Now, the next three hours by quarter hour, and every hour and day from yesterday to ten
     * days out, at [nowEpochSeconds] (which only stamps when it was fetched).
     */
    suspend fun forecast(latitude: Double, longitude: Double, nowEpochSeconds: Long): Result<WeatherReport> = suspendRunCatching {
        val response = httpClient.get("$FORECAST_BASE/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("current", CURRENT_VARIABLES)
            parameter("minutely_15", "precipitation,snowfall")
            parameter("past_minutely_15", 1)
            parameter("forecast_minutely_15", 13)
            parameter("hourly", HOURLY_VARIABLES)
            parameter("daily", DAILY_VARIABLES)
            parameter("past_days", 1)
            parameter("forecast_days", FORECAST_DAYS)
            parameter("timezone", "auto")
            parameter("timeformat", "unixtime")
        }
        if (!response.status.isSuccess()) throw forecastFailed(response.status)
        response.decode<ForecastDto>().toReport(nowEpochSeconds)
            ?: throw WeatherServiceException(UiText.of(Res.string.weather_error_no_forecast), response.status.value)
    }

    /** The US air quality index now, or null where the model has none. */
    suspend fun airQuality(latitude: Double, longitude: Double): Result<AirQuality?> = suspendRunCatching {
        val response = httpClient.get("$AIR_BASE/v1/air-quality") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("current", "us_aqi,pm2_5,pm10,ozone,nitrogen_dioxide")
            parameter("timeformat", "unixtime")
        }
        if (!response.status.isSuccess()) throw forecastFailed(response.status)
        response.decode<AirDto>().current?.let { c ->
            c.usAqi?.let { AirQuality(usAqi = it.roundToInt(), pm25 = c.pm25, pm10 = c.pm10, ozone = c.ozone, nitrogenDioxide = c.nitrogenDioxide) }
        }
    }

    /** Cities whose name starts with [query], most populous first; empty for fewer than two letters. */
    suspend fun search(query: String, language: String = "en"): Result<List<Place>> = suspendRunCatching {
        val name = query.trim()
        if (name.length < 2) return@suspendRunCatching emptyList()
        val response = httpClient.get("$GEOCODING_BASE/v1/search") {
            parameter("name", name)
            parameter("count", 12)
            parameter("language", language)
            parameter("format", "json")
        }
        if (!response.status.isSuccess()) {
            throw WeatherServiceException(UiText.of(Res.string.weather_error_search_answered, response.status.value), response.status.value)
        }
        // No matches comes back as no `results` at all.
        response.decode<GeocodingDto>().results.orEmpty().map { r ->
            Place(
                id = Place.idFor(r.latitude, r.longitude),
                name = r.name,
                region = listOfNotNull(r.admin1?.takeIf { it != r.name }, r.country).joinToString(", "),
                latitude = r.latitude,
                longitude = r.longitude,
            )
        }.distinctBy { it.id }
    }

    private fun forecastFailed(status: HttpStatusCode) =
        WeatherServiceException(UiText.of(Res.string.weather_error_forecast_answered, status.value), status.value)

    companion object {
        private const val FORECAST_BASE = "https://api.open-meteo.com"
        private const val AIR_BASE = "https://air-quality-api.open-meteo.com"
        private const val GEOCODING_BASE = "https://geocoding-api.open-meteo.com"

        /** Today and nine more. Models reach sixteen, but past ten the skill is gone and so is the point. */
        const val FORECAST_DAYS = 10

        private const val CURRENT_VARIABLES =
            "temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,is_day,weather_code,precipitation," +
                "cloud_cover,pressure_msl,visibility,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index"
        private const val HOURLY_VARIABLES =
            "temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,precipitation_probability,precipitation," +
                "snowfall,weather_code,cloud_cover,pressure_msl,visibility,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index,is_day"
        private const val DAILY_VARIABLES =
            "weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,sunrise,sunset," +
                "moonrise,moonset,uv_index_max,precipitation_sum,rain_sum,showers_sum,snowfall_sum,precipitation_hours," +
                "precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant"
    }
}

/**
 * The US National Weather Service (api.weather.gov, public domain): the alerts in force at a
 * point, and what the point is called. It covers the United States only, and answers 404 or 400
 * for anywhere else, which is read here as "nothing to say", not as a failure.
 */
@Inject
class NwsApi(@Named(WEATHER_CLIENT) private val httpClient: HttpClient) {

    suspend fun alerts(latitude: Double, longitude: Double): Result<List<WeatherAlert>> = suspendRunCatching {
        val response = httpClient.get("$BASE/alerts/active") {
            parameter("point", point(latitude, longitude))
            parameter("status", "actual")
            parameter("message_type", "alert,update")
            header(HttpHeaders.Accept, "application/geo+json")
        }
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.BadRequest) return@suspendRunCatching emptyList()
        if (!response.status.isSuccess()) throw WeatherServiceException(UiText.of(Res.string.weather_error_forecast_answered, response.status.value), response.status.value)
        response.decode<AlertsDto>().features.mapNotNull { it.properties?.toAlert() }.distinctBy { it.id }.sortedBy { it.severity.ordinal }
    }

    /** The nearest town and its state ("Portland" to "OR"), or null outside the US or when the service won't say. */
    suspend fun nameOf(latitude: Double, longitude: Double): Pair<String, String>? = suspendRunCatching {
        val response = httpClient.get("$BASE/points/${point(latitude, longitude)}") { header(HttpHeaders.Accept, "application/geo+json") }
        if (!response.status.isSuccess()) return@suspendRunCatching null
        response.decode<PointDto>().properties?.relativeLocation?.properties?.let { p ->
            p.city?.takeIf { it.isNotBlank() }?.let { it to p.state.orEmpty() }
        }
    }.getOrNull()

    /** The service wants no more than four decimal places, and redirects anything finer. */
    private fun point(latitude: Double, longitude: Double) = "${WeatherFormat.decimal(latitude, 4)},${WeatherFormat.decimal(longitude, 4)}"

    companion object {
        private const val BASE = "https://api.weather.gov"

        /** Whether the service is worth asking: a generous box round the fifty states and Puerto Rico. */
        fun covers(latitude: Double, longitude: Double): Boolean = latitude in 17.0..72.0 && longitude in -180.0..-64.0
    }
}

/**
 * Where a radar loop's frames are. Over the contiguous US that is the Iowa Environmental
 * Mesonet's tile service (mesonet.agron.iastate.edu, public domain): NOAA's MRMS mosaic every
 * ten minutes for the past two hours, then the HRRR model's forecast of the same picture every
 * fifteen minutes for the next two. Anywhere else it is RainViewer's composite of the world's
 * radars, which has the past two hours only.
 */
@Inject
class RadarApi(@Named(WEATHER_CLIENT) private val httpClient: HttpClient) {

    suspend fun timeline(latitude: Double, longitude: Double): Result<RadarTimeline> = suspendRunCatching {
        if (inContiguousUs(latitude, longitude)) {
            // If the US service is down the world's composite still has the US in it.
            suspendRunCatching { usTimeline() }.getOrNull() ?: worldTimeline()
        } else {
            worldTimeline()
        }
    }

    private suspend fun usTimeline(): RadarTimeline {
        val latest = httpClient.get("$IEM/data/gis/images/4326/mrms/lcref.json").also { if (!it.status.isSuccess()) throw radarFailed(it.status) }
            .decode<MrmsIndexDto>().meta.endValid.let(::parseInstant)
            ?: throw radarFailed(HttpStatusCode.NoContent)
        // MRMS frames are on even minutes; the loop steps back from the newest in tens.
        val newest = latest - latest % 120
        val observed = (PAST_FRAMES - 1 downTo 0).map { back ->
            val at = newest - back * PAST_STEP_SECONDS
            RadarFrame(at, forecast = false, tileUrl = "$IEM/cache/tile.py/1.0.0/mrms::lcref-${stamp(at)}/{z}/{x}/{y}.png", maxZoom = US_MAX_ZOOM)
        }
        // The forecast is a bonus: without it the loop simply ends at now.
        val forecast = suspendRunCatching {
            val init = httpClient.get("$IEM/data/gis/images/4326/hrrr/refd_0000.json").decode<HrrrIndexDto>().modelInitUtc.let(::parseInstant) ?: return@suspendRunCatching emptyList()
            forecastFrames(init, newest)
        }.getOrDefault(emptyList())
        return RadarTimeline(RadarSource.US_MRMS, observed + forecast)
    }

    private suspend fun worldTimeline(): RadarTimeline {
        val response = httpClient.get("https://api.rainviewer.com/public/weather-maps.json")
        if (!response.status.isSuccess()) throw radarFailed(response.status)
        val index = response.decode<RainViewerDto>()
        // Unsmoothed, and snow not coloured apart: the tile's colours are then exactly its table's,
        // and can be read back into strengths (see RadarDecoder).
        val frames = index.radar.past.map { frame ->
            RadarFrame(frame.time, forecast = false, tileUrl = "${index.host}${frame.path}/256/{z}/{x}/{y}/2/0_0.png", maxZoom = WORLD_MAX_ZOOM)
        }
        if (frames.isEmpty()) throw radarFailed(HttpStatusCode.NoContent)
        return RadarTimeline(RadarSource.RAINVIEWER, frames)
    }

    private fun radarFailed(status: HttpStatusCode) = WeatherServiceException(UiText.of(Res.string.weather_error_radar), status.value)

    companion object {
        private const val IEM = "https://mesonet.agron.iastate.edu"
        const val PAST_FRAMES = 13
        const val PAST_STEP_SECONDS = 600L
        const val FUTURE_FRAMES = 8
        const val FUTURE_STEP_SECONDS = 900L

        /** MRMS is a one-kilometre grid: zoom 8 already shows every cell of it. */
        const val US_MAX_ZOOM = 8
        const val WORLD_MAX_ZOOM = 7

        fun inContiguousUs(latitude: Double, longitude: Double): Boolean = latitude in 24.0..50.0 && longitude in -126.0..-66.0

        /**
         * The model's frames that fall after [newestObserved]: one every quarter hour from its
         * run at [initEpochSeconds], which is a couple of hours old by the time it's published.
         */
        internal fun forecastFrames(initEpochSeconds: Long, newestObserved: Long): List<RadarFrame> {
            // The address of a forecast tile names how far into the run it is, not which run: the
            // same address is a different picture an hour later. The run is added to it (the
            // server ignores the query) so that nothing cached by address outlives its run.
            val firstMinute = (((newestObserved - initEpochSeconds) / FUTURE_STEP_SECONDS) + 1).coerceAtLeast(0) * 15
            return (0 until FUTURE_FRAMES).mapNotNull { i ->
                val minute = firstMinute + i * 15
                // Past eighteen hours the model steps hourly; a run that old is no use here anyway.
                if (minute > 1080) return@mapNotNull null
                RadarFrame(
                    epochSeconds = initEpochSeconds + minute * 60,
                    forecast = true,
                    tileUrl = "$IEM/cache/tile.py/1.0.0/hrrr::REFD-F${minute.toString().padStart(4, '0')}-0/{z}/{x}/{y}.png?run=$initEpochSeconds",
                    maxZoom = US_MAX_ZOOM,
                )
            }
        }

        /** `yyyyMMddHHmm` in UTC, as the tile service names a frame. */
        internal fun stamp(epochSeconds: Long): String {
            val t = Instant.fromEpochSeconds(epochSeconds).toString()
            // "2026-10-08T04:38:00Z"
            return t.substring(0, 4) + t.substring(5, 7) + t.substring(8, 10) + t.substring(11, 13) + t.substring(14, 16)
        }

        internal fun parseInstant(text: String?): Long? = text?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() }
    }
}

// ---- Open-Meteo's shapes ---------------------------------------------------------------------

@Serializable
internal class ForecastDto(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val timezone: String = "",
    val current: CurrentDto? = null,
    @SerialName("minutely_15") val minutely: MinutelyDto? = null,
    val hourly: HourlyDto? = null,
    val daily: DailyDto? = null,
) {
    fun toReport(fetchedAt: Long): WeatherReport? {
        val now = current ?: return null
        val temperature = now.temperature ?: return null
        val hours = hourly?.toHours().orEmpty()
        return WeatherReport(
            fetchedAtEpochSeconds = fetchedAt,
            utcOffsetSeconds = utcOffsetSeconds,
            timeZoneId = timezone,
            current = CurrentConditions(
                epochSeconds = now.time,
                temperatureC = temperature,
                feelsLikeC = now.apparent ?: temperature,
                humidityPercent = now.humidity?.roundToInt() ?: 0,
                dewPointC = now.dewPoint,
                precipitationMm = now.precipitation ?: 0.0,
                weatherCode = now.weatherCode ?: 3,
                cloudCoverPercent = now.cloudCover?.roundToInt() ?: 0,
                pressureHpa = now.pressure,
                windKmh = now.wind ?: 0.0,
                windDirectionDeg = now.windDirection?.roundToInt() ?: 0,
                gustKmh = now.gust ?: 0.0,
                isDay = now.isDay != 0,
                visibilityM = now.visibility,
                uvIndex = now.uvIndex,
            ),
            minutely = minutely?.toSlices().orEmpty(),
            hourly = hours,
            daily = daily?.toDays().orEmpty(),
        )
    }
}

@Serializable
internal class CurrentDto(
    val time: Long = 0,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("apparent_temperature") val apparent: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Double? = null,
    @SerialName("dew_point_2m") val dewPoint: Double? = null,
    @SerialName("is_day") val isDay: Int = 1,
    @SerialName("weather_code") val weatherCode: Int? = null,
    val precipitation: Double? = null,
    @SerialName("cloud_cover") val cloudCover: Double? = null,
    @SerialName("pressure_msl") val pressure: Double? = null,
    val visibility: Double? = null,
    @SerialName("wind_speed_10m") val wind: Double? = null,
    @SerialName("wind_direction_10m") val windDirection: Double? = null,
    @SerialName("wind_gusts_10m") val gust: Double? = null,
    @SerialName("uv_index") val uvIndex: Double? = null,
)

@Serializable
internal class MinutelyDto(
    val time: List<Long> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val snowfall: List<Double?> = emptyList(),
) {
    /**
     * The service stamps an amount at the end of the quarter-hour it fell in; a slice here is
     * the quarter-hour that starts at its time, so each takes the amount stamped one step on.
     */
    fun toSlices(): List<PrecipSlice> = time.mapIndexed { i, at ->
        PrecipSlice(at, precipitation.getOrNull(i + 1) ?: 0.0, snowfall.getOrNull(i + 1) ?: 0.0)
    }
}

@Serializable
internal class HourlyDto(
    val time: List<Long> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("apparent_temperature") val apparent: List<Double?> = emptyList(),
    @SerialName("relative_humidity_2m") val humidity: List<Double?> = emptyList(),
    @SerialName("dew_point_2m") val dewPoint: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val probability: List<Double?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val snowfall: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Double?> = emptyList(),
    @SerialName("pressure_msl") val pressure: List<Double?> = emptyList(),
    val visibility: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m") val wind: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m") val windDirection: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m") val gust: List<Double?> = emptyList(),
    @SerialName("uv_index") val uvIndex: List<Double?> = emptyList(),
    @SerialName("is_day") val isDay: List<Int?> = emptyList(),
) {
    /**
     * An hour with no temperature is past the model's reach, and left out with every hour after it.
     *
     * Temperature, wind and the rest are the state of things at the hour's start. What falls is
     * different: the service stamps an amount (and the chance of one) at the end of the hour it
     * covers, so an hour here takes those from the entry one step on, and "the hour starting at
     * three" means the same thing for every number in it.
     */
    fun toHours(): List<HourForecast> = time.indices.asSequence().map { i ->
        val t = temperature.getOrNull(i) ?: return@map null
        HourForecast(
            epochSeconds = time[i],
            temperatureC = t,
            feelsLikeC = apparent.getOrNull(i) ?: t,
            humidityPercent = humidity.getOrNull(i)?.roundToInt() ?: 0,
            dewPointC = dewPoint.getOrNull(i),
            precipitationProbability = probability.getOrNull(i + 1)?.roundToInt(),
            precipitationMm = precipitation.getOrNull(i + 1) ?: 0.0,
            snowfallCm = snowfall.getOrNull(i + 1) ?: 0.0,
            weatherCode = weatherCode.getOrNull(i) ?: 3,
            cloudCoverPercent = cloudCover.getOrNull(i)?.roundToInt() ?: 0,
            visibilityM = visibility.getOrNull(i),
            windKmh = wind.getOrNull(i) ?: 0.0,
            windDirectionDeg = windDirection.getOrNull(i)?.roundToInt() ?: 0,
            gustKmh = gust.getOrNull(i) ?: 0.0,
            uvIndex = uvIndex.getOrNull(i),
            isDay = (isDay.getOrNull(i) ?: 1) != 0,
            pressureHpa = pressure.getOrNull(i),
        )
    }.takeWhile { it != null }.filterNotNull().toList()
}

@Serializable
internal class DailyDto(
    val time: List<Long> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val high: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val low: List<Double?> = emptyList(),
    @SerialName("apparent_temperature_max") val feelsHigh: List<Double?> = emptyList(),
    @SerialName("apparent_temperature_min") val feelsLow: List<Double?> = emptyList(),
    val sunrise: List<Long?> = emptyList(),
    val sunset: List<Long?> = emptyList(),
    val moonrise: List<Long?> = emptyList(),
    val moonset: List<Long?> = emptyList(),
    @SerialName("uv_index_max") val uvMax: List<Double?> = emptyList(),
    @SerialName("precipitation_sum") val precipitation: List<Double?> = emptyList(),
    @SerialName("rain_sum") val rain: List<Double?> = emptyList(),
    @SerialName("showers_sum") val showers: List<Double?> = emptyList(),
    @SerialName("snowfall_sum") val snowfall: List<Double?> = emptyList(),
    @SerialName("precipitation_hours") val precipitationHours: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val probability: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m_max") val wind: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m_max") val gust: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m_dominant") val windDirection: List<Double?> = emptyList(),
) {
    fun toDays(): List<DayForecast> = time.indices.mapNotNull { i ->
        val max = high.getOrNull(i) ?: return@mapNotNull null
        val min = low.getOrNull(i) ?: return@mapNotNull null
        DayForecast(
            epochSeconds = time[i],
            weatherCode = weatherCode.getOrNull(i) ?: 3,
            highC = max,
            lowC = min,
            feelsHighC = feelsHigh.getOrNull(i),
            feelsLowC = feelsLow.getOrNull(i),
            // A day the sun or moon doesn't rise comes back as 0.
            sunriseEpochSeconds = sunrise.getOrNull(i)?.takeIf { it > 0 },
            sunsetEpochSeconds = sunset.getOrNull(i)?.takeIf { it > 0 },
            moonriseEpochSeconds = moonrise.getOrNull(i)?.takeIf { it > 0 },
            moonsetEpochSeconds = moonset.getOrNull(i)?.takeIf { it > 0 },
            uvIndexMax = uvMax.getOrNull(i),
            precipitationMm = precipitation.getOrNull(i) ?: 0.0,
            rainMm = (rain.getOrNull(i) ?: 0.0) + (showers.getOrNull(i) ?: 0.0),
            snowfallCm = snowfall.getOrNull(i) ?: 0.0,
            precipitationHours = precipitationHours.getOrNull(i) ?: 0.0,
            precipitationProbability = probability.getOrNull(i)?.roundToInt(),
            windMaxKmh = wind.getOrNull(i) ?: 0.0,
            gustMaxKmh = gust.getOrNull(i) ?: 0.0,
            windDirectionDeg = windDirection.getOrNull(i)?.roundToInt() ?: 0,
        )
    }
}

@Serializable
internal class AirDto(val current: AirCurrentDto? = null)

@Serializable
internal class AirCurrentDto(
    @SerialName("us_aqi") val usAqi: Double? = null,
    @SerialName("pm2_5") val pm25: Double? = null,
    val pm10: Double? = null,
    val ozone: Double? = null,
    @SerialName("nitrogen_dioxide") val nitrogenDioxide: Double? = null,
)

@Serializable
internal class GeocodingDto(val results: List<GeocodingResultDto>? = null)

@Serializable
internal class GeocodingResultDto(
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val country: String? = null,
    val admin1: String? = null,
)

// ---- The National Weather Service's shapes ----------------------------------------------------

@Serializable
internal class AlertsDto(val features: List<AlertFeatureDto> = emptyList())

@Serializable
internal class AlertFeatureDto(val properties: AlertPropertiesDto? = null)

@Serializable
internal class AlertPropertiesDto(
    val id: String? = null,
    val event: String? = null,
    val headline: String? = null,
    val description: String? = null,
    val instruction: String? = null,
    val severity: String? = null,
    val senderName: String? = null,
    val onset: String? = null,
    val effective: String? = null,
    val ends: String? = null,
    val expires: String? = null,
    val parameters: AlertParametersDto? = null,
) {
    fun toAlert(): WeatherAlert? {
        val messageId = id ?: return null
        val name = event?.takeIf { it.isNotBlank() } ?: return null
        return WeatherAlert(
            id = messageId,
            key = parameters?.vtec?.firstNotNullOfOrNull(::eventKey) ?: messageId,
            event = name,
            headline = headline.orEmpty(),
            description = description.orEmpty().trim(),
            instruction = instruction.orEmpty().trim(),
            severity = AlertSeverity.from(severity),
            sender = senderName.orEmpty(),
            onsetEpochSeconds = RadarApi.parseInstant(onset ?: effective),
            endsEpochSeconds = RadarApi.parseInstant(ends ?: expires),
        )
    }
}

@Serializable
internal class AlertParametersDto(@SerialName("VTEC") val vtec: List<String>? = null)

/**
 * What names a warning through its life, from its VTEC string
 * (`/O.CON.KJAX.CF.W.0001.000000T0000Z-261008T2100Z/`): the office, what it's for, how serious,
 * and its number that year. Every update to a warning is a new message with a new id, but these
 * four stay put, so they are what "this warning" means. Null for a string that isn't one.
 */
internal fun eventKey(vtec: String): String? {
    val parts = vtec.trim('/').split('.')
    return if (parts.size >= 6 && parts.subList(2, 6).all { it.isNotBlank() }) parts.subList(2, 6).joinToString(".") else null
}

@Serializable
internal class PointDto(val properties: PointPropertiesDto? = null)

@Serializable
internal class PointPropertiesDto(val relativeLocation: RelativeLocationDto? = null)

@Serializable
internal class RelativeLocationDto(val properties: RelativeLocationPropertiesDto? = null)

@Serializable
internal class RelativeLocationPropertiesDto(val city: String? = null, val state: String? = null)

// ---- The radar indexes' shapes ----------------------------------------------------------------

@Serializable
internal class MrmsIndexDto(val meta: MrmsMetaDto = MrmsMetaDto())

@Serializable
internal class MrmsMetaDto(@SerialName("end_valid") val endValid: String? = null)

@Serializable
internal class HrrrIndexDto(@SerialName("model_init_utc") val modelInitUtc: String? = null)

@Serializable
internal class RainViewerDto(val host: String = "https://tilecache.rainviewer.com", val radar: RainViewerRadarDto = RainViewerRadarDto())

@Serializable
internal class RainViewerRadarDto(val past: List<RainViewerFrameDto> = emptyList())

@Serializable
internal class RainViewerFrameDto(val time: Long = 0, val path: String = "")
