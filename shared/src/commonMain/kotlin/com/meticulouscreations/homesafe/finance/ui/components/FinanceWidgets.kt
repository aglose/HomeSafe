package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.FinanceTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [text] with every digit on its own odometer wheel: when the number changes each digit rolls to
 * its new value — forward when the number grew, back when it shrank, carrying through 0 the way
 * an odometer does ($19 → $20 rolls the units on from 9 to 0) — the way Robinhood's portfolio
 * value ticks. Slots are keyed from the right so the decimal point stays put while the number
 * grows a digit on the left. Screen readers get the text, not ten digits a slot.
 */
@Composable
fun RollingNumber(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val animatedColor by animateColorAsState(color, tween(350), label = "rollingColor")
    val value = numericValue(text)
    // The last value shown, outside the snapshot system: it only decides which way the wheels
    // turn and must not recompose anything when it changes.
    val previous = remember { arrayOfNulls<Double>(1).also { it[0] = value } }
    val before = previous[0]
    val rising = value == null || before == null || value >= before
    SideEffect { previous[0] = value }
    Row(modifier.clearAndSetSemantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically) {
        val n = text.length
        text.forEachIndexed { i, ch ->
            key(n - i) {
                if (ch.isDigit()) {
                    DigitWheel(ch.digitToInt(), rising, style, animatedColor)
                } else {
                    Text(ch.toString(), style = style, color = animatedColor)
                }
            }
        }
    }
}

/** The number [text] writes ("$1,234.50" → 1234.5, "−0.25%" → -0.25), or null if it isn't one. */
internal fun numericValue(text: String): Double? {
    val negative = text.contains('-') || text.contains('−')
    val digits = text.filter { it.isDigit() || it == '.' }
    return digits.toDoubleOrNull()?.let { if (negative) -it else it }
}

@Composable
private fun DigitWheel(digit: Int, rising: Boolean, style: TextStyle, color: Color) {
    // An unbounded position: the wheel shows it mod 10, so rolling from 9 up to 10 lands on 0
    // having turned forward.
    val position = remember { Animatable(digit.toFloat()) }
    LaunchedEffect(digit) {
        val current = position.value
        val shown = ((current.roundToInt() % 10) + 10) % 10
        if (shown == digit && abs(current - current.roundToInt()) < 0.01f) return@LaunchedEffect
        val step = ((digit - shown) % 10 + 10) % 10
        val target = if (rising) current.roundToInt() + step else current.roundToInt() - ((10 - step) % 10)
        position.animateTo(target.toFloat(), spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
        // Back into 0..9 so the position never drifts far.
        position.snapTo(digit.toFloat())
    }
    Layout(
        content = { for (d in 0..9) Text(d.toString(), style = style, color = color) },
        modifier = Modifier.clipToBounds(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val digits = measurables.map { it.measure(loose) }
        val cell = digits.maxOf { it.height }
        val pos = ((position.value % 10f) + 10f) % 10f
        // The app's sans has proportional figures, so the wheel is as wide as the digit it shows,
        // easing between widths as it rolls: no gap after a narrow 1.
        val lo = pos.toInt() % 10
        val hi = (lo + 1) % 10
        val frac = pos - pos.toInt()
        val width = (digits[lo].width + (digits[hi].width - digits[lo].width) * frac).roundToInt()
        layout(width, cell) {
            digits.forEachIndexed { d, placeable ->
                var delta = d - pos
                if (delta < -5f) delta += 10f
                if (delta > 5f) delta -= 10f
                val y = (delta * cell).roundToInt()
                if (y > -cell && y < cell) placeable.place((width - placeable.width) / 2, y)
            }
        }
    }
}

/** A small up/down line for a list row: today's path against its baseline, drawn on once. */
@Composable
fun Sparkline(series: Series, color: Color, modifier: Modifier = Modifier, baseline: Double? = null) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    val dotColor = FinanceTheme.colors.textTertiary
    Canvas(modifier) {
        if (series.size < 2) return@Canvas
        val s = series.downsample(120)
        var lo = s.min()
        var hi = s.max()
        if (baseline != null) {
            lo = minOf(lo, baseline)
            hi = maxOf(hi, baseline)
        }
        if (hi == lo) hi = lo + 1
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        if (baseline != null) {
            drawLine(
                dotColor,
                Offset(0f, y(baseline)),
                Offset(size.width, y(baseline)),
                1.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 3.dp.toPx())),
            )
        }
        val path = Path()
        for (i in 0 until s.size) {
            val x = i / (s.size - 1f) * size.width
            if (i == 0) path.moveTo(x, y(s.values[i])) else path.lineTo(x, y(s.values[i]))
        }
        clipRect(right = size.width * reveal.value) {
            drawPath(path, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * Robinhood's row of range buttons under a chart: the chosen one sits in a filled pill (in the
 * chart's colour) that slides to the next pick.
 */
@Composable
fun <T> RangeSelector(options: List<T>, selected: T, label: @Composable (T) -> String, color: Color, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    BoxWithConstraints(modifier.fillMaxWidth().height(36.dp)) {
        val slot = maxWidth / options.size
        val index = options.indexOf(selected).coerceAtLeast(0)
        val pillX by animateDpAsState(slot * index, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium), label = "rangePill")
        val pillColor by animateColorAsState(color, tween(350), label = "rangePillColor")
        Box(
            Modifier
                .offset(x = pillX + 4.dp)
                .width(slot - 8.dp)
                .height(32.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .background(pillColor.copy(alpha = 0.18f)),
        )
        Row(Modifier.fillMaxWidth().align(Alignment.Center)) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .width(slot)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) {
                            if (!isSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(option)
                            }
                        }
                        .semantics { this.selected = isSelected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), style = type.label, color = if (isSelected) pillColor else colors.textSecondary)
                }
            }
        }
    }
}

