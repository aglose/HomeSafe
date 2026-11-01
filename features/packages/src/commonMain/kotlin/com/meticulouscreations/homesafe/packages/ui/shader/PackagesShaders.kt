package com.meticulouscreations.homesafe.packages.ui.shader

import com.meticulouscreations.homesafe.weather.ui.shader.SHADER_NOISE

// The packages app's shaders, in the language Android's RuntimeShader (AGSL) and Skia's
// RuntimeEffect share (see SkyShaders.kt for its rules). They are run through the weather app's
// `WeatherShader`, which is the same two platform classes whichever app is drawing.

/**
 * A parcel, lit by a porch light: a real box, marched through as a solid rather than painted, so
 * it turns in the light as the real thing does. Kraft board with its fibres, a strip of tape round
 * it and a shipping label on top. [spin] and [tilt] pose it; [drop] (1 high in the air, 0 landed)
 * is how it comes down when something arrives, and [land] the flash and dust as it lands. [warm]
 * is the light on it, from the blue of a package somewhere out there (0) to the amber of one at
 * the door (1).
 */
internal const val PARCEL_BOX_SHADER = """
uniform float2 size;
uniform float time;
uniform float spin;
uniform float tilt;
uniform float drop;
uniform float land;
uniform float warm;
""" + SHADER_NOISE + """
float2x2 turn(float a) {
    float s = sin(a);
    float c = cos(a);
    return float2x2(c, -s, s, c);
}

float box(float3 p, float3 half_, float r) {
    float3 q = abs(p) - half_ + r;
    return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r;
}

float3 pose(float3 p) {
    p.y -= drop * 1.6 - 0.02;
    float3 q = p;
    q.xz = turn(spin) * q.xz;
    q.yz = turn(tilt) * q.yz;
    return q;
}

float map(float3 p) {
    float3 q = pose(p);
    float parcel = box(q, float3(0.62, 0.40, 0.46), 0.022);
    float floor_ = p.y + 0.42;
    return min(parcel, floor_);
}

float3 normalAt(float3 p) {
    float e = 0.0015;
    return normalize(float3(
        map(p + float3(e, 0.0, 0.0)) - map(p - float3(e, 0.0, 0.0)),
        map(p + float3(0.0, e, 0.0)) - map(p - float3(0.0, e, 0.0)),
        map(p + float3(0.0, 0.0, e)) - map(p - float3(0.0, 0.0, e))
    ));
}

float3 boardAt(float3 q, float3 n) {
    // The board's own fibres run along it; the corrugation shows as faint bands on its long faces.
    float2 uv = abs(n.y) > 0.5 ? q.xz : (abs(n.x) > 0.5 ? q.zy : q.xy);
    float fibre = vnoise(uv * float2(90.0, 7.0)) * 0.5 + vnoise(uv * float2(18.0, 140.0)) * 0.5;
    float blotch = vnoise(uv * 3.3 + 9.0);
    float3 kraft = mix(float3(0.50, 0.33, 0.17), float3(0.74, 0.54, 0.31), blotch * 0.6 + fibre * 0.4);
    float flute = 0.5 + 0.5 * sin(uv.y * 150.0);
    kraft *= 1.0 - 0.035 * flute * step(abs(n.y), 0.5);
    // The tape across the top and down its sides, a shade glossier and lighter.
    float tape = 1.0 - smoothstep(0.075, 0.083, abs(q.x));
    float3 tapeCol = mix(float3(0.80, 0.63, 0.38), float3(0.92, 0.78, 0.53), vnoise(q.xz * float2(10.0, 40.0)));
    float3 col = mix(kraft, tapeCol, tape * (abs(n.z) > 0.5 ? 0.0 : 1.0));
    // The flaps' seam along the top, showing either side of the tape.
    if (n.y > 0.5) {
        col *= 1.0 - 0.45 * (1.0 - smoothstep(0.003, 0.009, abs(q.z))) * (1.0 - tape);
        // A shipping label: paper, a black band and the bars of a code.
        float2 l = q.xz - float2(0.32, 0.10);
        float paper = (1.0 - smoothstep(0.128, 0.134, abs(l.x))) * (1.0 - smoothstep(0.128, 0.134, abs(l.y)));
        float3 label = float3(0.93, 0.91, 0.86);
        float bars = step(0.5, fract(l.x * 58.0 + 0.5 * step(0.5, fract(l.x * 13.0))));
        label = mix(label, float3(0.08), bars * (1.0 - smoothstep(0.018, 0.024, abs(l.y + 0.055))));
        label = mix(label, float3(0.10), (1.0 - smoothstep(0.009, 0.013, abs(l.y - 0.050))) * step(l.x, 0.07));
        label = mix(label, float3(0.32), (1.0 - smoothstep(0.004, 0.007, abs(l.y - 0.085))) * step(l.x, 0.03));
        col = mix(col, label, paper);
    }
    // Printed arrows on the long face: this way up.
    if (abs(n.z) > 0.5) {
        float2 a = float2(abs(q.x + 0.32) , q.y + 0.03);
        float stem = (1.0 - smoothstep(0.012, 0.016, a.x)) * step(-0.09, a.y) * step(a.y, 0.03);
        float head = step(a.x, 0.055 - (a.y - 0.03)) * step(0.03, a.y) * step(a.y, 0.085);
        col = mix(col, float3(0.15, 0.10, 0.06), max(stem, head) * 0.8);
    }
    // Wear at the edges.
    float3 e = abs(q) / float3(0.62, 0.40, 0.46);
    float edge = max(min(e.x, e.y), max(min(e.y, e.z), min(e.x, e.z)));
    col *= mix(1.0, 1.14, smoothstep(0.93, 1.0, edge) * vnoise(q.xy * 40.0 + q.z * 40.0));
    return col;
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * size) / min(size.x, size.y);
    uv.y = -uv.y;
    float3 eye = float3(0.0, 1.32, 3.35);
    float3 fwd = normalize(float3(0.0, 0.02, 0.0) - eye);
    float3 right = normalize(cross(fwd, float3(0.0, 1.0, 0.0)));
    float3 up = cross(right, fwd);
    float3 dir = normalize(fwd * 1.72 + right * uv.x + up * uv.y);
    float t = 0.0;
    float hit = 0.0;
    for (int i = 0; i < 56; i++) {
        float d = map(eye + dir * t);
        if (d < 0.0012) {
            hit = 1.0;
            break;
        }
        t += d;
        if (t > 8.0) {
            break;
        }
    }
    float3 cool = float3(0.35, 0.52, 0.86);
    float3 amber = float3(1.00, 0.70, 0.36);
    float3 lightCol = mix(cool, amber, warm);
    float3 lightPos = float3(-1.3 + 0.12 * sin(time * 0.31), 2.3, 1.5);
    float3 col = mix(float3(0.030, 0.038, 0.060), float3(0.050, 0.036, 0.024), warm);
    float alpha = 0.0;
    if (hit > 0.5) {
        float3 p = eye + dir * t;
        float3 n = normalAt(p);
        float3 toLight = normalize(lightPos - p);
        float diffuse = max(dot(n, toLight), 0.0);
        // A soft shadow, marched toward the light.
        float shade = 1.0;
        float s = 0.03;
        for (int i = 0; i < 18; i++) {
            float d = map(p + n * 0.004 + toLight * s);
            shade = min(shade, 9.0 * d / s);
            s += clamp(d, 0.02, 0.2);
            if (s > 3.0) {
                break;
            }
        }
        shade = clamp(shade, 0.0, 1.0);
        float occlusion = clamp(map(p + n * 0.12) / 0.12, 0.0, 1.0);
        if (p.y < -0.415) {
            // The boards of the porch, fading into the dark.
            float plank = smoothstep(0.0, 0.015, abs(fract(p.x * 2.4 + 0.5 * step(0.5, fract(p.z * 0.4))) - 0.5) - 0.012 + 0.5 - 0.5);
            float grain = vnoise(p.xz * float2(3.0, 28.0));
            float3 wood = mix(float3(0.10, 0.075, 0.055), float3(0.20, 0.145, 0.10), grain) * mix(0.55, 1.0, plank);
            col = wood * (0.18 + 1.25 * diffuse * shade * lightCol) * (0.55 + 0.45 * occlusion);
            // The boards are only there in the pool of light, which is gone before the picture's edge.
            float glow = exp(-dot(p.xz, p.xz) * 0.42) * smoothstep(0.50, 0.26, length(uv));
            col *= glow;
            alpha = clamp(glow * 1.6, 0.0, 1.0);
            // A puff of dust where it lands.
            float2 rel = p.xz;
            float radius = length(rel) - land * 1.1;
            float puff = exp(-radius * radius * 22.0) * land * (1.0 - land) * 4.0;
            col += float3(0.55, 0.42, 0.28) * puff * vnoise(rel * 9.0 + time);
        } else {
            float3 q = pose(p);
            float3 nq = n;
            nq.xz = turn(spin) * nq.xz;
            nq.yz = turn(tilt) * nq.yz;
            float3 albedo = boardAt(q, nq);
            float3 h = normalize(toLight - dir);
            float spec = pow(max(dot(n, h), 0.0), 34.0) * 0.14;
            float rim = pow(1.0 - max(dot(n, -dir), 0.0), 3.5);
            col = albedo * (mix(cool, amber, 0.35) * 0.20 + lightCol * 1.42 * diffuse * shade) * (0.5 + 0.5 * occlusion);
            col += lightCol * spec * shade;
            col += lightCol * rim * 0.13;
            col += amber * land * 0.34;
            alpha = 1.0;
        }
    }
    // Motes in the light.
    float2 cell = fragCoord / min(size.x, size.y) * 15.0 + float2(time * 0.035, -time * 0.06);
    float2 id = floor(cell);
    float h1 = hash12(id);
    float2 at = fract(cell) - (0.25 + 0.5 * float2(h1, fract(h1 * 7.3)));
    float mote = exp(-dot(at, at) * 340.0) * step(0.74, h1) * (0.5 + 0.5 * sin(time * (0.5 + h1) + h1 * 40.0));
    col += lightCol * mote * 0.20 * smoothstep(1.0, 0.1, length(uv));
    alpha = max(alpha, mote * 0.3);
    // A little tone-mapping and grain, so the shadows don't band.
    col = col / (1.0 + col * 0.34);
    col += (hash12(fragCoord + fract(time) * 37.0) - 0.5) * 0.008;
    return half4(half3(col * alpha), half(alpha));
}
"""

