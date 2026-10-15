package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.ChartHaptics
import com.meticulouscreations.homesafe.finance.domain.ChartShader
import com.meticulouscreations.homesafe.finance.domain.ChartStyle
import com.meticulouscreations.homesafe.finance.domain.LineSharpness
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.finance.ui.components.RangeSelector
import com.meticulouscreations.homesafe.finance.ui.components.chartTick
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.chart_style_fine_print
import homesafe.shared.generated.resources.chart_style_page_intro
import homesafe.shared.generated.resources.chart_style_page_title
import homesafe.shared.generated.resources.chart_style_preview
import homesafe.shared.generated.resources.chart_style_preview_chart_description
import homesafe.shared.generated.resources.chart_style_preview_point
import homesafe.shared.generated.resources.chart_style_reset
import homesafe.shared.generated.resources.chart_style_section_haptics
import homesafe.shared.generated.resources.chart_style_section_line
import homesafe.shared.generated.resources.chart_style_section_look
import homesafe.shared.generated.resources.chart_style_tile_description
import homesafe.shared.generated.resources.chart_style_tile_use
import homesafe.shared.generated.resources.finance_change_with_percent
import org.jetbrains.compose.resources.stringResource
import kotlin.math.sin

/**
 * How every chart in the finance app looks and feels: the light on the line ([ChartShader]), how
 * closely it follows the data ([LineSharpness]) and how a scrub buzzes ([ChartHaptics]). A live
 * chart at the top shows each choice as it's made, and can be dragged to feel the haptics.
 */
@Composable
internal fun ChartSettingsScreen(style: ChartStyle, contentPadding: PaddingValues, onChange: (ChartStyle) -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val haptics = LocalHapticFeedback.current
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.testTag("chart_settings")) {
        item {
            Column(Modifier.padding(horizontal = PageGutter, vertical = 8.dp)) {
                Text(stringResource(Res.string.chart_style_page_title), style = type.title, color = colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.chart_style_page_intro), style = type.body, color = colors.textSecondary)
            }
        }
        item { PreviewCard() }

        item { SectionHeader(stringResource(Res.string.chart_style_section_look), subtitle = stringResource(style.shader.blurb)) }
        item {
            Column(Modifier.padding(horizontal = PageGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChartShader.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { shader ->
                            ShaderTile(shader, style, selected = shader == style.shader, onClick = { onChange(style.copy(shader = shader)) }, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item { SectionHeader(stringResource(Res.string.chart_style_section_line), subtitle = stringResource(style.sharpness.blurb)) }
        item {
            RangeSelector(LineSharpness.entries, style.sharpness, { stringResource(it.label) }, colors.gain, { onChange(style.copy(sharpness = it)) }, Modifier.padding(horizontal = PageGutter - 4.dp))
        }

        item { SectionHeader(stringResource(Res.string.chart_style_section_haptics), subtitle = stringResource(style.haptics.blurb)) }
        item {
            RangeSelector(
                ChartHaptics.entries,
                style.haptics,
                { stringResource(it.label) },
                colors.gain,
                { feel ->
                    onChange(style.copy(haptics = feel))
                    // A taste of the new feel straight away: a tick, then a landmark's thud.
                    haptics.chartTick(feel, landmark = true)
                },
                Modifier.padding(horizontal = PageGutter - 4.dp),
            )
        }

        item {
            Spacer(Modifier.height(24.dp))
            if (style != ChartStyle.DEFAULT) {
                Text(
                    stringResource(Res.string.chart_style_reset),
                    style = type.bodyStrong,
                    color = colors.gain,
                    modifier = Modifier
                        .padding(horizontal = PageGutter - 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onChange(ChartStyle.DEFAULT) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
            FinePrint(stringResource(Res.string.chart_style_fine_print))
        }
    }
}

/** A day-like chart under the page's title, drawn with the choices as they stand, to drag and feel. */
@Composable
private fun PreviewCard() {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val series = remember { previewSeries() }
    val open = series.values.first()
    var scrub by remember { mutableStateOf<Int?>(null) }
    val at = scrub
    val shown = at?.let { series.values[it] } ?: series.values.last()
    val change = shown - open
    val color = colors.direction(change)
    FinanceCard(padding = 0.dp, modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Text(
                if (at == null) stringResource(Res.string.chart_style_preview) else stringResource(Res.string.chart_style_preview_point, at + 1, series.size),
                style = type.label,
                color = colors.textSecondary,
            )
            Text(FinanceFormat.money(shown), style = type.title, color = colors.textPrimary)
            Text(
                stringResource(Res.string.finance_change_with_percent, FinanceFormat.signedMoney(change), FinanceFormat.signedPercent(change / open * 100)),
                style = type.label,
                color = color,
            )
        }
        Spacer(Modifier.height(8.dp))
        LineChart(
            lines = listOf(ChartLine(series, color, fill = true)),
            baseline = open,
            contentDescription = stringResource(Res.string.chart_style_preview_chart_description),
            onScrub = { scrub = it },
            modifier = Modifier.fillMaxWidth().height(180.dp).testTag("chart_settings_preview"),
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** One look to pick: a small chart drawn in it, its name, and a ring when it's the one in use. */
@Composable
private fun ShaderTile(shader: ChartShader, style: ChartStyle, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val series = remember { previewSeries() }
    val lookName = stringResource(shader.label)
    val description = stringResource(Res.string.chart_style_tile_description, lookName)
    val useLabel = stringResource(Res.string.chart_style_tile_use, lookName)
    Box(
        modifier
            .clip(shape)
            .background(colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.gain else colors.hairline, shape)
            .testTag("chart_shader_${shader.name.lowercase()}"),
    ) {
        // Hidden from screen readers: the overlay below is the control, and says the name itself.
        Column(Modifier.clearAndSetSemantics {}) {
            // The tile's chart in this look (and the line as chosen), quiet: no buzz from here.
            CompositionLocalProvider(LocalChartStyle provides style.copy(shader = shader, haptics = ChartHaptics.OFF)) {
                LineChart(
                    lines = listOf(ChartLine(series, colors.gain, fill = true, width = 2f)),
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp),
                )
            }
            Text(
                lookName,
                style = FinanceTheme.type.bodyStrong,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        // Over the whole tile, so a touch on its chart picks the look rather than starting a scrub.
        Box(
            Modifier
                .matchParentSize()
                .semantics {
                    contentDescription = description
                    this.selected = selected
                }
                .clickable(role = Role.RadioButton, onClickLabel = useLabel, onClick = onClick),
        )
    }
}

/**
 * A made-up trading day: 48 half-hourly-ish prices that drift up with a dip and a rally, plus a
 * little jitter, so a smooth line visibly rounds what a sharp one shows point by point.
 */
private fun previewSeries(): Series {
    val n = 48
    val times = LongArray(n) { 1_700_000_000L + it * 600L }
    val values = DoubleArray(n) { i ->
        val t = i / (n - 1.0)
        val trend = 100.0 + 6.0 * t
        val swell = 3.2 * sin(t * 7.5) + 1.4 * sin(t * 19.0 + 1.0)
        // A fixed jitter (not random), so the preview looks the same every time it's opened.
        val jitter = 0.9 * sin(i * 12.9898) * sin(i * 4.1414 + 0.5)
        trend + swell + jitter
    }
    return Series(times, values)
}
