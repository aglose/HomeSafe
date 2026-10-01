package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.ChartDomain
import com.meticulouscreations.homesafe.finance.domain.ChartStacking
import com.meticulouscreations.homesafe.finance.domain.ChartValueFormat
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SheetChart
import com.meticulouscreations.homesafe.finance.domain.SheetChartKind
import com.meticulouscreations.homesafe.finance.ui.components.BarSeries
import com.meticulouscreations.homesafe.finance.ui.components.ChartAxis
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.DonutChart
import com.meticulouscreations.homesafe.finance.ui.components.DonutSlice
import com.meticulouscreations.homesafe.finance.ui.components.GroupedBarChart
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.finance.ui.components.RollingNumber
import kotlin.math.abs

/**
 * One of the budget sheet's own charts, drawn the finance app's way: the headline is the value
 * at the latest point (or under the finger), with its change since the first, and a legend for
 * each series when there's more than one. Lines and areas scrub, bars select, pies highlight.
 */
@Composable
internal fun SheetChartCard(chart: SheetChart, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val uriHandler = LocalUriHandler.current
    var selected by remember(chart) { mutableStateOf<Int?>(null) }
    FinanceCard(modifier, padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(chart.title, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(chart.subtitle, chart.tab).filter { it.isNotBlank() }.joinToString(" · "),
                    style = FinanceTheme.type.micro,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            chart.sourceUrl?.let { url ->
                Text(
                    "Sheet ↗",
                    style = FinanceTheme.type.label,
                    color = colors.accent,
                    modifier = Modifier.clip(CircleShape).clickable { uriHandler.openUri(url) }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (!chart.isDrawable) {
            Text(
                if (chart.kind == SheetChartKind.OTHER) "A kind of chart the app doesn't draw. Open it in the sheet." else "Nothing in its range yet.",
                style = FinanceTheme.type.label,
                color = colors.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
            return@FinanceCard
        }
        val palette = colors.categorical
        when {
            chart.kind == SheetChartKind.PIE -> PieBody(chart, selected) { selected = if (selected == it) null else it }

            chart.kind == SheetChartKind.SCORECARD -> Headline(chart, selected = null)

            chart.kind.isBars && chart.series.all { it.kind.isBars } -> {
                Headline(chart, selected)
                GroupedBarChart(
                    labels = pointLabels(chart, short = true),
                    // One series reads as up and down, in the app's green and red; several take the categorical colours.
                    series = if (chart.series.size == 1) {
                        listOf(BarSeries(chart.series.single().values, colors.gain, colors.loss))
                    } else {
                        chart.series.mapIndexed { i, s -> BarSeries(s.values, palette[i % palette.size]) }
                    },
                    stacked = chart.stacking != ChartStacking.NONE,
                    selected = selected,
                    contentDescription = chart.title,
                    onSelect = { selected = it },
                    modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 16.dp),
                )
                SeriesLegend(chart, selected)
            }

            else -> LineBody(chart, selected) { selected = it }
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** The lines (or stacked areas) of a line, area, combo or scatter chart, scrubbable. */
@Composable
private fun LineBody(chart: SheetChart, selected: Int?, onSelect: (Int?) -> Unit) {
    val palette = FinanceTheme.colors.categorical
    val plot = remember(chart) { linePlot(chart) }
    Column {
        Headline(chart, selected)
        LineChart(
            lines = plot.lines.mapIndexed { i, s ->
                val series = plot.order[i]
                ChartLine(
                    series = s,
                    color = palette[series % palette.size],
                    fill = chart.kind == SheetChartKind.AREA || chart.kind == SheetChartKind.STEPPED_AREA || chart.stacking != ChartStacking.NONE,
                    width = if (i == 0) 3f else 2f,
                )
            },
            timeAxis = chart.domain is ChartDomain.Dates,
            axis = ChartAxis(
                formatValue = { v -> if (chart.stacking == ChartStacking.PERCENT) FinanceFormat.percent(v, 0) else formatValue(v, chart.series.first().format, compact = true) },
                formatTime = { t ->
                    when (val d = chart.domain) {
                        is ChartDomain.Dates -> shortMonthYear(t)
                        is ChartDomain.Categories -> d.labels.getOrNull(t.toInt()).orEmpty()
                    }
                },
            ),
            contentDescription = chart.title,
            onScrub = { i -> onSelect(i?.let { plot.points.getOrNull(it) }) },
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        SeriesLegend(chart, selected)
    }
}

/**
 * A chart's lines as the [LineChart] takes them: the scrubbed line first, then the rest. Stacked
 * series are drawn as running totals (each line the top of its band), the total first; a
 * percent stack as each band's share of the total.
 */
private class LinePlot(val lines: List<Series>, val order: List<Int>, val points: IntArray)

private fun linePlot(chart: SheetChart): LinePlot {
    val n = chart.domain.size
    val count = chart.series.size
    val drawn: List<List<Double?>> = if (chart.stacking == ChartStacking.NONE || count < 2) {
        chart.series.map { it.values }
    } else {
        val running = chart.series.indices.map { s ->
            (0 until n).map { i ->
                if (chart.series.all { it.values.getOrNull(i) == null }) null else (0..s).sumOf { chart.series[it].values.getOrNull(i) ?: 0.0 }
            }
        }
        if (chart.stacking == ChartStacking.PERCENT) {
            running.map { line -> line.mapIndexed { i, v -> v?.let { total -> running.last()[i]?.takeIf { it != 0.0 }?.let { total / it * 100 } } } }
        } else {
            running
        }
    }
    // The total leads a stack; otherwise the sheet's first series does.
    val order = if (chart.stacking != ChartStacking.NONE && count > 1) chart.series.indices.reversed().toList() else chart.series.indices.toList()
    val x: (Int) -> Long = when (val d = chart.domain) {
        is ChartDomain.Dates -> { i -> d.epochSeconds[i] }
        is ChartDomain.Categories -> { i -> i.toLong() }
    }
    val lines = order.map { s -> Series.of((0 until n).mapNotNull { i -> drawn[s].getOrNull(i)?.let { x(i) to it } }) }
    val points = (0 until n).filter { drawn[order.first()].getOrNull(it) != null }.toIntArray()
    return LinePlot(lines, order, points)
}

/** The value at the selected point — or the latest — big, with its change since the chart's first point. */
@Composable
private fun Headline(chart: SheetChart, selected: Int?) {
    val colors = FinanceTheme.colors
    val format = chart.series.first().format
    val stackTotal = chart.stacking != ChartStacking.NONE && chart.series.size > 1
    fun valueAt(i: Int): Double? = if (stackTotal) {
        chart.series.mapNotNull { it.values.getOrNull(i) }.takeIf { it.isNotEmpty() }?.sum()
    } else {
        chart.series.first().values.getOrNull(i)
    }
    val indices = (0 until chart.domain.size).filter { valueAt(it) != null }
    val point = selected?.takeIf { valueAt(it) != null } ?: indices.lastOrNull() ?: return
    val value = valueAt(point) ?: return
    val first = indices.first()
    val change = if (point != first) valueAt(first)?.let { value - it } else null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        RollingNumber(formatValue(value, format, compact = false), FinanceTheme.type.title, colors.textPrimary)
        val pointText = pointLabel(chart, point, short = false)
        val caption = when {
            chart.kind == SheetChartKind.SCORECARD -> chart.series.first().label.takeIf { it != chart.title }.orEmpty()
            change == null -> pointText
            selected != null -> "${formatChange(change, format)}  $pointText"
            else -> "${formatChange(change, format)} since ${pointLabel(chart, first, short = false)}"
        }
        if (caption.isNotEmpty()) {
            Text(
                (if (stackTotal && selected == null && change != null) "Total · " else "") + caption,
                style = FinanceTheme.type.label,
                color = when {
                    change == null || change == 0.0 -> colors.textSecondary
                    else -> colors.direction(change)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Each series' name and its value at the selected (or latest) point, when there's more than one. */
@Composable
private fun SeriesLegend(chart: SheetChart, selected: Int?) {
    if (chart.series.size < 2) return
    val palette = FinanceTheme.colors.categorical
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chart.series.forEachIndexed { i, s ->
            val at = selected ?: s.values.indexOfLast { it != null }
            LegendDot(s.label, s.values.getOrNull(at)?.let { formatValue(it, s.format, compact = true) } ?: "—", palette[i % palette.size])
        }
    }
}

@Composable
private fun LegendDot(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        Text(value, style = FinanceTheme.type.label, color = FinanceTheme.colors.textPrimary)
    }
}

/** A donut of the slices, with a row per slice: its value and share. Tapping a row lifts its slice. */
@Composable
private fun PieBody(chart: SheetChart, selected: Int?, onSelect: (Int) -> Unit) {
    val colors = FinanceTheme.colors
    val palette = colors.categorical
    val series = chart.series.first()
    val labels = (chart.domain as? ChartDomain.Categories)?.labels.orEmpty()
    val total = series.values.sumOf { it ?: 0.0 }
    Column {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            DonutChart(
                slices = series.values.mapIndexed { i, v -> DonutSlice(labels.getOrElse(i) { "" }, v ?: 0.0, palette[i % palette.size]) },
                highlighted = selected,
                modifier = Modifier.size(180.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val shown = selected?.let { series.values.getOrNull(it) } ?: total
                RollingNumber(formatValue(shown, series.format, compact = true), FinanceTheme.type.bodyStrong, colors.textPrimary)
                Text(selected?.let { labels.getOrNull(it) } ?: "Total", style = FinanceTheme.type.micro, color = colors.textSecondary, maxLines = 1)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            series.values.forEachIndexed { i, v ->
                Row(
                    Modifier.fillMaxWidth().clip(CircleShape).clickable { onSelect(i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(palette[i % palette.size].copy(alpha = if (selected == null || selected == i) 1f else 0.35f)))
                    Spacer(Modifier.width(10.dp))
                    Text(labels.getOrElse(i) { "" }, style = FinanceTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatValue(v ?: 0.0, series.format, compact = false), style = FinanceTheme.type.label, color = colors.textPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (total > 0) FinanceFormat.fractionPercent((v ?: 0.0) / total, 0) else "",
                        style = FinanceTheme.type.label,
                        color = colors.textTertiary,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }
        }
    }
}

private fun pointLabels(chart: SheetChart, short: Boolean): List<String> = (0 until chart.domain.size).map { pointLabel(chart, it, short) }

/** The x of point [i]: its date ("Nov 2019", or "Nov '19" when [short]) or the sheet's label. */
internal fun pointLabel(chart: SheetChart, i: Int, short: Boolean): String = when (val d = chart.domain) {
    is ChartDomain.Dates -> d.epochSeconds.getOrNull(i)?.let { if (short) shortMonthYear(it) else FinanceFormat.monthYear(it) }.orEmpty()
    is ChartDomain.Categories -> d.labels.getOrNull(i).orEmpty()
}

/** "Nov '19". */
private fun shortMonthYear(epochSeconds: Long): String = FinanceFormat.monthYear(epochSeconds).substringBefore(' ') + " " + FinanceFormat.shortYear(epochSeconds)

/** A value the way its cells are formatted: money, a percentage (a fraction in the sheet), or a plain number. */
internal fun formatValue(value: Double, format: ChartValueFormat, compact: Boolean): String = when (format) {
    ChartValueFormat.MONEY -> if (compact) FinanceFormat.compactMoney(value) else FinanceFormat.money(value, 0)

    ChartValueFormat.PERCENT -> FinanceFormat.fractionPercent(value, if (compact) 0 else 1)

    ChartValueFormat.NUMBER -> when {
        compact -> FinanceFormat.compactMoney(value).replace("$", "")
        abs(value) < 100 && value != value.toLong().toDouble() -> FinanceFormat.grouped(value, 2)
        else -> FinanceFormat.grouped(value, 0)
    }
}

internal fun formatChange(change: Double, format: ChartValueFormat): String = when (format) {
    ChartValueFormat.MONEY -> FinanceFormat.signedMoney(change, 0)
    ChartValueFormat.PERCENT -> FinanceFormat.signedPercent(change * 100, 1).removeSuffix("%") + " pts"
    ChartValueFormat.NUMBER -> (if (change < 0) "-" else "+") + formatValue(abs(change), format, compact = false)
}
