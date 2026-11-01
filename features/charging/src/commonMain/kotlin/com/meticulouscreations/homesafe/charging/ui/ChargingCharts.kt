package com.meticulouscreations.homesafe.charging.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.charging.domain.ChargingMonth
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_chart_hour_value
import homesafe.shared.generated.resources.charging_chart_with_hours
import homesafe.shared.generated.resources.charging_month_bar
import homesafe.shared.generated.resources.charging_month_bar_fast_only
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

/** One part of a stacked bar: how much, in what colour. */
internal class BarPart(val amount: Double, val color: Color)

/** A rounded bar across, shared out between [parts] by how much each is. */
@Composable
internal fun StackedBar(parts: List<BarPart>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val colors = ChargingTheme.colors
    Row(
        modifier.fillMaxWidth().height(height).clip(CircleShape).background(colors.surfaceRaised),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.filter { it.amount > 0 }.forEach { part ->
            Box(Modifier.weight(part.amount.toFloat()).fillMaxHeight().background(part.color))
        }
    }
}

/** A label's dot, its name, and at the end what it comes to. */
@Composable
internal fun LegendRow(color: Color, label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = ChargingTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = ChargingTheme.type.body, color = colors.textMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Text(detail, style = ChargingTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(end = 12.dp), maxLines = 1)
        }
        Text(value, style = ChargingTheme.type.bodyStrong, color = colors.text, maxLines = 1)
    }
}

/**
 * Each month's cost as a stack of where it was charged, oldest at the start. A tap picks that month,
 * where there is something to pick it for. A month with only Superchargers to go on is drawn faded, so
 * its short bar isn't read as little charging. Only as many as fit are drawn; the whole list is on the
 * history below it.
 */
@Composable
internal fun MonthBars(months: List<ChargingMonth>, selected: String?, modifier: Modifier = Modifier, onSelect: ((String) -> Unit)? = null) {
    val colors = ChargingTheme.colors
    val shown = months.take(MONTH_BARS).reversed()
    val highest = shown.maxOfOrNull { it.cost }?.takeIf { it > 0 } ?: 1.0
    Row(modifier.fillMaxWidth().height(148.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        shown.forEach { month ->
            val isSelected = month.key == selected
            val label = stringResource(ChargingFormat.month(month.month))
            val said = stringResource(if (month.homeRecorded) Res.string.charging_month_bar else Res.string.charging_month_bar_fast_only, monthYearText(month), moneyText(month.cost))
            val fade = if (month.homeRecorded) 1f else 0.45f
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) colors.surfaceRaised else Color.Transparent)
                    .then(if (onSelect != null) Modifier.clickable(role = Role.Button) { onSelect(month.key) }.handCursor() else Modifier)
                    .semantics(mergeDescendants = true) {
                        contentDescription = said
                        this.selected = isSelected
                    }
                    .testTag("charging_month_bar_${month.key}")
                    .padding(horizontal = 3.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Canvas(Modifier.fillMaxWidth().weight(1f)) {
                    val parts = listOf(month.homeCost to colors.home, month.fastCost to colors.fast, month.otherCost to colors.other)
                    var bottom = size.height
                    val scale = size.height / highest.toFloat()
                    parts.forEach { (amount, color) ->
                        if (amount > 0) {
                            val tall = max(amount.toFloat() * scale, 2.dp.toPx())
                            drawRoundRect(color.copy(alpha = fade), Offset(0f, bottom - tall), Size(size.width, tall), CornerRadius(3.dp.toPx()))
                            bottom -= tall + 1.dp.toPx()
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(label.take(1), style = ChargingTheme.type.micro, color = if (isSelected) colors.text else colors.textFaint, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Bars for each of a day's hours. [color] says what colour each is. Under them the hours 0, 6, 12
 * and 18 are named, each under its own bar's start. What the chart says goes in [description], and
 * a screen reader hears it followed by each hour with something in it, as [valueText] words it.
 */
@Composable
internal fun HourBars(values: List<Double>, description: String, valueText: @Composable (Double) -> String, modifier: Modifier = Modifier, height: Dp = 96.dp, color: (Int) -> Color) {
    val colors = ChargingTheme.colors
    val highest = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val words = values.mapIndexed { hour, value -> value to stringResource(Res.string.charging_chart_hour_value, hourText(hour), valueText(value)) }
        .filter { (value) -> value > 0 }
        .joinToString("; ") { (_, hour) -> hour }
    val spoken = stringResource(Res.string.charging_chart_with_hours, description, words)
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        Canvas(Modifier.fillMaxWidth().height(height)) { hourBars(values, highest, colors.surfaceRaised, color) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            for (hour in listOf(0, 6, 12, 18)) {
                Text(hourText(hour), style = ChargingTheme.type.micro, color = colors.textFaint, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun DrawScope.hourBars(values: List<Double>, highest: Double, track: Color, color: (Int) -> Color) {
    if (values.isEmpty()) return
    val gap = 2.dp.toPx()
    val wide = (size.width - gap * (values.size - 1)) / values.size
    values.forEachIndexed { index, value ->
        val x = index * (wide + gap)
        drawRoundRect(track, Offset(x, 0f), Size(wide, size.height), CornerRadius(2.dp.toPx()))
        if (value > 0) {
            val tall = max((value / highest).toFloat() * size.height, 2.dp.toPx())
            drawRoundRect(color(index), Offset(x, size.height - tall), Size(wide, tall), CornerRadius(2.dp.toPx()))
        }
    }
}

/** The battery from where a charge began to where it ended, as a bar. */
@Composable
internal fun BatteryBar(from: Int, to: Int, color: Color, modifier: Modifier = Modifier) {
    val colors = ChargingTheme.colors
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        drawRoundRect(colors.surfaceRaised, cornerRadius = CornerRadius(size.height / 2))
        val start = size.width * from.coerceIn(0, 100) / 100f
        val end = size.width * to.coerceIn(0, 100) / 100f
        drawRoundRect(color.copy(alpha = 0.3f), size = Size(start, size.height), cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(color, Offset(start, 0f), Size((end - start).coerceAtLeast(2f), size.height), CornerRadius(size.height / 2))
    }
}

private const val MONTH_BARS = 12
