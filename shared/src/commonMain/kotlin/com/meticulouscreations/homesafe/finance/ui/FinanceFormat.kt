package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.IndicatorUnit
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.Position
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.Instant

/** Number and date formatting for the finance screens; common code has no String.format. */
object FinanceFormat {

    /** 1234567.891 with 2 decimals → "1,234,567.89". */
    fun grouped(value: Double, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val factor = 10.0.pow(decimals)
        val scaled = (abs(value) * factor).roundToLong()
        // A value that rounds to zero is zero, not "-0.00".
        val negative = value < 0 && scaled != 0L
        val whole = scaled / factor.toLong()
        val frac = scaled % factor.toLong()
        val wholeText = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        val fracText = if (decimals > 0) "." + frac.toString().padStart(decimals, '0') else ""
        return (if (negative) "-" else "") + wholeText + fracText
    }

    /** "$1,234,567.89"; [decimals] 0 for big round sums. */
    fun money(value: Double, decimals: Int = 2): String =
        (if (isNegative(value, decimals)) "-$" else "$") + grouped(abs(value), decimals)

    /** "+$1,234.56" / "-$1,234.56". */
    fun signedMoney(value: Double, decimals: Int = 2): String =
        (if (isNegative(value, decimals)) "-$" else "+$") + grouped(abs(value), decimals)

    /** Below zero once rounded to [decimals]; a loss too small to show isn't one. */
    private fun isNegative(value: Double, decimals: Int): Boolean = value < 0 && (abs(value) * 10.0.pow(decimals)).roundToLong() != 0L

    /** "$2.19M", "$845K", "$1.2B" for tight spaces. */
    fun compactMoney(value: Double): String {
        val a = abs(value)
        val sign = if (isNegative(value, 0)) "-" else ""
        // The unit is chosen on the rounded figure, so 999,999 is "$1.00M", not "$1,000K".
        return sign + "$" + when {
            a >= 999_995e6 -> grouped(a / 1e12, 2) + "T"
            a >= 999_995e3 -> grouped(a / 1e9, 2) + "B"
            a >= 999_500 -> grouped(a / 1e6, 2) + "M"
            a >= 9_999.5 -> grouped(a / 1e3, 0) + "K"
            a >= 999.5 -> grouped(a / 1e3, 1) + "K"
            else -> grouped(a, 0)
        }
    }

    /** "+1.23%" (or "−" for losses, a real minus sign so it lines up with the plus). */
    fun signedPercent(value: Double, decimals: Int = 2): String =
        (if (isNegative(value, decimals)) "−" else "+") + grouped(abs(value), decimals) + "%"

    fun percent(value: Double, decimals: Int = 2): String = grouped(value, decimals) + "%"

    /** A fraction (0.3814) as "38.1%". */
    fun fractionPercent(fraction: Double, decimals: Int = 1): String = grouped(fraction * 100, decimals) + "%"

    /** A market price, written the way its kind is quoted. */
    fun price(value: Double, kind: InstrumentKind): String = when (kind) {
        InstrumentKind.INDEX -> grouped(value, 2)
        InstrumentKind.YIELD -> grouped(value, 3) + "%"
        InstrumentKind.CURRENCY -> grouped(value, 2)
        InstrumentKind.CRYPTO -> money(value, if (value >= 1000) 2 else 4)
        InstrumentKind.EQUITY, InstrumentKind.COMMODITY -> money(value, 2)
    }

    /** A change in price, with sign, in the instrument's own terms. */
    fun priceChange(value: Double, kind: InstrumentKind): String = when (kind) {
        InstrumentKind.INDEX, InstrumentKind.CURRENCY -> (if (isNegative(value, 2)) "−" else "+") + grouped(abs(value), 2)
        InstrumentKind.YIELD -> (if (isNegative(value, 3)) "−" else "+") + grouped(abs(value), 3)
        else -> signedMoney(value)
    }

    fun indicator(value: Double, unit: IndicatorUnit): String = when (unit) {
        IndicatorUnit.PERCENT -> grouped(value, 2) + "%"
        IndicatorUnit.INDEX -> grouped(value, 2)
        IndicatorUnit.THOUSANDS -> grouped(value, 0) + "K"
        IndicatorUnit.RATIO -> grouped(value, 2) + "×"
    }

    fun indicatorChange(value: Double, unit: IndicatorUnit): String {
        val decimals = if (unit == IndicatorUnit.THOUSANDS) 0 else 2
        if (grouped(abs(value), decimals).all { it == '0' || it == '.' }) return "unchanged"
        val sign = if (value >= 0) "+" else "−"
        return sign + when (unit) {
            IndicatorUnit.PERCENT -> grouped(abs(value), 2) + " pts"
            IndicatorUnit.THOUSANDS -> grouped(abs(value), 0) + "K"
            else -> grouped(abs(value), 2)
        }
    }

