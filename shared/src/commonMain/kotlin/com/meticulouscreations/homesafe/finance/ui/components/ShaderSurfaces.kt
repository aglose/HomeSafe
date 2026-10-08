package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.ui.FinanceTheme
import kotlin.math.min

/** Seconds since this composable came up, ticking every frame; read it only while drawing. */
@Composable
internal fun rememberShaderClock(running: Boolean = true): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        // An infinite animation's frames, so a UI test's idling (and anything else that waits for
        // animations to settle) knows this one never will and doesn't wait on it.
        val start = withInfiniteAnimationFrameNanos { it } - (time.floatValue * 1e9f).toLong()
        while (true) {
            withInfiniteAnimationFrameNanos { now -> time.floatValue = (now - start) / 1e9f }
        }
    }
    return time
}

/**
 * The drifting aurora behind a hero number ([AURORA_SHADER]), crossfading to [tint] whenever
 * the direction flips. Where no shader compiles it's a soft radial glow in the same colour.
 */
@Composable
internal fun AuroraBackground(tint: Color, modifier: Modifier = Modifier, secondary: Color = FinanceTheme.colors.cool, intensity: Float = 1f) {
    val shader = remember { financeShaderOrNull(AURORA_SHADER) }
    val animatedTint by animateColorAsState(tint, tween(600), label = "auroraTint")
    val animatedSecondary by animateColorAsState(secondary, tween(600), label = "auroraTint2")
    val animatedIntensity by animateFloatAsState(intensity, tween(900), label = "auroraIntensity")
    val time = rememberShaderClock()
    Box(
        modifier.drawBehind {
            if (shader != null) {
                shader.setUniform("size", size.width, size.height)
                shader.setUniform("time", time.value)
                shader.setUniform("tint", animatedTint)
                shader.setUniform("tint2", animatedSecondary)
                shader.setUniform("intensity", animatedIntensity)
                drawRect(shader.brush())
            } else {
                drawRect(
                    Brush.radialGradient(
                        listOf(animatedTint.copy(alpha = 0.22f * animatedIntensity), Color.Transparent),
                        center = Offset(size.width * 0.3f, 0f),
                        radius = size.maxDimension * 0.8f,
                    ),
                )
            }
        },
    )
}

/**
 * The composite stress gauge's ring ([STRESS_RING_SHADER]): [level] 0–1 of the arc filled, the
 * plasma's agitation set by the same value. Sweeps up from empty when it first appears. Where no
 * shader compiles, a plain gradient arc on a dim track.
 */
