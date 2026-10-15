package com.meticulouscreations.homesafe.fitness.ui.movement

import com.meticulouscreations.homesafe.fitness.ui.shader.NOISE

/**
 * Muscle, close to: the grain a belly of fibres has. It is drawn in the muscle's own space, not
 * the screen's: x is pixels along the muscle from its origin and y pixels across it, so the
 * strands run tendon to tendon and draw together where the belly narrows, however the limb lies.
 * It gives only light and dark (the mesh it is laid on carries the colour): [grain] is a strand's
 * width in pixels.
 */
internal const val FIBRE_SHADER = """
uniform float grain;
$NOISE
half4 main(float2 c) {
    float wander = noise(float2(c.x / (grain * 10.0), c.y / (grain * 2.5))) - 0.5;
    float strand = 0.5 + 0.5 * cos((c.y / grain + wander * 1.4) * 6.2831853);
    float fleck = noise(float2(c.x / (grain * 3.0), c.y / (grain * 0.5)));
    float m = 0.70 + 0.30 * (strand * 0.72 + fleck * 0.28);
    return half4(half3(m), 1.0);
}
"""

/**
 * The rendered lifter with its working muscles hot, as the renderer's own previews show it
 * (`tools/movement-render/pictures.py`, `glowing`): the stone takes the colour of heat where a
 * muscle works, deep red where it works a little and through orange to yellow where it works
 * hard, keeping the stone's own light and shade under it, and the heat throws a little light
 * on what is around it.
 *
 * [body] and [muscle] are the two sheets of frames; [cells] and [lights] say where in each the
 * two frames being blended start (in the sheet's pixels: the first frame's corner, then the
 * second's), [frames] how big a frame is in each, [blend] how far from the first frame to the
 * second, and [effort] how hard the rep is now. It is drawn over the square at [origin] that is
 * [extent] across.
 */
internal const val HEAT_SHADER = """
uniform shader body;
uniform shader muscle;
uniform float2 origin;
uniform float2 extent;
uniform float4 cells;
uniform float4 lights;
uniform float2 frames;
uniform float blend;
uniform float effort;

float3 ramp(float heat) {
    float3 color = mix(float3(0.78, 0.07, 0.05), float3(1.0, 0.42, 0.08), smoothstep(0.1, 0.5, heat));
    color = mix(color, float3(1.0, 0.82, 0.38), smoothstep(0.45, 0.85, heat));
    return mix(color, float3(1.0, 0.97, 0.88), smoothstep(0.8, 1.05, heat));
}

// How hot the skin is at a place in the frame (0 to 1 across and down it): the prime movers fully, the synergists
// less, what only steadies the lift not at all.
float heatAt(float2 place) {
    float2 at = clamp(place, 0.0, 1.0) * (frames.y - 1.0) + 0.5;
    float3 held = mix(muscle.eval(lights.xy + at).rgb, muscle.eval(lights.zw + at).rgb, blend);
    return (pow(max(held.r, 0.0), 1.2) + 0.6 * pow(max(held.g, 0.0), 1.2)) * (0.4 + 0.6 * effort);
}

half4 main(float2 fragCoord) {
    float2 place = (fragCoord - origin) / extent;
    if (place.x < 0.0 || place.y < 0.0 || place.x > 1.0 || place.y > 1.0) {
        return half4(0.0);
    }
    float2 at = place * (frames.x - 1.0) + 0.5;
    half4 lifter = mix(body.eval(cells.xy + at), body.eval(cells.zw + at), blend);
    float heat = clamp(heatAt(place), 0.0, 1.0);
    // The light the heat throws: the heat about this place, spread wide.
    float spread = heat;
    for (int i = 0; i < 6; i++) {
        float turn = float(i) * 1.0472;
        spread += 0.61 * heatAt(place + 0.022 * float2(cos(turn), sin(turn)));
        spread += 0.135 * heatAt(place + 0.044 * float2(cos(turn + 0.5236), sin(turn + 0.5236)));
    }
    spread = clamp(spread / 5.47, 0.0, 1.0);

    float there = lifter.a;
    float3 stone = there > 0.001 ? lifter.rgb / there : float3(0.0);
    // Never as far as white: on pale stone, white heat would look like no heat at all.
    float shade = 0.55 + 0.45 * clamp(dot(stone, float3(0.3333)) * 1.6, 0.0, 1.0);
    float cover = clamp(heat * 1.3, 0.0, 0.92);
    float3 lit = stone * (1.0 - cover) + ramp(heat * 0.74) * shade * cover + ramp(0.45) * spread * 0.12;
    float3 thrown = ramp(0.5) * spread * 0.55 * (1.0 - there);
    float3 color = clamp(lit, 0.0, 1.0) * there + thrown;
    return half4(half3(color), there + (1.0 - there) * clamp(max(thrown.r, max(thrown.g, thrown.b)), 0.0, 1.0));
}
"""

/** Every movement shader, by name, for the tests that check each compiles. */
internal val MOVEMENT_SHADERS = mapOf("fibre" to FIBRE_SHADER, "pulse" to PULSE_SHADER, "marble" to MARBLE_SHADER, "heat" to HEAT_SHADER)
