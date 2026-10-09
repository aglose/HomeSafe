package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.IndicatorUnit
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.Position
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.finance_date_month_day
import homesafe.shared.generated.resources.finance_date_month_day_year
import homesafe.shared.generated.resources.finance_date_month_year
import homesafe.shared.generated.resources.finance_date_short_year
import homesafe.shared.generated.resources.finance_date_time
import homesafe.shared.generated.resources.finance_month_dec
import homesafe.shared.generated.resources.finance_month_jan
import homesafe.shared.generated.resources.finance_month_oct
import homesafe.shared.generated.resources.finance_month_sep
import homesafe.shared.generated.resources.finance_time_am
import homesafe.shared.generated.resources.finance_time_pm
import homesafe.shared.generated.resources.narrator_format_days_ago
import homesafe.shared.generated.resources.narrator_format_hours_ago
import homesafe.shared.generated.resources.narrator_format_in_days
import homesafe.shared.generated.resources.narrator_format_in_months
import homesafe.shared.generated.resources.narrator_format_in_years
import homesafe.shared.generated.resources.narrator_format_just_now
import homesafe.shared.generated.resources.narrator_format_minutes_ago
import homesafe.shared.generated.resources.narrator_format_months_ago
import homesafe.shared.generated.resources.narrator_format_points_change
import homesafe.shared.generated.resources.narrator_format_today
import homesafe.shared.generated.resources.narrator_format_unchanged
import homesafe.shared.generated.resources.narrator_format_years_ago
import homesafe.shared.generated.resources.watchlist_coins
import homesafe.shared.generated.resources.watchlist_shares
import homesafe.shared.generated.resources.watchlist_shares_fraction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Number and date formatting for the finance screens. Common code has no `String.format`, so
 * [FinanceFormat] does its own grouping, rounding and 12-hour clock math — easy to get subtly
 * wrong at the edges, which is what these tests poke at. Dates and times are [UiText] built from
 * the strings file, so they are checked by their parts; what the English reads as is checked
 * where the strings can be loaded, in the JVM's `FinanceDatesEnglishTest`.
 */
class FinanceFormatTest {

    @Test
    fun groupedRoundsAndInsertsThousandsSeparators() {
        assertEquals("1,234,567.89", FinanceFormat.grouped(1234567.891, 2))
        assertEquals("0.00", FinanceFormat.grouped(0.0, 2))
        assertEquals("1,234,567", FinanceFormat.grouped(1234567.4, 0))
        assertEquals("12,345,678,901", FinanceFormat.grouped(12345678901.0, 0))
    }

    @Test
    fun groupedPrefixesNegativeValuesWithAHyphen() {
        assertEquals("-1,234.50", FinanceFormat.grouped(-1234.5, 2))
        assertEquals("-8,743", FinanceFormat.grouped(-8743.0, 0))
    }

