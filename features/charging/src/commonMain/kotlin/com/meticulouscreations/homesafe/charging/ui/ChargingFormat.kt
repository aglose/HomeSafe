package com.meticulouscreations.homesafe.charging.ui

import androidx.compose.runtime.Composable
import com.meticulouscreations.homesafe.charging.domain.ChargePlace
import com.meticulouscreations.homesafe.charging.domain.ChargingMonth
import com.meticulouscreations.homesafe.charging.domain.RatePeriod
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_duration_hours
import homesafe.shared.generated.resources.charging_duration_hours_minutes
import homesafe.shared.generated.resources.charging_duration_minutes
import homesafe.shared.generated.resources.charging_kwh
import homesafe.shared.generated.resources.charging_miles
import homesafe.shared.generated.resources.charging_money
import homesafe.shared.generated.resources.charging_period_off_peak
import homesafe.shared.generated.resources.charging_period_peak
import homesafe.shared.generated.resources.charging_period_tier1
import homesafe.shared.generated.resources.charging_period_tier2
import homesafe.shared.generated.resources.charging_place_fast
import homesafe.shared.generated.resources.charging_place_home
import homesafe.shared.generated.resources.charging_place_other
import homesafe.shared.generated.resources.charging_place_supercharger
import homesafe.shared.generated.resources.charging_rate_per_kwh
import homesafe.shared.generated.resources.finance_date_month_day
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.Instant

/** Numbers as the charging screens show them. */
internal object ChargingFormat {
    /** A number to [digits] places, thousands grouped: "1,234.50". */
    fun fixed(value: Double, digits: Int = 2): String {
        val scale = 10.0.pow(digits).toLong()
        val scaled = (abs(value) * scale).roundToLong()
        val whole = (scaled / scale).toString().reversed().chunked(3).joinToString(",").reversed()
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return if (digits == 0) sign + whole else sign + whole + "." + (scaled % scale).toString().padStart(digits, '0')
    }

    /** Short kWh figures lose their decimals once they are big enough not to need them. */
    fun energy(kwh: Double): String = fixed(kwh, if (abs(kwh) >= 100) 0 else 1)

    fun month(number: Int): StringResource = when (number) {
        1 -> Res.string.finance_month_jan
        2 -> Res.string.finance_month_feb
        3 -> Res.string.finance_month_mar
        4 -> Res.string.finance_month_apr
        5 -> Res.string.finance_month_may
        6 -> Res.string.finance_month_jun
        7 -> Res.string.finance_month_jul
        8 -> Res.string.finance_month_aug
        9 -> Res.string.finance_month_sep
        10 -> Res.string.finance_month_oct
        11 -> Res.string.finance_month_nov
        else -> Res.string.finance_month_dec
    }
}

/** "$12.34". */
@Composable
internal fun moneyText(value: Double): String = stringResource(Res.string.charging_money, ChargingFormat.fixed(value))

/** "12.3 kWh". */
@Composable
internal fun kwhText(kwh: Double): String = stringResource(Res.string.charging_kwh, ChargingFormat.energy(kwh))

/** "$0.288/kWh". */
@Composable
internal fun rateText(rate: Double): String = stringResource(Res.string.charging_rate_per_kwh, ChargingFormat.fixed(rate, 3))

@Composable
internal fun milesText(miles: Double): String = stringResource(Res.string.charging_miles, ChargingFormat.fixed(miles, 0))

/** "Oct 2026". */
@Composable
internal fun monthYearText(month: ChargingMonth): String = stringResource(Res.string.finance_date_month_year, stringResource(ChargingFormat.month(month.month)), month.year)

/** "Oct 31", on this device's clock. */
@Composable
internal fun dayText(epochSeconds: Long): String {
    val local = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
    return stringResource(Res.string.finance_date_month_day, stringResource(ChargingFormat.month(local.month.number)), local.day)
}

/** "8:05 PM", on this device's clock. */
@Composable
internal fun timeText(epochSeconds: Long): String {
    val local = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = (local.hour + 11) % 12 + 1
    return stringResource(if (local.hour < 12) Res.string.weather_time_am else Res.string.weather_time_pm, "$hour:${local.minute.toString().padStart(2, '0')}")
}

/** The hour of the day alone, for an axis: "6 AM". */
@Composable
internal fun hourText(hour: Int): String = stringResource(if (hour.mod(24) < 12) Res.string.weather_time_am else Res.string.weather_time_pm, ((hour.mod(24) + 11) % 12 + 1).toString())

/** "2 h 5 min". */
@Composable
internal fun durationText(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return when {
        minutes < 60 -> stringResource(Res.string.charging_duration_minutes, minutes.toString())
        minutes % 60 == 0L -> stringResource(Res.string.charging_duration_hours, (minutes / 60).toString())
        else -> stringResource(Res.string.charging_duration_hours_minutes, (minutes / 60).toString(), (minutes % 60).toString())
    }
}

internal val ChargePlace.label: StringResource
    get() = when (this) {
        ChargePlace.HOME -> Res.string.charging_place_home
        ChargePlace.SUPERCHARGER -> Res.string.charging_place_supercharger
        ChargePlace.FAST -> Res.string.charging_place_fast
        ChargePlace.OTHER -> Res.string.charging_place_other
    }

internal val RatePeriod.label: StringResource
    get() = when (this) {
        RatePeriod.PEAK -> Res.string.charging_period_peak
        RatePeriod.OFF_PEAK -> Res.string.charging_period_off_peak
        RatePeriod.TIER1 -> Res.string.charging_period_tier1
        RatePeriod.TIER2 -> Res.string.charging_period_tier2
    }