/** A change written in a filled pill, green or orange-red, as Robinhood's watchlist rows show it. */
@Composable
fun ChangePill(text: String, positive: Boolean, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val bg by animateColorAsState(if (positive) colors.gain else colors.loss, tween(300), label = "pill")
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = FinanceTheme.type.label, color = Color.Black)
    }
}

/** One slice of a [DonutChart]. */
data class DonutSlice(val label: String, val value: Double, val color: Color)

/**
 * A ring that sweeps round into its slices when it appears; [highlighted] lifts one slice out and
 * dims the rest (the legend under it chooses).
 */
@Composable
fun DonutChart(slices: List<DonutSlice>, modifier: Modifier = Modifier, highlighted: Int? = null, thickness: Dp = 22.dp) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val total = slices.sumOf { max(it.value, 0.0) }.takeIf { it > 0 } ?: return
    val track = FinanceTheme.colors.hairline
    val lift = slices.indices.map { i -> animateFloatAsState(if (highlighted == i) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "slice$i") }
    Canvas(modifier) {
        val stroke = thickness.toPx()
        val inset = stroke / 2 + 8.dp.toPx()
        val arcSize = Size(size.minDimension - inset * 2, size.minDimension - inset * 2)
        val origin = Offset((size.width - size.minDimension) / 2 + inset, (size.height - size.minDimension) / 2 + inset)
        drawArc(track, 0f, 360f, false, origin, arcSize, style = Stroke(stroke))
        var start = -90f
        val gap = if (slices.size > 1) 1.6f else 0f
        slices.forEachIndexed { i, slice ->
            val angle = (max(slice.value, 0.0) / total * 360.0).toFloat() * sweep.value
            val l = lift[i].value
            val dim = highlighted != null && highlighted != i
            drawArc(
                slice.color.copy(alpha = if (dim) 0.3f else 1f),
                start + gap / 2,
                (angle - gap).coerceAtLeast(0.1f),
                false,
                Offset(origin.x - l * 4.dp.toPx(), origin.y - l * 4.dp.toPx()),
                Size(arcSize.width + l * 8.dp.toPx(), arcSize.height + l * 8.dp.toPx()),
                style = Stroke(stroke + l * 6.dp.toPx(), cap = StrokeCap.Butt),
            )
            start += angle
        }
    }
}

/** One bar of a [BarChart]: a value, an optional second (stacked or ghosted behind), a label. */
data class Bar(val label: String, val value: Double, val color: Color, val secondary: Double? = null, val secondaryColor: Color = Color.Unspecified)

