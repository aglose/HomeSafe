package com.meticulouscreations.homesafe.weather.ui.shader

/*
 * The weather app's sky, as four runtime shaders stacked back to front (see WeatherSky):
 *
 *   1. CELESTIAL  the air itself, and what hangs in it: the gradient, the sun and its light, the
 *                 moon in its phase, the stars.
 *   2. CLOUDS     cloud, lit from where the sun is, with lightning inside it, and fog. Drawn at
 *                 half resolution, since nothing in it has an edge, which is most of the cost.
 *   3. PRECIP     what falls through the foreground: rain in streaks, snow in flakes, the bolt.
 *   4. GLASS      a render effect over the lot: drops on the glass you're looking through, each
 *                 one a small lens on the sky behind it.
 *
 * Everything is driven by uniforms that vary smoothly (cloud cover 0–1, rain 0–1 …) rather than
 * by a shader per kind of weather, so one sky turns into another by animating numbers.
 *
 * The source is AGSL, which is Skia's SkSL: `half4 main(float2)`, no `#version`, loops with
 * constant bounds, premultiplied colour out. Positions arrive in pixels with y down.
 */

/**
 * Noise shared by every shader here. The hashes are Dave Hoskins's "hash without sine" (MIT):
 * `fract(sin(x) * big)` gives different answers on different GPUs and bands on some phones,
 * these don't. `vnoise` is value noise with a quintic fade; the fbm turns the plane between
 * octaves so its grid never lines up with itself.
 */
internal const val SHADER_NOISE = """
float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float2 hash22(float2 p) {
    float3 p3 = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = hash12(i);
    float b = hash12(i + float2(1.0, 0.0));
    float c = hash12(i + float2(0.0, 1.0));
    float d = hash12(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float2 turn(float2 p) {
    return float2(0.8 * p.x - 0.6 * p.y, 0.6 * p.x + 0.8 * p.y);
}

float fbm2(float2 p) {
    float v = 0.5 * vnoise(p);
    p = turn(p) * 2.03 + float2(11.3, 7.1);
    v += 0.25 * vnoise(p);
    return v / 0.75;
}

float fbm3(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * vnoise(p);
        p = turn(p) * 2.03 + float2(11.3, 7.1);
        a *= 0.5;
    }
    return v / 0.875;
}

float fbm4(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise(p);
        p = turn(p) * 2.03 + float2(11.3, 7.1);
        a *= 0.5;
    }
    return v / 0.9375;
}

float fbm5(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * vnoise(p);
        p = turn(p) * 2.03 + float2(11.3, 7.1);
        a *= 0.5;
    }
    return v / 0.96875;
}
"""

/**
 * The air and what hangs in it. Opaque.
 *
 * - The gradient runs from [zenith] at the top to [horizon] at the foot, with [glow] pooled
 *   round the sun where it nears the horizon: the orange of a sunset is not in the whole sky,
 *   it is on the sun's side of it.
 * - The sun is a disc with a limb, inside two halos: a tight one (the glare) and a wide one (the
 *   air lighting up round it). `sun.z` fades the disc as cloud covers it; the halos fade less,
 *   which is how a hazy day looks.
 * - The moon is a lit sphere: `moonCycle` turns the light round it, so the terminator is the
 *   true curve of a crescent or a gibbous moon, not a circle slid across a circle. Its seas are
 *   noise; the unlit side keeps a trace of earthshine.
 * - The stars are three grids of points, most of them faint, each twinkling at its own rate,
 *   with a brighter band of them where the Milky Way would be.
 *
 * `unit` is pixels per dp, so stars and discs are the same size on a phone and in a small card.
 */
