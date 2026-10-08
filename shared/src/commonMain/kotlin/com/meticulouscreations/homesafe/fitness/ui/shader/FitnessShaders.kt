package com.meticulouscreations.homesafe.fitness.ui.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A compiled runtime shader whose uniforms can be set and drawn as a brush: an AGSL
 * `RuntimeShader` on Android, a Skia `RuntimeEffect` elsewhere (the same source compiles as
 * both, as the finance and weather apps' do).
 */
internal interface FitnessShader {
    fun setUniform(name: String, value: Float)

    fun setUniform(name: String, x: Float, y: Float)

    /** A colour as a `float3`, not premultiplied. */
    fun setUniform(name: String, color: Color)

    /** The shader as its uniforms stand now, to draw with. */
    fun brush(): Brush
}

/** [source] compiled, or null where it can't be (Android Studio's preview renderer, say): callers draw a plain fallback. */
internal expect fun fitnessShaderOrNull(source: String): FitnessShader?

// The sources below follow the conventions the other apps' shaders do: `half4 main(float2)` in
// pixel coordinates with y down, premultiplied colour out, loops with constant bounds.

private const val NOISE = """
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
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
"""

/**
 * The forge: heat rising off a bed of coals. Noise warped through itself twice, so it rolls and
 * licks upward the way a fire's glow does, with a few sparks carried up through it. [tint] is
 * the heart of the heat and [tint2] its edge; [heat] 0–1 is how hard the bellows are going
 * (brighter, quicker, more sparks). It fades out down the screen so what is written over it
 * stays readable.
 */
internal const val FORGE_SHADER = """
uniform float2 size;
uniform float time;
uniform float3 tint;
uniform float3 tint2;
uniform float heat;
uniform float intensity;
$NOISE
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float aspect = size.x / max(size.y, 1.0);
    float2 p = float2(uv.x * aspect, uv.y) * 2.4;
    float t = time * (0.10 + 0.12 * heat);
    float2 q = float2(fbm(p + float2(0.0, t * 1.4)), fbm(p + float2(5.2, 1.3 + t)));
    float2 r = float2(fbm(p + 2.6 * q + float2(1.7, 9.2 + t * 1.8)), fbm(p + 2.6 * q + float2(8.3, 2.8 - t * 0.7)));
    float f = fbm(p + 2.4 * r + float2(0.0, t * 2.2));
    float glow = smoothstep(0.22, 0.92, f + 0.3 * length(r) - 0.1);
    float core = smoothstep(0.58, 0.98, f * (0.75 + 0.7 * r.x));

    float2 g = float2(uv.x * aspect * 13.0, uv.y * 13.0 + time * (0.5 + 0.9 * heat));
    float2 cell = floor(g);
    float2 local = fract(g) - 0.5;
    float rnd = hash(cell);
    float2 offs = float2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5;
    float d = length(local - offs * 0.6);
    float spark = step(0.90 - 0.10 * heat, rnd) * smoothstep(0.10, 0.0, d) * (0.4 + 0.6 * sin(time * 3.0 + rnd * 40.0));
    spark = max(spark, 0.0);

    float fade = 1.0 - smoothstep(0.10, 1.0, uv.y);
    float3 col = mix(tint2, tint, glow) * glow * (0.50 + 0.50 * heat);
    col += float3(1.0, 0.86, 0.62) * core * (0.18 + 0.30 * heat);
    col += mix(tint, float3(1.0, 0.92, 0.75), 0.5) * spark * 1.3;
    col = clamp(col * fade * intensity, 0.0, 1.0);
    float alpha = clamp(max(max(col.r, col.g), col.b) * 1.15, 0.0, 1.0);
    return half4(half3(min(col, float3(alpha))), half(alpha));
}
"""

/**
 * Muscle, close up: a card's worth of fibres running slantwise, each strand its own brightness,
 * with a wave of contraction travelling down them. Opaque (it is a card's whole background).
 * [seed] makes each card's weave its own; [energy] 0–1 quickens and brightens the wave, for the
 * card that is up next or under a finger.
 */
internal const val FIBER_SHADER = """
uniform float2 size;
uniform float time;
uniform float3 tint;
uniform float3 tint2;
uniform float seed;
uniform float energy;
$NOISE
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float aspect = size.x / max(size.y, 1.0);
    float2 p = float2(uv.x * aspect, uv.y);
    float ang = 0.42 + seed * 0.5;
    float2 rp = float2(p.x * cos(ang) - p.y * sin(ang), p.x * sin(ang) + p.y * cos(ang));
    float bend = fbm(float2(rp.x * 1.1 + time * 0.04 + seed * 9.0, rp.y * 1.6)) - 0.5;
    float y = rp.y * 26.0 + bend * 7.0 + sin(rp.x * 2.6 + time * 0.5 + seed * 6.28) * 0.9;
    float strand = floor(y);
    float across = abs(fract(y) - 0.5) * 2.0;
    float line = smoothstep(0.95, 0.15, across);
    float weight = hash(float2(strand, seed * 13.0));
    float wave = 0.5 + 0.5 * sin(rp.x * 5.0 - time * (0.9 + 1.6 * energy) + strand * 0.55 + bend * 4.0);
    wave = wave * wave;
    float lum = line * (0.22 + 0.78 * weight) * (0.30 + 0.70 * wave) * (0.75 + 0.45 * energy);
    float light = 1.0 - smoothstep(0.0, 1.25, length(uv - float2(0.92, 0.05)));
    float3 base = mix(tint2 * 0.22, tint2 * 0.60, light);
    float3 col = base + mix(tint2, tint, wave) * lum * (0.45 + 0.75 * light);
    col += float3(1.0, 0.95, 0.85) * line * wave * wave * weight * 0.10 * (0.5 + energy);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * A gauge that goes all the way round: a ring filled clockwise from the top to [level] 0–1, in
 * plasma that runs along it from [tint2] at the start to [tint] at the head, with a bright bead
 * and a bloom where the fill ends. The rest timer and the "how much of your best" gauge.
 * [thickness] is the ring's width as a fraction of its radius.
 */
internal const val RING_SHADER = """
uniform float2 size;
uniform float time;
uniform float level;
uniform float3 tint;
uniform float3 tint2;
uniform float3 track;
uniform float thickness;
$NOISE
half4 main(float2 fragCoord) {
    float2 c = size * 0.5;
    float2 d = fragCoord - c;
    float radius = min(size.x, size.y) * 0.5;
    float w = radius * thickness * 0.5;
    float ringR = radius - w - radius * 0.07;
    float r = length(d);
    float ang = atan(d.x, -d.y);
    float along = (ang < 0.0 ? ang + 6.2831853 : ang) / 6.2831853;
    float lv = clamp(level, 0.0, 1.0);
    float band = smoothstep(w + 1.0, w - 1.0, abs(r - ringR));
    float filled = lv >= 0.999 ? 1.0 : smoothstep(lv + 0.003, lv - 0.003, along);
    float n = fbm(float2(along * 16.0 - time * 0.7, (r - ringR) / max(w, 1.0) * 1.4 + time * 0.25));
    float3 arc = mix(tint2, tint, clamp(along / max(lv, 0.001), 0.0, 1.0)) * (0.72 + 0.56 * n);
    float tipA = lv * 6.2831853;
    float2 tip = c + float2(sin(tipA), -cos(tipA)) * ringR;
    float shown = step(0.002, lv);
    float bead = exp(-length(fragCoord - tip) / max(w * 1.5, 1.0)) * shown;
    float bloom = smoothstep(w * 3.4, 0.0, abs(r - ringR)) * 0.22 * filled * shown;
    float3 col = track * band * (1.0 - filled);
    col += arc * band * filled;
    col += tint * bloom;
    col += mix(tint, float3(1.0), 0.6) * bead * 0.95;
    float alpha = clamp(band * (0.55 + 0.45 * filled) + bloom + bead, 0.0, 1.0);
    col = clamp(col, 0.0, 1.0);
    return half4(half3(min(col, float3(alpha))), half(alpha));
}
"""

/**
 * A record going off: a white-hot flash at [center], a ring of force running out from it, rays
 * behind the ring and sparks thrown clear. [progress] runs 0 to 1 once and everything has gone
 * by the end of it.
 */
internal const val BURST_SHADER = """
uniform float2 size;
uniform float2 center;
uniform float progress;
uniform float3 tint;
uniform float3 tint2;
$NOISE
half4 main(float2 fragCoord) {
    float scale = max(size.x, size.y);
    float2 d = (fragCoord - center) / scale;
    float r = length(d);
    float ang = atan(d.y, d.x);
    float p = clamp(progress, 0.0, 1.0);
    float ease = 1.0 - (1.0 - p) * (1.0 - p) * (1.0 - p);
    float fadeOut = 1.0 - smoothstep(0.45, 1.0, p);
    float ringR = ease * 0.85;
    float ring = smoothstep(0.06 * (1.0 - 0.6 * p), 0.0, abs(r - ringR)) * fadeOut;
    float rays = pow(abs(sin(ang * 7.0 + 1.3)), 10.0) + 0.6 * pow(abs(sin(ang * 13.0 - 0.7)), 14.0);
    rays *= smoothstep(ringR, ringR * 0.25, r) * smoothstep(0.0, 0.06, r) * fadeOut * 0.55;
    float flash = exp(-r * 16.0) * (1.0 - smoothstep(0.0, 0.45, p)) * 1.6;

    float2 g = float2(ang * 5.0, r * 16.0 - ease * 9.0);
    float2 cell = floor(g);
    float2 local = fract(g) - 0.5;
    float rnd = hash(cell + 11.0);
    float sparks = step(0.72, rnd) * smoothstep(0.22, 0.0, length(local)) * smoothstep(ringR + 0.05, ringR - 0.35, r) * smoothstep(0.02, 0.12, r) * fadeOut;

    float3 col = tint * (ring * 1.25 + flash) + tint2 * rays + mix(tint, float3(1.0), 0.7) * sparks * 1.2;
    col = clamp(col, 0.0, 1.0);
    float alpha = clamp(max(max(col.r, col.g), col.b), 0.0, 1.0);
    return half4(half3(min(col, float3(alpha))), half(alpha));
}
"""

/** Every shader here, by name, for the tests that check each compiles. */
internal val FITNESS_SHADERS = mapOf(
    "forge" to FORGE_SHADER,
    "fiber" to FIBER_SHADER,
    "ring" to RING_SHADER,
    "burst" to BURST_SHADER,
)
