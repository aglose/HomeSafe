package com.meticulouscreations.homesafe.atlas.ui.shader

import com.meticulouscreations.homesafe.weather.ui.shader.SHADER_NOISE

// The atlas app's shaders, in the language Android's RuntimeShader (AGSL) and Skia's
// RuntimeEffect share (see SkyShaders.kt for its rules). They are run through the weather app's
// `WeatherShader`, which is the same two platform classes whichever app is drawing.

/**
 * What the map lies on, and all there is of it where no tile has arrived (no connection, or an
 * ocean of nothing): deep night with a graticule, the lines of latitude and longitude, which
 * moves with the map, so a pan with nothing loaded still reads as moving over the Earth.
 *
 * [origin] is the map's top-left corner in whole-world pixels at the current zoom, and [cell]
 * the side of one graticule square in pixels.
 */
internal const val ATLAS_NIGHT_SHADER = """
uniform float2 size;
uniform float2 origin;
uniform float cell;
uniform float time;
""" + SHADER_NOISE + """
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float2 world = fragCoord + origin;
    float3 col = mix(float3(0.016, 0.024, 0.045), float3(0.030, 0.045, 0.085), smoothstep(1.2, -0.2, uv.y));
    // A slow unevenness, so the dark has depth rather than being one flat ink.
    float cloud = vnoise(world / (cell * 2.6) + float2(time * 0.01, 0.0));
    col += float3(0.012, 0.018, 0.034) * cloud;
    // The graticule: hairlines, with every fourth a little firmer.
    float2 g = world / cell;
    float2 fine = abs(fract(g) - 0.5);
    float line = 1.0 - smoothstep(0.0, 1.4 / cell, 0.5 - max(fine.x, fine.y));
    float2 coarse = abs(fract(g / 4.0) - 0.5);
    float major = 1.0 - smoothstep(0.0, 0.5 / cell, 0.5 - max(coarse.x, coarse.y));
    col += float3(0.10, 0.16, 0.26) * (line * 0.22 + major * 0.30);
    // Where two firm lines meet, a point of light that breathes.
    float2 node = abs(fract(g / 4.0 + 0.5) - 0.5) * 4.0 * cell;
    float dot_ = exp(-dot(node, node) / 9.0);
    col += float3(0.25, 0.42, 0.62) * dot_ * (0.55 + 0.45 * sin(time * 0.8 + hash12(floor(g / 4.0 + 0.5)) * 6.283));
    float vignette = smoothstep(1.25, 0.35, length(uv - 0.5) * 1.4);
    col *= mix(0.55, 1.0, vignette);
    return half4(half3(col), 1.0);
}
"""

/**
 * The glow. `content` is a layer of pictures of how often somewhere was gone through, made off
 * the screen with their bloom already in them (`HeatRaster`): in each pixel's red the road itself
 * (the spot and the ring just round it), in its green the soft halo beyond. This makes light of
 * it, the way a city is seen from orbit, and it looks once at each pixel to do so: the looking
 * round, thirteen times a pixel, used to be done here on every frame.
 *
 * - **Colour.** From a dim violet for somewhere gone once, through ember and amber for the usual
 *   roads, to white where every day runs. Lightness only rises along it.
 * - **Life.** The brightest parts shimmer very slightly, out of step with each other, as lights
 *   seen through air do.
 * - **Flights.** The pictures carry the flights' arcs in their blue, and those are laid over the
 *   glow in [air]'s colour: drawn as paths they were a mask the size of the screen, made and
 *   handed to the graphics chip on every frame the map moved.
 */
internal const val ATLAS_HEAT_SHADER = """
uniform shader content;
uniform float time;
uniform float3 air;
""" + SHADER_NOISE + """
half4 main(float2 fragCoord) {
    half4 picture = content.eval(fragCoord);
    float v = float(picture.r);
    float glow = float(picture.g);
    float3 dim = float3(0.36, 0.20, 0.62);
    float3 ember = float3(1.00, 0.36, 0.20);
    float3 amber = float3(1.00, 0.72, 0.33);
    float3 white = float3(1.00, 0.97, 0.88);
    float3 col = mix(dim, ember, smoothstep(0.04, 0.40, v));
    col = mix(col, amber, smoothstep(0.32, 0.72, v));
    col = mix(col, white, smoothstep(0.70, 1.0, v));
    float shimmer = 1.0 + 0.08 * sin(time * 2.3 + hash12(floor(fragCoord / 3.0)) * 6.283) * smoothstep(0.5, 1.0, v);
    // The road itself is all but solid; its halo is the dim end of the ramp, half seen through.
    float body = smoothstep(0.0, 0.16, v);
    float alpha = clamp(max(body * 0.96, glow * 0.5), 0.0, 1.0);
    float3 lit = clamp(mix(mix(dim, ember, 0.5), col * shimmer, body), 0.0, 1.0);
    // The flights lie over the glow, most of the way to solid along their line.
    float flight = float(picture.b) * 0.7;
    return half4(half3(lit * alpha * (1.0 - flight) + air * flight), half(alpha + flight - alpha * flight));
}
"""

/**
 * Contour lines, as on a walker's map: what the cards and the heads of the screens are drawn
 * on. A slow field of hills whose contours drift, lit in [tint] where they run closest together
 * and falling to [tint2] in the hollows. [seed] gives each card its own country.
 */
internal const val ATLAS_CONTOUR_SHADER = """
uniform float2 size;
uniform float time;
uniform float seed;
uniform float3 tint;
uniform float3 tint2;
uniform float energy;
""" + SHADER_NOISE + """
float hills(float2 p) {
    float h = vnoise(p) * 0.55 + vnoise(p * 2.03 + 17.0) * 0.30 + vnoise(p * 4.1 + 41.0) * 0.15;
    return h;
}

half4 main(float2 fragCoord) {
    float unit = min(size.x, size.y);
    float2 p = fragCoord / unit * 2.2 + float2(seed * 13.7, seed * 7.3);
    p += float2(time * 0.018, time * 0.011);
    float h = hills(p);
    float bands = h * 14.0;
    float f = abs(fract(bands) - 0.5);
    // How fast the ground rises here, by looking a pixel and a half either way: the language has
    // no derivatives, and a line of even width needs to know.
    float e = 3.3 / unit;
    float w = length(float2(hills(p + float2(e, 0.0)) - h, hills(p + float2(0.0, e)) - h)) * 14.0 * 1.2 + 0.02;
    float line = 1.0 - smoothstep(0.0, w, f);
    float major = 1.0 - smoothstep(0.0, w * 1.6, abs(fract(bands / 5.0) - 0.5) * 5.0);
    float3 base = mix(float3(0.016, 0.024, 0.045), tint2 * 0.55, smoothstep(0.25, 0.9, h));
    float3 col = base + tint * (line * 0.34 + major * 0.5) * (0.4 + energy * 0.6) * smoothstep(0.15, 0.75, h);
    // One contour at a time lights up and passes, like a survey sweeping the ground.
    float sweep = fract(time * 0.05 + seed);
    float lit = exp(-pow((h - sweep) * 9.0, 2.0));
    col += tint * lit * line * 0.9 * energy;
    float2 uv = fragCoord / size;
    col *= mix(0.6, 1.0, smoothstep(1.3, 0.3, length(uv - float2(0.3, 0.2))));
    return half4(half3(col), 1.0);
}
"""

internal val AtlasShaderSources: Map<String, String> = mapOf(
    "night" to ATLAS_NIGHT_SHADER,
    "heat" to ATLAS_HEAT_SHADER,
    "contour" to ATLAS_CONTOUR_SHADER,
)
