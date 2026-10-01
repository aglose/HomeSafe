package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.IndicatorUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Number and date formatting for the finance screens. Common code has no `String.format`, so
 * [FinanceFormat] does its own grouping, rounding and 12-hour clock math — easy to get subtly
 * wrong at the edges, which is what these tests poke at.
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
        assertEquals("Sep 30, 2026", FinanceFormat.date(epoch, offsetSeconds = -14400))
        assertEquals("Sep 2026", FinanceFormat.monthYear(epoch, offsetSeconds = -14400))
        assertEquals("4:38 PM", FinanceFormat.time(epoch, offsetSeconds = -14400), "20:38 UTC minus 4 hours is 16:38")
        assertEquals("Sep 30, 4:38 PM", FinanceFormat.dateTime(epoch, offsetSeconds = -14400))

        // Same instant with no offset: still Sep 30, but the UTC clock reading.
        assertEquals("8:38 PM", FinanceFormat.time(epoch, offsetSeconds = 0))
        assertEquals("Sep 30, 8:38 PM", FinanceFormat.dateTime(epoch, offsetSeconds = 0))
    }

    @Test
    fun timeHandlesNoonAndMidnightOnThe12HourClock() {
        val epoch = 1725000000L // 2024-08-30 06:40:00 UTC
        assertEquals("12:10 PM", FinanceFormat.time(epoch, offsetSeconds = 19800), "local clock lands on noon:10")
        assertEquals("12:40 AM", FinanceFormat.time(epoch, offsetSeconds = -21600), "local clock lands on midnight:40")
    }

    @Test
    fun shortYearIgnoresTheOffsetAndUsesTheLastTwoDigits() {
        assertEquals("'26", FinanceFormat.shortYear(1790800692L))
    }

    @Test
    fun relativeDaysDescribesTodayAndASingleDay() {
        assertEquals("today", FinanceFormat.relativeDays(0L, 0L))
        assertEquals("in 1 day", FinanceFormat.relativeDays(0L, 86_400L))
        assertEquals("1 day ago", FinanceFormat.relativeDays(86_400L, 0L))
    }

    @Test
    fun relativeDaysCountsSmallGapsInDays() {
        assertEquals("in 3 days", FinanceFormat.relativeDays(0L, 3 * 86_400L))
    }

    @Test
    fun relativeDaysSwitchesToMonthsUnderTwoYears() {
        assertEquals("in 4 months", FinanceFormat.relativeDays(0L, 120 * 86_400L))
        assertEquals("2 months ago", FinanceFormat.relativeDays(60 * 86_400L, 0L))
    }

    @Test
    fun relativeDaysSwitchesToYearsPastTwoYears() {
        assertEquals("in 2 years", FinanceFormat.relativeDays(0L, 800 * 86_400L))
        assertEquals("2 years ago", FinanceFormat.relativeDays(800 * 86_400L, 0L))
    }

    @Test
    fun indicatorChangeIsUnchangedWhenTheValueRoundsAwayToZero() {
        // New behavior: a value that rounds to zero at the unit's own precision reads as
        // "unchanged" instead of a misleading "+0.00" or "-0.00".
        assertEquals("unchanged", FinanceFormat.indicatorChange(-0.001, IndicatorUnit.PERCENT))
        assertEquals("unchanged", FinanceFormat.indicatorChange(0.001, IndicatorUnit.PERCENT))
        assertEquals("unchanged", FinanceFormat.indicatorChange(0.4, IndicatorUnit.THOUSANDS), "THOUSANDS rounds at 0 decimals")
    }

    @Test
    fun indicatorChangeStillSignsAndSuffixesARealMove() {
        assertEquals("+1.50 pts", FinanceFormat.indicatorChange(1.5, IndicatorUnit.PERCENT))
        assertEquals("−250K", FinanceFormat.indicatorChange(-250.4, IndicatorUnit.THOUSANDS))
        assertEquals("+0.02", FinanceFormat.indicatorChange(0.015, IndicatorUnit.INDEX))
    }

    @Test
    fun agoChangesUnitAtEachThresholdAndNeverGoesNegative() {
        val now = 1_790_000_000L
        assertEquals("just now", FinanceFormat.ago(now, now))
        assertEquals("just now", FinanceFormat.ago(now, now - 59))
        assertEquals("1 min ago", FinanceFormat.ago(now, now - 60))
        assertEquals("59 min ago", FinanceFormat.ago(now, now - 3_599))
        assertEquals("1 h ago", FinanceFormat.ago(now, now - 3_600))
        assertEquals("35 h ago", FinanceFormat.ago(now, now - (36 * 3_600 - 1)))
        val then = now - 36 * 3_600
        assertEquals(FinanceFormat.date(then, FinanceFormat.localOffsetSeconds(then)), FinanceFormat.ago(now, then), "a day and a half on, the date")
        assertEquals("just now", FinanceFormat.ago(now, now + 600), "a clock a little ahead of the relay's isn't the future")
    }
}
