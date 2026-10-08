package com.meticulouscreations.homesafe.fitness.ui.shader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessShaders
import kotlin.math.min

/** Where a shader's clock stands in a preview, which draws one frame: far enough in for everything to be on screen. */
private const val STILL_SECONDS = 14f

/**
 * Seconds for a shader's `time`, ticking every frame while [running] and starting over every
 * ten minutes so a float always has room for it. Still in a preview. Read it only while
 * drawing: a read during composition would recompose every frame.
 */
@Composable
internal fun rememberFitnessClock(running: Boolean = true): FloatState {
    val still = LocalInspectionMode.current
    val time = remember { mutableFloatStateOf(STILL_SECONDS) }
    LaunchedEffect(running, still) {
        if (!running || still) return@LaunchedEffect
        // An infinite animation's frames, so anything waiting for animations to settle doesn't wait on this.
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                time.floatValue = (time.floatValue + ((now - last) / 1e9f).coerceIn(0f, 0.1f)) % 600f
                last = now
            }
        }
    }
    return time
}

@Composable
private fun rememberShader(source: String): FitnessShader? {
    val shaded = LocalFitnessShaders.current
    return remember(shaded, source) { if (shaded) fitnessShaderOrNull(source) else null }
}

/**
 * The forge's glow behind a screen ([FORGE_SHADER]), crossfading to new colours when the phase
 * or the workout changes. [heat] 0–1 is how hard it burns. Where no shader compiles it is a soft
 * glow in the same colours.
 */
@Composable
internal fun ForgeBackground(tint: Color, tint2: Color, modifier: Modifier = Modifier, heat: Float = 0.5f, intensity: Float = 1f, running: Boolean = true) {
    val shader = rememberShader(FORGE_SHADER)
    val animatedTint by animateColorAsState(tint, tween(700), label = "forgeTint")
    val animatedTint2 by animateColorAsState(tint2, tween(700), label = "forgeTint2")
    val animatedHeat by animateFloatAsState(heat.coerceIn(0f, 1f), tween(900), label = "forgeHeat")
    val time = rememberFitnessClock(running)
    Box(
        modifier.drawBehind {
            if (shader != null) {
                shader.setUniform("size", size.width, size.height)
                shader.setUniform("time", time.floatValue)
                shader.setUniform("tint", animatedTint)
                shader.setUniform("tint2", animatedTint2)
                shader.setUniform("heat", animatedHeat)
                shader.setUniform("intensity", intensity)
                drawRect(shader.brush())
            } else {
                drawRect(
                    Brush.radialGradient(
                        listOf(animatedTint.copy(alpha = 0.34f * intensity), animatedTint2.copy(alpha = 0.16f * intensity), Color.Transparent),
                        center = Offset(size.width * 0.7f, 0f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
            }
        },
    )
}

/**
 * A card's background of muscle fibres ([FIBER_SHADER]) in the workout's two colours. [seed]
 * 0–1 gives each card its own weave, [energy] 0–1 quickens the wave running down them. Where no
 * shader compiles, a slanted gradient of the same colours.
 */
@Composable
internal fun FiberField(tint: Color, tint2: Color, seed: Float, modifier: Modifier = Modifier, energy: Float = 0f, running: Boolean = true) {
    val shader = rememberShader(FIBER_SHADER)
    val animatedEnergy by animateFloatAsState(energy.coerceIn(0f, 1f), tween(500), label = "fiberEnergy")
    val time = rememberFitnessClock(running)
    Box(
        modifier.drawBehind {
            if (shader != null) {
                shader.setUniform("size", size.width, size.height)
                shader.setUniform("time", time.floatValue)
                shader.setUniform("tint", tint)
                shader.setUniform("tint2", tint2)
                shader.setUniform("seed", seed)
                shader.setUniform("energy", animatedEnergy)
                drawRect(shader.brush())
            } else {
                drawRect(
                    Brush.linearGradient(
                        listOf(lerp(tint2, Color.Black, 0.55f), lerp(tint2, tint, 0.35f + 0.25f * animatedEnergy)),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f),
                    ),
                )
            }
        },
    )
}

/**
 * A ring filled clockwise from the top to [level] 0–1 in running plasma ([RING_SHADER]),
 * sweeping to a new level when it changes. Where no shader compiles, a gradient arc on a dim
 * track. [thickness] is the ring's width against its radius.
 */
@Composable
internal fun PlasmaRing(
    level: Float,
    tint: Color,
    tint2: Color,
    track: Color,
    modifier: Modifier = Modifier,
    thickness: Float = 0.16f,
    sweepMillis: Int = 900,
    running: Boolean = true,
) {
    val shader = rememberShader(RING_SHADER)
    val still = LocalInspectionMode.current
    val animated by animateFloatAsState(level.coerceIn(0f, 1f), if (still) tween(0) else tween(sweepMillis, easing = FastOutSlowInEasing), label = "ringLevel")
    val time = rememberFitnessClock(running)
    Canvas(modifier) {
        if (shader != null) {
            shader.setUniform("size", size.width, size.height)
            shader.setUniform("time", time.floatValue)
            shader.setUniform("level", animated)
            shader.setUniform("tint", tint)
            shader.setUniform("tint2", tint2)
            shader.setUniform("track", track)
            shader.setUniform("thickness", thickness)
            drawRect(shader.brush())
        } else {
            val radius = min(size.width, size.height) / 2
            val stroke = radius * thickness
            val inset = stroke / 2 + radius * 0.07f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                Brush.sweepGradient(listOf(tint2, tint, tint2)),
                -90f,
                360f * animated,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * A record's flash over the whole screen ([BURST_SHADER]), played once each time [token]
 * changes, from a point [origin] of the way across and down. Draws nothing between plays, and
 * nothing at all where no shader compiles: the banner that comes with it says it either way.
 */
@Composable
internal fun RecordBurst(token: Int, tint: Color, tint2: Color, modifier: Modifier = Modifier, origin: Offset = Offset(0.5f, 0.36f)) {
    val shader = rememberShader(BURST_SHADER)
    val progress = remember { Animatable(1f) }
    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1500, easing = LinearEasing))
    }
    Box(
        modifier.drawBehind {
            val p = progress.value
            if (shader == null || p >= 1f) return@drawBehind
            shader.setUniform("size", size.width, size.height)
            shader.setUniform("center", size.width * origin.x, size.height * origin.y)
            shader.setUniform("progress", p)
            shader.setUniform("tint", tint)
            shader.setUniform("tint2", tint2)
            drawRect(shader.brush())
        },
    )
}
