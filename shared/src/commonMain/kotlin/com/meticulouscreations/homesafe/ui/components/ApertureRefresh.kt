package com.meticulouscreations.homesafe.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import kotlin.math.exp
import kotlin.math.min

/**
 * Pull to refresh with a camera's eye: the list slides down to open a band above it, and in the
 * band a lens comes up out of the dark over a camera's motion grid.
 *
 * - **Pulling**, the lens grows in and its six-blade iris opens with the pull, the focus ring
 *   turning as it goes, while the grid lights outward from it. The iris is fully open, the lamp
 *   behind it white-hot and a halo round the barrel, at exactly the distance a release refreshes
 *   from — with a tick of haptics as it gets there — so the pull says when it will count.
 * - **Released**, the camera scans: a radar sweep turns round the lens and rings run out over the
 *   grid, and cells the sweep passes flash up inside corner brackets, the way a detection is boxed
 *   on a camera's picture. It runs until [isRefreshing] goes false, and then the list slides back.
 *
 * Drawn by [APERTURE_SCAN_SHADER] (AGSL on Android, the same source as a Skia runtime effect
 * elsewhere); where no shader can be compiled, [plainAperture] draws a ring and a sweeping arc.
 * The pull itself is Material's [pullToRefresh], so it takes over from [content]'s own scrolling
 * exactly when the standard indicator would: at the top of the list, pulling down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApertureRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    // How far the band is open, as a fraction of the refresh threshold, with the pull past it damped.
    val opening = { apertureOpening(state.distanceFraction) }
    val scanning = animateFloatAsState(if (isRefreshing) 1f else 0f, tween(if (isRefreshing) SCAN_FADE_IN_MS else SCAN_FADE_OUT_MS), label = "scan")
    val time = remember { mutableFloatStateOf(0f) }
    val showing by remember { derivedStateOf { state.distanceFraction > 0f } }
    val armed by remember { derivedStateOf { state.distanceFraction >= 1f } }

    // The shader's clock runs only while the band is open, carrying on where it stopped so nothing jumps.
    LaunchedEffect(showing || isRefreshing) {
        if (!showing && !isRefreshing) return@LaunchedEffect
        val from = time.floatValue
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time.floatValue = (from + (now - start) / 1_000_000_000f) % CLOCK_WRAP_SECONDS }
        }
    }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(armed) {
        if (armed && !isRefreshing) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .pullToRefresh(isRefreshing = isRefreshing, state = state, threshold = APERTURE_THRESHOLD, onRefresh = onRefresh),
    ) {
        ApertureScan(
            opening = opening,
            scanning = { scanning.value },
            time = { time.floatValue },
            modifier = Modifier.fillMaxWidth().height(APERTURE_THRESHOLD * (1f + MAX_OVERSHOOT)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = opening() * APERTURE_THRESHOLD.toPx() },
        ) {
            content()
        }
    }
}

/**
 * The band itself, stateless: [opening] is how far it is open as a fraction of the refresh
 * threshold (past 1 is a pull beyond it), [scanning] how far into the refresh's scan it is (0..1)
 * and [time] the seconds its animation runs on. All three are read at draw time only, so a pull
 * redraws the band without recomposing anything. It draws from its top edge down, as tall as
 * [opening] makes it, and nothing while it is shut.
 */
@Composable
internal fun ApertureScan(opening: () -> Float, scanning: () -> Float, time: () -> Float, modifier: Modifier = Modifier) {
    val shader = remember { apertureScanShaderOrNull() }
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val ember = colors.primaryContainer
    val metal = colors.surfaceContainerHighest
    Box(
        modifier = modifier.drawBehind {
            val open = opening()
            val scan = scanning()
            if (open <= 0f && scan <= 0f) return@drawBehind
            val band = Size(size.width, min(open * APERTURE_THRESHOLD.toPx(), size.height))
            if (band.height < 1f) return@drawBehind
            if (shader == null) {
                plainAperture(band, open, scan, time(), accent)
                return@drawBehind
            }
            shader.setUniform("size", band.width, band.height)
            shader.setUniform("progress", open)
            shader.setUniform("scanning", scan)
            shader.setUniform("time", time())
            shader.setUniform("density", density)
            shader.setUniform("accent", accent)
            shader.setUniform("ember", ember)
            shader.setUniform("metal", metal)
            drawRect(brush = shader.brush(), size = band)
        },
    )
}