internal const val CELESTIAL_SHADER = """
uniform float2 size;
uniform float time;
uniform float unit;
uniform float3 zenith;
uniform float3 horizon;
uniform float3 glow;
uniform float3 sunColor;
uniform float3 sun;
uniform float3 moon;
uniform float moonCycle;
uniform float stars;
""" + SHADER_NOISE + """
float starField(float2 fc, float cell, float seed) {
    float2 g = fc / cell;
    float2 id = floor(g);
    float2 f = fract(g) - 0.5;
    float h = hash12(id + seed);
    float present = step(0.82, h);
    float2 o = (hash22(id + seed * 1.7) - 0.5) * 0.72;
    float2 d = (f - o) * cell / unit;
    float mag = fract(h * 37.31);
    mag = mag * mag * mag;
    float tw = 0.72 + 0.28 * sin(time * (0.8 + 3.2 * fract(h * 91.7)) + h * 61.0);
    float r2 = dot(d, d);
    float core = exp(-r2 * (3.2 - 2.2 * mag));
    return present * core * (0.22 + 0.78 * mag) * tw;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float aspect = size.x / max(size.y, 1.0);
    float2 p = float2(uv.x * aspect, uv.y);
    float t = clamp(uv.y, 0.0, 1.0);

    float3 col = mix(zenith, horizon, pow(t, 0.82));

    // The sun's side of the sky.
    float2 sunP = float2(sun.x * aspect, sun.y);
    float dSun = length(p - sunP);
    float low = smoothstep(0.15, 0.95, t);
    col += glow * exp(-dSun * 2.4) * (0.3 + 0.7 * low);
    col += glow * exp(-abs(uv.y - sun.y) * 5.0) * exp(-abs(p.x - sunP.x) * 1.1) * 0.22;

    // Stars, and the band of the galaxy across them.
    if (stars > 0.004) {
        float band = fbm3(float2(p.x * 2.1 + p.y * 1.3, p.y * 2.1 - p.x * 1.3) * 1.4 + 3.0);
        float across = abs((p.x - 0.5 * aspect) * 0.62 + (p.y - 0.42) * 0.78);
        float galaxy = exp(-across * across * 9.0) * band;
        float s = starField(fragCoord, unit * 13.0, 1.0) * (0.75 + 1.3 * galaxy);
        s += starField(fragCoord, unit * 29.0, 7.0);
        s += starField(fragCoord, unit * 67.0, 13.0) * 1.35;
        float tint = hash12(floor(fragCoord / (unit * 29.0)));
        float3 starCol = mix(float3(0.74, 0.83, 1.0), float3(1.0, 0.91, 0.78), tint);
        // Fewer near the horizon, where there's more air to look through.
        float clear = 1.0 - 0.75 * smoothstep(0.45, 1.0, t);
        col += starCol * s * stars * clear;
        col += float3(0.36, 0.42, 0.62) * galaxy * galaxy * 0.22 * stars * clear;
    }

    // The moon.
    if (moon.z > 0.004) {
        float2 moonP = float2(moon.x * aspect, moon.y);
        float r = min(unit * 21.0, size.y * 0.085) / size.y;
        float2 q = (p - moonP) / r;
        float d2 = dot(q, q);
        float lit = 0.5 - 0.5 * cos(moonCycle * 6.2831853);
        col += float3(0.62, 0.7, 0.9) * (exp(-sqrt(d2) * 0.85) * 0.2 + exp(-d2 * 0.9) * 0.22) * moon.z * (0.2 + 0.8 * lit);
        if (d2 < 1.2) {
            float z = sqrt(max(1.0 - d2, 0.0));
            float a = moonCycle * 6.2831853;
            // The light swings round the sphere once a month: behind it at new, full on at full.
            float3 l = float3(sin(a), 0.0, -cos(a));
            float3 n = float3(q.x, -q.y, z);
            float day = smoothstep(-0.06, 0.16, dot(n, l));
            float seas = fbm4(q * 1.7 + 5.2);
            float pits = vnoise(q * 9.0 + 2.0);
            float albedo = 0.6 + 0.34 * smoothstep(0.38, 0.62, seas) + 0.08 * pits;
            float3 face = float3(0.97, 0.95, 0.9) * albedo * (0.72 + 0.28 * z);
            float3 body = face * (0.045 + 0.955 * day);
            float edge = 1.0 - smoothstep(1.0 - 2.4 / (r * size.y), 1.0, sqrt(d2));
            col = mix(col, body, edge * moon.z);
        }
    }

    // The sun: disc, glare, and the air lit up around it.
    if (sun.z > 0.004 || dot(sunColor, sunColor) > 0.0) {
        float r = min(unit * 17.0, size.y * 0.07) / size.y;
        float x = dSun / r;
        // Glare tight round the disc, then the air lit up for a long way round it.
        float glare = exp(-x * x * 0.55) * 0.5 + exp(-x * 0.62) * 0.3 + exp(-dSun * 4.2) * 0.22;
        // Rays: a few slow harmonics of the angle round the sun, strongest close in.
        float2 away = p - sunP;
        float ang = atan(away.y, away.x);
        float rays = 0.5 + 0.26 * sin(ang * 7.0 + time * 0.11) + 0.15 * sin(ang * 13.0 - time * 0.07) + 0.09 * sin(ang * 29.0 + time * 0.19);
        glare += rays * rays * exp(-x * 0.42) * 0.22 * sun.z;
        col += sunColor * glare * (0.25 + 0.75 * sun.z);
        // The disc itself is brighter than anything round it: it burns out to white at midday and
        // keeps the sun's colour when it is low and the air has taken the edge off it.
        float disc = 1.0 - smoothstep(1.0 - 2.0 / (r * size.y), 1.0, x);
        float3 face = mix(sunColor, float3(1.0), 0.55) * 1.6;
        col = mix(col, max(col, face), disc * sun.z);
    }

    col += (hash12(fragCoord + fract(time) * 61.0) - 0.5) / 180.0;
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * Cloud, fog, and the lightning inside them. Premultiplied, over [CELESTIAL_SHADER].
 *
 * The sky is treated as a ceiling seen from below: the screen's rows are mapped onto a plane
 * that recedes toward a horizon under the foot of the screen, so cloud overhead is large and
 * slow and cloud far off is small, close-packed and hazed, which is what gives it depth.
 *
 * Density is fbm pushed about by a second, slower fbm (so shapes billow and never just slide),
 * cut at a threshold that [cover] lowers: a few puffs at 0.2, a closed deck at 1. Light comes
 * from the sun's place on the same plane: where the cloud thins toward it the cloud is lit, where
 * it thickens it's in its own shade, and thin edges near the sun take a bright rim. [storm]
 * deepens the shade toward slate. Above it all a stretched, faster layer of cirrus shows when
 * the sky is partly open.
 *
 * `drift` is how far the wind has carried the deck (x) and how far it has evolved (y), both
 * summed on the CPU so a change of wind never makes the clouds jump. `unit` is pixels per dp
 * *of this canvas* (so half the screen's when it is drawn at half size): it keeps a cloud the
 * same size in the hand on a phone's whole sky and on a small card.
 *
 * `bolt` is a lightning strike: its x (0–1), a seed, seconds since it hit, and its power. The
 * cloud flares from inside round it, flickers, and takes a second, weaker stroke a moment later.
 */
internal const val CLOUD_SHADER = """
uniform float2 size;
uniform float time;
uniform float unit;
uniform float3 horizon;
uniform float3 sunColor;
uniform float3 sun;
uniform float3 cloudLit;
uniform float3 cloudShade;
uniform float cover;
uniform float storm;
uniform float2 drift;
uniform float fog;
uniform float4 bolt;
""" + SHADER_NOISE + """
float2 ceilingAt(float2 uv, float aspect, float s) {
    float h = 2.2 - uv.y;
    return float2((uv.x - 0.5) * aspect / h, 1.7 / h) * s;
}

