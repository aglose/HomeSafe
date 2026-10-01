package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.ChartDomain
import com.meticulouscreations.homesafe.finance.domain.ChartStacking
import com.meticulouscreations.homesafe.finance.domain.ChartValueFormat
import com.meticulouscreations.homesafe.finance.domain.SheetChart
import com.meticulouscreations.homesafe.finance.domain.SheetChartKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [SheetChartReader] reads each chart's ranges out of the workbook the way Google Sheets does.
 * The workbooks here are relay answers as JSON, so the relay's chart fields are decoded too. The
 * numbers are made up.
 */
class SheetChartReaderTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun read(body: String): List<SheetChart> {
        val wb = json.decodeFromString(RelayWorkbook.serializer(), body)
        return SheetChartReader.read(wb.charts, wb.grids(), wb.url)
    }

    // A history table like the budget's: a header row, then dated rows, the last two blank.
    // Serial 45000 is 2023-03-15; 45100 is 2023-06-23; 45200 is 2023-10-01.
    private val history = """
        {"title": "Home", "values": [
          [],
          ["Date", "Total Assets", "Change", "Debt"],
          [45100, 220000, 20000, -61000],
          [45000, 200000, "", -64000],
          [45200, 250000, 30000, -58000],
          [""],
          []
        ]}
    """

    @Test
    fun anAreaOverDatesReadsDownItsColumnSortedAndNamedFromTheHeaderAbove() {
        val chart = read(
            """
            {"url": "https://docs.google.com/spreadsheets/d/x/edit", "sheets": [$history], "charts": [
              {"id": 7, "sheet": "Home", "gid": 12, "title": "", "kind": "AREA",
               "domain": [{"sheet": "Home", "start_row": 2, "start_column": 0, "end_column": 1}],
               "domain_format": {"type": "DATE", "pattern": "mmm-yy"},
               "series": [{"ranges": [{"sheet": "Home", "start_row": 2, "start_column": 1, "end_column": 2}], "format": {"type": "CURRENCY"}}]}
            ]}
            """,
        ).single()
        assertEquals(SheetChartKind.AREA, chart.kind)
        val dates = assertIs<ChartDomain.Dates>(chart.domain)
        assertEquals(listOf(1_678_838_400L, 1_687_478_400L, 1_696_118_400L), dates.epochSeconds, "sorted by date, blank rows dropped")
        assertEquals(listOf(200_000.0, 220_000.0, 250_000.0), chart.series.single().values)
        assertEquals("Total Assets", chart.series.single().label, "no header count and a number first: the label above names it")
        assertEquals("Total Assets", chart.title, "an untitled chart takes its one series' name")
        assertEquals(ChartValueFormat.MONEY, chart.series.single().format)
        assertEquals("https://docs.google.com/spreadsheets/d/x/edit#gid=12", chart.sourceUrl)
        assertTrue(chart.isDrawable)
    }

    @Test
    fun withoutAFormatDateSerialsAreStillDatesButYearsAreNot() {
        val charts = read(
            """
            {"sheets": [$history, {"title": "Forecasts", "values": [["Year", "Income"], [2018, 100], [2019, 110]]}], "charts": [
              {"sheet": "Home", "title": "Change", "kind": "LINE",
               "domain": [{"sheet": "Home", "start_row": 2, "end_row": 7, "start_column": 0, "end_column": 1}],
               "series": [{"ranges": [{"sheet": "Home", "start_row": 2, "end_row": 7, "start_column": 2, "end_column": 3}]}]},
              {"sheet": "Forecasts", "title": "Income", "kind": "COLUMN",
               "domain": [{"sheet": "Forecasts", "start_row": 0, "end_row": 3, "start_column": 0, "end_column": 1}],
               "series": [{"ranges": [{"sheet": "Forecasts", "start_row": 0, "end_row": 3, "start_column": 1, "end_column": 2}]}]}
            ]}
            """,
        )
        val change = charts[0]
        assertIs<ChartDomain.Dates>(change.domain)
        assertEquals(listOf(20_000.0, 30_000.0), change.series.single().values, "a row whose value is blank is no point")
        assertEquals(ChartValueFormat.NUMBER, change.series.single().format)
        val income = charts[1]
        assertEquals(ChartDomain.Categories(listOf("2018", "2019")), income.domain, "a header row of words is the series' name, not a point")
        assertEquals("Income", income.series.single().label)
        assertEquals(listOf(100.0, 110.0), income.series.single().values)
    }

    @Test
    fun theDomainPlottedAgainAsASeriesIsLeftOutAndAHeaderCountIsHonoured() {
        val domain = """[{"sheet": "Home", "start_row": 1, "end_row": 5, "start_column": 0, "end_column": 1}]"""
        val chart = read(
            """
            {"sheets": [$history], "charts": [
              {"sheet": "Home", "title": "Total Debt", "kind": "COLUMN", "header_count": 1, "domain": $domain,
               "series": [{"ranges": [{"sheet": "Home", "start_row": 1, "end_row": 5, "start_column": 3, "end_column": 4}]}, {"ranges": $domain}]}
            ]}
            """,
        ).single()
        assertEquals(1, chart.series.size)
        assertEquals("Debt", chart.series.single().label)
        assertEquals(listOf(-64_000.0, -61_000.0, -58_000.0), chart.series.single().values)
    }

    @Test
    fun stackingAndEachSeriesOwnKindOnACombo() {
        val chart = read(
            """
            {"sheets": [{"title": "F", "values": [["Year", "Taxes", "Take home"], [2024, 30, 70], [2025, 32, 75]]}], "charts": [
              {"sheet": "F", "title": "Income", "kind": "COMBO", "stacked": "STACKED", "reversed": true,
               "domain": [{"sheet": "F", "start_row": 0, "end_row": 3, "start_column": 0, "end_column": 1}],
               "series": [
                 {"ranges": [{"sheet": "F", "start_row": 0, "end_row": 3, "start_column": 1, "end_column": 2}], "type": "COLUMN", "format": {"type": "NUMBER", "pattern": "$#,##0"}},
                 {"ranges": [{"sheet": "F", "start_row": 0, "end_row": 3, "start_column": 2, "end_column": 3}], "type": "LINE", "axis": "RIGHT_AXIS", "format": {"type": "PERCENT"}}
               ]}
            ]}
            """,
        ).single()
        assertEquals(ChartStacking.STACKED, chart.stacking)
        assertEquals(listOf(SheetChartKind.COLUMN, SheetChartKind.LINE), chart.series.map { it.kind })
        assertEquals(listOf(ChartValueFormat.MONEY, ChartValueFormat.PERCENT), chart.series.map { it.format })
        assertEquals(ChartDomain.Categories(listOf("2025", "2024")), chart.domain, "a reversed axis runs backwards")
        assertEquals(listOf(75.0, 70.0), chart.series[1].values)
        assertEquals(listOf(false, true), chart.series.map { it.rightAxis })
        assertTrue(chart.isDualAxis)
    }

    @Test
    fun aReversedDateAxisRunsNewestFirstButReadsOldestToNewest() {
        val chart = read(
            """
            {"sheets": [$history], "charts": [
              {"sheet": "Home", "title": "Assets", "kind": "COLUMN", "reversed": true,
               "domain": [{"sheet": "Home", "start_row": 2, "end_row": 5, "start_column": 0, "end_column": 1}],
               "domain_format": {"type": "DATE"},
               "series": [{"ranges": [{"sheet": "Home", "start_row": 2, "end_row": 5, "start_column": 1, "end_column": 2}]}]}
            ]}
            """,
        ).single()
        val dates = assertIs<ChartDomain.Dates>(chart.domain)
        assertEquals(listOf(1_696_118_400L, 1_687_478_400L, 1_678_838_400L), dates.epochSeconds)
        assertEquals(listOf(250_000.0, 220_000.0, 200_000.0), chart.series.single().values)
        assertEquals(listOf(2, 1, 0), chart.chronological, "oldest to newest, for the headline's change")
        assertFalse(chart.isDualAxis)
    }

    @Test
    fun aRowOrientedPieDropsEmptySlicesAndMergedTails() {
        val chart = read(
            """
            {"sheets": [{"title": "P", "values": [["Rent", "Food", "Fun", "Gift"], [2000, 600, 0, 50]],
                         "merges": [{"start_row": 1, "end_row": 2, "start_column": 2, "end_column": 4}]}], "charts": [
              {"sheet": "P", "title": "Spending", "kind": "PIE",
               "domain": [{"sheet": "P", "start_row": 0, "end_row": 1, "start_column": 0, "end_column": 4}],
               "series": [{"ranges": [{"sheet": "P", "start_row": 1, "end_row": 2, "start_column": 0, "end_column": 4}]}]}
            ]}
            """,
        ).single()
        // "Fun" is zero and "Gift" is the tail of a merge that starts at "Fun": both left out.
        assertEquals(ChartDomain.Categories(listOf("Rent", "Food")), chart.domain)
        assertEquals(listOf(2000.0, 600.0), chart.series.single().values)
    }

    @Test
    fun anAxisThatLabelsNothingGivesWayToTheLabelsBesideTheData() {
        // Like the budget's income charts: one points its x axis at a stray blank cell, the other has none.
        val forecasts = """{"title": "F", "values": [[], ["", "Year", "Income"], ["", 2018, 100], ["", 2019, 110], ["", "", ""]]}"""
        val series = """[{"ranges": [{"sheet": "F", "start_row": 2, "end_row": 5, "start_column": 2, "end_column": 3}]}]"""
        val charts = read(
            """
            {"sheets": [$forecasts], "charts": [
              {"sheet": "F", "title": "Stray", "kind": "COLUMN", "header_count": 0,
               "domain": [{"sheet": "F", "start_row": 0, "end_row": 1, "start_column": 0, "end_column": 1}], "series": $series},
              {"sheet": "F", "title": "None", "kind": "COLUMN", "header_count": 0, "series": $series},
              {"sheet": "F", "title": "Unlabelled", "kind": "COLUMN", "header_count": 0,
               "series": [{"ranges": [{"sheet": "F", "start_row": 2, "end_row": 5, "start_column": 0, "end_column": 1}]}]}
            ]}
            """,
        )
        assertEquals(ChartDomain.Categories(listOf("2018", "2019")), charts[0].domain)
        assertEquals(listOf(100.0, 110.0), charts[0].series.single().values)
        assertEquals(ChartDomain.Categories(listOf("2018", "2019")), charts[1].domain)
        assertEquals("Income", charts[1].series.single().label)
        assertFalse(charts[2].isDrawable, "a blank column with nothing beside it has no points")
    }

    @Test
    fun aScorecardIsItsFirstValue() {
        val chart = read(
            """
            {"sheets": [{"title": "S", "values": [["", 1234.5], ["", 99]]}], "charts": [
              {"sheet": "S", "title": "Net", "kind": "SCORECARD", "series": [{"ranges": [{"sheet": "S", "start_row": 0, "end_row": 2, "start_column": 1, "end_column": 2}]}]}
            ]}
            """,
        ).single()
        assertEquals(listOf(1234.5), chart.series.single().values)
    }

    @Test
    fun kindsTheAppDoesNotDrawAndEmptyRangesAreKeptButNotDrawable() {
        val charts = read(
            """
            {"url": "https://docs.google.com/spreadsheets/d/x/edit", "sheets": [{"title": "T", "values": [["a"]]}], "charts": [
              {"sheet": "T", "kind": "WATERFALL"},
              {"sheet": "T", "title": "Nothing", "kind": "LINE", "series": [{"ranges": [{"sheet": "T", "start_row": 4, "end_row": 9, "start_column": 0, "end_column": 1}]}]},
              {"sheet": "Gone", "title": "Elsewhere", "kind": "LINE", "series": [{"ranges": [{"sheet": "Gone", "start_row": 0, "end_row": 3}]}]}
            ]}
            """,
        )
        assertEquals(SheetChartKind.OTHER, charts[0].kind)
        assertEquals("Chart on T", charts[0].title)
        assertEquals("https://docs.google.com/spreadsheets/d/x/edit", charts[0].sourceUrl, "no tab id: the sheet itself")
        assertTrue(charts.none { it.isDrawable })
    }

    @Test
    fun anOlderRelayWithoutChartsReadsAsNone() {
        val wb = json.decodeFromString(RelayWorkbook.serializer(), """{"title": "B", "sheets": []}""")
        assertTrue(wb.charts.isEmpty())
        assertFalse(SheetChartReader.read(wb.charts, wb.grids(), null).any())
    }
}
