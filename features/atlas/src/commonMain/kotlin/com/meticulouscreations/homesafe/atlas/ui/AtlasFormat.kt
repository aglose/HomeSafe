package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.runtime.Composable
import com.meticulouscreations.homesafe.atlas.domain.AtlasHistory
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.domain.Locality
import com.meticulouscreations.homesafe.atlas.domain.Place
import com.meticulouscreations.homesafe.atlas.domain.PlaceKind
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.atlas.domain.weekdayOf
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_ago_days
import homesafe.shared.generated.resources.atlas_ago_months
import homesafe.shared.generated.resources.atlas_ago_today
import homesafe.shared.generated.resources.atlas_ago_years
import homesafe.shared.generated.resources.atlas_ago_yesterday
import homesafe.shared.generated.resources.atlas_date_weekday
import homesafe.shared.generated.resources.atlas_distance_km
import homesafe.shared.generated.resources.atlas_distance_miles
import homesafe.shared.generated.resources.atlas_duration_days
import homesafe.shared.generated.resources.atlas_duration_days_hours
import homesafe.shared.generated.resources.atlas_duration_hours
import homesafe.shared.generated.resources.atlas_duration_hours_minutes
import homesafe.shared.generated.resources.atlas_duration_minutes
import homesafe.shared.generated.resources.atlas_locality_pair
import homesafe.shared.generated.resources.atlas_mode_boat
import homesafe.shared.generated.resources.atlas_mode_bus
import homesafe.shared.generated.resources.atlas_mode_cycle
import homesafe.shared.generated.resources.atlas_mode_drive
import homesafe.shared.generated.resources.atlas_mode_ferry
import homesafe.shared.generated.resources.atlas_mode_fly
import homesafe.shared.generated.resources.atlas_mode_motorcycle
import homesafe.shared.generated.resources.atlas_mode_other
import homesafe.shared.generated.resources.atlas_mode_run
import homesafe.shared.generated.resources.atlas_mode_ski
import homesafe.shared.generated.resources.atlas_mode_subway
import homesafe.shared.generated.resources.atlas_mode_train
import homesafe.shared.generated.resources.atlas_mode_tram
import homesafe.shared.generated.resources.atlas_mode_walk
import homesafe.shared.generated.resources.atlas_place_home
import homesafe.shared.generated.resources.atlas_place_near
import homesafe.shared.generated.resources.atlas_place_unnamed
import homesafe.shared.generated.resources.atlas_place_work
import homesafe.shared.generated.resources.finance_date_month_day
import homesafe.shared.generated.resources.finance_date_month_day_year
import homesafe.shared.generated.resources.finance_date_month_year
import homesafe.shared.generated.resources.finance_month_apr
import homesafe.shared.generated.resources.finance_month_aug
import homesafe.shared.generated.resources.finance_month_dec
import homesafe.shared.generated.resources.finance_month_feb
import homesafe.shared.generated.resources.finance_month_jan
import homesafe.shared.generated.resources.finance_month_jul
import homesafe.shared.generated.resources.finance_month_jun
import homesafe.shared.generated.resources.finance_month_mar
import homesafe.shared.generated.resources.finance_month_may
import homesafe.shared.generated.resources.finance_month_nov
import homesafe.shared.generated.resources.finance_month_oct
import homesafe.shared.generated.resources.finance_month_sep
import homesafe.shared.generated.resources.weather_time_am
import homesafe.shared.generated.resources.weather_time_pm
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How the atlas screens write dates, times, lengths of time and distances. */
internal object AtlasFormat {
    private val MONTHS = listOf(
        Res.string.finance_month_jan, Res.string.finance_month_feb, Res.string.finance_month_mar, Res.string.finance_month_apr,
        Res.string.finance_month_may, Res.string.finance_month_jun, Res.string.finance_month_jul, Res.string.finance_month_aug,
        Res.string.finance_month_sep, Res.string.finance_month_oct, Res.string.finance_month_nov, Res.string.finance_month_dec,
    )
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

    const val METERS_PER_MILE = 1609.344

    fun month(month: Int): StringResource = MONTHS[(month - 1).mod(12)]

    /** "Mon"; [weekday] is 0 for Monday. */
    fun weekday(weekday: Int): StringResource = WEEKDAYS[weekday.mod(7)]

    /** "Monday". */
    fun weekdayLong(weekday: Int): StringResource = WEEKDAYS_LONG[weekday.mod(7)]

    /** 12,345 with its thousands marked. */
    fun grouped(number: Long): String {
        val digits = abs(number).toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return if (number < 0) "-$out" else out.toString()
    }

    /** A length as a number in the chosen unit: one decimal under ten, whole and grouped above. */
    fun length(meters: Double, metric: Boolean): String {
        val value = meters / if (metric) 1000.0 else METERS_PER_MILE
        return if (value < 9.95) ((value * 10).roundToInt() / 10.0).toString() else grouped(value.roundToLong())
    }
}

/** "Mar 4, 2019"; with [weekday], "Mon, Mar 4, 2019"; without [year], "Mar 4". [day] is counted from 1970-01-01. */
@Composable
internal fun dateText(day: Int, weekday: Boolean = false, year: Boolean = true): String {
    val date = CivilDate.of(day)
    val month = stringResource(AtlasFormat.month(date.month))
    val plain = if (year) stringResource(Res.string.finance_date_month_day_year, month, date.dayOfMonth, date.year) else stringResource(Res.string.finance_date_month_day, month, date.dayOfMonth)
    return if (weekday) stringResource(Res.string.atlas_date_weekday, stringResource(AtlasFormat.weekday(weekdayOf(day))), plain) else plain
}