@Composable
internal fun StressRing(level: Float, modifier: Modifier = Modifier) {
    val shader = remember { financeShaderOrNull(STRESS_RING_SHADER) }
    val colors = FinanceTheme.colors
    val animated by animateFloatAsState(level.coerceIn(0f, 1f), tween(1400), label = "stressLevel")
    val time = rememberShaderClock()
    Canvas(modifier) {
        if (shader != null) {
            shader.setUniform("size", size.width, size.height)
            shader.setUniform("time", time.value)
            shader.setUniform("level", animated)
            shader.setUniform("heat", animated)
            shader.setUniform("calm", colors.gain)
            shader.setUniform("warn", colors.watch)
            shader.setUniform("hot", colors.loss)
            shader.setUniform("track", colors.hairline)
            drawRect(shader.brush())
        } else {
            val stroke = min(size.width, size.height) * 0.075f * 2
            val inset = stroke / 2 + min(size.width, size.height) * 0.1f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(colors.hairline, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(colors.gain, colors.watch, colors.loss, colors.gain)),
                135f,
                270f * animated,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** The colour a month's (or a bucket's) [heat] reads as without a shader: calm, through a warning, to hot. */
internal fun heatColor(heat: Float, calm: Color, warn: Color, hot: Color): Color =
    if (heat < 0.5f) lerp(calm, warn, heat * 2f) else lerp(warn, hot, (heat - 0.5f) * 2f)

/**
 * A level that rises from empty to [target] when it first appears and follows it after. In a
 * preview it starts where it ends, so a still picture shows the real level.
 */
@Composable
private fun rememberRisingLevel(target: Float, millis: Int): State<Float> {
    val still = LocalInspectionMode.current
    val level = remember { Animatable(if (still) target else 0f) }
    LaunchedEffect(target) { level.animateTo(target, tween(millis, easing = FastOutSlowInEasing)) }
    return level.asState()
}

/**
 * The month's budget as a tank of liquid ([BUDGET_TANK_SHADER]): filled to [level] of the limit
 * (1 is the brim, and it can pass it), calm or agitated by [heat]. Fills from empty when it first
 * appears. Where no shader compiles, a plain rounded tank filled to the same height in the
 * heat's colour.
 */
@Composable
internal fun BudgetTank(level: Float, heat: Float, modifier: Modifier = Modifier, corner: Dp = 26.dp) {
    val shader = remember { financeShaderOrNull(BUDGET_TANK_SHADER) }
    val colors = FinanceTheme.colors
    val animatedLevel by rememberRisingLevel(level.coerceIn(0f, 1.5f), 1400)
    val animatedHeat by rememberRisingLevel(heat.coerceIn(0f, 1f), 1400)
    val time = rememberShaderClock(running = !LocalInspectionMode.current)
    Canvas(modifier) {
        if (shader != null) {
            shader.setUniform("size", size.width, size.height)
            shader.setUniform("time", time.value)
            shader.setUniform("level", animatedLevel)
            shader.setUniform("heat", animatedHeat)
            shader.setUniform("corner", corner.toPx())
            shader.setUniform("calm", colors.gain)
            shader.setUniform("warn", colors.watch)
            shader.setUniform("hot", colors.loss)
            shader.setUniform("glass", colors.textPrimary)
            drawRect(shader.brush())
        } else {
            val radius = CornerRadius(corner.toPx())
            val fill = heatColor(animatedHeat, colors.gain, colors.watch, colors.loss)
            val top = size.height * (1f - animatedLevel.coerceAtMost(1f) * 0.93f)
            drawRoundRect(colors.surfaceRaised, cornerRadius = radius)
            if (animatedLevel > 0f) {
                drawRoundRect(
                    Brush.verticalGradient(listOf(fill.copy(alpha = 0.9f), fill.copy(alpha = 0.45f)), startY = top, endY = size.height),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, size.height - top),
                    cornerRadius = radius,
                )
            }
            drawRoundRect(colors.hairline, cornerRadius = radius, style = Stroke(1.dp.toPx()))
        }
    }
}

/**
 * One bucket's spending against its limit ([BUDGET_HEAT_METER_SHADER]): a capsule filled to
 * [level] of the limit that warms with [heat], with embers off its end once the limit is passed.
 * Where no shader compiles, a plain bar.
 */
@Composable
internal fun HeatMeter(level: Float, heat: Float, modifier: Modifier = Modifier, height: Dp = 22.dp) {
    val shader = remember { financeShaderOrNull(BUDGET_HEAT_METER_SHADER) }
    val colors = FinanceTheme.colors
    val animatedLevel by rememberRisingLevel(level.coerceIn(0f, 1.5f), 1000)
    val time = rememberShaderClock(running = !LocalInspectionMode.current)
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (shader != null) {
            shader.setUniform("size", size.width, size.height)
            shader.setUniform("time", time.value)
            shader.setUniform("level", animatedLevel)
            shader.setUniform("heat", heat.coerceIn(0f, 1f))
            shader.setUniform("calm", colors.gain)
            shader.setUniform("warn", colors.watch)
            shader.setUniform("hot", colors.loss)
            shader.setUniform("track", colors.hairline)
            drawRect(shader.brush())
        } else {
            val thick = size.height * 0.34f
            val radius = CornerRadius(thick / 2)
            val top = size.height - thick - 1f
            drawRoundRect(colors.hairline, topLeft = Offset(0f, top), size = Size(size.width, thick), cornerRadius = radius)
            drawRoundRect(
                heatColor(heat.coerceIn(0f, 1f), colors.gain, colors.watch, colors.loss),
                topLeft = Offset(0f, top),
                size = Size(size.width * animatedLevel.coerceAtMost(1f), thick),
                cornerRadius = radius,
            )
        }
    }
}
