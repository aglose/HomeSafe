package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.weather.PlaceWeather
import com.meticulouscreations.homesafe.weather.WeatherUiState
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.data.RadarColorTable
import com.meticulouscreations.homesafe.weather.domain.AirQuality
import com.meticulouscreations.homesafe.weather.domain.AlertSeverity
import com.meticulouscreations.homesafe.weather.domain.CurrentConditions
import com.meticulouscreations.homesafe.weather.domain.DayForecast
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos

/**
 * A forecast made up for previews and tests: eleven days for Portland, Oregon, starting the day
 * before [TODAY], with a bit of everything in them — a shower this evening, a storm midweek, a
 * fog, a cold snap with snow at the end — so every part of the screens has something to draw.
 */
internal object WeatherFixtures {
    /** Local midnight on 9 October 2025 in Portland (UTC−7). */
    const val TODAY = 1_759_993_200L
    const val UTC_OFFSET = -25_200
    private const val HOUR = 3_600L
    private const val DAY = 86_400L

    /** Half past three that afternoon. */
    const val NOW = TODAY + 15 * HOUR + 1_800

    val portland = Place(Place.CURRENT_ID, "Portland", "OR", 45.5234, -122.6762)
    val seattle = Place(Place.idFor(47.6062, -122.3321), "Seattle", "Washington, United States", 47.6062, -122.3321)
    val tokyo = Place(Place.idFor(35.6762, 139.6503), "Tokyo", "Japan", 35.6762, 139.6503)

    /** What each day is like, yesterday first: the day's code, its warmest hour's temperature, and the hours it's wet. */
    private class DayPlan(val code: Int, val high: Double, val low: Double, val wet: IntRange? = null, val wetCode: Int = 61, val mmPerHour: Double = 0.8, val snow: Boolean = false)

    private val plans = listOf(
        DayPlan(1, 18.0, 9.0),
        DayPlan(2, 21.0, 11.0, wet = 18..21, wetCode = 61, mmPerHour = 1.1),
        DayPlan(80, 17.0, 10.0, wet = 6..10, wetCode = 80, mmPerHour = 1.6),
        DayPlan(0, 22.0, 9.0),
        DayPlan(2, 23.5, 11.0),
        DayPlan(65, 16.0, 11.0, wet = 3..19, wetCode = 65, mmPerHour = 4.2),
        DayPlan(95, 19.0, 12.0, wet = 14..18, wetCode = 95, mmPerHour = 6.5),
        DayPlan(1, 20.0, 8.0),
        DayPlan(45, 14.0, 6.0),
        DayPlan(3, 9.0, 2.0),
        DayPlan(73, 2.0, -3.0, wet = 5..16, wetCode = 73, mmPerHour = 1.4, snow = true),
        DayPlan(0, 4.0, -5.0),
    )

    private fun hour(day: Int, hour: Int): HourForecast {
        val plan = plans[day]
        val swing = (plan.high - plan.low) / 2
        val temperature = plan.low + swing + swing * cos((hour - 15) / 24.0 * 2 * PI)
        val wet = plan.wet?.contains(hour) == true
        val light = hour in 7..18
        return HourForecast(
            epochSeconds = TODAY + (day - 1) * DAY + hour * HOUR,
            temperatureC = temperature,
            feelsLikeC = temperature - if (wet) 1.5 else 0.4,
            humidityPercent = if (wet) 88 else 58,
            dewPointC = temperature - if (wet) 2.0 else 8.0,
            precipitationProbability = if (wet) {
                85
            } else if (plan.wet != null) {
                25
            } else {
                5
            },
            precipitationMm = if (wet) plan.mmPerHour else 0.0,
            snowfallCm = if (wet && plan.snow) plan.mmPerHour * 0.9 else 0.0,
            weatherCode = if (wet) {
                plan.wetCode
            } else if (plan.wet != null) {
                3
            } else {
                plan.code
            },
            cloudCoverPercent = when {
                wet -> 100
                plan.code == 0 -> 4
                plan.code == 1 -> 22
                plan.code == 2 -> 48
                else -> 92
            },
            visibilityM = if (plan.code == 45) 600.0 else 24_000.0,
            windKmh = 9.0 + (hour % 7) * 2.0 + if (plan.code == 95) 22.0 else 0.0,
            windDirectionDeg = 250,
            gustKmh = 18.0 + (hour % 5) * 4.0 + if (plan.code == 95) 46.0 else 0.0,
            uvIndex = if (light && !wet) (6.5 * cos((hour - 13) / 12.0 * PI)).coerceAtLeast(0.0) else 0.0,
            isDay = light,
            pressureHpa = 1016.0 - day * 0.8 - hour * 0.12,
        )
    }

