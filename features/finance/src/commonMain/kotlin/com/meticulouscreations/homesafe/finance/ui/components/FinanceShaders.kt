package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A compiled runtime shader whose uniforms can be set and drawn as a brush: an AGSL
 * `RuntimeShader` on Android, a Skia `RuntimeEffect` elsewhere (the same source compiles as
 * both, as the pull-to-refresh band's does — see ApertureRefresh).
 */
internal interface FinanceShader {
    fun setUniform(name: String, value: Float)

    fun setUniform(name: String, x: Float, y: Float)

    fun setUniform(name: String, color: Color)

    /** The shader as its uniforms stand now, to draw with. */
    fun brush(): Brush
}

/** [source] compiled, or null where it can't be (Android Studio's preview renderer, say): callers draw a plain fallback. */
internal expect fun financeShaderOrNull(source: String): FinanceShader?

/**
 * Slow drifting aurora bands — two fbm noise fields folded into each other — that fade toward
 * the bottom so the numbers over them stay readable. Tinted by the screen's direction: green
 * when the money's up, orange-red when it's down. [intensity] fades it in as data arrives.
 */
internal const val AURORA_SHADER = """
uniform float2 size;
uniform float time;
uniform float3 tint;
uniform float3 tint2;
uniform float intensity;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float aspect = size.x / max(size.y, 1.0);
    float2 p = float2(uv.x * aspect, uv.y) * 1.8;
    float t = time * 0.07;
    float warp = fbm(p * 1.3 + float2(-t, t * 0.5));
    float n = fbm(p + float2(t, -t * 0.6) + warp * 1.4);
    float band = smoothstep(0.38, 0.86, n);
    float streak = smoothstep(0.55, 1.0, fbm(float2(p.x * 3.0 + t * 2.0, p.y * 0.4)));
    float fade = (1.0 - uv.y);
    fade = fade * fade;
    float alpha = clamp((band * 0.8 + streak * 0.35) * intensity * (0.2 + 0.8 * fade), 0.0, 1.0) * 0.6;
    float3 col = mix(tint2, tint, band);
    return half4(half3(col * alpha), half(alpha));
}
"""

/**
 * The stress gauge: a 270° ring from bottom-left round to bottom-right, filled to [level] with a
 * gradient from [calm] through [warn] to [hot] along the arc. Plasma flows along the filled part,
 * faster and more turbulent the higher [heat] (the stress itself) is, the ring wobbles with it,
 * and a soft glow and a bright head sit at the fill's tip.
 */
internal const val STRESS_RING_SHADER = """
uniform float2 size;
uniform float time;
uniform float level;
uniform float heat;
uniform float3 calm;
uniform float3 warn;
uniform float3 hot;
uniform float3 track;

float hash(float2 p) {
    return fract(sin(dot(p, float2(41.3, 289.1))) * 15731.743);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float3 ramp(float f) {
    if (f < 0.5) return mix(calm, warn, f * 2.0);
    return mix(warn, hot, (f - 0.5) * 2.0);
}

half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 d = p - c;
    float radius = min(size.x, size.y) * 0.5;
    float r = length(d) / radius;
    float ang = atan(d.y, d.x);
    float a = mod(ang - 2.35619449 + 6.28318531, 6.28318531);
    float f = a / 4.71238898;
    float inArc = 1.0 - step(1.0, f);

    float wob = (noise(float2(f * 9.0, time * (0.6 + heat * 2.0))) - 0.5) * heat * 0.05;
    float dist = abs(r - 0.80 - wob);
    float halfW = 0.075;
    float ring = (1.0 - smoothstep(halfW - 0.015, halfW, dist)) * inArc;
    float filled = 1.0 - step(level, f);

    float3 grad = ramp(f);
    float flow = 0.5 + 0.5 * sin(f * 38.0 - time * (2.0 + heat * 6.0) + r * 24.0);
    float sparkle = noise(float2(f * 60.0 - time * 4.0, r * 30.0));
    float3 col = mix(track, grad * (0.7 + 0.3 * flow + 0.25 * sparkle * heat), filled);
    float alpha = ring * mix(0.55, 1.0, filled);

    float glow = exp(-dist * 16.0) * filled * inArc * (0.25 + 0.55 * heat);

    float2 headDir = float2(cos(level * 4.71238898 + 2.35619449), sin(level * 4.71238898 + 2.35619449));
    float2 head = c + headDir * radius * 0.80;
    float headD = length(p - head) / radius;
    float headGlow = exp(-headD * 26.0) * step(0.001, level) * (0.8 + 0.2 * sin(time * 5.0));

    float outA = clamp(alpha + glow * 0.8 + headGlow, 0.0, 1.0);
    // Premultiplied: no channel may exceed alpha, or the glow and the tip blow out.
    float3 outC = min(col * alpha + grad * glow + ramp(level) * headGlow, float3(outA));
    return half4(half3(outC), half(outA));
}
"""

/**
 * A chart's Aurora fill: curtains of light drifting sideways under the line, bright just under
 * the line's peak ([top]) and gone by the chart's floor ([bottom]). Drawn clipped to the area
 * under the line.
 */
internal const val CHART_AURORA_SHADER = """
uniform float2 size;
uniform float time;
uniform float3 tint;
uniform float top;
uniform float bottom;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p = p * 2.07 + float2(3.1, 1.7);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 p) {
    float depth = clamp((p.y - top) / max(bottom - top, 1.0), 0.0, 1.0);
    float2 uv = p / max(size.y, 1.0);
    float t = time * 0.18;
    float n = fbm(float2(uv.x * 2.4 + t, uv.y * 2.0 - t * 0.5));
    float curtain = 0.5 + 0.5 * sin(uv.x * 11.0 + n * 7.0 - t * 3.0);
    float fade = 1.0 - depth;
    float alpha = clamp(fade * (0.35 + 0.65 * n) * (0.45 + 0.55 * curtain), 0.0, 1.0) * 0.75;
    // The curtains shimmer between the line's colour and its channels turned round (green to
    // violet-blue, orange to green), the way a real aurora shifts hue along its folds.
    float3 col = mix(tint, tint.brg, 0.55 * curtain * (1.0 - n));
    col = mix(col, float3(1.0), 0.15 * curtain * fade);
    return half4(half3(col * alpha), half(alpha));
}
"""

/**
 * A chart's Halftone fill: a screen of dots under the line, big just under its peak ([top]) and
 * shrinking to nothing at the floor ([bottom]), swelling and brightening near [scrub] (the
 * finger's x, or negative when there's none). [cell] is the dot pitch in pixels.
 */
internal const val CHART_HALFTONE_SHADER = """
uniform float3 tint;
uniform float top;
uniform float bottom;
uniform float cell;
uniform float scrub;

half4 main(float2 p) {
    float2 center = floor(p / cell) * cell + cell * 0.5;
    float depth = clamp((center.y - top) / max(bottom - top, 1.0), 0.0, 1.0);
    float near = scrub >= 0.0 ? exp(-abs(center.x - scrub) / (cell * 3.5)) : 0.0;
    float radius = min(cell * 0.5, cell * 0.38 * (1.0 - depth) * (1.0 + 0.7 * near));
    float d = length(p - center);
    float disc = 1.0 - smoothstep(radius - 0.8, radius + 0.8, d);
    float alpha = disc * (0.45 + 0.4 * near);
    return half4(half3(tint * alpha), half(alpha));
}
"""

/**
 * The month's budget as a glass tank of liquid. It stands as high as [level] of the limit has
 * been spent (1 is the brim), and it shows how the month is going without a number being read:
 * [heat] takes it from [calm] through [warn] to [hot], roughens the surface and sends up more
 * bubbles, and past the brim the rim itself glows and beats. [corner] rounds the tank, in pixels.
 */
internal const val BUDGET_TANK_SHADER = """
uniform float2 size;
uniform float time;
uniform float level;
uniform float heat;
uniform float corner;
uniform float3 calm;
uniform float3 warn;
uniform float3 hot;
uniform float3 glass;

float hash(float2 p) {
    return fract(sin(dot(p, float2(91.7, 173.3))) * 24634.6345);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p = p * 2.07 + float2(3.1, 7.4);
        a *= 0.5;
    }
    return v;
}

float3 ramp(float f) {
    if (f < 0.5) return mix(calm, warn, f * 2.0);
    return mix(warn, hot, (f - 0.5) * 2.0);
}

float box(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 p) {
    float2 uv = p / size;
    float aspect = size.x / max(size.y, 1.0);
    float d = box(p - size * 0.5, size * 0.5, corner);
    float inside = 1.0 - smoothstep(-1.0, 0.5, d);
    float fill = clamp(level, 0.0, 1.0);
    float some = step(0.0005, fill);
    float over = clamp((level - 1.0) * 2.0, 0.0, 1.0);

    // Full leaves a little air under the rim, so the surface is still seen to move.
    float surface = 1.0 - fill * 0.93;
    float amp = 0.006 + 0.02 * heat;
    float speed = 0.8 + 2.4 * heat;
    float wave = sin(uv.x * 9.0 + time * speed) * amp
        + sin(uv.x * 21.0 - time * speed * 1.7) * amp * 0.5
        + (noise(float2(uv.x * 6.0, time * (0.5 + heat))) - 0.5) * amp * 1.5;
    float y = uv.y - surface - wave;
    float edge = 1.5 / max(size.y, 1.0);
    float liquid = smoothstep(-edge, edge, y) * some;
    float depth = clamp(y / max(1.0 - surface, 0.001), 0.0, 1.0);

    float3 body = ramp(clamp(heat * 0.85 + over * 0.15, 0.0, 1.0));
    float2 q = float2(uv.x * aspect, uv.y) * 3.0;
    float caustic = fbm(q + float2(time * 0.12, -time * 0.2 * (0.5 + heat)) + fbm(q * 1.7 - float2(time * 0.1)));
    float3 col = body * (0.5 + 0.5 * (1.0 - depth)) * (0.75 + 0.55 * caustic);
    float alpha = liquid * (0.74 + 0.22 * (1.0 - depth));

    // The bright line where the liquid meets the air.
    float meniscus = exp(-abs(y) * size.y / 4.0) * some;

    // One bubble a column, rising; a calm month has few columns with any.
    float columns = 16.0;
    float column = floor(uv.x * columns);
    float seed = hash(float2(column, 7.0));
    float rise = fract(seed * 13.0 + time * (0.06 + 0.22 * heat) * (0.5 + seed));
    float2 at = float2((column + 0.5 + (seed - 0.5) * 0.6) / columns, 1.0 - rise * (1.0 - surface));
    float bd = length((uv - at) * float2(aspect, 1.0)) * size.y;
    float bubble = (1.0 - smoothstep(1.5, 3.5 + seed * 2.5, bd)) * step(hash(float2(column, 3.0)), 0.12 + 0.88 * heat) * liquid;

    float3 rgb = col * alpha + body * meniscus * 0.7 + float3(bubble * 0.3);
    alpha = max(alpha, meniscus * 0.7) + bubble * 0.3;

    // The glass: a breath of it where the tank is empty, and its rim.
    float rim = 1.0 - smoothstep(0.0, 2.5, abs(d + 1.5));
    float glassAlpha = (1.0 - liquid) * 0.05 + rim * 0.24;
    rgb += glass * glassAlpha;
    alpha += glassAlpha;

    // Over the brim: the rim takes the heat and beats with it.
    float beat = 0.5 + 0.5 * sin(time * 3.0);
    float glow = exp(d * 0.1) * over * (0.3 + 0.35 * beat);
    rgb += hot * glow;
    alpha += glow;

    alpha = clamp(alpha, 0.0, 1.0) * inside;
    // Premultiplied: no channel may exceed alpha.
    rgb = min(rgb * inside, float3(alpha));
    return half4(half3(rgb), half(alpha));
}
"""

/**
 * One bucket's spending against its limit, as a capsule of flowing plasma filled to [level] of
 * the limit, on a [track]. It warms with [heat] from [calm] through [warn] to [hot] and carries
 * a bright tip; past the limit the capsule is full and embers lift off its end. The capsule sits
 * low in the box, which is taller than it is, so the embers have somewhere to go.
 */
internal const val BUDGET_HEAT_METER_SHADER = """
uniform float2 size;
uniform float time;
uniform float level;
uniform float heat;
uniform float3 calm;
uniform float3 warn;
uniform float3 hot;
uniform float3 track;

float hash(float2 p) {
    return fract(sin(dot(p, float2(53.9, 197.3))) * 31873.117);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float3 ramp(float f) {
    if (f < 0.5) return mix(calm, warn, f * 2.0);
    return mix(warn, hot, (f - 0.5) * 2.0);
}

half4 main(float2 p) {
    float thick = size.y * 0.34;
    float r = thick * 0.5;
    float cy = size.y - r - 1.0;
    float x0 = r;
    float x1 = size.x - r;
    float d = length(p - float2(clamp(p.x, x0, x1), cy)) - r;
    float capsule = 1.0 - smoothstep(-0.75, 0.75, d);

    float f = clamp(p.x / max(size.x, 1.0), 0.0, 1.0);
    float fill = clamp(level, 0.0, 1.0);
    float some = step(0.001, fill);
    float px = 1.0 / max(size.x, 1.0);
    float filled = (1.0 - smoothstep(fill - px, fill + px, f)) * some;

    float3 grad = ramp(clamp(heat * 0.8 + f * 0.25 * heat, 0.0, 1.0));
    float flow = 0.5 + 0.5 * sin(f * 30.0 - time * (1.5 + heat * 5.0));
    float sparkle = noise(float2(f * 50.0 - time * 3.0, p.y * 0.3));
    float3 col = mix(track, grad * (0.72 + 0.28 * flow + 0.25 * sparkle * heat), filled);
    float alpha = capsule * mix(0.7, 1.0, filled);

    float2 tip = float2(mix(x0, x1, fill), cy);
    float td = length(p - tip) / max(thick, 1.0);
    float tipGlow = exp(-td * td * 3.0) * some * (0.45 + 0.3 * heat + 0.12 * sin(time * 5.0));

    // Past the limit: sparks off the far end, each rising, drifting and dying.
    float over = clamp((level - 1.0) * 4.0, 0.0, 1.0);
    float2 e = float2((p.x - x1) / max(thick, 1.0), (cy - p.y) / max(thick, 1.0));
    float ember = 0.0;
    for (int i = 0; i < 6; i++) {
        float s = hash(float2(float(i), 5.0));
        float t = fract(time * (0.35 + 0.3 * s) + s * 7.0);
        float2 pos = float2((s - 0.8) * 2.4 + sin(t * 6.0 + s * 9.0) * 0.25, t * 1.9);
        float dd = length(e - pos);
        ember += exp(-dd * dd * 40.0) * (1.0 - t);
    }
    ember *= over;

    float outA = clamp(alpha + tipGlow * 0.9 + ember, 0.0, 1.0);
    // Premultiplied: no channel may exceed alpha, or the tip and the embers blow out.
    float3 outC = min(col * alpha + ramp(clamp(heat + 0.2, 0.0, 1.0)) * tipGlow + hot * ember, float3(outA));
    return half4(half3(outC), half(outA));
}
"""
