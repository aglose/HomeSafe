package com.meticulouscreations.homesafe.ui.components

import android.graphics.RuntimeShader
import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Matrix snake: an AGSL [RuntimeShader] drawn as a brush over the card, in a rect that
 * reaches [SNAKE_BLEED] past each edge so the glow and the snake's slither can leave the outline.
 *
 * minSdk is 33, so RuntimeShader (API 33) is always there. The shader is shared by every card:
 * each draw sets its uniforms right before drawing, and a draw records a snapshot of the shader
 * as it stands (setting a uniform discards the native instance, so the next draw builds a new
 * one), so one card's uniforms never leak into another's frame.
 *
 * [phase] is read at draw time only, so the frame clock driving it redraws the snake without
 * recomposing anything. The glyphs and the rain need a clock that doesn't loop every lap, so the
 * shader also gets the uptime — read in the same draw, which [phase] already invalidates every
 * frame; with a fixed [phase] (a test, a preview) the frame simply holds.
 *
 * Where the shader can't be compiled (Android Studio's preview renderer among them) the card
 * gets the plain [outlineRunner] in [color] instead of failing to draw.
 */
internal actual fun Modifier.loadingRunner(
    phase: () -> Float,
    phaseOffset: Float,
    cornerRadius: Dp,
    color: Color,
): Modifier {
    val shader = matrixSnakeShader ?: return outlineRunner(phase = phase, phaseOffset = phaseOffset, cornerRadius = cornerRadius, color = color)
    return matrixSnake(shader, phase, phaseOffset, cornerRadius)
}

private fun Modifier.matrixSnake(shader: RuntimeShader, phase: () -> Float, phaseOffset: Float, cornerRadius: Dp): Modifier = drawWithCache {
    val brush = ShaderBrush(shader)
    val bleed = SNAKE_BLEED.toPx()
    val area = Size(size.width + 2 * bleed, size.height + 2 * bleed)
    val corner = cornerRadius.toPx()

    onDrawWithContent {
        drawContent()
        if (size.width <= 0f || size.height <= 0f) return@onDrawWithContent
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("corner", corner)
        shader.setFloatUniform("head", (phase() + phaseOffset) % 1f)
        shader.setFloatUniform("time", (SystemClock.uptimeMillis() % SNAKE_CLOCK_WRAP_MS) / 1000f)
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("seed", phaseOffset * 37f)
        drawRect(brush = brush, topLeft = Offset(-bleed, -bleed), size = area)
    }
}

/** How far past the card's edges the snake is drawn: its slither, its glyphs' half-width and its glow. */
private val SNAKE_BLEED = 16.dp

/**
 * The shader's clock wraps this often, so its float keeps sub-millisecond precision. The wrap
 * is a single frame in which every glyph changes at once — once in ten minutes of loading.
 */
private const val SNAKE_CLOCK_WRAP_MS = 600_000L

/**
 * Compiled once, on first use, and shared (see [loadingRunner]); null where it can't be compiled.
 * A device test (MatrixSnakeShaderTest) keeps the fallback from hiding a broken shader.
 */
private val matrixSnakeShader: RuntimeShader? by lazy(LazyThreadSafetyMode.NONE) {
    runCatching { RuntimeShader(MATRIX_SNAKE_AGSL) }.getOrNull()
}