/** "Mar 2019", from months since year 0. */
@Composable
internal fun monthText(monthIndex: Int): String = stringResource(Res.string.finance_date_month_year, stringResource(AtlasFormat.month(monthIndex % 12 + 1)), monthIndex / 12)

/** "8:05 AM", from minutes past midnight. */
@Composable
internal fun clockText(minuteOfDay: Int): String {
    val minute = minuteOfDay.mod(1440)
    val hour = minute / 60
    return stringResource(if (hour < 12) Res.string.weather_time_am else Res.string.weather_time_pm, "${(hour + 11) % 12 + 1}:${(minute % 60).toString().padStart(2, '0')}")
}

/** "6 AM": the hour alone, for an axis. */
@Composable
internal fun hourText(hour: Int): String = stringResource(if (hour.mod(24) < 12) Res.string.weather_time_am else Res.string.weather_time_pm, ((hour.mod(24) + 11) % 12 + 1).toString())

/** "45 min", "3 h 20 min", "2 d 4 h": as fine as the length of it warrants. */
@Composable
internal fun durationText(seconds: Long): String {
    val minutes = (seconds / 60).coerceAtLeast(0)
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 60 -> stringResource(Res.string.atlas_duration_minutes, minutes.toInt())

        hours < 24 && minutes % 60 == 0L -> stringResource(Res.string.atlas_duration_hours, AtlasFormat.grouped(hours))

        hours < 24 -> stringResource(Res.string.atlas_duration_hours_minutes, hours.toInt(), (minutes % 60).toInt())

        // Past a few days, hours are what a total is counted in.
        days >= 4 -> stringResource(Res.string.atlas_duration_hours, AtlasFormat.grouped(hours))

        hours % 24 == 0L -> stringResource(Res.string.atlas_duration_days, days.toInt())

        else -> stringResource(Res.string.atlas_duration_days_hours, days.toInt(), (hours % 24).toInt())
    }
}

/** "4.2 mi", or kilometres where [LocalAtlasMetric] says so. */
@Composable
internal fun distanceText(meters: Double): String {
    val metric = LocalAtlasMetric.current
    return stringResource(if (metric) Res.string.atlas_distance_km else Res.string.atlas_distance_miles, AtlasFormat.length(meters, metric))
}

/** "Lakemont, WI"; the country stands in where there is no state, and either part alone where that is all there is. */
@Composable
internal fun localityText(locality: Locality?): String? {
    locality ?: return null
    val wider = locality.region ?: locality.country
    return when {
        locality.city != null && wider != null -> stringResource(Res.string.atlas_locality_pair, locality.city, wider)
        else -> locality.city ?: wider
    }
}

/**
 * What to call a place: "Home" and "Work" for those, Google's name for a place it named, and for
 * the rest the town it is in ("Near Fairview") or, with nothing at all to go on, that it has no name.
 */
@Composable
internal fun placeTitle(place: Place, history: AtlasHistory): String = when {
    place.kind == PlaceKind.HOME -> stringResource(Res.string.atlas_place_home)
    place.kind == PlaceKind.WORK && place.name == null -> stringResource(Res.string.atlas_place_work)
    place.name != null -> place.name
    else -> history.locality(place)?.let { it.city ?: it.region ?: it.country }?.let { stringResource(Res.string.atlas_place_near, it) } ?: stringResource(Res.string.atlas_place_unnamed)
}

/** "Today", "3 days ago", "5 months ago", "2 years ago". */
@Composable
internal fun agoText(days: Int): String = when {
    days <= 0 -> stringResource(Res.string.atlas_ago_today)
    days == 1 -> stringResource(Res.string.atlas_ago_yesterday)
    days < 60 -> pluralStringResource(Res.plurals.atlas_ago_days, days, days)
    days < 730 -> (days / 30).let { pluralStringResource(Res.plurals.atlas_ago_months, it, it) }
    else -> (days / 365).let { pluralStringResource(Res.plurals.atlas_ago_years, it, it) }
}

internal val TravelMode.label: StringResource
    get() = when (this) {
        TravelMode.WALK -> Res.string.atlas_mode_walk
        TravelMode.RUN -> Res.string.atlas_mode_run
        TravelMode.CYCLE -> Res.string.atlas_mode_cycle
        TravelMode.DRIVE -> Res.string.atlas_mode_drive
        TravelMode.MOTORCYCLE -> Res.string.atlas_mode_motorcycle
        TravelMode.BUS -> Res.string.atlas_mode_bus
        TravelMode.TRAIN -> Res.string.atlas_mode_train
        TravelMode.SUBWAY -> Res.string.atlas_mode_subway
        TravelMode.TRAM -> Res.string.atlas_mode_tram
        TravelMode.FLY -> Res.string.atlas_mode_fly
        TravelMode.FERRY -> Res.string.atlas_mode_ferry
        TravelMode.BOAT -> Res.string.atlas_mode_boat
        TravelMode.SKI -> Res.string.atlas_mode_ski
        TravelMode.OTHER -> Res.string.atlas_mode_other
    }
