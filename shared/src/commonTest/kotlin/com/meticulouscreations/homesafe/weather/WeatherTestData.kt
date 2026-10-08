package com.meticulouscreations.homesafe.weather

import com.meticulouscreations.homesafe.weather.domain.AirQuality
import com.meticulouscreations.homesafe.weather.domain.CurrentConditions
import com.meticulouscreations.homesafe.weather.domain.DayForecast
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherReport

/**
 * Small hand-built forecasts for the rules that read one, so each test changes only the hours
 * it is about. Everything is set in Portland (UTC-7) around Thursday 9 October 2025: the day
 * index 0 starts at [MIDNIGHT], -1 is the day before.
 *
 * The base forecast is calm: 20 degrees, dry, overcast, light wind, daylight from 7 AM to 6 PM.
 */
internal object WeatherData {
    /** Local midnight on Thursday 9 October 2025 in Portland. */
    const val MIDNIGHT = 1_759_993_200L
    const val OFFSET = -25_200
    const val HOUR = 3_600L
    const val DAY = 86_400L

    /** [hourOfDay] o'clock on day [day] (0 is Thursday the 9th). */
    fun at(day: Int, hourOfDay: Int, minute: Int = 0): Long = MIDNIGHT + day * DAY + hourOfDay * HOUR + minute * 60L

    fun hour(
        epochSeconds: Long,
        temperatureC: Double = 20.0,
        feelsLikeC: Double = temperatureC,
        precipitationMm: Double = 0.0,
        snowfallCm: Double = 0.0,
        weatherCode: Int = 3,
        probability: Int? = 0,
        windKmh: Double = 5.0,
        gustKmh: Double = 8.0,
        uvIndex: Double? = 0.0,
        isDay: Boolean = ((epochSeconds - MIDNIGHT) / HOUR + 24) % 24 in 7..18,
        pressureHpa: Double? = null,
    ) = HourForecast(
        epochSeconds = epochSeconds,
        temperatureC = temperatureC,
        feelsLikeC = feelsLikeC,
        precipitationProbability = probability,
        precipitationMm = precipitationMm,
        snowfallCm = snowfallCm,
        weatherCode = weatherCode,
        windKmh = windKmh,
        gustKmh = gustKmh,
        uvIndex = uvIndex,
        isDay = isDay,
        pressureHpa = pressureHpa,
    )

    /** Twenty-four calm hours for each of [days], each passed through [change]. */
    fun calmHours(days: IntRange = -1..2, change: (HourForecast) -> HourForecast = { it }): List<HourForecast> =
        days.flatMap { d -> (0..23).map { change(hour(at(d, it))) } }

    fun day(
        index: Int,
        highC: Double = 20.0,
        lowC: Double = 10.0,
        weatherCode: Int = 3,
    ) = DayForecast(epochSeconds = at(index, 0), weatherCode = weatherCode, highC = highC, lowC = lowC)

    fun calmDays(days: IntRange = -1..2, change: (DayForecast) -> DayForecast = { it }): List<DayForecast> = days.map { change(day(it)) }

    fun current(
        epochSeconds: Long,
        temperatureC: Double = 20.0,
        weatherCode: Int = 3,
        precipitationMm: Double = 0.0,
        pressureHpa: Double? = null,
        feelsLikeC: Double = temperatureC,
        isDay: Boolean = true,
    ) = CurrentConditions(
        epochSeconds = epochSeconds,
        temperatureC = temperatureC,
        feelsLikeC = feelsLikeC,
        humidityPercent = 50,
        precipitationMm = precipitationMm,
        weatherCode = weatherCode,
        pressureHpa = pressureHpa,
        isDay = isDay,
    )

    /** [mm] by quarter hour from [start]. */
    fun slices(start: Long, vararg mm: Double, snowCm: Double = 0.0): List<PrecipSlice> =
        mm.mapIndexed { i, v -> PrecipSlice(start + i * 900L, v, if (v > 0) snowCm else 0.0) }

    fun report(
        now: Long,
        hourly: List<HourForecast> = calmHours(),
        daily: List<DayForecast> = calmDays(),
        minutely: List<PrecipSlice> = emptyList(),
        current: CurrentConditions = current(now),
        alerts: List<WeatherAlert> = emptyList(),
        air: AirQuality? = null,
        fetchedAt: Long = now,
    ) = WeatherReport(
        fetchedAtEpochSeconds = fetchedAt,
        utcOffsetSeconds = OFFSET,
        timeZoneId = "America/Los_Angeles",
        current = current,
        minutely = minutely,
        hourly = hourly,
        daily = daily,
        air = air,
        alerts = alerts,
    )
}
