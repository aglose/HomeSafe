package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.text.load
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_date_month_short_year
import homesafe.shared.generated.resources.finance_date_month_year
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the finance dates read as in English, with the strings file actually loaded (the desktop
 * can; common tests only see which resource a date is built from). Also that [FinanceDates],
 * which fills the patterns itself for a chart's axis, writes what the resource formatter does.
 */
class FinanceDatesEnglishTest {

    private val epoch = 1790800692L // 2026-09-30 20:38:12 UTC

    @Test
    fun datesAndTimesReadAsTheyDidBeforeTheyWereResources() = runBlocking {
        assertEquals("Sep 30, 2026", FinanceFormat.date(epoch, offsetSeconds = -14400).load())
        assertEquals("Sep 2026", FinanceFormat.monthYear(epoch).load())
        assertEquals("4:38 PM", FinanceFormat.time(epoch, offsetSeconds = -14400).load())
        assertEquals("Sep 30, 4:38 PM", FinanceFormat.dateTime(epoch, offsetSeconds = -14400).load())
        assertEquals("'26", FinanceFormat.shortYear(epoch).load())
        assertEquals("Oct 7", FinanceFormat.dayOfMonth("2026-10-07").load())
        assertEquals("Oct 22", FinanceFormat.dayOfMonth("2026-10", 22).load())
        assertEquals("Oct", FinanceFormat.month("2026-10").load())
        assertEquals("Oct 2026", FinanceFormat.month("2026-10", year = true).load())
    }

    @Test
    fun everyMonthHasItsOwnName() = runBlocking {
        assertEquals(
            listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
            FinanceFormat.MONTHS.map { getString(it) },
        )
    }

    @Test
    fun anAxisDateMatchesTheSameDateFromTheResourceFormatter() = runBlocking {
        val dates = FinanceDates(FinanceFormat.MONTHS.map { getString(it) }, getString(Res.string.finance_date_month_year), getString(Res.string.finance_date_month_short_year))
        assertEquals(FinanceFormat.monthYear(epoch).load(), dates.monthYear(epoch))
        assertEquals("Sep '26", dates.monthShortYear(epoch))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aCompositionReadsTheSameWordsForAnAxis() = runComposeUiTest {
        var dates: FinanceDates? = null
        setContent { dates = rememberFinanceDates() }
        waitForIdle()
        assertEquals("Sep 2026", dates?.monthYear(epoch))
        assertEquals("Sep '26", dates?.monthShortYear(epoch))
    }
}