/**
 * What the app's pages and the drawer's card lie on: dark with a warm cone of light falling from
 * up and to one side, dust drifting through it, and shipping-tape bands moving past very slowly.
 * [warm] is how much is here or coming today, and turns the cold dark toward the porch light.
 */
internal const val PACKAGES_BACKDROP_SHADER = """
uniform float2 size;
uniform float time;
uniform float seed;
uniform float warm;
""" + SHADER_NOISE + """
half4 main(float2 fragCoord) {
    float unit = min(size.x, size.y);
    float2 uv = fragCoord / size;
    float2 p = fragCoord / unit;
    float3 night = mix(float3(0.030, 0.034, 0.052), float3(0.052, 0.036, 0.024), warm);
    float3 lamp = mix(float3(0.32, 0.46, 0.78), float3(1.00, 0.66, 0.30), warm);
    // A cone from a lamp off the top edge, breathing a little.
    float2 from = float2(size.x / unit * (0.22 + seed * 0.5), -0.25);
    float2 d = p - from;
    float angle = atan(d.x, d.y);
    float cone = smoothstep(0.62, 0.05, abs(angle - (0.12 - seed * 0.3)));
    float fall = 1.0 / (1.0 + dot(d, d) * 1.1);
    float breath = 0.92 + 0.08 * sin(time * 0.53 + seed * 9.0);
    float dust = vnoise(p * 3.1 + float2(time * 0.018, -time * 0.03)) * 0.6 + vnoise(p * 7.7 - float2(time * 0.025, time * 0.012)) * 0.4;
    float3 col = night + lamp * cone * fall * (0.13 + 0.15 * dust) * breath;
    // Tape: wide, faint bands on a slant, printed with dashes, sliding past.
    float s = p.x * 0.5 + p.y * 0.86 + time * 0.012 + seed * 3.0;
    float band = smoothstep(0.012, 0.0, abs(fract(s * 1.25) - 0.5) - 0.085);
    float along = -p.x * 0.86 + p.y * 0.5 + time * 0.02;
    float print = step(0.5, fract(along * 13.0)) * smoothstep(0.03, 0.02, abs(fract(s * 1.25) - 0.5));
    col += lamp * band * (0.016 + 0.018 * print);
    // Motes, each on its own slow path.
    float2 cell = p * 13.0 + float2(time * 0.04, -time * 0.075) + seed * 7.0;
    float2 id = floor(cell);
    float h = hash12(id);
    float2 at = fract(cell) - (0.25 + 0.5 * float2(h, fract(h * 9.7)));
    float mote = exp(-dot(at, at) * 260.0) * step(0.68, h) * (0.45 + 0.55 * sin(time * (0.4 + h) + h * 30.0));
    col += lamp * mote * 0.24 * cone;
    col *= mix(0.72, 1.0, smoothstep(1.3, 0.25, length(uv - float2(0.5, 0.22))));
    col += (hash12(fragCoord) - 0.5) * 0.006;
    return half4(half3(col), 1.0);
}
"""

