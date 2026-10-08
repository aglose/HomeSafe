package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Somewhere the weather app shows a forecast for: the phone's own position, or a city the
 * household added. [id] is [CURRENT_ID] for the phone's position (whose coordinates move) and
 * otherwise made from the coordinates, so the same city added twice is one place.
 */
@Immutable
@Serializable
data class Place(
    val id: String,
    /** A name that is data: what the geocoder called it, or what it was called when it was saved. */
    val name: String,
    /** "Oregon, United States", or empty when the geocoder had none. */
    val region: String = "",
    val latitude: Double,
    val longitude: Double,
) {
    val isCurrentLocation: Boolean get() = id == CURRENT_ID

    companion object {
        const val CURRENT_ID = "current"

        /** A place's id from where it is, to a hundredth of a degree (about a kilometre). */
        fun idFor(latitude: Double, longitude: Double): String {
            fun round(v: Double) = kotlin.math.round(v * 100).toInt()
            return "${round(latitude)},${round(longitude)}"
        }
    }
}

/** Right now at a place. Everything is metric; the screen converts (see `WeatherFormat`). */
@Immutable
@Serializable
data class CurrentConditions(
    val epochSeconds: Long,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val dewPointC: Double? = null,
    /** What fell in the last 15 minutes. */
    val precipitationMm: Double = 0.0,
    val weatherCode: Int,
    val cloudCoverPercent: Int = 0,
    val pressureHpa: Double? = null,
    val windKmh: Double = 0.0,
    /** Where the wind comes from, in degrees clockwise from north. */
    val windDirectionDeg: Int = 0,
    val gustKmh: Double = 0.0,
    val isDay: Boolean = true,
    val visibilityM: Double? = null,
    val uvIndex: Double? = null,
) {
    val kind: WeatherKind get() = WeatherKind.fromCode(weatherCode)
}

/** One hour of the forecast, starting at [epochSeconds]. */
@Immutable
@Serializable
data class HourForecast(
    val epochSeconds: Long,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int = 0,
    val dewPointC: Double? = null,
    /** 0–100, or null where the model gives none. */
    val precipitationProbability: Int? = null,
    val precipitationMm: Double = 0.0,
    val snowfallCm: Double = 0.0,
    val weatherCode: Int,
    val cloudCoverPercent: Int = 0,
    val visibilityM: Double? = null,
    val windKmh: Double = 0.0,
    val windDirectionDeg: Int = 0,
    val gustKmh: Double = 0.0,
    val uvIndex: Double? = null,
    val isDay: Boolean = true,
    val pressureHpa: Double? = null,
) {
    val kind: WeatherKind get() = WeatherKind.fromCode(weatherCode)

    /** Wet enough to matter: measurable precipitation, or better than even odds of it. */
    val isWet: Boolean get() = precipitationMm >= WET_HOUR_MM || ((precipitationProbability ?: 0) >= 55 && kind.isPrecipitation)

    companion object {
        /** An hour with less than this is a trace: a damp pavement, not a reason to change plans. */
        const val WET_HOUR_MM = 0.2
    }
}

/** One calendar day at the place, starting at its local midnight ([epochSeconds]). */
@Immutable
@Serializable
data class DayForecast(
    val epochSeconds: Long,
    val weatherCode: Int,
    val highC: Double,
    val lowC: Double,
    val feelsHighC: Double? = null,
    val feelsLowC: Double? = null,
    val sunriseEpochSeconds: Long? = null,
    val sunsetEpochSeconds: Long? = null,
    val moonriseEpochSeconds: Long? = null,
    val moonsetEpochSeconds: Long? = null,
    val uvIndexMax: Double? = null,
    val precipitationMm: Double = 0.0,
    val rainMm: Double = 0.0,
    val snowfallCm: Double = 0.0,
    val precipitationHours: Double = 0.0,
    val precipitationProbability: Int? = null,
    val windMaxKmh: Double = 0.0,
    val gustMaxKmh: Double = 0.0,
    val windDirectionDeg: Int = 0,
) {
    val kind: WeatherKind get() = WeatherKind.fromCode(weatherCode)

    val daylightSeconds: Long? get() = if (sunriseEpochSeconds != null && sunsetEpochSeconds != null) sunsetEpochSeconds - sunriseEpochSeconds else null

    /** Mostly snow: more of the day's water fell frozen than not. A centimetre of snow is about a millimetre of water. */
    val isSnowy: Boolean get() = snowfallCm >= 0.5 && snowfallCm >= rainMm

    /** A day to plan around: real odds of a real amount. */
    val isWet: Boolean
        get() = (precipitationProbability ?: 100) >= 45 && (precipitationMm >= 1.0 || snowfallCm >= 0.5)
}

/** Fifteen minutes of the short-range precipitation forecast, starting at [epochSeconds]. */
@Immutable
@Serializable
data class PrecipSlice(
    val epochSeconds: Long,
    val precipitationMm: Double,
    val snowfallCm: Double = 0.0,
) {
    /** As a rate, the way radar and "light / heavy" are spoken of. */
    val ratePerHourMm: Double get() = precipitationMm * 4

    val isWet: Boolean get() = precipitationMm >= WET_SLICE_MM

    val isSnow: Boolean get() = snowfallCm > 0.0 && snowfallCm * 0.7 >= precipitationMm * 0.5

    companion object {
        /** 0.1 mm in a quarter hour is 0.4 mm/h: the lightest rain worth an umbrella. */
        const val WET_SLICE_MM = 0.1
    }
}