float deck(float2 q, float2 warp) {
    return fbm5(q + warp);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float aspect = size.x / max(size.y, 1.0);
    float2 p = float2(uv.x * aspect, uv.y);
    float t = clamp(uv.y, 0.0, 1.0);
    float3 rgb = float3(0.0);
    float alpha = 0.0;

    float2 sunP = float2(sun.x * aspect, sun.y);
    float dSun = length(p - sunP);

    // Lightning's light: bright at the strike, a flicker, and a second stroke.
    float age = bolt.z;
    float flash = 0.0;
    if (bolt.w > 0.0 && age >= 0.0 && age < 1.2) {
        float first = exp(-age * 9.0) * (0.6 + 0.4 * sin(age * 70.0));
        float second = step(0.16, age) * exp(-(age - 0.16) * 7.0) * 0.7;
        flash = bolt.w * (first + second);
    }

    if (cover > 0.01) {
        // A cloud is about as big in the hand whatever it is drawn on: a phone's sky or a small card's.
        float s = clamp(size.y / (unit * 64.0), 1.2, 18.0);
        float2 q = ceilingAt(uv, aspect, s);
        float2 qs = ceilingAt(float2(sun.x, clamp(sun.y, -0.4, 1.2)), aspect, s);
        float2 toSun = qs - q;
        // Mostly one direction for the whole sky (from its middle toward the sun), bent a little
        // toward the sun from here, and fading out at the sun itself, where "toward" stops meaning
        // anything. A direction that turned freely from pixel to pixel would comb the cloud into arcs.
        float2 fromMiddle = qs - ceilingAt(float2(0.5, 0.45), aspect, s);
        float2 l = fromMiddle / (length(fromMiddle) + 0.9) * 0.7 + toSun / (length(toSun) + 1.6) * 0.3;
        q.x += drift.x;

        // Two fields, one for each way the cloud is pushed: pushed both ways by the same one it
        // would only ever be smeared along a line, and comb out into streaks.
        float2 wq = q * 0.45 + float2(drift.y, -drift.y * 0.6);
        // A closed deck is pushed about less: it is a sheet, not a field of separate clouds.
        float2 warp = (float2(fbm2(wq), fbm2(wq + float2(31.7, 17.3))) - 0.5) * mix(1.5, 0.7, smoothstep(0.6, 1.0, cover));
        float n0 = deck(q, warp);
        float n1 = deck(q + l * 0.42, warp);

        float th = mix(0.7, 0.2, cover);
        float soft = mix(0.085, 0.32, cover);
        float dens = smoothstep(th, th + soft, n0);
        float thick = smoothstep(th, th + 0.5, n0);

        // Lit where it thins toward the sun, shaded where it thickens.
        float relief = mix(3.4, 1.1, smoothstep(0.55, 1.0, cover));
        float lit = smoothstep(0.0, 1.0, clamp(0.55 + (n0 - n1) * relief, 0.0, 1.0));
        float shade = thick * (0.45 + 0.5 * storm);
        float3 c = mix(cloudShade, cloudLit, lit * (1.0 - shade));
        // Its underside, in a storm, is the colour of slate.
        c = mix(c, cloudShade * 0.72, storm * thick * 0.55);
        // The bright rim of thin cloud in front of the sun.
        float rim = dens * (1.0 - dens) * 4.0;
        c += sunColor * rim * exp(-dSun * 3.2) * (0.35 + 0.65 * sun.z) * 0.5;
        // Sunlight soaking through thinner cloud near the sun.
        c += sunColor * exp(-dSun * 5.5) * (1.0 - thick) * 0.18;
        // Far cloud takes the colour of the air in front of it.
        c = mix(c, horizon, 0.42 * smoothstep(0.35, 1.0, t) * (1.0 - 0.6 * storm));

        // Lightning inside the cloud: brightest in its thin parts, round the strike.
        if (flash > 0.0) {
            float close = exp(-length(float2((uv.x - bolt.x) * aspect, uv.y - 0.22)) * 2.0);
            c += float3(0.78, 0.84, 1.0) * flash * (0.25 + 0.75 * close) * (0.35 + 0.65 * (1.0 - thick));
        }

        float a = dens * mix(0.9, 1.0, cover);
        rgb = c * a;
        alpha = a;

        // Cirrus: high, thin, combed out by the wind, only where the sky is partly open.
        float open = smoothstep(0.04, 0.3, cover) * (1.0 - smoothstep(0.55, 0.9, cover));
        if (open > 0.01) {
            float2 h = ceilingAt(uv, aspect, s);
            float wisp = fbm3(float2(h.x * 0.16 + drift.x * 1.7 + 4.0, h.y * 0.9 + h.x * 0.22));
            float ca = smoothstep(0.56, 0.92, wisp) * 0.34 * open * (1.0 - alpha);
            float3 cc = mix(cloudLit, horizon, 0.25) + sunColor * exp(-dSun * 3.0) * 0.3;
            rgb += cc * ca;
            alpha += ca;
        }
    } else if (flash > 0.0) {
        rgb += float3(0.78, 0.84, 1.0) * flash * 0.12;
    }

    if (fog > 0.01) {
        float f1 = fbm3(float2(p.x * 1.25 + drift.x * 0.9, p.y * 2.7 - drift.y * 0.5));
        float f2 = fbm2(float2(p.x * 2.6 - drift.x * 1.5 + 9.0, p.y * 4.4 + drift.y));
        float bank = 0.3 + 0.7 * smoothstep(0.0, 0.9, t);
        float fa = clamp(fog * bank * (0.3 + 1.25 * f1 * (0.5 + 1.0 * f2)), 0.0, 0.86);
        float3 fc = mix(horizon, cloudLit, 0.3) * (0.9 + 0.14 * f2) + float3(0.78, 0.84, 1.0) * flash * 0.2;
        rgb = rgb * (1.0 - fa) + fc * fa;
        alpha = alpha * (1.0 - fa) + fa;
    }

    return half4(half3(clamp(rgb, 0.0, 1.0)), half(clamp(alpha, 0.0, 1.0)));
}
"""

/**
 * What falls. Premultiplied, over the clouds, at full resolution: these are the only things in
 * the sky with edges.
 *
 * Rain is four sheets of streaks at four depths. A sheet is cut into narrow columns and each
 * column into rows; a row holds one drop, or none, falling at its column's own speed, so nothing
 * lines up. Near sheets are fast, long, wide apart and faint, the way close rain is a blur; far
 * ones are slow, short, packed and fine. Each streak has a bright head and a tail that fades up
 * behind it. [wind] leans them all.
 *
 * Snow is six sheets of flakes. Each flake swings on its way down, at a rate of its own; far
 * flakes are sharp points, near ones are large and out of focus, as a lens would have them.
 *
 * The bolt is drawn here too, so it keeps a hard core: a line shaken sideways by noise that is
 * coarse at the top and fine toward the ground, with a branch leaving it part way down.
 */
internal const val PRECIP_SHADER = """
uniform float2 size;
uniform float time;
uniform float unit;
uniform float rain;
uniform float snow;
uniform float wind;
uniform float3 light;
uniform float4 bolt;
""" + SHADER_NOISE + """
float streaks(float2 fc, float cell, float speed, float len, float amount, float seed) {
    float2 g = float2(fc.x + fc.y * wind * 0.35, fc.y) / (unit * cell);
    float col = floor(g.x);
    float cx = fract(g.x) - 0.5;
    float h1 = hash11(col * 1.37 + seed * 17.0);
    float h2 = hash11(col * 2.91 + seed * 31.0 + 5.0);
    float period = len * (2.2 + 3.6 * h2);
    float y = g.y / period - time * speed * (0.72 + 0.56 * h1) / period + h1 * 37.0;
    float row = floor(y);
    float ry = fract(y);
    float h3 = hash12(float2(col, row) + seed);
    float present = step(1.0 - amount, h3);
    float off = (hash12(float2(row, col) + seed * 3.1) - 0.5) * 0.6;
    float width = 0.035 + 0.04 * h3;
    float line = 1.0 - smoothstep(0.0, width, abs(cx - off));
    float frac = len / period;
    float s = (ry - (1.0 - frac)) / frac;
    float body = smoothstep(0.0, 0.75, s) * (1.0 - smoothstep(0.93, 1.0, s)) * step(0.0, s);
    return present * line * body * (0.55 + 0.45 * h3);
}

