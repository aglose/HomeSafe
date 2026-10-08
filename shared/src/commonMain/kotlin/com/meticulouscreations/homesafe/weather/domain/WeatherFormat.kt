package com.meticulouscreations.homesafe.weather.domain

import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_compass_e
import homesafe.shared.generated.resources.weather_compass_n
import homesafe.shared.generated.resources.weather_compass_ne
import homesafe.shared.generated.resources.weather_compass_nw
import homesafe.shared.generated.resources.weather_compass_s
import homesafe.shared.generated.resources.weather_compass_se
import homesafe.shared.generated.resources.weather_compass_sw
import homesafe.shared.generated.resources.weather_compass_w
import homesafe.shared.generated.resources.weather_date_month_day
import homesafe.shared.generated.resources.weather_month_apr
import homesafe.shared.generated.resources.weather_month_aug
import homesafe.shared.generated.resources.weather_month_dec
import homesafe.shared.generated.resources.weather_month_feb
import homesafe.shared.generated.resources.weather_month_jan
import homesafe.shared.generated.resources.weather_month_jul
import homesafe.shared.generated.resources.weather_month_jun
import homesafe.shared.generated.resources.weather_month_mar
import homesafe.shared.generated.resources.weather_month_may
import homesafe.shared.generated.resources.weather_month_nov
import homesafe.shared.generated.resources.weather_month_oct
import homesafe.shared.generated.resources.weather_month_sep
import homesafe.shared.generated.resources.weather_time_am
import homesafe.shared.generated.resources.weather_time_pm
import homesafe.shared.generated.resources.weather_unit_cm
import homesafe.shared.generated.resources.weather_unit_hpa
import homesafe.shared.generated.resources.weather_unit_in
import homesafe.shared.generated.resources.weather_unit_inhg
import homesafe.shared.generated.resources.weather_unit_km
import homesafe.shared.generated.resources.weather_unit_kmh
import homesafe.shared.generated.resources.weather_unit_mi
import homesafe.shared.generated.resources.weather_unit_mm
import homesafe.shared.generated.resources.weather_unit_mph
import homesafe.shared.generated.resources.weather_weekday_fri
import homesafe.shared.generated.resources.weather_weekday_long_fri
import homesafe.shared.generated.resources.weather_weekday_long_mon
import homesafe.shared.generated.resources.weather_weekday_long_sat
import homesafe.shared.generated.resources.weather_weekday_long_sun
import homesafe.shared.generated.resources.weather_weekday_long_thu
import homesafe.shared.generated.resources.weather_weekday_long_tue
import homesafe.shared.generated.resources.weather_weekday_long_wed
import homesafe.shared.generated.resources.weather_weekday_mon
import homesafe.shared.generated.resources.weather_weekday_sat
import homesafe.shared.generated.resources.weather_weekday_sun
import homesafe.shared.generated.resources.weather_weekday_thu
import homesafe.shared.generated.resources.weather_weekday_tue
import homesafe.shared.generated.resources.weather_weekday_wed
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns the forecast's metric numbers and instants into what a person reads, in the units they
 * chose and on the place's own clock. Numbers come back as strings (they are data); anything
 * with a word in it, a unit or a day's name, comes back as a resource to be resolved where it's
 * shown.
 */
object WeatherFormat {
    fun toDisplay(celsius: Double, units: WeatherUnits): Double = when (units.temperature) {
        TemperatureUnit.FAHRENHEIT -> celsius * 9 / 5 + 32
        TemperatureUnit.CELSIUS -> celsius
    }

    /** "72°". Rounded half away from zero, and never "-0°". */
    fun degrees(celsius: Double, units: WeatherUnits): String = "${whole(toDisplay(celsius, units))}°"

    /** A difference between two temperatures, as a size: "6°". */
    fun degreesBetween(celsiusDelta: Double, units: WeatherUnits): String {
        val scaled = if (units.temperature == TemperatureUnit.FAHRENHEIT) celsiusDelta * 9 / 5 else celsiusDelta
        return "${abs(whole(scaled))}°"
    }

    /** The same difference as a number in the chosen unit, for deciding whether it's worth a mention. */
    fun degreesBetweenValue(celsiusDelta: Double, units: WeatherUnits): Int =
        abs(whole(if (units.temperature == TemperatureUnit.FAHRENHEIT) celsiusDelta * 9 / 5 else celsiusDelta))