/**
 * The snake, in the card's own coordinates (fragCoord (0, 0) is its top-left corner):
 *
 *  - Every pixel is placed against the card's rounded outline: its signed distance from it
 *    (negative inside) and the arc length, clockwise from the top-left, of the nearest point on
 *    it — the path the snake travels. The perimeter is cut into a whole number of glyph cells,
 *    and the slither into a whole number of waves, so both close on themselves with no seam.
 *  - The snake's body is a stream of glyphs standing along that path, the way the Matrix's code
 *    rains down a column: the characters stay where they are, and the head lights them as it
 *    passes and leaves them fading behind it. The head's cell burns white; behind it they cool to
 *    phosphor green and then to a deep green, flickering, with the odd one flaring. Each glyph is
 *    a handful of strokes on a 3×4 lattice (edges and diagonals chosen by a hash of the glyph's
 *    seed), which reads as a character rather than as noise, and re-rolls its seed at its own
 *    rate — fast at the head, slow in the tail. Glyphs shrink toward the tail, so it tapers.
 *  - The body slithers: its centre line is a sine wave about the outline that drifts slowly
 *    backwards, so the head weaves along an S-curve and the body follows it through the same
 *    bends. The glyphs ride the wave; a soft bloom around them narrows with the taper.
 *  - Round the head, a white core and a wide green halo; ahead of it, a thin scan line traced on
 *    the outline, as if the head were drawing the way. The whole outline carries a faint dashed
 *    phosphor trace, glowing brighter where the snake has most recently been.
 *  - Inside the card, faint digital rain: columns of glyphs falling at their own speeds with
 *    white heads and fading trails, over a scatter of dormant ones, kept off the card's rim and
 *    brightening where the snake's head passes close. `seed` gives each card its own rain.
 *  - Scanlines over all of it. The light is output premultiplied, alpha from its brightest
 *    channel, so over the dark card it composites as glow.
 *
 * Distances are in pixels, scaled by `density` so the snake is the same size on every screen.
 */