float flakes(float2 fc, float cell, float speed, float blur, float amount, float seed) {
    float2 g = fc / (unit * cell);
    g.y -= time * speed;
    g.x += wind * time * speed * 0.45 + sin(g.y * 0.9 + seed * 3.0) * 0.28;
    float2 id = floor(g);
    float2 f = fract(g) - 0.5;
    float h = hash12(id + seed * 5.3);
    float present = step(1.0 - amount, h);
    float2 o = (hash22(id + seed) - 0.5) * 0.62;
    o.x += sin(time * (0.6 + 1.4 * fract(h * 13.0)) + h * 40.0) * 0.1;
    float r = mix(0.07, 0.17, fract(h * 57.0));
    float d = length(f - o);
    float flake = 1.0 - smoothstep(r * (1.0 - blur), r, d);
    return present * flake * (0.5 + 0.5 * fract(h * 7.3));
}

float boltLine(float2 p, float x0, float seed, float top, float bottom, float sway) {
    float y = clamp((p.y - top) / max(bottom - top, 0.001), 0.0, 1.0);
    float x = x0 + (fbm3(float2(p.y * 4.0, seed)) - 0.5) * sway * (0.25 + y)
        + (vnoise(float2(p.y * 46.0, seed + 3.0)) - 0.5) * 0.018;
    float d = abs(p.x - x);
    float within = smoothstep(top, top + 0.07, p.y) * (1.0 - smoothstep(bottom - 0.03, bottom, p.y));
    float core = 0.0016 / (d + 0.0016);
    return within * (core * core * 1.1 + exp(-d * 55.0) * 0.5);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float aspect = size.x / max(size.y, 1.0);
    float3 rgb = float3(0.0);
    float alpha = 0.0;

    if (rain > 0.01) {
        float amount = 0.12 + 0.6 * rain;
        float back = streaks(fragCoord, 5.0, 46.0, 7.0, amount, 1.0) * 0.5;
        back += streaks(fragCoord, 8.0, 62.0, 10.0, amount, 2.0) * 0.62;
        float nearer = streaks(fragCoord, 14.0, 84.0, 14.0, amount * 0.8, 3.0) * 0.6;
        nearer += streaks(fragCoord, 24.0, 118.0, 20.0, amount * 0.55 * smoothstep(0.25, 0.8, rain), 4.0) * 0.42;
        float a = clamp((back + nearer) * (0.24 + 0.4 * rain), 0.0, 0.8);
        rgb += light * a;
        alpha += a;
    }

    if (snow > 0.01) {
        float amount = 0.2 + 0.6 * snow;
        float s = flakes(fragCoord, 9.0, 1.1, 0.25, amount, 1.0) * 0.5;
        s += flakes(fragCoord, 14.0, 1.0, 0.3, amount, 2.0) * 0.62;
        s += flakes(fragCoord, 22.0, 0.9, 0.4, amount, 3.0) * 0.74;
        s += flakes(fragCoord, 36.0, 0.8, 0.55, amount * 0.85, 4.0) * 0.78;
        s += flakes(fragCoord, 62.0, 0.7, 0.75, amount * 0.6, 5.0) * 0.5;
        s += flakes(fragCoord, 110.0, 0.62, 0.9, amount * 0.4 * smoothstep(0.3, 0.9, snow), 6.0) * 0.3;
        float a = clamp(s, 0.0, 0.95);
        float3 white = mix(light, float3(1.0), 0.6);
        rgb = rgb * (1.0 - a) + white * a;
        alpha = alpha * (1.0 - a) + a;
    }

    float age = bolt.z;
    if (bolt.w > 0.0 && age >= 0.0 && age < 0.45) {
        float2 p = float2(uv.x * aspect, uv.y);
        float x0 = bolt.x * aspect;
        float seed = bolt.y;
        float reach = 0.5 + 0.32 * hash11(seed);
        float fork = 0.16 + 0.2 * hash11(seed + 2.0);
        float lean = (hash11(seed + 4.0) - 0.5) * 0.9;
        float main_ = boltLine(p, x0, seed, -0.02, reach, 0.3);
        float forkX = x0 + (fbm3(float2(fork * 4.0, seed)) - 0.5) * 0.3 * (0.25 + (fork + 0.02) / (reach + 0.02));
        float2 pb = float2(p.x - (p.y - fork) * lean, p.y);
        float branch = boltLine(pb, forkX, seed + 9.0, fork, fork + 0.24, 0.18) * 0.55;
        float strike = exp(-age * 16.0) + step(0.16, age) * exp(-(age - 0.16) * 13.0) * 0.6;
        float b = clamp((main_ + branch) * strike * bolt.w, 0.0, 1.0);
        float3 white = float3(0.9, 0.93, 1.0);
        rgb = rgb * (1.0 - b) + white * b;
        alpha = alpha * (1.0 - b) + b;
    }

    return half4(half3(clamp(rgb, 0.0, 1.0)), half(clamp(alpha, 0.0, 1.0)));
}
"""

/**
 * Drops on the glass, as a render effect over the whole sky (`content` is the sky behind it).
 *
 * Every drop is a lens: the picture inside it is the sky pulled in from around it, upside down
 * and small, which is done here by reading `content` at a point pushed away from the drop's
 * centre in proportion to where in the drop the pixel is. Three populations share the glass:
 *
 * - a fine mist of beads that sit where they landed, swelling and drying out on their own clocks;
 * - large drops that hang, gather weight, and run, each in its own lane, jerkily, wandering
 *   sideways as they go;
 * - the trail a running drop leaves: a wet track that narrows, and breaks into beads behind it.
 *
 * [amount] is how wet the glass is, 0–1: it thins the beads out and slows the runs when small.
 * Each drop gets a rim that darkens toward its edge and a glint where the light catches it.
 */
internal const val GLASS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float time;
uniform float unit;
uniform float amount;
""" + SHADER_NOISE + """
// xy: which way the glass bends the view here; z: how much of a drop this is.
float3 beads(float2 fc, float cell, float seed) {
    float2 g = fc / (unit * cell);
    float2 id = floor(g);
    float2 f = fract(g) - 0.5;
    float h = hash12(id + seed);
    float2 o = (hash22(id + seed * 2.3) - 0.5) * 0.74;
    float life = fract(time * (0.03 + 0.05 * h) + h * 11.0);
    float swell = smoothstep(0.0, 0.25, life) * (1.0 - smoothstep(0.7, 1.0, life));
    float r = (0.07 + 0.19 * fract(h * 23.0)) * swell * step(1.0 - (0.12 + 0.38 * amount), fract(h * 3.7));
    float2 d = f - o;
    float m = 1.0 - smoothstep(r * 0.55, r, length(d));
    return float3(d / max(r, 0.001) * m, m);
}

float3 runners(float2 fc, float lane, float seed) {
    float2 g = fc / (unit * lane);
    float col = floor(g.x);
    float h = hash11(col * 1.9 + seed * 7.0);
    // A lane is busy or it isn't; more of them are as the glass gets wetter.
    float busy = step(1.0 - (0.25 + 0.6 * amount), fract(h * 5.3));
    float rows = 3.2;
    float y = g.y / rows - time * (0.05 + 0.09 * h) * (0.5 + 0.8 * amount) + h * 17.0;
    float row = floor(y);
    float fy = fract(y);
    float hr = hash12(float2(col, row) + seed * 3.0);
    // The drop hangs near the top of its cell, then lets go: slow, slow, quick.
    float phase = fract(time * (0.11 + 0.1 * hr) + hr * 9.0);
    float fall = phase * phase * (3.0 - 2.0 * phase);
    float dropY = 0.12 + 0.76 * fall;
    float wob = sin(fy * 9.0 + hr * 30.0) * 0.07 * (1.0 - fall * 0.5);
    float dropX = 0.5 + (hr - 0.5) * 0.22 + wob;
    float2 d = float2(fract(g.x) - dropX, (fy - dropY) * rows);
    // Small enough that neither the drop nor its trail reaches the lane's edge, where it would be cut.
    float r = 0.15 + 0.08 * hr;
    // Heavier below, as a hanging drop is.
    float shape = length(d * float2(1.0, 0.82 + 0.3 * step(0.0, d.y)));
    float m = (1.0 - smoothstep(r * 0.6, r, shape)) * busy;
    float2 bend = d / r * m;

    // The trail: above the drop, narrowing, breaking into beads as it dries.
    float above = step(fy, dropY) * smoothstep(0.0, dropY, fy);
    float track = (1.0 - smoothstep(0.0, r * 0.42 * above + 0.02, abs(fract(g.x) - dropX))) * above * busy;
    float beadY = fract(fy * rows * 3.5) - 0.5;
    float bead = (1.0 - smoothstep(0.12, 0.34, abs(beadY))) * track;
    float trail = bead * (0.3 + 0.7 * above) * (1.0 - m);
    bend += float2((fract(g.x) - dropX) / (r * 0.42), beadY * 1.6) * trail * 0.7;
    return float3(bend, max(m, trail * 0.75));
}

half4 main(float2 fragCoord) {
    if (amount < 0.01) {
        return content.eval(fragCoord);
    }
    float3 a = beads(fragCoord, 9.0, 1.0);
    float3 b = beads(fragCoord, 17.0, 4.0);
    float3 c = runners(fragCoord, 30.0, 2.0);
    float3 e = runners(fragCoord + float2(unit * 13.0, unit * 41.0), 46.0, 6.0);
    float2 bend = a.xy * 0.45 + b.xy * 0.7 + c.xy + e.xy * 1.15;
    float wet = clamp(a.z * 0.6 + b.z * 0.8 + c.z + e.z, 0.0, 1.0);

    // A lens turns the view over: look the other way, further off the nearer the rim.
    float2 from = fragCoord - bend * unit * 16.0;
    half4 seen = content.eval(clamp(from, float2(0.5), size - 0.5));
    float3 col = float3(seen.rgb);
    float rimDark = dot(bend, bend);
    col *= 1.0 - 0.34 * clamp(rimDark, 0.0, 1.0) * wet;
    float glint = pow(clamp(dot(normalize(float3(-bend, 0.6)), normalize(float3(-0.45, -0.6, 0.66))), 0.0, 1.0), 26.0);
    col += glint * wet * 0.55;
    col += 0.035 * wet;
    return half4(half3(clamp(col, 0.0, 1.0)), seen.a);
}
"""

