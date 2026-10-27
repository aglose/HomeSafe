package com.meticulouscreations.homesafe.ui.assistant

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The light round the assistant's box: colour that flows along its edge and spills a soft halo
 * out over the screen beneath, brightest as the box blooms out of the bar and settling to a
 * breathing rim. [time] (seconds) drives the flow and [intensity] (0 to about 1.6) how much light
 * there is; both are read at draw time only, so the animation redraws without recomposing.
 *
 * It is drawn behind the box and past its edges by [spread]; the box's own fill covers the inside.
 * [shader] is [assistantGlowShaderOrNull], remembered by the caller; where runtime shaders can't
 * run (previews, some test hosts) it is null and a plain rotating gradient rim stands in.
 */
internal fun Modifier.assistantGlow(
    shader: AssistantGlowShader?,
    cornerRadius: Dp,
    time: () -> Float,
    intensity: () -> Float,
    spread: Dp = GLOW_SPREAD,
): Modifier = drawBehind {
    val light = intensity()
    if (light <= 0.01f) return@drawBehind
    val reach = spread.toPx()
    val radius = cornerRadius.toPx().coerceAtMost(size.minDimension / 2f)
    if (shader == null) {
        plainGlow(radius, reach, light)
        return@drawBehind
    }
    // The shader draws in this box's own coordinates; the area it covers reaches past it on every side.
    shader.setUniform("box", 0f, 0f, size.width, size.height)
    shader.setUniform("radius", radius)
    shader.setUniform("spread", reach)
    shader.setUniform("time", time())
    shader.setUniform("intensity", light)
    GLOW_COLORS.forEachIndexed { index, color -> shader.setUniform("c$index", color) }
    drawRect(brush = shader.brush(), topLeft = Offset(-reach, -reach), size = Size(size.width + 2 * reach, size.height + 2 * reach))
}

/** The no-shader stand-in: the same colours swept round the rim, in a few widening, fainter strokes. */
private fun DrawScope.plainGlow(radius: Float, reach: Float, light: Float) {
    val brush = Brush.sweepGradient(GLOW_COLORS + GLOW_COLORS.first(), center = center)
    listOf(0.55f to 0.12f, 0.28f to 0.22f, 0.08f to 0.9f).forEach { (width, alpha) ->
        val stroke = reach * width
        drawRoundRect(
            brush = brush,
            topLeft = Offset(-stroke / 2f, -stroke / 2f),
            size = Size(size.width + stroke, size.height + stroke),
            cornerRadius = CornerRadius(radius + stroke / 2f),
            style = Stroke(width = stroke),
            alpha = (alpha * light).coerceIn(0f, 1f),
        )
    }
}

/**
 * A compiled [ASSISTANT_GLOW_SHADER] whose uniforms can be set and drawn as a brush: an AGSL
 * `RuntimeShader` on Android, and the same source as a Skia runtime effect on iOS.
 */
internal interface AssistantGlowShader {
    fun setUniform(name: String, value: Float)

    fun setUniform(name: String, x: Float, y: Float, z: Float, w: Float)

    /** A colour, as the shader's `float3`: sRGB, straight (not premultiplied). */
    fun setUniform(name: String, color: Color)

    /** The shader as its uniforms stand now, to draw with. */
    fun brush(): Brush
}

/** The platform's [AssistantGlowShader], or null where runtime shaders aren't available. */
internal expect fun assistantGlowShaderOrNull(): AssistantGlowShader?

/** How far past the box its light reaches. */
internal val GLOW_SPREAD: Dp = 36.dp

/** Claude's coral, a warm amber, violet and a clear blue, in the order they flow round the edge. */
internal val GLOW_COLORS = listOf(Color(0xFFD97757), Color(0xFFF2B36B), Color(0xFF9D7BF5), Color(0xFF5B9CF7))

/**
 * The glow, in AGSL (and so also valid SkSL). fragCoord is in the box's own coordinates: `box` is
 * its left, top, right and bottom (0, 0, width, height), and the area drawn reaches `spread` px
 * past it on every side.
 *
 * - The box is a rounded rectangle of corner `radius`; `d` is the signed distance from its edge.
 * - Colour flows round the edge: the four colours `c0`..`c3` in a loop by angle about the centre,
 *   turning with `time` and wavering a little, so it never looks like a still gradient.
 * - Outside, light falls off over `spread` with ripples running outward; on the edge a thin
 *   bright rim. `intensity` scales both: a burst as the box opens, a quiet breath while it waits.
 * - Output is premultiplied, with alpha from the light, so over the screen it composites as glow.
 */
internal const val ASSISTANT_GLOW_SHADER = """
uniform float4 box;
uniform float radius;
uniform float spread;
uniform float time;
uniform float intensity;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;

const float TAU = 6.2831853;

float roundedBox(float2 p, float2 extent, float r) {
    float2 q = abs(p) - extent + r;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float3 loopColors(float t) {
    float s = fract(t) * 4.0;
    float f = smoothstep(0.0, 1.0, fract(s));
    if (s < 1.0) return mix(c0, c1, f);
    if (s < 2.0) return mix(c1, c2, f);
    if (s < 3.0) return mix(c2, c3, f);
    return mix(c3, c0, f);
}

half4 main(float2 p) {
    float2 centre = (box.xy + box.zw) * 0.5;
    float2 extent = (box.zw - box.xy) * 0.5;
    float2 d2 = p - centre;
    float d = roundedBox(d2, extent, min(radius, min(extent.x, extent.y)));

    // Angle round the box, stretched so a wide box's long sides don't get one colour each.
    float2 squashed = d2 / max(extent, float2(1.0));
    float around = atan(squashed.y, squashed.x) / TAU;
    float waver = 0.06 * sin(time * 0.8 + p.x * 0.006) + 0.04 * sin(time * 1.3 - p.y * 0.01);
    float3 colour = loopColors(around + time * 0.11 + waver);

    float outside = max(d, 0.0);
    float halo = exp(-outside / max(spread * 0.32, 1.0)) * step(0.0, d);
    float ripple = 0.65 + 0.35 * sin(outside * 0.09 - time * 3.2);
    // The rim's width goes with the glow's reach, so it is the same on every screen density.
    float rim = 1.0 - smoothstep(0.0, spread * 0.05, abs(d));
    // A whiter core where the rim is hottest, as light is.
    float3 hot = mix(colour, float3(1.0), 0.35 * rim * clamp(intensity - 0.8, 0.0, 1.0));

    float light = halo * ripple * intensity * 0.9 + rim * clamp(intensity * 1.3, 0.0, 1.0);
    light *= smoothstep(-4.0, -1.0, d);
    float alpha = clamp(light, 0.0, 1.0);
    return half4(half3(hot * alpha), half(alpha));
}
"""
