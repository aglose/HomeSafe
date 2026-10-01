package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable

/** How a chart in the budget sheet draws its data, as Google Sheets names the kinds the app draws itself. */
enum class SheetChartKind {
    LINE,
    AREA,
    STEPPED_AREA,
    COLUMN,

    /** Horizontal bars in the sheet; drawn upright on a phone, like [COLUMN]. */
    BAR,

    /** Series of different kinds on one chart; drawn as lines. */
    COMBO,
    SCATTER,
    PIE,

    /** One big number. */
    SCORECARD,

    /** A kind the app doesn't draw (a waterfall, a treemap…): its card links to the sheet instead. */
    OTHER,
    ;

    val isBars: Boolean get() = this == COLUMN || this == BAR
}

/** Whether series sit on top of one another, as the sheet's "Stacking" setting has it. */
enum class ChartStacking { NONE, STACKED, PERCENT }

/** How a series' numbers are written, from the number format of the cells it plots. */
enum class ChartValueFormat { MONEY, PERCENT, NUMBER }

/** What runs along a chart's x axis (a pie's slices): dates, or labels in the sheet's order. */
@Immutable
sealed interface ChartDomain {
    val size: Int

    @Immutable
    data class Dates(val epochSeconds: List<Long>) : ChartDomain {
        override val size: Int get() = epochSeconds.size
    }

    @Immutable
    data class Categories(val labels: List<String>) : ChartDomain {
        override val size: Int get() = labels.size
    }
}

/** One plotted series: a value (or a gap) for each point of the chart's [ChartDomain]. */
@Immutable
data class SheetChartSeries(
    val label: String,
    val values: List<Double?>,
    val kind: SheetChartKind,
    val format: ChartValueFormat,
)

/**
 * A chart from the budget sheet, with its data read out of the sheet's cells. The sheet decides
 * what's charted and how; the app draws it in its own look (see `SheetChartReader`).
 */
@Immutable
data class SheetChart(
    val id: Long,
    /** The tab it sits on. */
    val tab: String,
    val title: String,
    val subtitle: String,
    val kind: SheetChartKind,
    val stacking: ChartStacking,
    val domain: ChartDomain,
    val series: List<SheetChartSeries>,
    /** The chart's tab in Google Sheets, when the relay gave the sheet's address. */
    val sourceUrl: String?,
) {
    /** Whether there's anything to draw: a kind the app draws, and a value somewhere. */
    val isDrawable: Boolean get() = kind != SheetChartKind.OTHER && series.any { s -> s.values.any { it != null } }
}