    @Test
    fun groupedIsAnEmDashForNonFiniteValues() {
        assertEquals("—", FinanceFormat.grouped(Double.NaN))
        assertEquals("—", FinanceFormat.grouped(Double.POSITIVE_INFINITY))
        assertEquals("—", FinanceFormat.grouped(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun moneyAndSignedMoneyAddTheDollarSign() {
        assertEquals("$1,234.50", FinanceFormat.money(1234.5))
        assertEquals("-$1,234.50", FinanceFormat.money(-1234.5))
        assertEquals("+$1,234.50", FinanceFormat.signedMoney(1234.5))
        assertEquals("-$1,234.50", FinanceFormat.signedMoney(-1234.5))
    }

    @Test
    fun compactMoneyPicksTheRightSuffixForTheMagnitude() {
        assertEquals("$4.32M", FinanceFormat.compactMoney(4_321_000.0))
        assertEquals("$845K", FinanceFormat.compactMoney(845_000.0))
        assertEquals("$1.2K", FinanceFormat.compactMoney(1_200.0))
        assertEquals("$950", FinanceFormat.compactMoney(950.0))
        assertEquals("-$845K", FinanceFormat.compactMoney(-845_000.0))
    }

    @Test
    fun signedPercentUsesARealMinusSignForLosses() {
        assertEquals("+1.23%", FinanceFormat.signedPercent(1.234))
        assertEquals("−1.23%", FinanceFormat.signedPercent(-1.234), "U+2212 minus, not a hyphen, so it lines up with the plus")
    }

    @Test
    fun dateMonthYearAndTimeApplyTheOffsetBeforeReadingTheClock() {
        val epoch = 1790800692L // 2026-09-30 20:38:12 UTC
        val sep = UiText.of(Res.string.finance_month_sep)
        val sep30 = UiText.of(Res.string.finance_date_month_day, sep, 30)
        assertEquals(UiText.of(Res.string.finance_date_month_day_year, sep, 30, 2026), FinanceFormat.date(epoch, offsetSeconds = -14400))
        assertEquals(UiText.of(Res.string.finance_date_month_year, sep, 2026), FinanceFormat.monthYear(epoch, offsetSeconds = -14400))
        val afternoon = UiText.of(Res.string.finance_time_pm, "4:38")
        assertEquals(afternoon, FinanceFormat.time(epoch, offsetSeconds = -14400), "20:38 UTC minus 4 hours is 16:38")
        assertEquals(UiText.of(Res.string.finance_date_time, sep30, afternoon), FinanceFormat.dateTime(epoch, offsetSeconds = -14400))

        // Same instant with no offset: still Sep 30, but the UTC clock reading.
        val evening = UiText.of(Res.string.finance_time_pm, "8:38")
        assertEquals(evening, FinanceFormat.time(epoch, offsetSeconds = 0))
        assertEquals(UiText.of(Res.string.finance_date_time, sep30, evening), FinanceFormat.dateTime(epoch, offsetSeconds = 0))
    }

    @Test
    fun anOffsetThatCrossesMidnightMovesTheDayTheMonthAndTheYear() {
        val epoch = 1798763400L // 2027-01-01 00:30:00 UTC
        assertEquals(UiText.of(Res.string.finance_date_month_day_year, UiText.of(Res.string.finance_month_dec), 31, 2026), FinanceFormat.date(epoch, offsetSeconds = -18000))
        assertEquals(UiText.of(Res.string.finance_date_month_year, UiText.of(Res.string.finance_month_jan), 2027), FinanceFormat.monthYear(epoch))
        assertEquals(2026, FinanceFormat.year(epoch, offsetSeconds = -18000))
        assertEquals(2027, FinanceFormat.year(epoch))
    }

    @Test
    fun timeHandlesNoonAndMidnightOnThe12HourClock() {
        val epoch = 1725000000L // 2024-08-30 06:40:00 UTC
        assertEquals(UiText.of(Res.string.finance_time_pm, "12:10"), FinanceFormat.time(epoch, offsetSeconds = 19800), "local clock lands on noon:10")
        assertEquals(UiText.of(Res.string.finance_time_am, "12:40"), FinanceFormat.time(epoch, offsetSeconds = -21600), "local clock lands on midnight:40")
    }

    @Test
    fun shortYearIgnoresTheOffsetAndUsesTheLastTwoDigits() {
        assertEquals(UiText.of(Res.string.finance_date_short_year, "26"), FinanceFormat.shortYear(1790800692L))
        assertEquals(UiText.of(Res.string.finance_date_short_year, "05"), FinanceFormat.shortYear(1104580800L), "2005 keeps its zero")
    }

    @Test
    fun dayOfMonthReadsAnIsoDayOrAMonthAndADayOfIt() {
        val oct7 = UiText.of(Res.string.finance_date_month_day, UiText.of(Res.string.finance_month_oct), 7)
        assertEquals(oct7, FinanceFormat.dayOfMonth("2026-10-07"))
        assertEquals(oct7, FinanceFormat.dayOfMonth("2026-10", 7))
        assertEquals("soon".asUiText(), FinanceFormat.dayOfMonth("soon"), "not a day: the text as it came")
        assertEquals("2026-13-07".asUiText(), FinanceFormat.dayOfMonth("2026-13-07"), "there is no thirteenth month")
    }

    @Test
    fun monthNamesAMonthAndItsYearWhenAsked() {
        val oct = UiText.of(Res.string.finance_month_oct)
        assertEquals(oct, FinanceFormat.month("2026-10"))
        assertEquals(oct, FinanceFormat.month("2026-10-07"), "a day in the month names the month")
        assertEquals(UiText.of(Res.string.finance_date_month_year, oct, 2026), FinanceFormat.month("2026-10", year = true))
        assertEquals("2026".asUiText(), FinanceFormat.month("2026"), "not a month: the text as it came")
    }

    @Test
    fun relativeDaysDescribesTodayAndASingleDay() {
        assertEquals(UiText.of(Res.string.narrator_format_today), FinanceFormat.relativeDays(0L, 0L))
        assertEquals(UiText.plural(Res.plurals.narrator_format_in_days, 1), FinanceFormat.relativeDays(0L, 86_400L))
        assertEquals(UiText.plural(Res.plurals.narrator_format_days_ago, 1), FinanceFormat.relativeDays(86_400L, 0L))
    }

    @Test
    fun relativeDaysCountsSmallGapsInDays() {
        assertEquals(UiText.plural(Res.plurals.narrator_format_in_days, 3), FinanceFormat.relativeDays(0L, 3 * 86_400L))
    }

    @Test
    fun relativeDaysSwitchesToMonthsUnderTwoYears() {
        assertEquals(UiText.plural(Res.plurals.narrator_format_in_months, 4), FinanceFormat.relativeDays(0L, 120 * 86_400L))
        assertEquals(UiText.plural(Res.plurals.narrator_format_months_ago, 2), FinanceFormat.relativeDays(60 * 86_400L, 0L))
    }

    @Test
    fun relativeDaysSwitchesToYearsPastTwoYears() {
        assertEquals(UiText.plural(Res.plurals.narrator_format_in_years, 2), FinanceFormat.relativeDays(0L, 800 * 86_400L))
        assertEquals(UiText.plural(Res.plurals.narrator_format_years_ago, 2), FinanceFormat.relativeDays(800 * 86_400L, 0L))
    }

    @Test
    fun indicatorChangeIsUnchangedWhenTheValueRoundsAwayToZero() {
        // New behavior: a value that rounds to zero at the unit's own precision reads as
        // "unchanged" instead of a misleading "+0.00" or "-0.00".
        assertEquals(UiText.of(Res.string.narrator_format_unchanged), FinanceFormat.indicatorChange(-0.001, IndicatorUnit.PERCENT))
        assertEquals(UiText.of(Res.string.narrator_format_unchanged), FinanceFormat.indicatorChange(0.001, IndicatorUnit.PERCENT))
        assertEquals(UiText.of(Res.string.narrator_format_unchanged), FinanceFormat.indicatorChange(0.4, IndicatorUnit.THOUSANDS), "THOUSANDS rounds at 0 decimals")
    }

    @Test
    fun indicatorChangeStillSignsAndSuffixesARealMove() {
        assertEquals(UiText.of(Res.string.narrator_format_points_change, "+1.50"), FinanceFormat.indicatorChange(1.5, IndicatorUnit.PERCENT))
        assertEquals("−250K".asUiText(), FinanceFormat.indicatorChange(-250.4, IndicatorUnit.THOUSANDS))
        assertEquals("+0.02".asUiText(), FinanceFormat.indicatorChange(0.015, IndicatorUnit.INDEX))
    }

    @Test
    fun agoChangesUnitAtEachThresholdAndNeverGoesNegative() {
        val now = 1_790_000_000L
        val justNow = UiText.of(Res.string.narrator_format_just_now)
        assertEquals(justNow, FinanceFormat.ago(now, now))
        assertEquals(justNow, FinanceFormat.ago(now, now - 59))
        assertEquals(UiText.plural(Res.plurals.narrator_format_minutes_ago, 1), FinanceFormat.ago(now, now - 60))
        assertEquals(UiText.plural(Res.plurals.narrator_format_minutes_ago, 59), FinanceFormat.ago(now, now - 3_599))
        assertEquals(UiText.plural(Res.plurals.narrator_format_hours_ago, 1), FinanceFormat.ago(now, now - 3_600))
        assertEquals(UiText.plural(Res.plurals.narrator_format_hours_ago, 35), FinanceFormat.ago(now, now - (36 * 3_600 - 1)))
        val then = now - 36 * 3_600
        assertEquals(FinanceFormat.date(then, FinanceFormat.localOffsetSeconds(then)), FinanceFormat.ago(now, then), "a day and a half on, the date")
        assertEquals(justNow, FinanceFormat.ago(now, now + 600), "a clock a little ahead of the relay's isn't the future")
    }

    @Test
    fun plainDecimalWritesEveryDigitAndNoExponent() {
        assertEquals("0.12345678", FinanceFormat.plainDecimal(0.12345678))
        assertEquals("0.0042", FinanceFormat.plainDecimal(0.0042))
        assertEquals("0.00000001", FinanceFormat.plainDecimal(1e-8))
        assertEquals("12", FinanceFormat.plainDecimal(12.0))
        assertEquals("125000000000000000000", FinanceFormat.plainDecimal(1.25e20))
        assertEquals(1e-8, FinanceFormat.plainDecimal(1e-8).toDouble())
    }

    @Test
    fun moneyInAnotherCurrencyNamesItInsteadOfADollarSign() {
        assertEquals("$1,234.50", FinanceFormat.money(1234.5))
        assertEquals("$1,234.50", FinanceFormat.money(1234.5, currency = "USD"))
        assertEquals("3,000.00 JPY", FinanceFormat.money(3000.0, currency = "JPY"))
        assertEquals("-12.00 GBp", FinanceFormat.signedMoney(-12.0, currency = "GBp"))
        assertEquals("+12.00 GBp", FinanceFormat.priceChange(12.0, InstrumentKind.EQUITY, "GBp"))
    }

    @Test
    fun positionLineCountsSharesOrCoinsThenTheirWorth() {
        assertEquals(UiText.plural(Res.plurals.watchlist_shares, 1, "1"), FinanceFormat.held(1.0), "one share picks the singular")
        assertEquals(UiText.plural(Res.plurals.watchlist_shares, 1_250, "1,250"), FinanceFormat.held(1_250.0))
        assertEquals(UiText.of(Res.string.watchlist_shares_fraction, "0.5"), FinanceFormat.held(0.5), "a fraction isn't a whole count")
        assertEquals(UiText.of(Res.string.watchlist_coins, "0.25", "BTC"), FinanceFormat.held(0.25, InstrumentKind.CRYPTO, "BTC"))
        assertEquals(UiText.plural(Res.plurals.watchlist_shares, 10, "10"), FinanceFormat.positionLine(Position(10.0), price = null), "no price yet: just the holding")
        assertEquals(
            UiText.Joined(listOf(UiText.plural(Res.plurals.watchlist_shares, 10, "10"), "$2,431.20".asUiText()), UiText.of(Res.string.common_dot_separator)),
            FinanceFormat.positionLine(Position(10.0), price = 243.12),
        )
    }
}
