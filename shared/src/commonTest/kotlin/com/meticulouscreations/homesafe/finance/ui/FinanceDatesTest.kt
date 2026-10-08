package com.meticulouscreations.homesafe.finance.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [FinanceDates]: the dates a chart writes while it draws, from words and patterns handed to it.
 * What the app's own strings make of them is checked where they can be loaded, in the JVM's
 * `FinanceDatesEnglishTest`.
 */
class FinanceDatesTest {

    private val english = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val epoch = 1790800692L // 2026-09-30 20:38:12 UTC

    @Test
    fun aPatternIsFilledWithTheMonthAndTheYear() {
        val dates = FinanceDates(english, "%1\$s %2\$d", "%1\$s '%2\$s")
        assertEquals("Sep 2026", dates.monthYear(epoch))
        assertEquals("Sep '26", dates.monthShortYear(epoch))
        assertEquals("Jan '05", dates.monthShortYear(1104580800L), "2005 keeps its zero")
    }

    @Test
    fun theOffsetIsAppliedBeforeTheMonthIsRead() {
        val dates = FinanceDates(english, "%1\$s %2\$d", "%1\$s '%2\$s")
        val newYear = 1798763400L // 2027-01-01 00:30:00 UTC
        assertEquals("Jan 2027", dates.monthYear(newYear))
        assertEquals("Dec 2026", dates.monthYear(newYear, offsetSeconds = -18000))
    }

    @Test
    fun aTranslationRenamesTheMonthsAndReordersTheParts() {
        val months = listOf("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月")
        val dates = FinanceDates(months, "%2\$d年%1\$s", "%2\$s年%1\$s")
        assertEquals("2026年9月", dates.monthYear(epoch))
        assertEquals("26年9月", dates.monthShortYear(epoch))
    }

    @Test
    fun aPlaceholderThatNumbersNoArgumentIsLeftAsWritten() {
        assertEquals("Sep 2026 %3\$s", FinanceDates(english, "%1\$s %2\$d %3\$s", "").monthYear(epoch))
    }
}
