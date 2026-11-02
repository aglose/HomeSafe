package com.meticulouscreations.homesafe.car3d

/** GLSL for the car, without its `#version` line: each platform's [Gl] adds its own. */
internal object CarShaders {
    const val VERTEX = """
layout(location = 0) in vec4 aPosition; // w is the baked occlusion
layout(location = 1) in vec2 aNormal;
uniform mat4 uViewProjection;
uniform mat4 uModel;
uniform float uUnit;
out vec3 vWorld;
out vec3 vLocal;
out vec3 vNormal;
out float vOcclusion;

vec3 octDecode(vec2 e) {
    vec3 n = vec3(e.xy, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return normalize(n);
}

void main() {
    vec3 local = aPosition.xyz * uUnit;
    vec4 world = uModel * vec4(local, 1.0);
    vLocal = local;
    vWorld = world.xyz;
    // The model matrix only moves, turns and mirrors, so it can carry the normal as it is.
    vNormal = mat3(uModel) * octDecode(aNormal);
    vOcclusion = aPosition.w / 32767.0;
    gl_Position = uViewProjection * world;
}
"""

    private const val LIGHT = """
precision highp float;
uniform samplerCube uEnvironment;
uniform float uLevels;
uniform float uExposure;
uniform vec3 uCamera;
uniform vec3 uHarmonics[9];
uniform vec2 uSkyTurn; // cos and sin: the sky stays where the viewer is, as if the car were on a turntable

vec3 turned(vec3 d) {
    return vec3(uSkyTurn.x * d.x + uSkyTurn.y * d.y, -uSkyTurn.y * d.x + uSkyTurn.x * d.y, d.z);
}

// The world has Z up; the cube's faces were laid out for GL's Y up.
vec3 sky(vec3 world, float roughness) {
    vec3 d = turned(world);
    return textureLod(uEnvironment, vec3(d.x, d.z, -d.y), roughness * (uLevels - 1.0)).rgb;
}

vec3 irradiance(vec3 world) {
    vec3 n = turned(world);
    return uHarmonics[0] * 0.886227
        + (uHarmonics[1] * n.y + uHarmonics[2] * n.z + uHarmonics[3] * n.x) * 1.023328
        + uHarmonics[4] * n.x * n.y * 0.858086
        + uHarmonics[5] * n.y * n.z * 0.858086
        + uHarmonics[6] * (3.0 * n.z * n.z - 1.0) * 0.247708
        + uHarmonics[7] * n.x * n.z * 0.858086
        + uHarmonics[8] * (n.x * n.x - n.y * n.y) * 0.429043;
}

// Karis's fit to the split-sum's lookup table.
vec3 specularTerm(vec3 f0, float roughness, float nv) {
    vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 r = roughness * c0 + c1;
    float a004 = min(r.x * r.x, exp2(-9.28 * nv)) * r.x + r.y;
    vec2 ab = vec2(-1.04, 1.04) * a004 + r.zw;
    return f0 * ab.x + ab.y;
}

float specularOcclusion(float nv, float occlusion, float roughness) {
    return clamp(pow(nv + occlusion, exp2(-16.0 * roughness * roughness - 1.0)) - 1.0 + occlusion, 0.0, 1.0);
}

// Khronos' PBR Neutral, what the reference renders use: colours keep their hue in the highlights.
vec3 neutral(vec3 color) {
    float startCompression = 0.8 - 0.04;
    float desaturation = 0.15;
    float x = min(color.r, min(color.g, color.b));
    float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
    color -= offset;
    float peak = max(color.r, max(color.g, color.b));
    if (peak < startCompression) return color;
    float d = 1.0 - startCompression;
    float newPeak = 1.0 - d * d / (peak + d - startCompression);
    color *= newPeak / peak;
    float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
    return mix(color, vec3(newPeak), g);
}

vec3 toScreen(vec3 linear) {
    vec3 c = clamp(neutral(linear * uExposure), 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}
"""

