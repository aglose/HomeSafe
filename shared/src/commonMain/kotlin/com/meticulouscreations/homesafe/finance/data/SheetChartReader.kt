package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.ChartDomain
import com.meticulouscreations.homesafe.finance.domain.ChartStacking
import com.meticulouscreations.homesafe.finance.domain.ChartValueFormat
import com.meticulouscreations.homesafe.finance.domain.SheetChart
import com.meticulouscreations.homesafe.finance.domain.SheetChartKind
import com.meticulouscreations.homesafe.finance.domain.SheetChartSeries
import kotlin.math.roundToLong

/**
 * The budget sheet's own charts, with their data read out of the workbook the relay sent. The
 * relay passes on what Google says each chart plots — its kind, its ranges, the number format
 * of the cells — and this reads those ranges the way Sheets does:
 *
 * - A range runs down a column, or along a row when it's one row high. A chart's ranges may
 *   reach past the data (`A2:A`, or a fixed range left long for rows to come); blank rows are
 *   dropped, as are points whose x is blank.
 * - The first [RelayChart.headerCount] cells of each range are headers, the series' names. When
 *   the sheet leaves that to Google, a range that starts with words has a header and one that
 *   starts with a number doesn't — and then the label just above it (or left of it) names it.
 * - The x axis is dates when its cells are formatted as dates; without a format, when every
 *   value is a plausible date serial (1970–2099). A chart whose x axis labels none of its points
 *   (a stray one-cell range, or none set) takes the column of labels left of its data instead.
 */
object SheetChartReader {

    // 1970-01-01 and 2100-01-01 as Sheets date serials (days since 1899-12-30).
    private const val SERIAL_1970 = 25_569.0
    private const val SERIAL_2100 = 73_051.0
    private const val DAY_SECONDS = 86_400.0

    fun read(charts: List<RelayChart>, sheets: List<SheetGrid>, sourceUrl: String?): List<SheetChart> {
        val grids = sheets.associateBy { it.title }
        return charts.mapIndexed { i, chart -> read(chart, grids, sourceUrl, i) }
    }

