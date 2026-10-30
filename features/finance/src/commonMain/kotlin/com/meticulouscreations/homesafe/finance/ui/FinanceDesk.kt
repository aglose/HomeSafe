// The desk's locals are the finance theme's own, as its palette is: every block reads them and none passes them.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.handCursor

/*
 * The desk: the finance app laid out for a laptop's or a desktop's window, or a tablet on its side.
 * The phone's pages are one column read top to bottom; a desk's are dashboards, with the headline
 * and its chart beside what explains it, and the rest in panels side by side, so a wide window
 * shows a page's worth at once rather than the phone's column with black either side of it.
 *
 * Every block is the phone's own: a block keeps [PageGutter] from the edge of what holds it, and a
 * [DeskPanel] sets that gutter for what it holds, as the phone's page does, while a block standing
 * in a desk's column has none (the page keeps the margin). A [FinanceCard] in a panel lies flat on
 * it rather than drawing a card in a card.
 */

/** Whether the finance pages are laid out as a desk's dashboards rather than the phone's column. */
internal val LocalFinanceDesk = compositionLocalOf { false }

/** What [PageGutter] reads: the phone's page edge by default. */
internal val LocalPageGutter = compositionLocalOf { 20.dp }

/** Whether a block is inside a [DeskPanel], which is the card there. */
internal val LocalInPanel = compositionLocalOf { false }

/**
 * The page width from which the tabs are dashboards: room for a main column and a rail beside it,
 * each at a width it reads well at. Narrower, a watchlist row in the rail cuts its names short and
 * five index cards across clip their prices, so the phone's column, centred, reads better.
 */
internal val DESK_MIN_WIDTH = 1040.dp

/** The main column's share beside a rail's one: the rail never narrower than a watchlist row needs (about 390dp at [DESK_MIN_WIDTH]). */
internal const val DESK_MAIN_WEIGHT = 1.6f

/** How wide a dashboard grows before it centres: past this, a panel's words and a chart's ends are too far apart to read across. */
internal val DESK_MAX_WIDTH = 1360.dp

/** The margin a dashboard keeps from the window's edges (or the sidebar's). */
internal val DESK_MARGIN = 32.dp

/** The space between a dashboard's panels, across and down. */
internal val DeskGap = 20.dp

/** How tall a dashboard's headline chart is: the phone's is a thumb's reach, a desk's has room for the history in it. */
internal val DESK_HERO_CHART = 320.dp

private val PanelShape = RoundedCornerShape(22.dp)

/**
 * A dashboard's page: its blocks stand in columns with no gutter of their own. Wrapped around a
 * desk page's list, inside the padding that centres it.
 */
@Composable
internal fun DeskPage(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageGutter provides 0.dp, LocalInPanel provides false, content = content)
}

/**
 * A raised panel of a dashboard, with an optional heading the way the phone's [SectionHeader]
 * reads, and [content] padded from its edges by the phone's gutter, so a phone block sits in it as
 * it sits on the phone's page. A chart that bleeds to the phone's edges bleeds to the panel's.
 */
@Composable
internal fun DeskPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: String? = null,
    info: String? = null,
    action: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    bottom: Dp = 16.dp,
    top: Dp = PANEL_TOP,
    background: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FinanceTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(Brush.verticalGradient(listOf(colors.surfaceRaised.copy(alpha = 0.55f), colors.surface)))
            .border(1.dp, colors.hairline, PanelShape)
            .let { m -> if (onClick != null) m.handCursor().clickable(role = Role.Button, onClick = onClick) else m },
    ) {
        background?.invoke()
        CompositionLocalProvider(LocalPageGutter provides PANEL_GUTTER, LocalInPanel provides true) {
            Column(Modifier.fillMaxWidth().padding(bottom = bottom)) {
                if (title != null) {
                    SectionHeader(title, trailing = trailing, subtitle = subtitle, info = info, action = action, top = 18.dp)
                } else {
                    Spacer(Modifier.height(top))
                }
                content()
            }
        }
    }
}

private val PANEL_GUTTER = 22.dp
private val PANEL_TOP = 14.dp

/** Panels side by side, each given a weighted share of the width with [DeskColumn]: tops aligned, as a dashboard's row reads. */
@Composable
internal fun DeskRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DeskGap), verticalAlignment = Alignment.Top, content = content)
}

/** One column of a [DeskRow]: its panels stacked with the dashboard's gap between them. */
@Composable
internal fun RowScope.DeskColumn(weight: Float, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.weight(weight), verticalArrangement = Arrangement.spacedBy(DeskGap), content = content)
}

/**
 * [items] dealt into [columns] columns, each dealt to the next column in turn: a dashboard's grid of
 * panels of different heights, packed down each column rather than leaving a row's gaps.
 */
@Composable
internal fun <T> DeskMasonry(items: List<T>, columns: Int, modifier: Modifier = Modifier, item: @Composable (T) -> Unit) {
    DeskRow(modifier) {
        for (c in 0 until columns) {
            DeskColumn(1f) {
                items.forEachIndexed { i, it -> if (i % columns == c) item(it) }
            }
        }
    }
}

/**
 * [items] in rows of [columns], each the same width, a short last row keeping the grid's widths:
 * cards of a kind side by side. With [equalHeights] each row's cells are as tall as its tallest,
 * for an [item] that fills the height it is given; its content must answer intrinsic measurement
 * (no lazy list or BoxWithConstraints in it).
 */
@Composable
internal fun <T> DeskGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    gap: Dp = DeskGap,
    rowGap: Dp = gap,
    equalHeights: Boolean = false,
    item: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(rowGap)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().let { if (equalHeights) it.height(IntrinsicSize.Min) else it }, horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { Box(Modifier.weight(1f).let { m -> if (equalHeights) m.fillMaxHeight() else m }) { item(it) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A heading standing over a dashboard's row of panels, at the column's edge. */
@Composable
internal fun DeskHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: String? = null, info: String? = null, action: (@Composable () -> Unit)? = null) {
    SectionHeader(title, modifier, trailing = trailing, subtitle = subtitle, info = info, action = action, top = 12.dp)
}

/** Where an item of a lazy list stands in a panel drawn across several items: [deskPanelBand]. */
internal enum class PanelBand { TOP, MIDDLE, BOTTOM }

/**
 * Draws this item as its [band] of one panel running down several items of a lazy list, as a
 * long table is: the top rounded with the heading's rule under it, the middle the panel's sides,
 * the bottom rounded with a rule over it. A table of hundreds of rows stays lazy and still reads
 * as one panel.
 */
@Composable
internal fun Modifier.deskPanelBand(band: PanelBand): Modifier {
    val colors = FinanceTheme.colors
    val radius = 22.dp
    return when (band) {
        PanelBand.TOP -> {
            val shape = RoundedCornerShape(topStart = radius, topEnd = radius)
            clip(shape).background(colors.surface).border(1.dp, colors.hairline, shape)
        }

        PanelBand.BOTTOM -> {
            val shape = RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
            clip(shape).background(colors.surface).border(1.dp, colors.hairline, shape)
        }

        PanelBand.MIDDLE -> background(colors.surface).drawBehind {
            val w = 1.dp.toPx()
            drawRect(colors.hairline, Offset.Zero, Size(w, size.height))
            drawRect(colors.hairline, Offset(size.width - w, 0f), Size(w, size.height))
        }
    }
}