    private fun whole(value: Double): Int = value.roundToInt()

    fun speedValue(kmh: Double, units: WeatherUnits): Int = when (units.measures) {
        MeasureSystem.IMPERIAL -> (kmh * 0.621371).roundToInt()
        MeasureSystem.METRIC -> kmh.roundToInt()
    }

    /** "12 mph". */
    fun speed(kmh: Double, units: WeatherUnits): UiText = UiText.of(speedUnit(units), speedValue(kmh, units).toString())

    fun speedUnit(units: WeatherUnits): StringResource = when (units.measures) {
        MeasureSystem.IMPERIAL -> Res.string.weather_unit_mph
        MeasureSystem.METRIC -> Res.string.weather_unit_kmh
    }

    /** Rain or melted snow: "0.42 in" or "10.7 mm". */
    fun precipitation(mm: Double, units: WeatherUnits): UiText = when (units.measures) {
        MeasureSystem.IMPERIAL -> UiText.of(Res.string.weather_unit_in, decimal(mm / 25.4, if (mm / 25.4 >= 1) 1 else 2))
        MeasureSystem.METRIC -> UiText.of(Res.string.weather_unit_mm, decimal(mm, if (mm >= 10) 0 else 1))
    }

    /** Snow on the ground: "3.5 in" or "9 cm". */
    fun snow(cm: Double, units: WeatherUnits): UiText = when (units.measures) {
        MeasureSystem.IMPERIAL -> UiText.of(Res.string.weather_unit_in, decimal(cm / 2.54, if (cm / 2.54 >= 10) 0 else 1))
        MeasureSystem.METRIC -> UiText.of(Res.string.weather_unit_cm, decimal(cm, if (cm >= 10) 0 else 1))
    }

    /** "10 mi" or "16 km"; under ten, one decimal. */
    fun distance(meters: Double, units: WeatherUnits): UiText {
        val value = if (units.measures == MeasureSystem.IMPERIAL) meters / 1609.344 else meters / 1000.0
        val res = if (units.measures == MeasureSystem.IMPERIAL) Res.string.weather_unit_mi else Res.string.weather_unit_km
        return UiText.of(res, decimal(value, if (value >= 10) 0 else 1))
    }

    /** "30.12 inHg" or "1020 hPa". */
    fun pressure(hpa: Double, units: WeatherUnits): UiText = when (units.measures) {
        MeasureSystem.IMPERIAL -> UiText.of(Res.string.weather_unit_inhg, decimal(hpa * 0.0295299830714, 2))
        MeasureSystem.METRIC -> UiText.of(Res.string.weather_unit_hpa, decimal(hpa, 0))
    }

    fun percent(value: Int): String = "$value%"

    /** [value] to [decimals] places, with no thousands separators and no "-0". */
    fun decimal(value: Double, decimals: Int): String {
        val scale = 10.0.pow(decimals)
        val scaled = (abs(value) * scale).roundToLong()
        val negative = value < 0 && scaled != 0L
        val whole = scaled / scale.toLong()
        val text = if (decimals == 0) whole.toString() else "$whole.${(scaled % scale.toLong()).toString().padStart(decimals, '0')}"
        return if (negative) "-$text" else text
    }

    // ---- The place's clock --------------------------------------------------------------------

    private fun localSeconds(epochSeconds: Long, utcOffsetSeconds: Int): Long = epochSeconds + utcOffsetSeconds

    private fun localDay(epochSeconds: Long, utcOffsetSeconds: Int): Long = floor(localSeconds(epochSeconds, utcOffsetSeconds) / 86_400.0).toLong()

    private fun secondOfDay(epochSeconds: Long, utcOffsetSeconds: Int): Int = (localSeconds(epochSeconds, utcOffsetSeconds) - localDay(epochSeconds, utcOffsetSeconds) * 86_400).toInt()

    /** "3 PM": the hour alone, for axes and for sentences about roughly when. */
    fun hour(epochSeconds: Long, utcOffsetSeconds: Int): UiText {
        val hour = secondOfDay(epochSeconds, utcOffsetSeconds) / 3600
        return UiText.of(if (hour < 12) Res.string.weather_time_am else Res.string.weather_time_pm, ((hour + 11) % 12 + 1).toString())
    }

