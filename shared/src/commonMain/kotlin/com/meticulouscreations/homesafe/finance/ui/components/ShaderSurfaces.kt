package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