    const val SURFACE = LIGHT + """
in vec3 vWorld;
in vec3 vLocal;
in vec3 vNormal;
in float vOcclusion;
uniform vec3 uBase;
uniform float uMetal;
uniform float uRoughness;
uniform float uCoat;
uniform float uCoatRoughness;
uniform vec3 uFlake;
uniform float uFlakeAmount;
uniform vec3 uEmissive;
uniform float uOcclusionPower;
out vec4 fragColor;

vec3 hash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

void main() {
    vec3 v = normalize(uCamera - vWorld);
    vec3 n = normalize(vNormal);
    if (dot(n, v) < 0.0) n = -n; // the base mesh's winding isn't to be trusted
    float nv = max(dot(n, v), 0.001);
    vec3 base = uBase;
    float roughness = uRoughness;
    vec3 baseNormal = n;

    if (uFlakeAmount > 0.0) {
        // Metal flakes in the paint, each with its own tilt. Too fine to see from afar, where they
        // only widen the base layer's highlight, so they fade as a pixel covers many.
        const float cells = 3000.0;
        vec3 q = vLocal * cells;
        float footprint = length(fwidth(q));
        float near = clamp(1.6 - footprint * 0.8, 0.0, 1.0);
        vec3 h = hash(floor(q));
        if (near > 0.0) {
            baseNormal = normalize(n + (h - 0.5) * 0.14 * near);
        }
        float glint = mix(uFlakeAmount, (0.8 + h.x * h.x * 0.7) * uFlakeAmount, near);
        base = mix(uBase, uFlake, glint);
        roughness = mix(roughness + 0.04, roughness, near);
    }

    vec3 f0 = mix(vec3(0.04), base, uMetal);
    vec3 baseR = reflect(-v, baseNormal);
    float baseNv = max(dot(baseNormal, v), 0.001);
    float occlusion = pow(vOcclusion, uOcclusionPower);
    float specOcc = specularOcclusion(baseNv, occlusion, roughness);
    vec3 color = base * (1.0 - uMetal) * irradiance(n) * occlusion / 3.14159265
        + sky(baseR, roughness) * specularTerm(f0, roughness, baseNv) * specOcc;

    if (uCoat > 0.0) {
        float fresnel = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
        vec3 r = reflect(-v, n);
        // The coat's reflection of what's under the horizon of its own surface is the car itself.
        float horizon = clamp(1.0 + 1.3 * dot(r, normalize(vNormal)), 0.0, 1.0);
        // Only a real overhang dims the coat: on an open panel, the bake's small variations would
        // show as blotches in every reflection.
        vec3 coat = sky(r, uCoatRoughness) * smoothstep(0.15, 0.65, vOcclusion) * horizon * horizon;
        color = mix(color, color * (1.0 - fresnel) * (1.0 - fresnel) + coat * fresnel, uCoat);
    }
    fragColor = vec4(toScreen(color + uEmissive), 1.0);
}
"""

    /** A single sheet of glass or a lens: what it reflects, over what its tint lets through. */
    const val GLASS = LIGHT + """
in vec3 vWorld;
in vec3 vLocal;
in vec3 vNormal;
in float vOcclusion;
uniform vec3 uTint;
uniform float uSide; // 1 draws only faces seen from outside, -1 only those seen from inside, 0 both
out vec4 fragColor;

void main() {
    vec3 v = normalize(uCamera - vWorld);
    vec3 n = normalize(vNormal);
    // Winding can't be trusted, but a pane's outside faces away from the middle of the cabin.
    if (dot(n, vLocal - vec3(0.0, 0.2, 0.85)) < 0.0) n = -n;
    bool outside = dot(n, v) > 0.0;
    if (uSide != 0.0 && outside != (uSide > 0.0)) discard;
    if (!outside) n = -n;
    float nv = max(dot(n, v), 0.001);
    float fresnel = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
    // Seen through the near window, the far panes' inner faces mirror the dark cabin, not the room.
    vec3 reflection = outside ? sky(reflect(-v, n), 0.0) * fresnel * mix(0.35, 1.0, vOcclusion) : vec3(0.0);
    float through = dot(uTint, vec3(0.333)) * (1.0 - fresnel);
    vec3 hue = uTint / max(max(uTint.r, max(uTint.g, uTint.b)), 0.001);
    // Premultiplied: what's behind is kept by `through`, and a coloured lens adds a little of its hue.
    vec3 color = toScreen(reflection + (1.0 - through) * hue * 0.01);
    fragColor = vec4(color, 1.0 - through);
}
"""

    const val SHADOW_VERTEX = """
layout(location = 0) in vec2 aCorner;
uniform mat4 uViewProjection;
uniform float uHalfSize;
out vec2 vUv;

void main() {
    vUv = aCorner * 0.5 + 0.5;
    gl_Position = uViewProjection * vec4(aCorner * uHalfSize, 0.0, 1.0);
}
"""

    const val SHADOW = """
precision highp float;
in vec2 vUv;
uniform sampler2D uShadow;
uniform float uStrength;
uniform float uFloor;
out vec4 fragColor;

void main() {
    float reach = length(vUv - 0.5) * 2.0;
    // The bake's far edge is a faint haze that would show as a square round a car on a map: only
    // the shadow near the car is kept.
    float dark = clamp((1.0 - texture(uShadow, vUv).r - 0.06) / 0.94, 0.0, 1.0) * uStrength * (1.0 - smoothstep(0.5, 0.92, reach));
    // A pool of light on a showroom floor, fading out into whatever the view sits on. Premultiplied.
    float pool = uFloor * (1.0 - smoothstep(0.25, 1.0, reach));
    vec3 floorColor = vec3(0.235, 0.245, 0.26) * pool * (1.0 - dark);
    fragColor = vec4(floorColor, max(pool, dark));
}
"""
}