    val hours: List<HourForecast> = plans.indices.flatMap { day -> (0..23).map { hour(day, it) } }

    val days: List<DayForecast> = plans.mapIndexed { i, plan ->
        val start = TODAY + (i - 1) * DAY
        val wetHours = plan.wet?.count() ?: 0
        DayForecast(
            epochSeconds = start,
            weatherCode = plan.wet?.let { plan.wetCode } ?: plan.code,
            highC = plan.high,
            lowC = plan.low,
            feelsHighC = plan.high - 0.5,
            feelsLowC = plan.low - 1.5,
            sunriseEpochSeconds = start + 7 * HOUR + 1_260,
            sunsetEpochSeconds = start + 18 * HOUR + 2_040,
            moonriseEpochSeconds = start + 19 * HOUR,
            moonsetEpochSeconds = start + 9 * HOUR,
            uvIndexMax = if (plan.wet == null) 6.0 else 3.0,
            precipitationMm = wetHours * plan.mmPerHour,
            rainMm = if (plan.snow) 0.0 else wetHours * plan.mmPerHour,
            snowfallCm = if (plan.snow) wetHours * plan.mmPerHour * 0.9 else 0.0,
            precipitationHours = wetHours.toDouble(),
            precipitationProbability = if (plan.wet != null) 85 else 5,
            windMaxKmh = if (plan.code == 95) 42.0 else 21.0,
            gustMaxKmh = if (plan.code == 95) 78.0 else 34.0,
            windDirectionDeg = 250,
        )
    }

    /** Today at [NOW]: partly cloudy and mild, with this evening's shower still a couple of hours off. */
    val report: WeatherReport = reportAt(NOW)

    /** The same forecast read at [nowEpochSeconds], with the quarter-hours from then drawn from the hours. */
    fun reportAt(nowEpochSeconds: Long, alerts: List<WeatherAlert> = emptyList()): WeatherReport {
        val at = hours.last { it.epochSeconds <= nowEpochSeconds }
        val quarter = nowEpochSeconds - nowEpochSeconds % 900
        return WeatherReport(
            fetchedAtEpochSeconds = nowEpochSeconds - 240,
            utcOffsetSeconds = UTC_OFFSET,
            timeZoneId = "America/Los_Angeles",
            current = CurrentConditions(
                epochSeconds = quarter,
                temperatureC = at.temperatureC,
                feelsLikeC = at.feelsLikeC,
                humidityPercent = at.humidityPercent,
                dewPointC = at.dewPointC,
                precipitationMm = at.precipitationMm / 4,
                weatherCode = at.weatherCode,
                cloudCoverPercent = at.cloudCoverPercent,
                pressureHpa = at.pressureHpa,
                windKmh = at.windKmh,
                windDirectionDeg = at.windDirectionDeg,
                gustKmh = at.gustKmh,
                isDay = at.isDay,
                visibilityM = at.visibilityM,
                uvIndex = at.uvIndex,
            ),
            minutely = (0 until 13).map { i ->
                val slice = quarter + i * 900L
                val hour = hours.last { it.epochSeconds <= slice }
                PrecipSlice(slice, hour.precipitationMm / 4, hour.snowfallCm / 4)
            },
            hourly = hours,
            daily = days,
            air = AirQuality(usAqi = 42, pm25 = 8.4, pm10 = 14.0, ozone = 61.0),
            alerts = alerts,
        )
    }

