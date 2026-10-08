package com.meticulouscreations.homesafe.weather.ui.shader

/*
 * The radar screen's two shaders. Both are render effects: each reworks a layer of map tiles
 * that has already been drawn, which it reads through `content`.
 */

/**
 * Night over a daylight map. The base map's tiles are OpenStreetMap's standard style, which is
 * drawn for paper: pale land, pale water, dark words. Radar wants the opposite, a dark, quiet
 * map that colour can sit on, so this turns it over:
 *
 * - lightness is inverted, so land goes dark and lettering goes light;
 * - hue is then turned half way round, which undoes what inverting did to it (inverted water
 *   is brown; turned back it is blue again, and parks are green again);
 * - most of the colour is taken out and what's left is cooled toward the night sky's blue, the
 *   blacks lifted off pure black so the map still reads as a surface, and the whole of it held
 *   down to leave the top of the range for the radar.
 *
 * [dim] takes it down further, for when the radar is drawn over it.
 */
internal const val MAP_NIGHT_SHADER = """
uniform shader content;
uniform float dim;

half4 main(float2 fragCoord) {
    half4 src = content.eval(fragCoord);
    float3 c = float3(src.rgb);
    float3 inv = float3(1.0) - c;
    float mean = (inv.r + inv.g + inv.b) / 3.0;
    float3 turned = clamp(2.0 * mean - inv, 0.0, 1.0);
    float lum = dot(turned, float3(0.299, 0.587, 0.114));
    float3 col = mix(float3(lum), turned, 0.42);
    // Deepen the darks (land, sea) and keep the lights (roads' edges, lettering) apart from them.
    col = pow(col, float3(1.35));
    col = col * float3(0.78, 0.86, 1.0) * 0.82 + float3(0.035, 0.045, 0.07);
    col *= 1.0 - 0.35 * dim;
    return half4(half3(col * float(src.a)), src.a);
}
"""

/**
 * Radar. `content` is a layer of decoded radar tiles (see RadarDecoder): not colours but
 * strengths, grey for how hard it is raining and clear where it isn't, stretched up from tiles
 * several times coarser than the screen. This makes a picture of them.
 *
 * - **Smoothing.** Nine taps in a tent a few pixels wide ([blur]) melt the stretched texels'
 *   corners into the rounded shapes rain actually has. Because the layer holds strengths, the
 *   average of two neighbours is the strength between them; and because one frame is faded into
 *   the next in the same layer, the in-between frames are in-between rain, not a double exposure
 *   of two palettes.
 * - **Colour.** One ramp for every source, ordered so it reads without a legend and still reads
 *   with one kind of colour blindness or another: pale blue mist, blue, teal, yellow, orange,
 *   red, and magenta into white for hail. Lightness never doubles back along it.
 * - **Depth.** Light rain is translucent and the map shows through it; heavy rain is solid. Each
 *   band's upper edge carries a faint brighter line, as an isobar would, so the structure of a
 *   storm reads at a glance. Cores above about 50 dBZ glow and breathe.
 * - **Life.** A fine grain drifts down through the echoes, so even a still frame looks like weather.
 * - **Forecast.** Frames that are a model's guess ([forecast] 1) are drawn paler and hatched,
 *   so nobody takes them for something a radar saw.
 */
internal const val RADAR_SHADER = """
uniform shader content;
uniform float2 size;
uniform float time;
uniform float unit;
uniform float blur;
uniform float forecast;
uniform float strength;
""" + SHADER_NOISE + """
float level(float2 at) {
    return float(content.eval(at).r);
}

float3 ramp(float v) {
    float3 c = float3(0.62, 0.80, 0.96);
    c = mix(c, float3(0.26, 0.56, 0.96), smoothstep(0.03, 0.17, v));
    c = mix(c, float3(0.10, 0.76, 0.66), smoothstep(0.17, 0.33, v));
    c = mix(c, float3(0.98, 0.90, 0.30), smoothstep(0.33, 0.48, v));
    c = mix(c, float3(1.00, 0.56, 0.14), smoothstep(0.48, 0.62, v));
    c = mix(c, float3(0.94, 0.16, 0.22), smoothstep(0.62, 0.77, v));
    c = mix(c, float3(0.86, 0.30, 0.96), smoothstep(0.77, 0.9, v));
    c = mix(c, float3(1.0, 0.94, 1.0), smoothstep(0.9, 1.0, v));
    return c;
}

half4 main(float2 fragCoord) {
    float r = blur;
    float v = level(fragCoord) * 0.25;
    v += (level(fragCoord + float2(r, 0.0)) + level(fragCoord - float2(r, 0.0)) + level(fragCoord + float2(0.0, r)) + level(fragCoord - float2(0.0, r))) * 0.125;
    v += (level(fragCoord + float2(r, r)) + level(fragCoord - float2(r, r)) + level(fragCoord + float2(r, -r)) + level(fragCoord + float2(-r, r))) * 0.0625;
    if (v < 0.004) {
        return half4(0.0);
    }

    float3 col = ramp(v);
    // Light rain lets the map through; heavy rain doesn't.
    float a = smoothstep(0.006, 0.07, v) * mix(0.58, 0.96, smoothstep(0.05, 0.45, v));

    // A brighter line along the top of each band of the ramp.
    float bands = v * 6.6 + 0.2;
    float line = 1.0 - smoothstep(0.0, 0.09, abs(fract(bands) - 0.5) * 2.0 - 0.86);
    col += line * 0.1 * smoothstep(0.08, 0.3, v);

    // Rain that looks like it is falling: a grain drifting down through the echoes.
    float grain = vnoise(float2(fragCoord.x, fragCoord.y - time * unit * 26.0) / (unit * 2.6));
    col *= 0.9 + 0.2 * grain * smoothstep(0.04, 0.3, v);

    // The cores of storms glow, and breathe.
    float core = smoothstep(0.58, 0.86, v);
    col += float3(1.0, 0.55, 0.62) * core * (0.16 + 0.1 * sin(time * 2.6));

    // A forecast is paler and hatched: a model's guess, not something a radar saw.
    float hatch = 0.5 + 0.5 * sin((fragCoord.x + fragCoord.y) / (unit * 2.4));
    float guess = mix(1.0, 0.6 + 0.26 * hatch, forecast);
    col = mix(col, float3(dot(col, float3(0.299, 0.587, 0.114))), 0.22 * forecast);

    a *= guess * strength;
    return half4(half3(clamp(col, 0.0, 1.0) * a), half(a));
}
"""