    private fun read(chart: RelayChart, grids: Map<String, SheetGrid>, sourceUrl: String?, index: Int): SheetChart {
        val kind = kindOf(chart.kind) ?: SheetChartKind.OTHER
        val link = sourceUrl?.let { url -> chart.gid?.let { "$url#gid=$it" } ?: url }
        val base = SheetChart(
            id = chart.id ?: index.toLong(),
            tab = chart.sheet,
            title = chart.title,
            subtitle = chart.subtitle,
            kind = kind,
            stacking = when (chart.stacked) {
                "STACKED" -> ChartStacking.STACKED
                "PERCENT_STACKED" -> ChartStacking.PERCENT
                else -> ChartStacking.NONE
            },
            domain = ChartDomain.Categories(emptyList()),
            series = emptyList(),
            sourceUrl = link,
        )
        if (kind == SheetChartKind.OTHER) {
            val name = chart.kind.lowercase().replace('_', ' ').takeUnless { it == "other" }
            return base.withTitle(null).copy(issue = "${name?.let { "A $it chart" } ?: "This kind of chart"} isn't one the app draws")
        }

        // A domain plotted again as a series of its own (Sheets allows it; dates as bars) isn't data.
        val plotted = chart.series.filterNot { chart.domain.isNotEmpty() && it.ranges == chart.domain }
        val domainCells = cells(chart.domain, grids)
        val seriesCells = plotted.map { cells(it.ranges, grids) }
        val headers = (chart.headerCount ?: if (seriesCells.any { it.firstOrNull().isWords() }) 1 else 0).coerceAtLeast(0)

        val values = seriesCells.map { cells -> cells.drop(headers).map(::numberOf) }
        val plottedPoints = (0 until (values.maxOfOrNull { it.size } ?: 0)).filter { i -> values.any { it.getOrNull(i) != null } }
        // The chart's own x axis, unless it labels none of the points (a stray one-cell range, or
        // none at all); then the column of labels beside the data, as the sheet lays it out.
        val declared = domainCells.drop(headers)
        val ownAxis = chart.domain.isNotEmpty() && plottedPoints.any { !declared.getOrNull(it).isBlank() }
        val domain = if (ownAxis) declared else labelsBeside(plotted.firstOrNull()?.ranges?.firstOrNull(), headers, plottedPoints, grids)
        val length = maxOf(domain?.size ?: 0, values.maxOfOrNull { it.size } ?: 0)
        var kept = (0 until length).filter { i ->
            (domain == null || !domain.getOrNull(i).isBlank()) && values.any { it.getOrNull(i) != null }
        }
        if (kind == SheetChartKind.SCORECARD) kept = kept.take(1)

        val format = if (ownAxis) chart.domainFormat else null
        val dates = kind != SheetChartKind.PIE && kept.isNotEmpty() && domain != null && isDates(format, kept.map { domain.getOrNull(it) })
        // Dates in date order, whatever order the rows are in; then the sheet's "reverse" setting.
        if (dates) kept = kept.sortedBy { (domain[it] as CellValue.Number).value }
        if (chart.reversed) kept = kept.reversed()
        val axis = if (dates) {
            ChartDomain.Dates(kept.map { epochOfSerial((domain[it] as CellValue.Number).value) })
        } else {
            ChartDomain.Categories(kept.map { i -> if (domain == null) "${i + 1}" else labelOf(domain.getOrNull(i)) })
        }

        var series = plotted.mapIndexed { s, spec ->
            SheetChartSeries(
                label = seriesLabel(spec, seriesCells[s], headers, grids) ?: (chart.title.takeIf { plotted.size == 1 && it.isNotBlank() } ?: "Series ${s + 1}"),
                values = kept.map { values[s].getOrNull(it) },
                kind = spec.type?.let(::kindOf)?.takeUnless { it == SheetChartKind.OTHER } ?: kind,
                format = formatOf(spec.format),
                rightAxis = spec.axis.equals("RIGHT_AXIS", ignoreCase = true),
            )
        }
        var domainOut: ChartDomain = axis
        if (kind == SheetChartKind.PIE) {
            // A slice can't be negative or nothing: Sheets leaves those out too.
            val slices = series.firstOrNull()?.values.orEmpty().indices.filter { (series.first().values[it] ?: 0.0) > 0.0 }
            val labels = (axis as ChartDomain.Categories).labels
            domainOut = ChartDomain.Categories(slices.map { labels[it] })
            series = series.take(1).map { s -> s.copy(values = slices.map { s.values[it] }) }
        }
        val drawn = base.copy(domain = domainOut, series = series).withTitle(series.singleOrNull()?.label)
        return if (drawn.isDrawable) drawn else drawn.copy(issue = emptyReason(chart, grids))
    }

    /** Why a chart of a kind the app draws has nothing to draw. */
    private fun emptyReason(chart: RelayChart, grids: Map<String, SheetGrid>): String {
        val tabs = (chart.domain + chart.series.flatMap { it.ranges }).map { it.sheet }.distinct()
        val gone = tabs.filter { it !in grids }
        return when {
            chart.series.isEmpty() -> "It has no data ranges"
            gone.isNotEmpty() -> "It plots ${gone.joinToString { "“$it”" }}, which the sheet no longer has"
            else -> "The cells it plots are empty"
        }
    }

    /** A chart without a title is named after its one series, or its tab. */
    private fun SheetChart.withTitle(fallback: String?): SheetChart =
        if (title.isNotBlank()) this else copy(title = fallback?.takeIf { it.isNotBlank() } ?: "Chart on $tab")

    fun kindOf(name: String): SheetChartKind? = when (name.uppercase()) {
        "LINE" -> SheetChartKind.LINE
        "AREA" -> SheetChartKind.AREA
        "STEPPED_AREA" -> SheetChartKind.STEPPED_AREA
        "COLUMN" -> SheetChartKind.COLUMN
        "BAR" -> SheetChartKind.BAR
        "COMBO" -> SheetChartKind.COMBO
        "SCATTER" -> SheetChartKind.SCATTER
        "PIE" -> SheetChartKind.PIE
        "SCORECARD" -> SheetChartKind.SCORECARD
        else -> null
    }