    val windAdvisory = WeatherAlert(
        id = "urn:oid:preview.1",
        event = "Wind Advisory",
        headline = "Wind Advisory issued October 9 at 2:12PM PDT until October 10 at 4:00AM PDT by NWS Portland OR",
        description = "* WHAT...South winds 20 to 30 mph with gusts up to 50 mph expected.\n\n* WHERE...Greater Portland Metro Area.\n\n* IMPACTS...Gusty winds could blow around unsecured objects. Tree limbs could be blown down and a few power outages may result.",
        instruction = "Use extra caution when driving, especially if operating a high profile vehicle. Secure outdoor objects.",
        severity = AlertSeverity.MODERATE,
        sender = "NWS Portland OR",
        onsetEpochSeconds = TODAY + 14 * HOUR,
        endsEpochSeconds = TODAY + 28 * HOUR,
    )

    /** The app with three places, the phone's own in view. */
    fun state(nowEpochSeconds: Long = NOW, alerts: List<WeatherAlert> = emptyList()): WeatherUiState = WeatherUiState(
        places = listOf(
            PlaceWeather(portland, reportAt(nowEpochSeconds, alerts)),
            PlaceWeather(seattle, reportAt(nowEpochSeconds + 2 * DAY)),
            PlaceWeather(tokyo, reportAt(nowEpochSeconds + 4 * DAY + 9 * HOUR)),
        ),
        selectedId = Place.CURRENT_ID,
        locationSupported = true,
        locationAccess = LocationAccess.WHILE_IN_USE,
        nowEpochSeconds = nowEpochSeconds,
        notificationPermission = NotificationPermission.GRANTED,
        settled = true,
    )
}

/** A map with no tiles to give: what a preview draws its radar screen from. */
internal object NoTiles : MapTileSource {
    override val arrivals: StateFlow<Int> = MutableStateFlow(0)

    override fun image(url: String): ImageBitmap? = null

    override fun request(urls: Collection<String>, scope: CoroutineScope, keep: Boolean, radar: RadarColorTable?) = Unit
}

// A handful of whole screens, not every state: each is drawn by the sky's shaders, which the
// preview renderers run on the CPU, a frame at a time. SkyPreviews.kt has the skies themselves, small.
@Preview
@Composable
private fun WeatherTodayPreview() {
    WeatherAppContent(WeatherFixtures.state(), NoTiles, WeatherActions())
}

@Preview
@Composable
private fun WeatherTodayRainPreview() {
    // Seven that evening, in the shower, with a warning in force.
    WeatherAppContent(WeatherFixtures.state(WeatherFixtures.TODAY + 19 * 3_600L, listOf(WeatherFixtures.windAdvisory)), NoTiles, WeatherActions())
}

@Preview
@Composable
private fun WeatherForecastPreview() {
    WeatherAppContent(WeatherFixtures.state(), NoTiles, WeatherActions(), initialTab = WeatherTab.FORECAST)
}

@Preview
@Composable
private fun WeatherRadarPreview() {
    WeatherAppContent(WeatherFixtures.state(), NoTiles, WeatherActions(), initialTab = WeatherTab.RADAR)
}

@Preview
@Composable
private fun WeatherPlacesPreview() {
    WeatherAppContent(WeatherFixtures.state(), NoTiles, WeatherActions(), initialPage = WeatherPage.Places)
}

@Preview
@Composable
private fun WeatherSettingsPreview() {
    WeatherAppContent(WeatherFixtures.state(), NoTiles, WeatherActions(), initialPage = WeatherPage.Settings)
}
