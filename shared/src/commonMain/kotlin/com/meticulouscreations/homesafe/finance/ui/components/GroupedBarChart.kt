package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.ui.FinanceTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One series of a [GroupedBarChart]: a value (or a gap) per group, in one colour ([negativeColor] below zero, if given). */
@Immutable
data class BarSeries(val values: List<Double?>, val color: Color, val negativeColor: Color = color) {
    fun colorOf(value: Double): Color = if (value < 0) negativeColor else color
}

/**
 * Bars for several series over the same groups — side by side, or [stacked] into one bar per
 * group — growing up (or down, for negatives) from zero one group after another. A tap, or a
 * finger sliding across, selects a group with a haptic tick; [onSelect] gets null when the slide
 * ends, so the caller can go back to showing the latest. When the labels don't all fit, those that
 * would touch a neighbour are left out (never the last), and only the selected one is written while
 * one is.
 */
@Composable
fun GroupedBarChart(
    labels: List<String>,
    series: List<BarSeries>,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    selected: Int? = null,
    contentDescription: String = "",
    onSelect: (Int?) -> Unit = {},
) {
    val groups = labels.size
    val grow = remember { Animatable(0f) }
    LaunchedEffect(groups, series.size) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val haptics = LocalHapticFeedback.current
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)
    if (groups == 0 || series.isEmpty()) return

    // The value range: each group's stack (positives up, negatives down) or its tallest bar.
    var top = 0.0
    var bottom = 0.0
    for (g in 0 until groups) {
        if (stacked) {
            top = max(top, series.sumOf { (it.values.getOrNull(g) ?: 0.0).coerceAtLeast(0.0) })
            bottom = min(bottom, series.sumOf { (it.values.getOrNull(g) ?: 0.0).coerceAtMost(0.0) })
        } else {
            series.forEach { s ->
                s.values.getOrNull(g)?.let {
                    top = max(top, it)
                    bottom = min(bottom, it)
                }
            }
        }
    }
    val span = (top - bottom).takeIf { it > 0 } ?: return
    val labelColor = FinanceTheme.colors.textTertiary
    val selectedColor = FinanceTheme.colors.textPrimary
    val zeroColor = FinanceTheme.colors.hairline
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = FinanceTheme.type.micro

    Canvas(
        modifier
            .semantics { if (contentDescription.isNotEmpty()) this.contentDescription = contentDescription }
            .pointerInput(groups) {
                detectTapGestures { offset ->
                    val g = (offset.x / size.width * groups).toInt().coerceIn(0, groups - 1)
                    if (g != currentSelected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        currentOnSelect(g)
                    }
                }
            }
            .pointerInput(groups) {
                var last = -1
                detectHorizontalDragGestures(
                    onDragStart = { last = -1 },
                    onDragEnd = { currentOnSelect(null) },
                    onDragCancel = { currentOnSelect(null) },
                ) { change, _ ->
                    change.consume()
                    val g = (change.position.x / size.width * groups).toInt().coerceIn(0, groups - 1)
                    if (g != last) {
                        last = g
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        currentOnSelect(g)
                    }
                }
            },
    ) {
        val labelBand = 20.dp.toPx()
        val plotH = size.height - labelBand
        val slot = size.width / groups
        val zeroY = (plotH * (top / span)).toFloat()
        fun height(v: Double) = (v / span * plotH).toFloat()
        drawLine(zeroColor, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
        val radius = CornerRadius(min(4.dp.toPx(), slot * 0.15f))
        for (g in 0 until groups) {
            val eased = FastOutSlowInEasing.transform(((grow.value * (groups + 3) - g) / 4f).coerceIn(0f, 1f))
            val alpha = if (selected != null && selected != g) 0.35f else 1f
            if (stacked) {
                val barW = slot * 0.62f
                val x = slot * g + (slot - barW) / 2
                var up = zeroY
                var down = zeroY
                series.forEach { s ->
                    val v = s.values.getOrNull(g) ?: return@forEach
                    val h = abs(height(v)) * eased
                    if (v >= 0) {
                        up -= h
                        drawRoundRect(s.colorOf(v).copy(alpha = alpha), Offset(x, up), Size(barW, h), radius)
                    } else {
                        drawRoundRect(s.colorOf(v).copy(alpha = alpha), Offset(x, down), Size(barW, h), radius)
                        down += h
                    }
                }
            } else {
                val inner = slot * 0.72f
                val barW = inner / series.size
                series.forEachIndexed { i, s ->
                    val v = s.values.getOrNull(g) ?: return@forEachIndexed
                    val h = height(v) * eased
                    val x = slot * g + (slot - inner) / 2 + barW * i
                    drawRoundRect(s.colorOf(v).copy(alpha = alpha), Offset(x + barW * 0.06f, if (h >= 0) zeroY - h else zeroY), Size(barW * 0.88f, abs(h)), radius)
                }
            }
        }

        // The labels: the selected group's alone, or as many as fit without touching, the last always.
        val gap = 8.dp.toPx()
        fun placed(g: Int, color: Color): Pair<TextLayoutResult, Float> {
            val layout = textMeasurer.measure(labels[g], labelStyle.copy(color = color))
            val x = (slot * g + slot / 2 - layout.size.width / 2).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            return layout to x
        }
        val labelY = plotH + 6.dp.toPx()
        if (selected != null && selected in labels.indices) {
            val (layout, x) = placed(selected, selectedColor)
            drawText(layout, topLeft = Offset(x, labelY))
        } else {
            val (lastLayout, lastX) = placed(groups - 1, labelColor)
            var right = Float.NEGATIVE_INFINITY
            for (g in 0 until groups - 1) {
                val (layout, x) = placed(g, labelColor)
                if (x < right + gap || x + layout.size.width > lastX - gap) continue
                drawText(layout, topLeft = Offset(x, labelY))
                right = x + layout.size.width
            }
            drawText(lastLayout, topLeft = Offset(lastX, labelY))
        }
    }
}
