package com.meticulouscreations.homesafe.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A drop of glass that rides the timeline's playhead and magnifies what is under it.
 *
 * At rest it is a slim lens over the playhead that enlarges the bars around it a little. Press
 * and hold and it swells into a bubble ([held]) that magnifies [HELD_ZOOM]x, for picking a single
 * moment out of a busy evening; letting go shrinks it back. Everything about it is sprung, so it
 * behaves like a drop of liquid rather than a cursor: it trails the playhead and overshoots when
 * it catches up, stretches along the way it is moving and thins as it does, swells past its size
 * and settles, and its surface ripples while it is held.
 *
 * Drawn by [LIQUID_LENS_SHADER] over the timeline's own layer (see [liquidLens]).
 */
@Stable
internal class LiquidLensState(private val shader: LiquidLensShader?, initialPosition: Float) {
    /** Where the lens is, as a fraction of the way across the timeline: it chases the playhead. */
    val position = Animatable(initialPosition)

    /** 0 at rest, 1 fully swollen; overshoots both ways, which is the bubble's wobble. */
    val inflation = Animatable(0f)

    /** Whether a finger is holding the bubble open. */
    var held by mutableStateOf(false)

    /** Seconds on the clock the surface ripple runs on; only advanced while the bubble is animating. */
    var time by mutableFloatStateOf(0f)

    suspend fun follow(fraction: Float) {
        position.animateTo(fraction, LENS_FOLLOW)
    }

    suspend fun inflate(to: Float) {
        inflation.animateTo(to, if (to > 0f) LENS_SWELL else LENS_SETTLE)
    }

    /** Whether the surface is rippling, and so [runRippleClock] needs to be ticking. */
    val rippling: Boolean get() = held || inflation.isRunning

    /** Advances [time] every frame until cancelled, carrying on from where it last stopped so the ripple never jumps. */
    suspend fun runRippleClock() {
        val from = time
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time = from + (now - start) / 1_000_000_000f }
        }
    }

    /** The lens as a render effect for a layer [width] x [height] px, or null where there is no shader to draw it with. */
    fun renderEffect(width: Float, height: Float, density: Float): RenderEffect? {
        val shader = shader ?: return null
        if (width <= 0f || height <= 0f) return null
        val swell = inflation.value
        // How fast it is going, in dp a second: the faster, the longer and thinner the drop.
        val speed = abs(position.velocity * width) / density
        val stretch = 1f + (speed / STRETCH_SPEED).coerceAtMost(MAX_STRETCH)
        val halfWidth = (lerp(REST_HALF_WIDTH, HELD_HALF_WIDTH, swell) * density * stretch).coerceAtLeast(MIN_HALF * density)
        val halfHeight = (lerp(REST_HALF_HEIGHT, HELD_HALF_HEIGHT, swell) * density / sqrt(stretch)).coerceAtLeast(MIN_HALF * density)
        val focusX = position.value * width
        // The lens stays whole inside the card, but magnifies about the playhead itself, so at the
        // live edge it sits just inside and still enlarges the last few minutes.
        val inset = EDGE_INSET * density
        val centerX = focusX.coerceIn(halfWidth + inset, (width - halfWidth - inset).coerceAtLeast(halfWidth + inset))
        val centerY = lerp(REST_CENTER_Y, HELD_CENTER_Y, swell) * density
        val ripple = (swell.coerceIn(0f, 1f) * HELD_RIPPLE + (speed / RIPPLE_SPEED).coerceAtMost(1f) * MOVING_RIPPLE) * density

        shader.setUniform("size", width, height)
        shader.setUniform("center", centerX, centerY)
        shader.setUniform("radii", halfWidth, halfHeight)
        shader.setUniform("focus", focusX, FOCUS_Y * density)
        shader.setUniform("zoom", lerp(REST_ZOOM, HELD_ZOOM, swell).coerceAtLeast(1f), lerp(REST_ZOOM_Y, HELD_ZOOM_Y, swell).coerceAtLeast(1f))
        shader.setUniform("ripple", ripple)
        shader.setUniform("time", time)
        shader.setUniform("density", density)
        shader.setUniform("swell", swell.coerceIn(0f, 1f))
        return shader.renderEffect()
    }
}

/** Draws [lens] over everything this layer draws, the card's background included. */
internal fun Modifier.liquidLens(lens: LiquidLensState): Modifier = graphicsLayer {
    renderEffect = lens.renderEffect(size.width, size.height, density)
}

/**
 * A compiled [LIQUID_LENS_SHADER] whose uniforms can be set and turned into a render effect over
 * a layer's content: an AGSL `RuntimeShader` on Android, and the same source as a Skia runtime
 * effect everywhere else (AGSL is Android's name for Skia's shading language, so one source serves).
 */
internal interface LiquidLensShader {
    fun setUniform(name: String, value: Float)

    fun setUniform(name: String, x: Float, y: Float)

    fun renderEffect(): RenderEffect
}

/** The platform's [LiquidLensShader], or null where runtime shaders aren't available (previews, tests off-device). */
internal expect fun liquidLensShaderOrNull(): LiquidLensShader?

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

// Sizes are in dp, before [LiquidLensState.renderEffect] scales them by density. The card is
// 108dp tall: tick labels along the top, detection dots at 38dp, bars from 50dp to 92dp.
private const val REST_HALF_WIDTH = 13f
private const val REST_HALF_HEIGHT = 27f
private const val REST_CENTER_Y = 71f
private const val REST_ZOOM = 1.35f
private const val REST_ZOOM_Y = 1.1f

/**
 * The height magnification is about: the middle of the bars. The zoom is mostly across, since
 * across is time — held, the bubble spreads a few minutes over its width but only lifts the bars
 * and dots a little, so they stay in it.
 */
