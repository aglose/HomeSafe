package com.meticulouscreations.homesafe.car3d

/**
 * [CarShaders] in Metal's language, for iOS: OpenGL ES is deprecated there, and its simulator
 * renderer can't run the GLSL. The two must say the same thing; change them together.
 */
internal const val CAR_SHADERS_METAL = """
#include <metal_stdlib>
using namespace metal;

// All one block, every field a float4 slot: MetalGl writes it by slot number.
struct Uniforms {
    float4x4 viewProjection;
    float4x4 model;
    float4 unit;
    float4 levels;
    float4 exposure;
    float4 camera;
    float4 harmonics[9];
    float4 skyTurn;
    float4 base;
    float4 metalness;
    float4 roughness;
    float4 coat;
    float4 coatRoughness;
    float4 flake;
    float4 flakeAmount;
    float4 emissive;
    float4 occlusionPower;
    float4 tint;
    float4 halfSize;
    float4 strength;
    float4 floorAmount;
};

struct VertexIn {
    short4 position [[attribute(0)]]; // w is the baked occlusion
    float2 normal [[attribute(1)]];
};

struct Varyings {
    float4 position [[position]];
    float3 world;
    float3 local;
    float3 normal;
    float occlusion;
};

struct ShadowVaryings {
    float4 position [[position]];
    float2 uv;
};

static float3 octDecode(float2 e) {
    float3 n = float3(e.x, e.y, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        float2 flipped = (1.0 - abs(n.yx)) * float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
        n.x = flipped.x;
        n.y = flipped.y;
    }
    return normalize(n);
}

vertex Varyings carVertex(VertexIn in [[stage_in]], constant Uniforms& u [[buffer(1)]]) {
    Varyings out;
    float3 local = float3(in.position.xyz) * u.unit.x;
    float4 world = u.model * float4(local, 1.0);
    out.local = local;
    out.world = world.xyz;
    // The model matrix only moves, turns and mirrors, so it can carry the normal as it is.
    out.normal = float3x3(u.model[0].xyz, u.model[1].xyz, u.model[2].xyz) * octDecode(in.normal);
    out.occlusion = float(in.position.w) / 32767.0;
    out.position = u.viewProjection * world;
    out.position.z = (out.position.z + out.position.w) * 0.5; // GL's depth runs -1 to 1, Metal's 0 to 1
    return out;
}

static float3 turned(float3 d, constant Uniforms& u) {
    return float3(u.skyTurn.x * d.x + u.skyTurn.y * d.y, -u.skyTurn.y * d.x + u.skyTurn.x * d.y, d.z);
}

static float3 sky(float3 world, float roughness, texturecube<float> environment, sampler s, constant Uniforms& u) {
    float3 d = turned(world, u);
    return environment.sample(s, float3(d.x, d.z, -d.y), level(roughness * (u.levels.x - 1.0))).rgb;
}

static float3 irradiance(float3 world, constant Uniforms& u) {
    float3 n = turned(world, u);
    return u.harmonics[0].xyz * 0.886227
        + (u.harmonics[1].xyz * n.y + u.harmonics[2].xyz * n.z + u.harmonics[3].xyz * n.x) * 1.023328
        + u.harmonics[4].xyz * n.x * n.y * 0.858086
        + u.harmonics[5].xyz * n.y * n.z * 0.858086
        + u.harmonics[6].xyz * (3.0 * n.z * n.z - 1.0) * 0.247708
        + u.harmonics[7].xyz * n.x * n.z * 0.858086
        + u.harmonics[8].xyz * (n.x * n.x - n.y * n.y) * 0.429043;
}

static float3 specularTerm(float3 f0, float roughness, float nv) {
    float4 c0 = float4(-1.0, -0.0275, -0.572, 0.022);
    float4 c1 = float4(1.0, 0.0425, 1.04, -0.04);
    float4 r = roughness * c0 + c1;
    float a004 = min(r.x * r.x, pow(2.0, -9.28 * nv)) * r.x + r.y;
    float2 ab = float2(-1.04, 1.04) * a004 + r.zw;
    return f0 * ab.x + ab.y;
}

static float specularOcclusion(float nv, float occlusion, float roughness) {
    return clamp(pow(nv + occlusion, pow(2.0, -16.0 * roughness * roughness - 1.0)) - 1.0 + occlusion, 0.0, 1.0);
}

static float3 neutral(float3 color) {
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
    return mix(color, float3(newPeak), g);
}

static float3 toScreen(float3 linear, constant Uniforms& u) {
    float3 c = clamp(neutral(linear * u.exposure.x), 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, float3(1.0 / 2.4)) - 0.055, step(float3(0.0031308), c));
}

static float3 hash(float3 p) {
    p = fract(p * float3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

fragment float4 carSurface(
    Varyings in [[stage_in]],
    constant Uniforms& u [[buffer(0)]],
    texturecube<float> environment [[texture(0)]],
    sampler s [[sampler(0)]]
) {
    float3 v = normalize(u.camera.xyz - in.world);
    float3 n = normalize(in.normal);
    if (dot(n, v) < 0.0) n = -n; // the base mesh's winding isn't to be trusted
    float nv = max(dot(n, v), 0.001);
    float3 base = u.base.xyz;
    float roughness = u.roughness.x;
    float3 baseNormal = n;

    if (u.flakeAmount.x > 0.0) {
        const float cells = 3000.0;
        float3 q = in.local * cells;
        float footprint = length(fwidth(q));
        float near = clamp(1.6 - footprint * 0.8, 0.0, 1.0);
        float3 h = hash(floor(q));
        if (near > 0.0) {
            baseNormal = normalize(n + (h - 0.5) * 0.14 * near);
        }
        float glint = mix(u.flakeAmount.x, (0.8 + h.x * h.x * 0.7) * u.flakeAmount.x, near);
        base = mix(u.base.xyz, u.flake.xyz, glint);
        roughness = mix(roughness + 0.04, roughness, near);
    }

    float3 f0 = mix(float3(0.04), base, u.metalness.x);
    float3 baseR = reflect(-v, baseNormal);
    float baseNv = max(dot(baseNormal, v), 0.001);
    float occlusion = pow(in.occlusion, u.occlusionPower.x);
    float specOcc = specularOcclusion(baseNv, occlusion, roughness);
    float3 color = base * (1.0 - u.metalness.x) * irradiance(n, u) * occlusion / 3.14159265
        + sky(baseR, roughness, environment, s, u) * specularTerm(f0, roughness, baseNv) * specOcc;

    if (u.coat.x > 0.0) {
        float fresnel = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
        float3 r = reflect(-v, n);
        float horizon = clamp(1.0 + 1.3 * dot(r, normalize(in.normal)), 0.0, 1.0);
        float3 coat = sky(r, u.coatRoughness.x, environment, s, u) * specularOcclusion(nv, occlusion, u.coatRoughness.x) * horizon * horizon;
        color = mix(color, color * (1.0 - fresnel) * (1.0 - fresnel) + coat * fresnel, u.coat.x);
    }
    return float4(toScreen(color + u.emissive.xyz, u), 1.0);
}

fragment float4 carGlass(
    Varyings in [[stage_in]],
    constant Uniforms& u [[buffer(0)]],
    texturecube<float> environment [[texture(0)]],
    sampler s [[sampler(0)]]
) {
    float3 v = normalize(u.camera.xyz - in.world);
    float3 n = normalize(in.normal);
    if (dot(n, in.local - float3(0.0, 0.2, 0.85)) < 0.0) n = -n;
    bool outside = dot(n, v) > 0.0;
    if (!outside) n = -n;
    float nv = max(dot(n, v), 0.001);
    float fresnel = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
    float3 reflection = outside ? sky(reflect(-v, n), 0.0, environment, s, u) * fresnel * mix(0.35, 1.0, in.occlusion) : float3(0.0);
    float3 tint = u.tint.xyz;
    float through = dot(tint, float3(0.333)) * (1.0 - fresnel);
    float3 hue = tint / max(max(tint.r, max(tint.g, tint.b)), 0.001);
    float3 color = toScreen(reflection + (1.0 - through) * hue * 0.01, u);
    return float4(color, 1.0 - through);
}

vertex ShadowVaryings carShadowVertex(VertexIn in [[stage_in]], constant Uniforms& u [[buffer(1)]]) {
    ShadowVaryings out;
    float2 corner = float2(in.position.xy);
    out.uv = corner * 0.5 + 0.5;
    out.position = u.viewProjection * float4(corner * u.halfSize.x, 0.0, 1.0);
    out.position.z = (out.position.z + out.position.w) * 0.5;
    return out;
}

fragment float4 carShadow(
    ShadowVaryings in [[stage_in]],
    constant Uniforms& u [[buffer(0)]],
    texture2d<float> shadow [[texture(1)]],
    sampler s [[sampler(0)]]
) {
    float reach = length(in.uv - 0.5) * 2.0;
    float dark = clamp((1.0 - shadow.sample(s, in.uv).r - 0.06) / 0.94, 0.0, 1.0) * u.strength.x * smoothstep(0.92, 0.5, reach);
    float pool = u.floorAmount.x * smoothstep(1.0, 0.25, reach);
    float3 floorColor = float3(0.235, 0.245, 0.26) * pool * (1.0 - dark);
    return float4(floorColor, max(pool, dark));
}
"""