/**
 * The road a parcel is on: a dashed line from where it set out to the door, the part behind it
 * lit, and at [progress] (0-1 along the width) a parcel of light with a comet's tail, pulsing while
 * it moves. Past the end it has arrived: the line glows whole and settles. [tint] is its colour.
 */
internal const val PACKAGES_ROUTE_SHADER = """
uniform float2 size;
uniform float time;
uniform float progress;
uniform float margin;
uniform float3 tint;
""" + SHADER_NOISE + """
half4 main(float2 fragCoord) {
    float2 p = fragCoord;
    float span = size.x - margin * 2.0;
    float x = (p.x - margin) / span;
    float y = p.y - size.y * 0.5;
    float within = smoothstep(-0.002, 0.002, x) * smoothstep(1.002, 0.998, x);
    float line = exp(-y * y / 2.4) * within;
    float dash = smoothstep(0.42, 0.5, fract(p.x / 9.0 - time * 0.45)) ;
    float at = progress;
    float behind = smoothstep(at + 0.002, at - 0.002, x);
    float3 col = float3(0.0);
    float a = 0.0;
    // The way ahead, dashed and dim.
    a += line * dash * 0.30 * (1.0 - behind);
    col += float3(0.70, 0.66, 0.60) * line * dash * 0.30 * (1.0 - behind);
    // The way it came, lit, with light running along it toward the parcel.
    float run = 0.65 + 0.35 * sin((x - at) * 42.0 - time * 5.0);
    float lit = line * behind * run;
    a += lit * 0.95;
    col += tint * lit * 0.95;
    float halo = exp(-y * y / 26.0) * behind * within * 0.26 * run;
    a += halo;
    col += tint * halo;
    // The parcel itself, with its tail streaming behind it.
    float dx = (x - at) * span;
    float tail = exp(-max(-dx, 0.0) / 34.0) * step(dx, 0.0) * exp(-y * y / 9.0);
    float pulse = 0.82 + 0.18 * sin(time * 3.6);
    float head = exp(-(dx * dx + y * y) / (34.0 * pulse));
    float glow = exp(-(dx * dx + y * y) / (240.0 * pulse));
    float moving = step(at, 0.999);
    a += (head * 1.0 + glow * 0.52 + tail * 0.58) * moving;
    col += (float3(1.0, 0.95, 0.85) * head + tint * glow * 0.52 + tint * tail * 0.58) * moving;
    a = clamp(a, 0.0, 1.0);
    return half4(half3(clamp(col, 0.0, 1.0)), half(a));
}
"""