    /** Every cell of [ranges], in order: down each column of a range, or along it when it's one row high. */
    private fun cells(ranges: List<RelayRange>, grids: Map<String, SheetGrid>): List<CellValue> = ranges.flatMap { r ->
        val grid = grids[r.sheet] ?: return@flatMap emptyList()
        val endRow = minOf(r.endRow ?: grid.rowCount, grid.rowCount)
        val endColumn = r.endColumn ?: (0 until grid.rowCount).maxOfOrNull { grid.columnCount(it) } ?: 0
        if (endRow <= r.startRow || endColumn <= r.startColumn) return@flatMap emptyList()
        val rows = r.startRow until endRow
        val columns = r.startColumn until endColumn

        // A merged block's value is in its first cell; Sheets charts see the rest as blank.
        fun cell(row: Int, column: Int) = if (grid.isMergeTail(row, column)) CellValue.Empty else grid.value(row, column)
        if (r.endRow == r.startRow + 1 && columns.count() > 1) {
            columns.map { cell(r.startRow, it) }
        } else {
            columns.flatMap { c -> rows.map { cell(it, c) } }
        }
    }

    /**
     * The labels in the column left of a column of data ("Year | Income"), one per value after the
     * headers, when every plotted point has one; null otherwise.
     */
    private fun labelsBeside(range: RelayRange?, headers: Int, points: List<Int>, grids: Map<String, SheetGrid>): List<CellValue>? {
        if (range == null || range.startColumn == 0 || range.endColumn != range.startColumn + 1 || points.isEmpty()) return null
        val grid = grids[range.sheet] ?: return null
        val labels = (0..points.last()).map { grid.value(range.startRow + headers + it, range.startColumn - 1) }
        return labels.takeIf { all -> points.all { !all[it].isBlank() } }
    }

    /** The series' name: its header cell, or the label just before its range. */
    private fun seriesLabel(series: RelayChartSeries, cells: List<CellValue>, headers: Int, grids: Map<String, SheetGrid>): String? {
        if (headers > 0) return (cells.firstOrNull() as? CellValue.Text)?.text?.let(SheetGrid::normalise)?.takeIf { it.isNotEmpty() }
        val r = series.ranges.firstOrNull() ?: return null
        val grid = grids[r.sheet] ?: return null
        val alongRow = r.endRow == r.startRow + 1 && (r.endColumn ?: Int.MAX_VALUE) - r.startColumn > 1
        val (row, column) = if (alongRow) r.startRow to r.startColumn - 1 else r.startRow - 1 to r.startColumn
        if (row < 0 || column < 0) return null
        return grid.text(row, column)?.takeIf { SheetGrid.parseLooseNumber(it) == null }
    }

    private fun isDates(format: RelayNumberFormat?, cells: List<CellValue?>): Boolean = when (format?.type?.uppercase()) {
        "DATE", "DATE_TIME" -> cells.all { it is CellValue.Number }
        null, "" -> cells.all { it is CellValue.Number && it.value >= SERIAL_1970 && it.value < SERIAL_2100 }
        else -> false
    }

    private fun epochOfSerial(serial: Double): Long = ((serial - SERIAL_1970) * DAY_SECONDS).roundToLong()

    internal fun formatOf(format: RelayNumberFormat?): ChartValueFormat {
        val pattern = format?.pattern.orEmpty()
        return when {
            format == null -> ChartValueFormat.NUMBER
            format.type.equals("CURRENCY", ignoreCase = true) || pattern.any { it in "$€£¥" } -> ChartValueFormat.MONEY
            format.type.equals("PERCENT", ignoreCase = true) || '%' in pattern -> ChartValueFormat.PERCENT
            else -> ChartValueFormat.NUMBER
        }
    }

    private fun numberOf(cell: CellValue): Double? = when (cell) {
        is CellValue.Number -> cell.value
        is CellValue.Text -> SheetGrid.parseLooseNumber(cell.text)
        else -> null
    }

    private fun labelOf(cell: CellValue?): String = when (cell) {
        is CellValue.Text -> SheetGrid.normalise(cell.text)
        is CellValue.Number -> if (cell.value == cell.value.roundToLong().toDouble()) cell.value.roundToLong().toString() else cell.value.toString()
        is CellValue.Bool -> if (cell.value) "TRUE" else "FALSE"
        else -> ""
    }

    private fun CellValue?.isWords(): Boolean = this is CellValue.Text && text.isNotBlank() && SheetGrid.parseLooseNumber(text) == null

    private fun CellValue?.isBlank(): Boolean = when (this) {
        null, CellValue.Empty -> true
        is CellValue.Text -> text.isBlank()
        else -> false
    }
}