/** The US air quality index and what it's made of. */
@Immutable
@Serializable
data class AirQuality(
    val usAqi: Int,
    val pm25: Double? = null,
    val pm10: Double? = null,
    val ozone: Double? = null,
    val nitrogenDioxide: Double? = null,
) {
    val band: AqiBand get() = AqiBand.of(usAqi)
}

enum class AqiBand(val upTo: Int) {
    GOOD(50),
    MODERATE(100),
    SENSITIVE(150),
    UNHEALTHY(200),
    VERY_UNHEALTHY(300),
    HAZARDOUS(Int.MAX_VALUE),
    ;

    companion object {
        fun of(aqi: Int): AqiBand = entries.first { aqi <= it.upTo }
    }
}

/** How bad a government alert says things are, in its own scale. */
enum class AlertSeverity {
    EXTREME,
    SEVERE,
    MODERATE,
    MINOR,
    UNKNOWN,
    ;

    companion object {
        fun from(value: String?): AlertSeverity = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * A government weather alert in force at the place (in the US, from the National Weather
 * Service). The words are the agency's own, shown as written.
 */
@Immutable
@Serializable
data class WeatherAlert(
    val id: String,
    /** "Winter Storm Warning". */
    val event: String,
    val headline: String = "",
    val description: String = "",
    val instruction: String = "",
    val severity: AlertSeverity = AlertSeverity.UNKNOWN,
    val sender: String = "",
    val onsetEpochSeconds: Long? = null,
    val endsEpochSeconds: Long? = null,
) {
    /** A warning for something dangerous now, worth interrupting for whatever the hour. */
    val isUrgent: Boolean get() = severity == AlertSeverity.EXTREME || severity == AlertSeverity.SEVERE
}

/**
 * Everything the weather app knows about one place from one fetch. Hours and days are in order;
 * [hourly] starts at the place's local midnight yesterday and [daily] at yesterday too, so there
 * is a past to compare with ([yesterday]) and a pressure trend to read.
 */
@Immutable
@Serializable
data class WeatherReport(
    val fetchedAtEpochSeconds: Long,
    /** What to add to an instant to read the place's own clock. */
    val utcOffsetSeconds: Int,
    val timeZoneId: String = "",
    val current: CurrentConditions,
    val minutely: List<PrecipSlice> = emptyList(),
    val hourly: List<HourForecast> = emptyList(),
    val daily: List<DayForecast> = emptyList(),
    val air: AirQuality? = null,
    val alerts: List<WeatherAlert> = emptyList(),
) {
    /** The index in [daily] of the day [epochSeconds] falls in, or -1. */
    fun dayIndexAt(epochSeconds: Long): Int = daily.indexOfLast { it.epochSeconds <= epochSeconds }.let { i ->
        if (i >= 0 && epochSeconds < daily[i].epochSeconds + DAY_SECONDS + 2 * HOUR_SECONDS) i else -1
    }

    fun today(nowEpochSeconds: Long = current.epochSeconds): DayForecast? = daily.getOrNull(dayIndexAt(nowEpochSeconds))

    fun yesterday(nowEpochSeconds: Long = current.epochSeconds): DayForecast? = daily.getOrNull(dayIndexAt(nowEpochSeconds) - 1)

    fun tomorrow(nowEpochSeconds: Long = current.epochSeconds): DayForecast? = dayIndexAt(nowEpochSeconds).let { if (it < 0) null else daily.getOrNull(it + 1) }

    /** Today and the days after it: what the forecast list shows. */
    fun daysFromToday(nowEpochSeconds: Long = current.epochSeconds): List<DayForecast> {
        val i = dayIndexAt(nowEpochSeconds)
        return if (i < 0) daily else daily.drop(i)
    }

    /** The hour in progress and those after it. */
    fun hoursFrom(nowEpochSeconds: Long = current.epochSeconds): List<HourForecast> {
        val i = hourly.indexOfLast { it.epochSeconds <= nowEpochSeconds }
        return if (i < 0) hourly else hourly.drop(i)
    }

    /** The hours of the day that starts at [dayEpochSeconds]. */
    fun hoursOf(day: DayForecast): List<HourForecast> = hourly.filter { it.epochSeconds >= day.epochSeconds && it.epochSeconds < day.epochSeconds + DAY_SECONDS }

    /** The quarter-hours from the one in progress on. */
    fun slicesFrom(nowEpochSeconds: Long = current.epochSeconds): List<PrecipSlice> {
        val i = minutely.indexOfLast { it.epochSeconds <= nowEpochSeconds }
        return if (i < 0) minutely.filter { it.epochSeconds > nowEpochSeconds } else minutely.drop(i)
    }

    /** Seconds since local midnight at the place, at [epochSeconds]. */
    fun secondOfDay(epochSeconds: Long): Int = ((epochSeconds + utcOffsetSeconds) % DAY_SECONDS + DAY_SECONDS).toInt() % DAY_SECONDS.toInt()

    /** The hour on the place's clock, 0–23. */
    fun localHour(epochSeconds: Long): Int = secondOfDay(epochSeconds) / 3600

    companion object {
        const val HOUR_SECONDS = 3_600L
        const val DAY_SECONDS = 86_400L
    }
}