/**
 * A surprise, wrapped: what a parcel that's being kept from this phone wears in place of its
 * details. Foil paper with its creases catching a moving light, a ribbon across it both ways, and
 * tiny stars printed on the paper. [tint] and [tint2] are the paper's two shades.
 */
internal const val PACKAGES_GIFT_SHADER = """
uniform float2 size;
uniform float time;
uniform float seed;
uniform float3 tint;
uniform float3 tint2;
""" + SHADER_NOISE + """
half4 main(float2 fragCoord) {
    float unit = min(size.x, size.y);
    float2 p = fragCoord / unit;
    float2 q = p * 2.6 + seed * 11.0;
    // Soft folds in the paper: a gentle height, and the slope of it catching the light.
    float e = 0.09;
    float h = vnoise(q) + 0.42 * vnoise(q * 2.1 + 3.0);
    float hx = vnoise(q + float2(e, 0.0)) + 0.42 * vnoise((q + float2(e, 0.0)) * 2.1 + 3.0);
    float hy = vnoise(q + float2(0.0, e)) + 0.42 * vnoise((q + float2(0.0, e)) * 2.1 + 3.0);
    float3 n = normalize(float3((h - hx) * 2.6, (h - hy) * 2.6, 1.0));
    float3 l = normalize(float3(sin(time * 0.5 + seed * 6.0) * 0.8, cos(time * 0.37) * 0.7, 1.0));
    float diff = max(dot(n, l), 0.0);
    float spec = pow(max(dot(n, normalize(l + float3(0.0, 0.0, 1.0))), 0.0), 26.0);
    float3 paper = mix(tint2, tint, smoothstep(0.35, 1.15, h) * 0.75);
    float3 col = paper * (0.42 + 0.58 * diff) + mix(tint, float3(1.0), 0.45) * spec * 0.28;
    // The print: little four-point stars that twinkle as the foil turns.
    float2 cell = p * 7.0 + seed * 3.0;
    float2 id = floor(cell);
    float2 s = fract(cell) - 0.5;
    float r = hash12(id);
    float star = pow(max(1.0 - (abs(s.x) + abs(s.y)) * (6.0 + 2.0 * r), 0.0), 2.0);
    col += float3(1.0, 0.93, 0.76) * star * step(0.45, r) * (0.22 + 0.55 * pow(max(sin(time * 1.1 + r * 40.0), 0.0), 3.0));
    // The ribbon, with a soft shadow either side of it and a satin sheen along it.
    float2 c = (fragCoord - size * float2(0.84, 0.15)) / unit;
    float dv = abs(c.x);
    float dh = abs(c.y);
    float d = min(dv, dh);
    col *= 1.0 - 0.34 * smoothstep(0.075, 0.042, d);
    float rib = smoothstep(0.045, 0.040, d);
    float across = (dv < dh ? c.x : c.y) / 0.042;
    float along = (dv < dh ? c.y : c.x);
    float satin = 0.72 + 0.20 * (1.0 - across * across) + 0.16 * sin(along * 7.0 + time * 0.8 + seed * 5.0);
    float3 ribbon = float3(0.95, 0.80, 0.50) * satin * (0.65 + 0.40 * diff);
    col = mix(col, ribbon, rib);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * Laid over a picture from the door camera, as a scan: a bright line sweeps down at [sweep] (0-1,
 * and it can go below 0 or past 1 when there is no sweep to draw), lighting what it has just passed
 * with [tint] and drawing a faint grid behind it, so a kept picture reads as something being
 * looked through rather than simply shown. `content` is the picture.
 */
internal const val PACKAGES_SCAN_SHADER = """
uniform shader content;
uniform float2 size;
uniform float sweep;
uniform float3 tint;
half4 main(float2 fragCoord) {
    half4 picture = content.eval(fragCoord);
    float y = fragCoord.y / size.y;
    float d = y - sweep;
    float line = exp(-d * d * 42000.0);
    float trail = smoothstep(-0.20, 0.0, d) * step(d, 0.0);
    float2 g = abs(fract(fragCoord / 26.0) - 0.5);
    float grid = (1.0 - smoothstep(0.0, 0.04, 0.5 - max(g.x, g.y)));
    float3 col = float3(picture.rgb);
    col += tint * trail * (0.10 + grid * 0.22) * float(picture.a);
    col += mix(tint, float3(1.0), 0.45) * line * 0.95 * float(picture.a);
    return half4(half3(col), picture.a);
}
"""

internal val PackagesShaderSources: Map<String, String> = mapOf(
    "box" to PARCEL_BOX_SHADER,
    "backdrop" to PACKAGES_BACKDROP_SHADER,
    "route" to PACKAGES_ROUTE_SHADER,
    "gift" to PACKAGES_GIFT_SHADER,
    "scan" to PACKAGES_SCAN_SHADER,
)