    /** A share count as typed: "10", "0.5", "1,250.125" — up to six places, no trailing zeros. */
    fun shares(value: Double): String {
        val text = grouped(value, 6)
        return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    }

    /** "10 shares · $2,431.20", "0.25 BTC · $28,940.11"; without a price yet, just the holding. */
    fun positionLine(position: Position, price: Double?, kind: InstrumentKind = InstrumentKind.EQUITY, unit: String? = null): String {
        val held = shares(position.shares) + " " + when {
            kind == InstrumentKind.CRYPTO && unit != null -> unit
            position.shares == 1.0 -> "share"
            else -> "shares"
        }
        return if (price == null) held else "$held · ${money(position.value(price))}"
    }

    /** A change that can run to thousands of percent over decades: "+12.34%", "+456.7%", "+43,210%". */
    fun longRunPercent(value: Double): String = signedPercent(
        value,
        when {
            abs(value) >= 1000 -> 0
            abs(value) >= 100 -> 1
            else -> 2
        },
    )

    /** "1.23B" shares or contracts. */
    fun volume(value: Double): String = when {
        value >= 999_995e3 -> grouped(value / 1e9, 2) + "B"
        value >= 999_950 -> grouped(value / 1e6, 2) + "M"
        value >= 999.5 -> grouped(value / 1e3, 1) + "K"
        else -> grouped(value, 0)
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun local(epochSeconds: Long, offsetSeconds: Int): LocalDateTime =
        Instant.fromEpochSeconds(epochSeconds + offsetSeconds).toLocalDateTime(TimeZone.UTC)

    /** "Sep 30, 2026". */
    fun date(epochSeconds: Long, offsetSeconds: Int = 0): String {
        val d = local(epochSeconds, offsetSeconds)
        return "${MONTHS[d.month.ordinal]} ${d.day}, ${d.year}"
    }

    /** "Sep 2026". */
    fun monthYear(epochSeconds: Long, offsetSeconds: Int = 0): String {
        val d = local(epochSeconds, offsetSeconds)
        return "${MONTHS[d.month.ordinal]} ${d.year}"
    }

    /** "'26". */
    fun shortYear(epochSeconds: Long): String = "'" + (local(epochSeconds, 0).year % 100).toString().padStart(2, '0')

    /** "10:35 AM". */
    fun time(epochSeconds: Long, offsetSeconds: Int): String {
        val d = local(epochSeconds, offsetSeconds)
        val h12 = ((d.hour + 11) % 12) + 1
        return "$h12:${d.minute.toString().padStart(2, '0')} ${if (d.hour < 12) "AM" else "PM"}"
    }

    /** "Sep 30, 10:35 AM" — a point on an intraday chart. */
    fun dateTime(epochSeconds: Long, offsetSeconds: Int): String {
        val d = local(epochSeconds, offsetSeconds)
        return "${MONTHS[d.month.ordinal]} ${d.day}, ${time(epochSeconds, offsetSeconds)}"
    }

    /** The device's UTC offset at [epochSeconds], for writing a time in the household's own clock. */
    fun localOffsetSeconds(epochSeconds: Long): Int = TimeZone.currentSystemDefault().offsetAt(Instant.fromEpochSeconds(epochSeconds)).totalSeconds

    /** How long ago a sync was: "just now", "4 min ago", "3 h ago", then the date. */
    fun ago(nowEpochSeconds: Long, thenEpochSeconds: Long): String {
        val s = (nowEpochSeconds - thenEpochSeconds).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3_600 -> "${s / 60} min ago"
            s < 36 * 3_600 -> "${s / 3_600} h ago"
            else -> date(thenEpochSeconds, localOffsetSeconds(thenEpochSeconds))
        }
    }

    /** "in 3 days", "in 4 months", "2 months ago". */
    fun relativeDays(fromEpochSeconds: Long, toEpochSeconds: Long): String {
        // Rounded to the nearest day, so a payout 16 hours off is "in 1 day", not "today".
        val days = ((toEpochSeconds - fromEpochSeconds) / 86_400.0).let { if (it >= 0) it + 0.5 else it - 0.5 }.toLong()
        val a = abs(days)
        fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
        val text = when {
            a == 0L -> return "today"
            a < 45 -> plural(a, "day")
            a < 365 * 2 -> plural((a / 30.4).roundToLong().coerceAtLeast(2), "month")
            else -> plural((a / 365.25).roundToLong(), "year")
        }
        return if (days > 0) "in $text" else "$text ago"
    }
}
