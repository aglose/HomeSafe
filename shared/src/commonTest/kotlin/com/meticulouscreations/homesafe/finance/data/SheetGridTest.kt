package com.meticulouscreations.homesafe.finance.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SheetGrid] is the thin read layer over the Sheets API's raw rows: a merged block's value
 * everywhere it spans, whitespace-folded label text, and the "number somewhere to the right of
 * this label" scans the parser builds everything else on.
 */
class SheetGridTest {

    private fun grid(rows: List<List<CellValue>>, merges: List<MergedRange> = emptyList()) = SheetGrid("Test", rows, merges)

    private fun text(s: String) = CellValue.Text(s)
    private fun number(v: Double) = CellValue.Number(v)

    @Test
    fun mergesReadTheTopLeftValueEverywhereInTheBlock() {
        val rows = listOf(
            listOf(text("Joint Account"), CellValue.Empty),
            listOf(CellValue.Empty, CellValue.Empty),
        )
        val g = grid(rows, listOf(MergedRange(startRow = 0, endRow = 2, startColumn = 0, endColumn = 2)))
        assertEquals(text("Joint Account"), g.value(0, 0))
        assertEquals(text("Joint Account"), g.value(0, 1))
        assertEquals(text("Joint Account"), g.value(1, 0))
        assertEquals(text("Joint Account"), g.value(1, 1))
    }

    @Test
    fun textFoldsLineBreaksAndRunsOfWhitespaceToASingleSpace() {
        val g = grid(listOf(listOf(text("Student Loans \n(APR 3.08%,   ~5 years left)"))))
        assertEquals("Student Loans (APR 3.08%, ~5 years left)", g.text(0, 0))
    }

    @Test
    fun textIsNullForBlankOrNonTextCells() {
        val g = grid(listOf(listOf(text("   "), number(1.0), CellValue.Empty)))
        assertNull(g.text(0, 0))
        assertNull(g.text(0, 1))
        assertNull(g.text(0, 2))
    }

    @Test
    fun parseLooseNumberReadsCurrencyAndPercentText() {
        assertEquals(1234000.0, SheetGrid.parseLooseNumber("$1,234,000"))
        assertEquals(-4321.65, SheetGrid.parseLooseNumber("-$4,321.65"))
        assertEquals(0.2, SheetGrid.parseLooseNumber("20.0%"))
        assertNull(SheetGrid.parseLooseNumber("garbage"))
        assertNull(SheetGrid.parseLooseNumber(""))
        assertNull(SheetGrid.parseLooseNumber("N/A"))
    }

    @Test
    fun findOnlyMatchesAMergedBlockAtItsTopLeftCell() {
        val rows = listOf(listOf(text("Header"), CellValue.Empty, text("Other")))
        val g = grid(rows, listOf(MergedRange(startRow = 0, endRow = 1, startColumn = 0, endColumn = 2)))
        assertEquals(0 to 0, g.find { it == "header" })
        // The merge's second cell carries the same value through text()/value(), but find() must
        // not treat it as a second, separate match.
        assertEquals("Header", g.text(0, 1))
        assertEquals(0 to 2, g.find { it == "other" })
    }

    @Test
    fun numberRightOfSkipsPastALabelsOwnMergeBeforeLooking() {
        val rows = listOf(listOf(text("Label"), CellValue.Empty, number(42.0)))
        val g = grid(rows, listOf(MergedRange(startRow = 0, endRow = 1, startColumn = 0, endColumn = 2)))
        assertEquals(42.0, g.numberRightOf(0, 0))
    }

    @Test
    fun numberRightOfGivesUpAfterMaxColumns() {
        val rows = listOf(listOf(text("Label"), CellValue.Empty, CellValue.Empty, number(42.0)))
        val g = grid(rows)
        assertNull(g.numberRightOf(0, 0, maxColumns = 2))
        assertEquals(42.0, g.numberRightOf(0, 0, maxColumns = 3))
    }

    @Test
    fun lastNumberRightOfPicksTheRightmostNumberInTheWindow() {
        // "Cash Deposit | 20.0% | $87,650" — the percentage is a number too, but the dollar
        // amount is what callers want.
        val rows = listOf(listOf(text("Cash Deposit"), text("20.0%"), number(87650.0)))
        val g = grid(rows)
        assertEquals(87650.0, g.lastNumberRightOf(0, 0))
    }

    @Test
    fun lastNumberRightOfFallsBackWhenTheRightmostCellIsEmpty() {
        val rows = listOf(listOf(text("Label"), text("20.0%"), CellValue.Empty))
        val g = grid(rows)
        assertEquals(0.2, g.lastNumberRightOf(0, 0))
    }
}