/**
 * The moon on its own, for the moon tile: the same lit sphere as the sky's (see
 * [CELESTIAL_SHADER]), filling its canvas, on nothing. `cycle` is 0 at new, 0.5 at full; the
 * unlit side shows faintly, as it does in a clear sky. Premultiplied.
 */
internal const val MOON_SHADER = """
uniform float2 size;
uniform float cycle;
""" + SHADER_NOISE + """
half4 main(float2 fragCoord) {
    float r = min(size.x, size.y) * 0.5;
    float2 q = (fragCoord - size * 0.5) / (r * 0.86);
    float d2 = dot(q, q);
    float lit = 0.5 - 0.5 * cos(cycle * 6.2831853);
    float halo = exp(-max(sqrt(d2) - 1.0, 0.0) * 9.0) * 0.22 * (0.25 + 0.75 * lit);
    if (d2 >= 1.0) {
        return half4(half3(float3(0.75, 0.82, 1.0) * halo), half(halo));
    }
    float z = sqrt(1.0 - d2);
    float a = cycle * 6.2831853;
    float3 l = float3(sin(a), 0.0, -cos(a));
    float3 n = float3(q.x, -q.y, z);
    float day = smoothstep(-0.05, 0.14, dot(n, l));
    float seas = fbm4(q * 1.7 + 5.2);
    float pits = vnoise(q * 9.0 + 2.0);
    float albedo = 0.6 + 0.34 * smoothstep(0.38, 0.62, seas) + 0.08 * pits;
    float3 face = float3(0.97, 0.95, 0.9) * albedo * (0.72 + 0.28 * z);
    float3 body = face * (0.07 + 0.93 * day);
    float edge = 1.0 - smoothstep(1.0 - 2.4 / r, 1.0, sqrt(d2));
    return half4(half3(body * edge), half(edge));
}
"""
