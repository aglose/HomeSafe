package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.weather.domain.CurrentConditions
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The forecast a "rain on the way" push carries (`rain_soon_data` in relay/relay.py): only what
 * [com.meticulouscreations.homesafe.weather.domain.WeatherNoticePlanner] reads about a shower, in
 * as little room as will do, since a push has 4 KB. Times are the first quarter-hour's and hour's,
 * each amount already the one for the interval that starts at its time.
 */
@Serializable
class WeatherSoonPush(
    private val zone: String = "",
    private val offset: Int = 0,
    private val day: Int = 1,
    private val code: Int = 3,
    private val mm: Double = 0.0,
    private val q0: Long = 0,
    private val qmm: List<Double> = emptyList(),
    private val qcm: List<Double> = emptyList(),
    private val h0: Long = 0,
    private val hmm: List<Double> = emptyList(),
    private val hcm: List<Double> = emptyList(),
    private val hp: List<Int?> = emptyList(),
    private val hc: List<Int> = emptyList(),
) {
    /** A report to plan a shower's notification from, and nothing else: it knows no temperatures, no days and no warnings. */
    private fun toReport(nowEpochSeconds: Long) = WeatherReport(
        fetchedAtEpochSeconds = nowEpochSeconds,
        utcOffsetSeconds = offset,
        timeZoneId = zone,
        current = CurrentConditions(
            epochSeconds = nowEpochSeconds,
            temperatureC = 0.0,
            feelsLikeC = 0.0,
            humidityPercent = 0,
            precipitationMm = mm,
            weatherCode = code,
            isDay = day != 0,
        ),
        minutely = qmm.mapIndexed { i, amount -> PrecipSlice(q0 + i * WeatherReport.SLICE_SECONDS, amount, qcm.getOrElse(i) { 0.0 }) },
        hourly = hmm.mapIndexed { i, amount ->
            HourForecast(
                epochSeconds = h0 + i * WeatherReport.HOUR_SECONDS,
                temperatureC = 0.0,
                feelsLikeC = 0.0,
                precipitationProbability = hp.getOrNull(i),
                precipitationMm = amount,
                snowfallCm = hcm.getOrElse(i) { 0.0 },
                weatherCode = hc.getOrElse(i) { 3 },
                isDay = day != 0,
            )
        },
    )

    companion object {
        /** The push's data key that holds this, which is also what marks it as one of these. */
        const val KEY = "weather_soon"

        /** The push's data key that holds the shower's key, the same as the planner's for it. */
        const val KEY_ID = "notif_id"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** Null for what this build can't read: a newer relay's. */
        fun report(payload: String, nowEpochSeconds: Long): WeatherReport? =
            runCatching { JSON.decodeFromString<WeatherSoonPush>(payload).toReport(nowEpochSeconds) }.getOrNull()
    }
}
