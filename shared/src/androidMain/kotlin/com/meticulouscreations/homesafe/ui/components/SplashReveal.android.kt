package com.meticulouscreations.homesafe.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The splash as an AGSL [RuntimeShader] applied to the content's own layer as a [RenderEffect],
 * so the water refracts the real screen underneath it — live video included, since the players
 * draw through TextureViews that Compose composites into this layer.
 *
 * minSdk is 33, so RuntimeShader (API 33) is always there. The uniforms are rewritten and a new
 * RenderEffect wrapped around the shader on every frame of the transition; that is the documented
 * way to animate one (an effect snapshots its shader's uniforms when it is created).
 */
actual fun Modifier.splashReveal(progress: () -> Float, originFraction: Offset): Modifier = graphicsLayer {
    val p = progress()
    if (p >= 1f || size.width <= 0f || size.height <= 0f) {
        renderEffect = null
        return@graphicsLayer
    }
    val origin = Offset(originFraction.x * size.width, originFraction.y * size.height)
    val shader = splashShader
    shader.setFloatUniform("origin", origin.x, origin.y)
    shader.setFloatUniform("progress", p.coerceIn(0f, 1f))
    shader.setFloatUniform("maxRadius", splashMaxRadius(origin, size.width, size.height))
    shader.setFloatUniform("density", density)
    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}

/**
 * Compiled once, on first use. Only one splash runs at a time and every frame's effect snapshots
 * the uniforms it was made with, so sharing the instance is safe and spares a recompile each time
 * the screen that uses it recomposes.
 */
private val splashShader: RuntimeShader by lazy(LazyThreadSafetyMode.NONE) { RuntimeShader(SPLASH_AGSL) }

/**
 * The water, as a height field around the point of impact:
 *
 *  - The front sits at a radius that eases out along the same curve as [splashFrontRadius]. Its
 *    rim is a crown — whole-number harmonics of the angle, sharpened into jets where they peak —
 *    roughened by value noise sampled on the unit circle (so it closes on itself all the way
 *    round, with no seam at ±π). It stands proud at impact and relaxes to a clean circle.
 *  - On the front, a Gaussian ridge of water; behind it, capillary ripples that die away
 *    within a short distance and fade faster than the ridge, so the sheet behind lies flat. Everything is scaled by `calm`, which goes to exactly 0 at the end, so the last
 *    frame of the splash is the undistorted content and handing over to no effect is seamless.
 *  - The surface slope (analytic, per unit of each feature's own width) bends where the content is
 *    sampled — refraction — a little differently for red, green and blue, which is the fringing
 *    real water shows at an edge. The same slope tilts a normal for a Blinn-Phong glint from a
 *    light above and to the left (the flat-water baseline subtracted so only curvature shines),
 *    and for a Fresnel sheen where the surface is steep. Thick water takes a faint cool cast.
 *  - Just inside the rim, the meniscus: the thin dark line where water meets glass, with a sliver
 *    of light on the side facing the light — what makes a drop on a window read as a drop.
 *  - Outside the front the content is transparent, apart from a soft contact shadow ahead of the
 *    rim (darkening the screen being covered), a faint caustic where the ridge focuses light onto
 *    it, and droplets thrown out at random moments. Each droplet is stretched along its flight
 *    while it's fast, lands round, and sits as a bead until the front swallows it; each is a tiny
 *    ball lens showing the new screen magnified and upside-down, dark at its rim, with a glint.
 *
 * Distances are in pixels, scaled by `density` so the water looks the same size on every screen.
 */
private const val SPLASH_AGSL = """
uniform shader content;
uniform float2 origin;
uniform float progress;
uniform float maxRadius;
uniform float density;

const float TAU = 6.2831853;

float hash11(float n) {
    return fract(sin(n * 127.1) * 43758.5453);
}

float hash21(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// The rim's irregularity, about -0.5..0.5, as a function of the direction (a point on the unit
// circle) rather than the angle, so it is seamless all the way round.
float rimNoise(float2 dir) {
    return vnoise(dir * 2.5 + float2(3.1, 5.3)) * 0.55
         + vnoise(dir * 6.0 + float2(7.7, 1.9)) * 0.30
         + vnoise(dir * 14.0 + float2(1.3, 8.2)) * 0.15
         - 0.5;
}

float crown(float a) {
    return 0.50 * sin(5.0 * a + 1.3)
         + 0.30 * sin(8.0 * a + 4.1)
         + 0.18 * sin(13.0 * a + 2.2)
         + 0.10 * sin(21.0 * a + 5.7);
}

// x: water height at distance r from the impact; y: its slope going outwards, in units of each
// feature's own width so the ridge and the ripples bend light by comparable amounts.
float2 water(float r, float edge, float ridgeWidth, float wavelength, float decay, float rippleAmp) {
    float b = edge - r;
    float u = b / ridgeWidth;
    float ridge = exp(-u * u);
    float ridgeSlope = 2.0 * u * ridge;
    float height = ridge;
    float slope = ridgeSlope;
    if (b > 0.0) {
        float k = TAU / wavelength;
        float fall = exp(-b / decay);
        height += rippleAmp * sin(b * k) * fall;
        slope += -rippleAmp * (cos(b * k) - sin(b * k) / (k * decay)) * fall;
    }
    return float2(height, slope);
}

half4 main(float2 fragCoord) {
    float t = clamp(progress, 0.0, 1.0);
    float eased = 1.0 - pow(1.0 - t, 3.0);
    float front = eased * maxRadius * 1.12;
    float calm = pow(1.0 - t, 1.3);
    float px = density;

    float2 d = fragCoord - origin;
    float r = length(d);
    float2 dir = r > 0.001 ? d / r : float2(0.0, 0.0);
    float a = atan(d.y, d.x);

    // Lobes sharpened into jets where the crown peaks, softened where it dips, then roughened.
    float lobes = crown(a);
    float jets = pow(max(lobes, 0.0), 1.6) * 1.4 + min(lobes, 0.0) * 0.5;
    float wobble = jets * 0.6 + rimNoise(dir) * 0.9;
    float edge = front + wobble * (0.06 * calm + 0.008) * front;
    float inside = 1.0 - smoothstep(edge - 1.25 * px, edge + 1.25 * px, r);

    float3 lightDir = normalize(float3(-0.45, -0.75, 0.8));
    float3 halfVec = normalize(lightDir + float3(0.0, 0.0, 1.0));
    float flatGlint = pow(halfVec.z, 60.0);
    float2 towardLight = normalize(lightDir.xy);

    float4 color = float4(0.0);
    if (inside > 0.0) {
        float ridgeWidth = (10.0 + 18.0 * calm) * px;
        // Ripples trail close behind the front and die away quickly, so the film behind them lies
        // flat — a real splash thins to a calm sheet — and fade out faster than the ridge does.
        float wavelength = (18.0 + 10.0 * t) * px;
        float decay = (38.0 + 60.0 * t) * px;
        float2 hs = water(r, edge, ridgeWidth, wavelength, decay, 0.32 * calm) * calm;

        float2 bend = dir * hs.y * 26.0 * px;
        float4 red = float4(content.eval(fragCoord + bend * 1.07));
        float4 green = float4(content.eval(fragCoord + bend));
        float4 blue = float4(content.eval(fragCoord + bend * 0.93));
        color = float4(red.r, green.g, blue.b, green.a);

        // Thick water absorbs a touch of red: a faint cool cast where the ridge stands.
        float thickness = clamp(hs.x, 0.0, 1.0);
        color.rgb *= mix(float3(1.0), float3(0.90, 0.96, 1.0), thickness * 0.8);

        float3 normal = normalize(float3(-dir * hs.y * 1.6, 1.0));
        float glint = max(pow(max(dot(normal, halfVec), 0.0), 60.0) - flatGlint, 0.0);
        float fresnel = pow(1.0 - normal.z, 3.0);
        color.rgb *= 1.0 + 0.16 * hs.x;
        color.rgb += (0.85 * glint + 0.06 * max(hs.x, 0.0) + 0.5 * fresnel) * color.a;

        // The meniscus, just inside the rim: dark where water meets glass, lit on the near side.
        float m = (edge - r - 2.0 * px) / (1.6 * px);
        float meniscus = exp(-m * m) * calm;
        float facing = max(dot(dir, towardLight), 0.0);
        color.rgb *= 1.0 - 0.45 * meniscus;
        color.rgb += 0.55 * meniscus * facing * facing * color.a;
        color.rgb = clamp(color.rgb, 0.0, color.a);
    }

    float4 outside = float4(0.0);
    if (inside < 1.0) {
        float gap = r - edge;
        float s = (gap - 8.0 * px) / (16.0 * px);
        float shadow = gap > 0.0 ? 0.28 * calm * exp(-s * s) : 0.0;
        // Light the ridge focuses lands just ahead of it: a faint bright band on the old screen.
        float cs = (gap - 3.0 * px) / (4.0 * px);
        float caustic = gap > 0.0 ? 0.10 * calm * exp(-cs * cs) : 0.0;
        float4 ground = float4(caustic, caustic, caustic, caustic + shadow * (1.0 - caustic));

        float4 drops = float4(0.0);
        for (int i = 0; i < 22; i++) {
            float fi = float(i);
            float launch = 0.03 + 0.32 * hash11(fi + 17.0);
            float age = t - launch;
            if (age > 0.0) {
                float flight = clamp(age / 0.30, 0.0, 1.0);
                float ang = hash11(fi + 1.0) * TAU;
                float speed = 0.6 + 0.8 * hash11(fi + 43.0);
                float launchFront = (1.0 - pow(1.0 - launch, 3.0)) * maxRadius * 1.12;
                float travel = (40.0 + 140.0 * speed) * px * (1.0 - (1.0 - flight) * (1.0 - flight));
                float2 fwd = float2(cos(ang), sin(ang));
                float2 side = float2(-fwd.y, fwd.x);
                float2 centre = origin + fwd * (launchFront + 6.0 * px + travel);
                float radius = (2.5 + 5.5 * hash11(fi + 91.0)) * px;
                // Drawn out along its path while it's fast; round once it lands.
                float stretch = 1.0 + 1.8 * (1.0 - flight);
                float2 rel = fragCoord - centre;
                float2 q = float2(dot(rel, fwd) / (radius * stretch), dot(rel, side) / radius);
                float qq = dot(q, q);
                if (qq < 1.0) {
                    float z = sqrt(1.0 - qq);
                    float3 n = normalize(float3(fwd * q.x + side * q.y, z));
                    float4 seen = float4(content.eval(centre - (fwd * q.x * stretch + side * q.y) * radius * 1.6));
                    float dropGlint = pow(max(dot(n, halfVec), 0.0), 40.0);
                    float rimDark = smoothstep(0.55, 1.0, qq);
                    float cover = 1.0 - smoothstep(0.82, 1.0, qq);
                    float3 rgb = clamp(seen.rgb * (0.70 + 0.30 * z) * (1.0 - 0.35 * rimDark) + 0.9 * dropGlint, 0.0, 1.0);
                    float4 drop = float4(rgb * cover, cover);
                    drops = drop + drops * (1.0 - drop.a);
                }
            }
        }
        outside = drops + ground * (1.0 - drops.a);
    }

    return half4(color * inside + outside * (1.0 - inside));
}
"""