/**
 * Vertical bars that grow up from the baseline one after another when they appear. A bar's
 * [Bar.secondary] is drawn as a wider ghost behind it (income behind take-home). Tapping a bar
 * selects it, with the chart settings' haptic tick when that's a change.
 */
@Composable
fun BarChart(bars: List<Bar>, modifier: Modifier = Modifier, selected: Int? = null, onSelect: (Int) -> Unit = {}) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(bars.size) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val maxValue = bars.maxOfOrNull { max(abs(it.value), abs(it.secondary ?: 0.0)) }?.takeIf { it > 0 } ?: return
    val minValue = bars.minOfOrNull { minOf(it.value, it.secondary ?: 0.0) }?.coerceAtMost(0.0) ?: 0.0
    val labelColor = FinanceTheme.colors.textTertiary
    val type = FinanceTheme.type
    val haptics = LocalHapticFeedback.current
    val feel = FinanceTheme.chart.haptics
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = bars.size
            val slot = size.width / n
            val barW = slot * 0.56f
            val span = maxValue - minValue
            val zeroY = (size.height * (maxValue / span)).toFloat()
            bars.forEachIndexed { i, bar ->
                val stagger = ((grow.value * (n + 3) - i) / 4f).coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(stagger)
                val dim = selected != null && selected != i
                val cx = slot * i + slot / 2
                bar.secondary?.let { sv ->
                    val hgt = (sv / span * size.height).toFloat() * eased
                    val c = if (bar.secondaryColor == Color.Unspecified) bar.color.copy(alpha = 0.25f) else bar.secondaryColor
                    drawRoundRect(
                        c.copy(alpha = c.alpha * (if (dim) 0.4f else 1f)),
                        Offset(cx - slot * 0.4f, if (hgt >= 0) zeroY - hgt else zeroY),
                        Size(slot * 0.8f, abs(hgt)),
                        CornerRadius(4.dp.toPx()),
                    )
                }
                val hgt = (bar.value / span * size.height).toFloat() * eased
                drawRoundRect(
                    if (dim) bar.color.copy(alpha = 0.35f) else bar.color,
                    Offset(cx - barW / 2, if (hgt >= 0) zeroY - hgt else zeroY),
                    Size(barW, abs(hgt)),
                    CornerRadius(4.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            bars.forEachIndexed { i, bar ->
                Text(
                    bar.label,
                    style = type.micro,
                    color = if (selected == i) FinanceTheme.colors.textPrimary else labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (i != selected) haptics.chartTick(feel)
                            onSelect(i)
                        },
                )
            }
        }
    }
}

/** A horizontal meter that fills to [fraction] when it appears. */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp, delayMillis: Int = 0) {
    val fill = remember { Animatable(0f) }
    LaunchedEffect(fraction) { fill.animateTo(fraction.coerceIn(0f, 1f), tween(800, delayMillis = delayMillis, easing = FastOutSlowInEasing)) }
    val track = FinanceTheme.colors.hairline
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * fill.value, size.height), cornerRadius = r)
    }
}

/** A loading placeholder with a highlight sweeping across it. */
@Composable
fun Shimmer(modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "shimmerX")
    val base = FinanceTheme.colors.surfaceRaised
    val glint = FinanceTheme.colors.hairline
    Canvas(modifier.clip(RoundedCornerShape(corner))) {
        drawRect(base)
        val cx = size.width * x
        drawRect(Brush.linearGradient(listOf(Color.Transparent, glint, Color.Transparent), start = Offset(cx - size.width * 0.4f, 0f), end = Offset(cx + size.width * 0.4f, size.height)))
    }
}

/**
 * Lifts [content] in from below and fades it up the first time it appears, [index] steps after
 * the first, so a page's cards cascade in rather than popping.
 */
@Composable
fun CascadeIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(520, delayMillis = (index * 55).coerceAtMost(440), easing = FastOutSlowInEasing)) }
    Box(
        modifier.graphicsLayer {
            alpha = p.value
            translationY = (1f - p.value) * 36.dp.toPx()
            val s = 0.97f + 0.03f * p.value
            scaleX = s
            scaleY = s
        },
    ) { content() }
}