private const val FOCUS_Y = 71f

/** Held, the bubble clears the scrub label along the top so the time it reads stays legible. */
private const val HELD_HALF_WIDTH = 58f
private const val HELD_HALF_HEIGHT = 36f
private const val HELD_CENTER_Y = 65f

/** How much the held bubble magnifies time; a held drag scrubs this much finer to match (see RecordingTimeline). */
internal const val HELD_ZOOM = 2.6f
private const val HELD_ZOOM_Y = 1.15f

private const val MIN_HALF = 4f
private const val EDGE_INSET = 3f
private const val STRETCH_SPEED = 1_600f
private const val MAX_STRETCH = 0.45f
private const val RIPPLE_SPEED = 1_800f
private const val HELD_RIPPLE = 0.9f
private const val MOVING_RIPPLE = 2.2f

/** Loose enough to trail a fast scrub and overshoot a little when it catches up. */
private val LENS_FOLLOW = spring<Float>(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow)

/** A pop: swells past full size and wobbles back. */
private val LENS_SWELL = spring<Float>(dampingRatio = 0.42f, stiffness = 320f)

/** Letting go: shrinks a touch past resting size before it settles, like a drop reforming. */
private val LENS_SETTLE = spring<Float>(dampingRatio = 0.5f, stiffness = 420f)

/**
 * The lens, in AGSL (and so also valid SkSL for the non-Android targets). `content` is the
 * timeline layer it sits over; every length uniform is in pixels.
 *
 * - The glass is a rounded box, `radii` half-extents about `center`, with a ripple of `ripple`
 *   px running round its edge.
 * - Inside, content is magnified about `focus` by up to `zoom` (across, down), easing from 1x at
 *   the rim to the full zoom a little way in: a dome, so the edge bends the picture rather than
 *   cutting it off.
 *   The rim also pulls in a sliver of what is just outside, and splits colour slightly, as thick
 *   glass does.
 * - A thin lit rim, a sheen on the side facing the light (up and to the left) and a soft glint
 *   give it a surface; a soft shadow just below lifts it off the timeline. `swell` (0..1)
 *   brightens both as the bubble inflates.
 */
internal const val LIQUID_LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 center;
uniform float2 radii;
uniform float2 focus;
uniform float2 zoom;
uniform float ripple;
uniform float time;
uniform float density;
uniform float swell;

float lensDistance(float2 p) {
    float2 d = p - center;
    float corner = min(radii.x, radii.y);
    float2 q = abs(d) - radii + corner;
    float box = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - corner;
    float a = atan(d.y, d.x + 0.0001);
    float wave = 0.6 * sin(3.0 * a + time * 5.0) + 0.4 * sin(5.0 * a - time * 7.3);
    return box - ripple * wave;
}

half4 look(float2 p) {
    return content.eval(clamp(p, float2(0.5), size - float2(0.5)));
}

half4 main(float2 p) {
    half4 base = content.eval(p);
    float sd = lensDistance(p);
    if (sd > 16.0 * density) {
        return base;
    }

    float shadowSd = lensDistance(p - float2(0.0, 3.0 * density));
    float shadow = (1.0 - smoothstep(-3.0 * density, 11.0 * density, shadowSd)) * (0.16 + 0.16 * swell);
    half4 outside = base * (1.0 - shadow) + half4(0.0, 0.0, 0.0, shadow);
    float inside = 1.0 - smoothstep(-0.75, 0.75, sd);
    if (inside <= 0.0) {
        return outside;
    }

    float2 grad = float2(
        lensDistance(p + float2(1.0, 0.0)) - lensDistance(p - float2(1.0, 0.0)),
        lensDistance(p + float2(0.0, 1.0)) - lensDistance(p - float2(0.0, 1.0)));
    float2 n = grad / max(length(grad), 0.0001);

    float rim = max(min(radii.x, radii.y) * 0.55, 1.0);
    float t = clamp(-sd / rim, 0.0, 1.0);
    float dome = 1.0 - (1.0 - t) * (1.0 - t);
    float2 z = mix(float2(1.0), zoom, dome);
    float edge = 1.0 - dome;
    float2 bend = n * edge * (1.0 - t) * 4.0 * density;
    float spread = edge * 0.035 * (zoom.x - 0.6);
    float2 rel = p - focus;
    half4 cr = look(focus + rel / (z * (1.0 - spread)) + bend);
    half4 cg = look(focus + rel / z + bend);
    half4 cb = look(focus + rel / (z * (1.0 + spread)) + bend);
    half4 glass = half4(cr.r, cg.g, cb.b, cg.a);
    glass.rgb = min(glass.rgb * 1.04 + (0.02 + 0.03 * swell) * glass.a, half3(glass.a));

    float2 lightDir = normalize(float2(-0.55, -0.85));
    float facing = dot(n, lightDir);
    float rimLine = 1.0 - smoothstep(0.0, 1.6 * density, -sd);
    float lit = rimLine * (0.22 + 0.5 * max(facing, 0.0));
    lit += pow(max(facing, 0.0), 3.0) * edge * (0.2 + 0.2 * swell);
    lit += pow(max(-facing, 0.0), 2.0) * edge * 0.08;
    float2 g = (p - (center + radii * float2(-0.4, -0.5))) / max(radii * float2(0.4, 0.26), float2(1.0));
    lit += exp(-dot(g, g) * 2.0) * (0.08 + 0.14 * swell);
    lit = clamp(lit, 0.0, 0.85);
    glass = glass * (1.0 - lit) + half4(lit);

    return mix(outside, glass, inside);
}
"""