    /** "3:15 PM". */
    fun clock(epochSeconds: Long, utcOffsetSeconds: Int): UiText {
        val second = secondOfDay(epochSeconds, utcOffsetSeconds)
        val hour = second / 3600
        val minute = (second % 3600) / 60
        return UiText.of(
            if (hour < 12) Res.string.weather_time_am else Res.string.weather_time_pm,
            "${(hour + 11) % 12 + 1}:${minute.toString().padStart(2, '0')}",
        )
    }

    /** [hour], unless the instant isn't on the hour, when it's [clock]. */
    fun hourOrClock(epochSeconds: Long, utcOffsetSeconds: Int): UiText =
        if (secondOfDay(epochSeconds, utcOffsetSeconds) % 3600 < 60) hour(epochSeconds, utcOffsetSeconds) else clock(epochSeconds, utcOffsetSeconds)

    /** 0 for Monday through 6 for Sunday. 1 January 1970 was a Thursday. */
    fun weekdayIndex(epochSeconds: Long, utcOffsetSeconds: Int): Int = ((localDay(epochSeconds, utcOffsetSeconds) + 3) % 7 + 7).toInt() % 7

    fun isWeekend(epochSeconds: Long, utcOffsetSeconds: Int): Boolean = weekdayIndex(epochSeconds, utcOffsetSeconds) >= 5

    /** "Mon". */
    fun weekday(epochSeconds: Long, utcOffsetSeconds: Int): StringResource = WEEKDAYS[weekdayIndex(epochSeconds, utcOffsetSeconds)]

    /** "Monday". */
    fun weekdayLong(epochSeconds: Long, utcOffsetSeconds: Int): StringResource = WEEKDAYS_LONG[weekdayIndex(epochSeconds, utcOffsetSeconds)]

    /** "Oct 7". */
    fun date(epochSeconds: Long, utcOffsetSeconds: Int): UiText {
        val date = LocalDate.fromEpochDays(localDay(epochSeconds, utcOffsetSeconds).toInt())
        return UiText.of(Res.string.weather_date_month_day, UiText.of(MONTHS[date.month.ordinal]), date.day.toString())
    }

    /** Whether two instants fall on the same calendar day at the place. */
    fun sameDay(a: Long, b: Long, utcOffsetSeconds: Int): Boolean = localDay(a, utcOffsetSeconds) == localDay(b, utcOffsetSeconds)

    /** The compass point a bearing is nearest: where the wind comes from. */
    fun compass(degrees: Int): StringResource = COMPASS[(((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

    /** A length of time in minutes, to the nearest five: near enough for "in about 25 min". */
    fun roundedMinutes(seconds: Long): Int = ((seconds / 60.0 / 5).roundToInt() * 5).coerceAtLeast(5)

    private val WEEKDAYS = listOf(
        Res.string.weather_weekday_mon,
        Res.string.weather_weekday_tue,
        Res.string.weather_weekday_wed,
        Res.string.weather_weekday_thu,
        Res.string.weather_weekday_fri,
        Res.string.weather_weekday_sat,
        Res.string.weather_weekday_sun,
    )
    private val WEEKDAYS_LONG = listOf(
        Res.string.weather_weekday_long_mon,
        Res.string.weather_weekday_long_tue,
        Res.string.weather_weekday_long_wed,
        Res.string.weather_weekday_long_thu,
        Res.string.weather_weekday_long_fri,
        Res.string.weather_weekday_long_sat,
        Res.string.weather_weekday_long_sun,
    )
    private val MONTHS = listOf(
        Res.string.weather_month_jan,
        Res.string.weather_month_feb,
        Res.string.weather_month_mar,
        Res.string.weather_month_apr,
        Res.string.weather_month_may,
        Res.string.weather_month_jun,
        Res.string.weather_month_jul,
        Res.string.weather_month_aug,
        Res.string.weather_month_sep,
        Res.string.weather_month_oct,
        Res.string.weather_month_nov,
        Res.string.weather_month_dec,
    )
    private val COMPASS = listOf(
        Res.string.weather_compass_n,
        Res.string.weather_compass_ne,
        Res.string.weather_compass_e,
        Res.string.weather_compass_se,
        Res.string.weather_compass_s,
        Res.string.weather_compass_sw,
        Res.string.weather_compass_w,
        Res.string.weather_compass_nw,
    )
}