/**
 * The pull distance Material reports, as far as the band opens: one for one up to the threshold,
 * then damped so that however far the finger goes the band stops [MAX_OVERSHOOT] of the threshold
 * further down, like a spring reaching the end of its travel.
 */
internal fun apertureOpening(distanceFraction: Float): Float =
    if (distanceFraction <= 1f) {
        distanceFraction.coerceAtLeast(0f)
    } else {
        1f + MAX_OVERSHOOT * (1f - exp(-(distanceFraction - 1f) * OVERSHOOT_STIFFNESS))
    }

/** The no-shader stand-in: a ring that closes as the pull grows, and an arc sweeping round it while refreshing. */
private fun DrawScope.plainAperture(band: Size, open: Float, scan: Float, time: Float, accent: Color) {
    val radius = (band.height / 2f - 12.dp.toPx()).coerceIn(0f, 18.dp.toPx())
    if (radius <= 0f) return
    val centre = Offset(band.width / 2f, band.height / 2f)
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    val topLeft = centre - Offset(radius, radius)
    val arc = Size(radius * 2f, radius * 2f)
    drawCircle(accent.copy(alpha = 0.25f), radius, centre, style = stroke)
    val sweep = 360f * open.coerceIn(0f, 1f)
    val start = -90f + scan * time * 300f
    drawArc(accent, start, sweep.coerceAtLeast(scan * 90f), useCenter = false, topLeft = topLeft, size = arc, style = stroke)
}

/**
 * A compiled [APERTURE_SCAN_SHADER] whose uniforms can be set and drawn as a brush: an AGSL
 * `RuntimeShader` on Android, and the same source as a Skia runtime effect everywhere else.
 */
internal interface ApertureScanShader {
    fun setUniform(name: String, value: Float)

    fun setUniform(name: String, x: Float, y: Float)

    /** A colour, as the shader's `float3`: sRGB, straight (not premultiplied). */
    fun setUniform(name: String, color: Color)

    /** The shader as its uniforms stand now, to draw with. */
    fun brush(): Brush
}

/** The platform's [ApertureScanShader], or null where runtime shaders aren't available. */
internal expect fun apertureScanShaderOrNull(): ApertureScanShader?

/** How far down a release refreshes from, which is also how tall the band stands while it does. */
internal val APERTURE_THRESHOLD: Dp = 88.dp

/** How much further than the threshold the band can be pulled open, as a fraction of it. */
private const val MAX_OVERSHOOT = 0.45f
private const val OVERSHOOT_STIFFNESS = 1.4f

private const val SCAN_FADE_IN_MS = 350
private const val SCAN_FADE_OUT_MS = 250

/** The shader clock wraps this often so its float keeps its precision; the scan's pattern has no period to match, so the wrap is one frame's jump. */
private const val CLOCK_WRAP_SECONDS = 600f

/**
 * The band, in AGSL (and so also valid SkSL). fragCoord (0, 0) is the band's top-left, `size`
 * its size in pixels; every length is scaled by `density` so it is the same size on every screen.
 *
 * - `progress` is how far the band is open, as a fraction of the refresh threshold (past 1 is
 *   pulling beyond it); `scanning` fades the refresh's scan in and out (0..1); `time` is seconds.
 * - The motion grid: a dot every 14dp, faint at rest, lit outward from the lens as the pull grows.
 *   Scanning, a radar sweep turns round the lens lighting the dots it passes, rings of light run
 *   outward, and here and there a cell the sweep has just passed flashes up bracketed like a
 *   detection, then fades with the sweep's afterglow.
 * - The lens, centred in the band, grows in as the band opens (it fits the band with room to spare,
 *   so it never clips) and swells a little past the threshold. A dark barrel with a lit rim and a
 *   ring of focus ticks turning with the pull; inside it a six-blade iris whose blades are shaded
 *   metal with spiral seams, opening from nearly shut to wide at the threshold. Through it, the
 *   lamp: an ember glow warming to the accent, white at its heart once the pull would refresh.
 *   A halo round the barrel says the same thing from outside; a glint sits on the glass.
 * - Output is premultiplied: the lens is opaque, the light about it has alpha from its brightest
 *   channel, so over the dark background it composites as glow.
 */
