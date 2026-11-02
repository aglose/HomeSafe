#!/usr/bin/env python3
# Authors the studio the car is lit by and prefilters it into the cube map the app's shaders read:
# one mip per roughness, GGX-filtered, plus spherical harmonics for diffuse.
#
#   envmap.py <out.env> <face size>
#
# The format (little-endian), read by CarEnvironment.kt:
#   "HSCE" u32 version, u32 face size, u32 mips, 27 f32 spherical harmonics (L2, RGB, Z up)
#   then for each mip, largest first, each face (+X -X +Y -Y +Z -Z): RGB9E5 texels, u32 each.
import argparse
import struct

import numpy as np

ROOM = 0.03
FILL = 0.9
GLOW = 0.3
ZENITH = 0.35
SHOULDER = 6.0
STRIP = 3.0
AHEAD = 0.8
FLOOR = 0.05
WHITE = (1.0, 0.985, 0.96)


def downsample(img, width):
    """Box-filtered to width x width/2."""
    h, w = img.shape[:2]
    factor = w // width
    if factor <= 1:
        return img
    return img[: h // factor * factor, : w // factor * factor].reshape(h // factor, factor, w // factor, factor, 3).mean(axis=(1, 3))


def sample_equirect(img, directions):
    """Bilinear lookup, with Z up and the panorama's middle towards +Y."""
    h, w = img.shape[:2]
    x, y, z = directions[..., 0], directions[..., 1], directions[..., 2]
    u = (np.arctan2(x, y) / (2 * np.pi) + 0.5) * w - 0.5
    v = (np.arccos(np.clip(z, -1, 1)) / np.pi) * h - 0.5
    u0 = np.floor(u).astype(np.int64)
    v0 = np.clip(np.floor(v).astype(np.int64), 0, h - 1)
    v1 = np.clip(v0 + 1, 0, h - 1)
    fu, fv = (u - u0)[..., None], (v - np.floor(v))[..., None]
    u1 = (u0 + 1) % w
    u0 = u0 % w
    return (img[v0, u0] * (1 - fu) + img[v0, u1] * fu) * (1 - fv) + (img[v1, u0] * (1 - fu) + img[v1, u1] * fu) * fv


def face_directions(face, size):
    """GL cube face order, for a cube sampled with direction (x, z, -y): Z up in the world is +Y in GL."""
    t = (np.arange(size) + 0.5) / size * 2 - 1
    s, tt = np.meshgrid(t, t)
    one = np.ones_like(s)
    d = [
        np.stack([one, -tt, -s], axis=-1),
        np.stack([-one, -tt, s], axis=-1),
        np.stack([s, one, tt], axis=-1),
        np.stack([s, -one, -tt], axis=-1),
        np.stack([s, -tt, one], axis=-1),
        np.stack([-s, -tt, -one], axis=-1),
    ][face]
    d = d / np.linalg.norm(d, axis=-1, keepdims=True)
    # GL's +Y is the world's up; GL's +Z is the world's -Y.
    return np.stack([d[..., 0], -d[..., 2], d[..., 1]], axis=-1)


def rgb9e5(rgb):
    rgb = np.clip(rgb, 0.0, 65408.0)
    biggest = rgb.max(axis=-1)
    exponent = np.maximum(-16, np.floor(np.log2(np.maximum(biggest, 1e-30)))) + 16
    denominator = np.power(2.0, exponent - 16 - 8)
    top = np.floor(biggest / denominator + 0.5)
    exponent = np.where(top >= 512, exponent + 1, exponent)
    denominator = np.power(2.0, exponent - 16 - 8)
    r, g, b = [np.floor(rgb[..., i] / denominator + 0.5).astype(np.uint32) for i in range(3)]
    return (r & 511) | ((g & 511) << 9) | ((b & 511) << 18) | (exponent.astype(np.uint32) << 27)


def hammersley(count):
    bits = np.arange(count, dtype=np.uint32)
    bits = (bits << 16) | (bits >> 16)
    bits = ((bits & 0x55555555) << 1) | ((bits & 0xAAAAAAAA) >> 1)
    bits = ((bits & 0x33333333) << 2) | ((bits & 0xCCCCCCCC) >> 2)
    bits = ((bits & 0x0F0F0F0F) << 4) | ((bits & 0xF0F0F0F0) >> 4)
    bits = ((bits & 0x00FF00FF) << 8) | ((bits & 0xFF00FF00) >> 8)
    return np.arange(count) / count, bits.astype(np.float64) * 2.3283064365386963e-10


def prefilter(img, normals, roughness, samples=512):
    """GGX split-sum filtering with N=V=R."""
    if roughness <= 0.0:
        return sample_equirect(img, normals)
    xi1, xi2 = hammersley(samples)
    a = roughness * roughness
    phi = 2 * np.pi * xi1
    cos_t = np.sqrt((1 - xi2) / (1 + (a * a - 1) * xi2))
    sin_t = np.sqrt(1 - cos_t * cos_t)
    h_local = np.stack([sin_t * np.cos(phi), sin_t * np.sin(phi), cos_t], axis=-1)

    up = np.where(np.abs(normals[..., 2:3]) < 0.999, np.array([0.0, 0.0, 1.0]), np.array([1.0, 0.0, 0.0]))
    tangent = np.cross(up, normals)
    tangent /= np.linalg.norm(tangent, axis=-1, keepdims=True)
    bitangent = np.cross(normals, tangent)
    total = np.zeros_like(normals, dtype=np.float32)
    weight = np.zeros(normals.shape[:2] + (1,), dtype=np.float32)
    for h_l in h_local:
        h = tangent * h_l[0] + bitangent * h_l[1] + normals * h_l[2]
        n_dot_h = np.sum(normals * h, axis=-1, keepdims=True)
        l = 2 * n_dot_h * h - normals
        n_dot_l = np.sum(normals * l, axis=-1, keepdims=True)
        lit = n_dot_l > 0
        colour = sample_equirect(img, l)
        total += colour * (n_dot_l * lit)
        weight += n_dot_l * lit
    return total / np.maximum(weight, 1e-4)


def spherical_harmonics(img):
    h, w = img.shape[:2]
    theta = (np.arange(h) + 0.5) / h * np.pi
    phi = (np.arange(w) + 0.5) / w * 2 * np.pi - np.pi
    pp, tt = np.meshgrid(phi, theta)
    d = np.stack([np.sin(tt) * np.sin(pp), np.sin(tt) * np.cos(pp), np.cos(tt)], axis=-1)
    x, y, z = d[..., 0], d[..., 1], d[..., 2]
    basis = np.stack(
        [
            0.282095 * np.ones_like(x), 0.488603 * y, 0.488603 * z, 0.488603 * x,
            1.092548 * x * y, 1.092548 * y * z, 0.315392 * (3 * z * z - 1), 1.092548 * x * z, 0.546274 * (x * x - y * y),
        ],
        axis=-1,
    )
    d_omega = (2 * np.pi / w) * (np.pi / h) * np.sin(tt)
    return np.einsum("hwc,hwb,hw->bc", img, basis, d_omega)


def smooth(edge0, edge1, x):
    t = np.clip((x - edge0) / (edge1 - edge0), 0, 1)
    return t * t * (3 - 2 * t)


def studio(width):
    """
    The car sits on a dark page, so it is lit as in a dark studio: neutral, or it would pick up a
    colour nothing around it explains, and with every edge soft, as a hard line in the room comes
    back from a panel as a zigzag. A clear coat returns about 4% of what it faces, so the lines it
    draws come from small, very bright lights; the broad dim ones light the paint under it without
    showing in it. Directions are in the viewer's frame (the room turns with them): they stand at
    -Y, looking towards +Y.
    """
    height = width // 2
    theta = (np.arange(height) + 0.5) / height * np.pi
    phi = ((np.arange(width) + 0.5) / width - 0.5) * 2 * np.pi
    pp, tt = np.meshgrid(phi, theta)
    x, y, z = np.sin(tt) * np.sin(pp), np.sin(tt) * np.cos(pp), np.cos(tt)
    elevation = np.degrees(np.arcsin(np.clip(z, -1, 1)))
    around = np.degrees(np.abs(np.arctan2(x, -y)))  # 0 behind the viewer, 180 ahead of them
    behind = smooth(140, 60, around)

    room = ROOM + FILL * behind * smooth(0, 25, elevation) * smooth(85, 45, elevation)
    # Dim straight up, so a window lying under it shows the cabin, not a white sheet.
    room += ZENITH * smooth(50, 80, elevation)
    # Past the car, a faint glow low down: what a roof shows at a grazing angle.
    room += GLOW * smooth(70, 130, around) * smooth(0, 8, elevation) * smooth(30, 12, elevation)
    # A long light behind the viewer: the stroke along a car's shoulder.
    room += SHOULDER * behind * smooth(17, 25, elevation) * smooth(33, 30, elevation)
    # Strips either side of the viewer: the upright highlights on a rounded corner.
    room += STRIP * smooth(7, 3, np.abs(around - 74)) * smooth(4, 14, elevation) * smooth(64, 50, elevation)
    # A big light past the car, which a bonnet, seen from in front, mirrors.
    room += AHEAD * smooth(100, 150, around) * smooth(16, 34, elevation) * smooth(58, 54, elevation)

    floor = FLOOR + FLOOR * smooth(-4, -60, elevation)
    light = floor + (room - floor) * smooth(-3, 3, elevation)
    return (light[..., None] * np.array(WHITE)).astype(np.float32)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("size", type=int)
    args = parser.parse_args()

    img = studio(2048)
    harmonics = spherical_harmonics(img)
    blurred = downsample(img, 512)  # filtered mips don't need the fine room
    mips = int(np.log2(args.size)) - 1  # down to 4x4... enough for roughness 1
    with open(args.out, "wb") as f:
        f.write(b"HSCE")
        f.write(struct.pack("<III", 1, args.size, mips))
        f.write(struct.pack("<27f", *harmonics.ravel().tolist()))
        for mip in range(mips):
            size = args.size >> mip
            roughness = mip / (mips - 1)
            source = img if mip == 0 else blurred
            for face in range(6):
                normals = face_directions(face, size)
                colour = prefilter(source, normals, roughness)
                f.write(rgb9e5(colour).astype("<u4").tobytes())
            print("mip", mip, "size", size, "roughness", round(roughness, 2), flush=True)


if __name__ == "__main__":
    main()