internal const val MATRIX_SNAKE_AGSL = """
uniform float2 size;
uniform float corner;
uniform float head;
uniform float time;
uniform float density;
uniform float seed;

const float TAU = 6.2831853;
const float HALF_PI = 1.5707963;

const float3 HOT = float3(0.80, 1.00, 0.84);
const float3 GREEN = float3(0.00, 1.00, 0.26);
const float3 DEEP = float3(0.00, 0.34, 0.07);

// How much of the perimeter the snake covers, head to the tip of its tail.
const float LENGTH = 0.34;
// The overall strength of the light: a progress cue, not the thing on screen.
const float INTENSITY = 0.9;
// The rain's peak strength inside the card.
const float RAIN = 0.20;

// A sine-free hash (sin loses precision on some GPUs at large arguments).
float hash21(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float segDist(float2 p, float2 a, float2 b) {
    float2 pa = p - a;
    float2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Coverage 0..1 of glyph `id` at p, in pixels within its box from (0, 0) to `box`, drawn in
// strokes of half-width hw. The strokes are edges and diagonals of a 3×4 lattice. Each pixel
// looks only at the six segments of the lattice cell it is in; an edge shared by two cells hashes
// to the same answer from both sides, so strokes run on unbroken across cells.
float glyph(float2 p, float2 box, float hw, float id) {
    if (p.x < -hw - 1.0 || p.y < -hw - 1.0 || p.x > box.x + hw + 1.0 || p.y > box.y + hw + 1.0) {
        return 0.0;
    }
    float2 cell = box / float2(2.0, 3.0);
    float2 g = clamp(floor(p / cell), float2(0.0), float2(1.0, 2.0));
    float2 a = g * cell;
    float2 b = a + float2(cell.x, 0.0);
    float2 c = a + float2(0.0, cell.y);
    float2 d = a + cell;
    float dist = 1e4;
    if (hash21(float2(id, g.x + g.y * 2.0)) < 0.52) dist = min(dist, segDist(p, a, b));
    if (hash21(float2(id, g.x + (g.y + 1.0) * 2.0)) < 0.52) dist = min(dist, segDist(p, c, d));
    if (hash21(float2(id, 20.0 + g.x + g.y * 3.0)) < 0.46) dist = min(dist, segDist(p, a, c));
    if (hash21(float2(id, 21.0 + g.x + g.y * 3.0)) < 0.46) dist = min(dist, segDist(p, b, d));
    float diag = hash21(float2(id, 40.0 + g.x + g.y * 2.0));
    if (diag < 0.13) {
        dist = min(dist, segDist(p, a, d));
    } else if (diag < 0.26) {
        dist = min(dist, segDist(p, b, c));
    }
    return 1.0 - smoothstep(hw - 0.6, hw + 0.6, dist);
}

// The point at arc length s along the outline, clockwise from the left end of the top edge.
float2 outlinePoint(float s, float2 c, float2 e, float r, float lw, float lh, float arc) {
    if (s < lw) return float2(c.x - e.x + s, c.y - e.y - r);
    s -= lw;
    if (s < arc) { float t = s / r; return c + float2(e.x, -e.y) + r * float2(sin(t), -cos(t)); }
    s -= arc;
    if (s < lh) return float2(c.x + e.x + r, c.y - e.y + s);
    s -= lh;
    if (s < arc) { float t = s / r; return c + e + r * float2(cos(t), sin(t)); }
    s -= arc;
    if (s < lw) return float2(c.x + e.x - s, c.y + e.y + r);
    s -= lw;
    if (s < arc) { float t = s / r; return c + float2(-e.x, e.y) + r * float2(-sin(t), cos(t)); }
    s -= arc;
    if (s < lh) return float2(c.x - e.x - r, c.y + e.y - s);
    s -= lh;
    float t = s / r;
    return c - e + r * float2(-cos(t), -sin(t));
}

half4 main(float2 p) {
    float px = density;
    float2 c = size * 0.5;
    float r = clamp(corner, 0.5, min(c.x, c.y));
    float2 e = max(c - r, float2(0.0));
    float lw = 2.0 * e.x;
    float lh = 2.0 * e.y;
    float arc = r * HALF_PI;
    float perimeter = 2.0 * (lw + lh) + 4.0 * arc;

    // Signed distance to the outline, and the arc length of the nearest point on it.
    float2 q = p - c;
    float2 a = abs(q) - e;
    float sd;
    float s;
    if (a.x > 0.0 && a.y > 0.0) {
        float2 v = p - (c + sign(q) * e);
        sd = length(v) - r;
        if (q.x >= 0.0 && q.y < 0.0) {
            s = lw + r * atan(v.x, -v.y);
        } else if (q.x >= 0.0) {
            s = lw + arc + lh + r * atan(v.y, v.x);
        } else if (q.y >= 0.0) {
            s = 2.0 * lw + lh + 2.0 * arc + r * atan(-v.x, v.y);
        } else {
            s = 2.0 * lw + 2.0 * lh + 3.0 * arc + r * atan(-v.y, -v.x);
        }
    } else {
        sd = max(a.x, a.y) - r;
        if (a.x > a.y) {
            s = q.x >= 0.0
                ? lw + arc + clamp(q.y + e.y, 0.0, lh)
                : 2.0 * lw + lh + 3.0 * arc + clamp(e.y - q.y, 0.0, lh);
        } else {
            s = q.y < 0.0
                ? clamp(q.x + e.x, 0.0, lw)
                : lw + 2.0 * arc + lh + clamp(e.x - q.x, 0.0, lw);
        }
    }

    float headS = fract(head) * perimeter;
    float2 headPoint = outlinePoint(headS, c, e, r, lw, lh, arc);
    float len = perimeter * LENGTH;

    // How far the head is past this point along the path; negative a little way ahead of it.
    float behind = mod(headS - s, perimeter);
    float along = behind > perimeter - 48.0 * px ? behind - perimeter : behind;

    // The slither: a whole number of waves round the outline, drifting slowly backwards.
    float waves = max(floor(perimeter / (46.0 * px) + 0.5), 1.0);
    float wiggle = 2.4 * px * sin(TAU * waves * s / perimeter - 1.3 * time);
    float lateral = sd - wiggle;

    float3 light = float3(0.0);

    // The body: glyphs standing on the path, lit as the head passes them.
    float cells = max(floor(perimeter / (10.0 * px) + 0.5), 1.0);
    float cellLen = perimeter / cells;
    float ci = floor(s / cellLen);
    float cellBehind = mod(headS - (ci + 0.5) * cellLen, perimeter);
    if (cellBehind < len && abs(lateral) < 7.0 * px) {
        float ct = cellBehind / len;
        float scale = mix(1.0, 0.5, ct);
        float2 box = float2(6.0, 8.0) * px * scale;
        float2 gp = float2(lateral + 0.5 * box.x, (fract(s / cellLen) - 0.5) * cellLen + 0.5 * box.y);
        float hot = exp(-cellBehind / (0.9 * cellLen));
        float rate = hot > 0.5 ? 22.0 : 2.0 + 9.0 * hash21(float2(ci, 3.1 + seed));
        float tick = floor(time * rate + 13.0 * hash21(float2(ci, 7.7)));
        float id = floor(hash21(float2(ci + seed * 17.0, tick)) * 4096.0);
        float cover = glyph(gp, box, 0.62 * px * mix(1.0, 0.8, ct), id);

        float flare = step(0.94, hash21(float2(ci, floor(time * 9.0) + seed))) * 0.7;
        float lum = pow(1.0 - ct, 1.4) * (0.6 + 0.4 * hash21(float2(ci, 5.3 + floor(time * 5.0))) + flare);
        float3 col = mix(GREEN, HOT, hot);
        col = mix(col, DEEP, smoothstep(0.45, 1.0, ct));
        light += col * cover * lum * (1.0 + 0.8 * hot);
    }

    // Bloom about the body, narrowing with its taper.
    if (along > 0.0 && along < len) {
        float t = along / len;
        float sigma = mix(4.5, 1.6, t) * px;
        float fade = pow(1.0 - t, 1.8);
        light += GREEN * 0.28 * fade * exp(-lateral * lateral / (2.0 * sigma * sigma));
    }

    // The head: a white core in a wide green halo, and a scan line traced on ahead of it.
    float2 fromHead = float2(along, lateral);
    float d2 = dot(fromHead, fromHead);
    light += HOT * 0.55 * exp(-d2 / (2.0 * 3.5 * 3.5 * px * px));
    light += GREEN * 0.20 * exp(-d2 / (2.0 * 15.0 * 15.0 * px * px));
    if (along < 0.0) {
        light += GREEN * 0.4 * exp(along / (12.0 * px)) * exp(-sd * sd / (2.0 * 0.8 * 0.8 * px * px));
    }

    // The outline's phosphor trace, dashed, glowing where the snake has lately been.
    float trace = exp(-sd * sd / (2.0 * 0.7 * 0.7 * px * px));
    float afterglow = exp(-behind / (0.45 * perimeter));
    float dash = 0.5 + 0.5 * step(0.35, fract(s / (4.0 * px)));
    light += mix(DEEP, GREEN, 0.35) * trace * (0.12 + 0.35 * afterglow) * dash;

    // Digital rain inside the card.
    float inner = -sd;
    if (inner > 2.0 * px) {
        float2 cellSize = float2(10.0, 13.0) * px;
        float2 rc = floor(p / cellSize);
        float2 local = p - rc * cellSize;
        float colHash = hash21(float2(rc.x, seed));
        float rows = ceil(size.y / cellSize.y);
        float speed = 2.5 + 5.0 * hash21(float2(rc.x, seed + 1.7));
        float trail = 5.0 + 9.0 * hash21(float2(rc.x, seed + 3.3));
        float cycle = rows + trail + 4.0 + 12.0 * hash21(float2(rc.x, seed + 5.9));
        float headRow = floor(mod(time * speed + colHash * 97.0, cycle));
        float rowBehind = headRow - rc.y;
        float lum = 0.0;
        float hot = 0.0;
        if (colHash < 0.7 && rowBehind >= 0.0 && rowBehind < trail) {
            lum = pow(1.0 - rowBehind / trail, 1.5);
            hot = rowBehind < 0.5 ? 1.0 : 0.0;
        }
        float dormant = 0.12 * step(0.6, hash21(rc + seed * 3.1));
        float level = max(lum, dormant);
        if (level > 0.0) {
            float tick = floor(time * (1.0 + 6.0 * hash21(rc + 9.1)) + 11.0 * hash21(rc + 2.3));
            float id = floor(hash21(float2(rc.x * 31.0 + rc.y, tick + seed)) * 4096.0);
            float2 box = float2(6.0, 8.5) * px;
            float cover = glyph(local - 0.5 * (cellSize - box), box, 0.5 * px, id);
            float near = exp(-length(p - headPoint) / (45.0 * px));
            float rim = smoothstep(2.0 * px, 22.0 * px, inner);
            float3 col = mix(mix(DEEP, GREEN, lum), HOT, 0.8 * hot);
            light += col * cover * level * RAIN * (1.0 + 2.2 * near) * (1.0 + 1.5 * hot) * rim;
        }
    }

    light *= INTENSITY * (0.86 + 0.14 * sin(p.y * TAU / (3.0 * px)));
    float alpha = clamp(max(light.r, max(light.g, light.b)), 0.0, 1.0);
    return half4(half3(min(light, float3(alpha))), alpha);
}
"""