internal const val APERTURE_SCAN_SHADER = """
uniform float2 size;
uniform float progress;
uniform float scanning;
uniform float time;
uniform float density;
uniform float3 accent;
uniform float3 ember;
uniform float3 metal;

const float TAU = 6.2831853;
const float BLADES = 6.0;

// A sine-free hash (sin loses precision on some GPUs at large arguments).
float hash21(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Signed distance to the iris's opening: a regular BLADES-gon of inradius a, turned by twist.
float aperture(float2 d, float a, float twist) {
    float seg = TAU / BLADES;
    float t = mod(atan(d.y, d.x) - twist + 0.5 * seg, seg) - 0.5 * seg;
    return cos(t) * length(d) - a;
}

// rgb at coverage alpha, over what is below (premultiplied).
half4 layer(float3 rgb, float alpha, half4 below) {
    return half4(half3(rgb * alpha), half(alpha)) + below * half(1.0 - alpha);
}

half4 main(float2 p) {
    float px = density;
    float pull = clamp(progress, 0.0, 1.0);
    float stretch = max(progress - 1.0, 0.0);
    float appear = max(smoothstep(0.0, 0.35, progress), scanning);
    float armed = max(smoothstep(0.97, 1.0, progress), scanning);

    float2 c = size * 0.5;
    float2 d = p - c;
    float r = length(d);
    float ang = atan(d.y, d.x);

    // The lens fits the band with room to spare, and swells a little past the threshold.
    float R = clamp(size.y * 0.5 - 12.0 * px, 0.0, 18.0 * px) + 4.0 * stretch * px;
    float barrelR = R + 3.0 * px;
    float lens = R > 0.5 ? 1.0 - smoothstep(barrelR - 0.75, barrelR + 0.75, r) : 0.0;
    // The grid keeps clear of the barrel and the focus ring round it.
    float outside = R > 0.5 ? smoothstep(barrelR + 6.0 * px, barrelR + 10.0 * px, r) : 1.0;

    float3 light = float3(0.0);

    // The motion grid.
    float spacing = 14.0 * px;
    float2 cell = floor(p / spacing);
    float2 dotCentre = (cell + 0.5) * spacing;
    float2 fromLens = dotCentre - c;
    float dotR = length(fromLens);
    float reach = max(size.x * 0.5, 1.0);
    float falloff = 1.0 - smoothstep(reach * 0.5, reach * 1.05, dotR);
    float dotCover = 1.0 - smoothstep(0.7 * px, 1.4 * px, length(p - dotCentre));
    float lit = pull * reach * 0.7;
    float field = pull * (1.0 - smoothstep(lit, lit + 48.0 * px, dotR));

    // The scan: a sweep, rings running outward, and cells flashing up as detections.
    float sweep = time * 3.6;
    float behind = mod(sweep - atan(fromLens.y, fromLens.x), TAU);
    float beam = exp(-2.4 * behind);
    float ringRadius = mod(time * 160.0 * px, reach);
    float ringDist = (dotR - ringRadius) / (9.0 * px);
    float ring = exp(-ringDist * ringDist) * (1.0 - ringRadius / reach);
    float motion = step(0.9, hash21(cell + floor(time * 1.4) * 7.0)) * exp(-0.9 * behind) * scanning;
    float dotLevel = 0.07 * appear + 0.3 * field + scanning * (0.8 * beam + 0.6 * ring) + 1.4 * motion;
    float3 dotColour = mix(accent, float3(1.0, 0.95, 0.9), clamp(motion, 0.0, 1.0));
    light += dotColour * dotCover * dotLevel * falloff * outside;

    float2 local = abs(p - dotCentre);
    float boxHalf = 5.0 * px;
    float bracket = (1.0 - smoothstep(0.4 * px, 1.0 * px, abs(max(local.x, local.y) - boxHalf))) * step(0.45 * boxHalf, min(local.x, local.y));
    light += accent * bracket * motion * falloff * outside * 0.9;

    // The sweep's wash over the grid, and the halo round the barrel once a release would refresh.
    float washBehind = mod(sweep - ang, TAU);
    float wash = scanning * exp(-3.0 * washBehind) * smoothstep(0.0, 0.06, washBehind) * (1.0 - smoothstep(R, reach * 0.6, r));
    light += ember * wash * 0.18 * outside;
    float halo = armed * exp(-max(r - barrelR, 0.0) / (10.0 * px)) * (0.8 + 0.2 * sin(time * 6.0));
    light += ember * halo * 0.35 * outside * appear;

    half4 colour = half4(0.0);
    if (R > 0.5) {
        // The barrel, its rim, and the focus ring's ticks turning with the pull.
        colour = layer(metal * 0.5, lens, colour);
        light += accent * exp(-((r - barrelR) * (r - barrelR)) / (0.8 * px * px)) * (0.25 + 0.6 * armed);
        float tickTurn = pull * 1.4 + scanning * time * 1.1;
        float tickPhase = fract((ang - tickTurn) / TAU * 24.0);
        float tickArc = min(tickPhase, 1.0 - tickPhase) * (TAU / 24.0) * r;
        float tickBand = step(barrelR + 2.0 * px, r) * step(r, barrelR + 2.0 * px + min(4.0 * px, 0.3 * R));
        light += accent * (1.0 - smoothstep(0.5 * px, 1.1 * px, tickArc)) * tickBand * 0.5 * smoothstep(0.3, 0.7, progress);

        // The iris, opening with the pull and breathing while it scans.
        float open = R * mix(0.1, 0.62, pull) * (1.0 + 0.06 * scanning * sin(time * 5.0));
        float twist = (1.0 - pull) * 1.1 + scanning * time * 0.8;
        float hole = aperture(d, open, twist);
        float inside = 1.0 - smoothstep(R - 0.75, R + 0.75, r);

        // The lamp behind it.
        float core = exp(-(r * r) / max(open * open * 0.5, 1.0));
        float3 lamp = mix(ember * 0.55, accent, core) + float3(core * core * armed * 0.55);
        lamp *= (0.55 + 0.45 * pull) * (0.85 + 0.15 * scanning * sin(time * 7.0));

        // The blades: lit from the upper left, seams spiralling in, inner edges catching the lamp.
        float seg = TAU / BLADES;
        float sheen = 0.5 + 0.5 * cos(ang + 2.4);
        float seamPhase = fract((ang - twist) / seg + 0.35 * r / max(R, 1.0));
        float seam = 1.0 - smoothstep(0.6 * px, 1.3 * px, min(seamPhase, 1.0 - seamPhase) * seg * r);
        float3 blade = metal * (0.9 + 0.5 * sheen) * (1.0 - 0.45 * seam);
        float lip = (1.0 - smoothstep(0.0, 2.5 * px, hole)) * smoothstep(-0.75, 0.75, hole);
        blade += accent * lip * (0.35 + 0.4 * armed);
        float3 iris = mix(lamp, blade, smoothstep(-0.75, 0.75, hole));
        colour = layer(iris, inside, colour);

        // A glint on the glass.
        float2 g = (d - float2(-0.38, -0.42) * R) / max(R * float2(0.26, 0.16), float2(1.0));
        light += float3(exp(-dot(g, g)) * 0.35 * inside);
    }

    float3 rgb = float3(colour.rgb) + light;
    float alpha = clamp(max(float(colour.a), max(light.r, max(light.g, light.b))), 0.0, 1.0);
    rgb = min(rgb, float3(alpha));
    float fade = appear * smoothstep(0.0, 6.0 * px, p.y) * smoothstep(0.0, 6.0 * px, size.y - p.y);
    return half4(half3(rgb * fade), half(alpha * fade));
}
"""

@Preview
@Composable
private fun ApertureScanPullingPreview() {
    ApertureScanPreview(opening = 0.6f, scanning = 0f)
}

@Preview
@Composable
private fun ApertureScanArmedPreview() {
    ApertureScanPreview(opening = 1.15f, scanning = 0f)
}

@Preview
@Composable
private fun ApertureScanScanningPreview() {
    ApertureScanPreview(opening = 1f, scanning = 1f)
}

@Composable
private fun ApertureScanPreview(opening: Float, scanning: Float) {
    FrigateTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp)) {
            ApertureScan(
                opening = { opening },
                scanning = { scanning },
                time = { 1.7f },
                modifier = Modifier.fillMaxWidth().height(APERTURE_THRESHOLD * 1.45f),
            )
        }
    }
}
