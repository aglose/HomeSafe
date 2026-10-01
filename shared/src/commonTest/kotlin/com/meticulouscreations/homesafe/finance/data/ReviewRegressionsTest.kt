package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.FinanceFormat
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cases the code review of the finance feature turned up, kept so they stay fixed. */
class ReviewRegressionsTest {

    private fun day(y: Int, m: Int, d: Int = 1) = LocalDate(y, m, d).toEpochDays() * Series.DAY_SECONDS

    @Test
    fun yearOverYearSkipsAPointWhoseYearAgoReadingIsMissing() {
        // A monthly index with October 2025 left blank, as FRED's CPI is after the shutdown.
        val months = (0 until 24).map { i -> (2025 + (i / 12)) to (i % 12 + 1) }.filterNot { it == (2025 to 10) }
        val series = Series.of(months.mapIndexed { i, (y, m) -> day(y, m) to 100.0 + i })
        val yoy = series.yearOverYearPercent()
        assertFalse(day(2026, 10) in yoy.times.toList(), "October 2026 has no October 2025 to compare with")
        assertTrue(day(2026, 11) in yoy.times.toList())
    }

    @Test
    fun aDebtMergedDownOverTwoRowsIsOneDebt() {
        val rows = MutableList(8) { MutableList<CellValue>(8) { CellValue.Empty } }
        rows[0][0] = CellValue.Text("Monthly Cash Flow")
        rows[1][0] = CellValue.Text("Flow In")
        rows[1][1] = CellValue.Text("Alex")
        rows[1][2] = CellValue.Text("Sam")
        rows[2][4] = CellValue.Text("Debt")
        rows[3][5] = CellValue.Text("Alex")
        rows[3][6] = CellValue.Text("Sam")
        rows[4][4] = CellValue.Text("Car loan (APR 3.4%)")
        rows[4][6] = CellValue.Number(-5_432.0)
        rows[7][4] = CellValue.Text("Grand Total")
        val merges = listOf(MergedRange(4, 6, 4, 5), MergedRange(4, 6, 6, 7))
        val finance = PersonalFinanceParser.parse("t", 0, listOf(SheetGrid("Home", rows, merges)))
        assertEquals(1, finance.debts.size)
        assertEquals(5_432.0, finance.consumerDebt)
    }

    @Test
    fun compactUnitsAreChosenAfterRounding() {
        assertEquals("$1.00M", FinanceFormat.compactMoney(999_999.0))
        assertEquals("$10K", FinanceFormat.compactMoney(9_999.6))
        assertEquals("$1.00B", FinanceFormat.compactMoney(999_999_999.0))
        assertEquals("1.00M", FinanceFormat.volume(999_960.0))
    }

    @Test
    fun aValueThatRoundsToZeroHasNoMinus() {
        assertEquals("$0.00", FinanceFormat.money(-0.001))
        assertEquals("0.00", FinanceFormat.grouped(-0.001))
        assertEquals("+0.00%", FinanceFormat.signedPercent(-0.0004))
        assertEquals("-$1.00", FinanceFormat.money(-1.0))
    }

    @Test
    fun relativeDaysRoundsAndPluralises() {
        assertEquals("in 1 day", FinanceFormat.relativeDays(0, 16 * 3600))
        assertEquals("today", FinanceFormat.relativeDays(0, 6 * 3600))
        assertEquals("in 2 months", FinanceFormat.relativeDays(0, 45 * 86_400))
        assertEquals("3 days ago", FinanceFormat.relativeDays(3 * 86_400, 0))
    }
}
