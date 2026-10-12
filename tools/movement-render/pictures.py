"""Pictures and materials the render scripts share: the passes each frame is rendered in, and how they are put together."""

import bpy
import numpy as np


def emission(name, color=None, attribute=None):
    """A material that only gives off light: of one colour, or of whatever is painted on the skin under [attribute]."""
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    nodes = material.node_tree.nodes
    nodes.clear()
    out = nodes.new("ShaderNodeOutputMaterial")
    glow = nodes.new("ShaderNodeEmission")
    material.node_tree.links.new(glow.outputs["Emission"], out.inputs["Surface"])
    if attribute is not None:
        paint = nodes.new("ShaderNodeAttribute")
        paint.attribute_name = attribute
        material.node_tree.links.new(paint.outputs["Color"], glow.inputs["Color"])
    else:
        glow.inputs["Color"].default_value = (*color, 1.0)
    return material


def swap_material(thing, material):
    """Gives a thing and everything it carries one material."""
    for child in [thing] + list(thing.children_recursive):
        if child.type == "MESH":
            if child.data.materials:
                child.data.materials[0] = material
            else:
                child.data.materials.append(material)


def load(path):
    """A picture as rows of straight RGBA, top row first."""
    image = bpy.data.images.load(path)
    pixels = np.empty(image.size[0] * image.size[1] * 4, dtype=np.float32)
    image.pixels.foreach_get(pixels)
    shape = (image.size[1], image.size[0], 4)
    bpy.data.images.remove(image)
    return pixels.reshape(shape)[::-1]


def save(rows, path, quality=88):
    """Rows of RGB, top row first, as a WebP or PNG by the path's ending."""
    picture = np.ones((rows.shape[0], rows.shape[1], 4), dtype=np.float32)
    picture[..., :rows.shape[2]] = rows
    image = bpy.data.images.new("picture", picture.shape[1], picture.shape[0], alpha=rows.shape[2] == 4)
    image.pixels.foreach_set(picture[::-1].ravel())
    image.filepath_raw = path
    image.file_format = "WEBP" if path.endswith(".webp") else "PNG"
    image.save(quality=quality)
    bpy.data.images.remove(image)


def sheet(frames, columns):
    """Frames side by side in rows, as one picture."""
    rows = len(frames) // columns
    height, width, channels = frames[0].shape
    whole = np.zeros((rows * height, columns * width, channels), dtype=np.float32)
    for index, frame in enumerate(frames):
        row, column = divmod(index, columns)
        whole[row * height:(row + 1) * height, column * width:(column + 1) * width] = frame
    return whole


def blur(field, sigma):
    reach = max(int(sigma * 3), 1)
    kernel = np.exp(-0.5 * (np.arange(-reach, reach + 1) / sigma) ** 2)
    kernel /= kernel.sum()
    for axis in (0, 1):
        field = sum(weight * np.roll(field, offset - reach, axis=axis) for offset, weight in enumerate(kernel))
    return field


def ramp(heat):
    """The colour of something glowing: deep red where it is faint, through orange and yellow, to near white."""
    def step(low, high):
        x = np.clip((heat - low) / (high - low), 0.0, 1.0)
        return (x * x * (3.0 - 2.0 * x))[..., None]
    color = np.array([0.78, 0.07, 0.05]) * (1.0 - step(0.1, 0.5)) + np.array([1.0, 0.42, 0.08]) * step(0.1, 0.5)
    color = color * (1.0 - step(0.45, 0.85)) + np.array([1.0, 0.82, 0.38]) * step(0.45, 0.85)
    return color * (1.0 - step(0.8, 1.05)) + np.array([1.0, 0.97, 0.88]) * step(0.8, 1.05)


GROUND = np.array([0.043, 0.047, 0.063])


def glowing(body, muscle, effort):
    """One frame as the app would show it: the stone figure on the app's dark ground, its working muscles glowing as hard as the rep is."""
    size = body.shape[0]
    alpha = body[..., 3:4]
    # Prime movers glow fully, synergists a little less; what only steadies the lift does not glow.
    # (The muscle picture holds each head's field; a little under its square root fills the belly out to its edges.)
    heat = (muscle[..., 0] ** 1.2 + 0.6 * muscle[..., 1] ** 1.2) * (0.4 + 0.6 * effort)
    heat = np.clip(blur(heat, size / 360.0), 0.0, 1.0)
    aura = np.clip(blur(heat, size * 0.022), 0.0, 1.0)[..., None]
    # Never as far as white: on pale stone, white heat would look like no heat at all.
    hot = ramp(heat * 0.74)
    light = 0.55 + 0.45 * np.clip(body[..., :3].mean(axis=2, keepdims=True) * 1.6, 0.0, 1.0)
    mix = np.clip(heat * 1.3, 0.0, 0.92)[..., None]
    figure_rgb = body[..., :3] * (1.0 - mix) + hot * light * mix + ramp(np.full_like(heat, 0.45)) * aura * 0.12
    thrown = ramp(np.full_like(heat, 0.5)) * aura * 0.55
    return np.clip(figure_rgb * alpha + (GROUND + thrown) * (1.0 - alpha), 0.0, 1.0)


def tinted(body, paint, strength=0.78):
    """The lit figure with a painting laid over it, the paint taking the body's light and shade."""
    alpha = body[..., 3:4]
    color = paint[..., :3] ** 2.2
    cover = np.clip(color.max(axis=2, keepdims=True) * 1.4, 0.0, 1.0) * strength
    shade = 0.35 + 0.95 * np.clip(body[..., :3].mean(axis=2, keepdims=True) * 1.5, 0.0, 1.0)
    rgb = body[..., :3] * (1.0 - cover) + (color / np.maximum(color.max(axis=2, keepdims=True), 1e-6)) * shade * cover
    return np.clip(rgb * alpha + GROUND * (1.0 - alpha), 0.0, 1.0)


def square_around(pictures_, margin=0.05):
    """The square (left, top, side, in pixels) that holds everything any of the pictures shows, with a margin; it may run past their edges."""
    seen = np.zeros(pictures_[0].shape[:2], dtype=bool)
    for picture in pictures_:
        seen |= picture[..., 3] > 0.02
    rows = np.where(seen.any(axis=1))[0]
    columns = np.where(seen.any(axis=0))[0]
    side = int(max(rows[-1] - rows[0], columns[-1] - columns[0]) * (1.0 + 2.0 * margin)) + 1
    left = (columns[0] + columns[-1]) // 2 - side // 2
    top = (rows[0] + rows[-1]) // 2 - side // 2
    return int(left), int(top), side


def cut(picture, left, top, side):
    """The square of a picture at (left, top), clear wherever it runs past the picture's edge."""
    out = np.zeros((side, side, picture.shape[2]), dtype=np.float32)
    if picture.shape[2] == 4 and picture[..., 3].min() >= 0.999:
        out[..., 3] = 1.0
    x0, y0 = max(left, 0), max(top, 0)
    x1, y1 = min(left + side, picture.shape[1]), min(top + side, picture.shape[0])
    out[y0 - top:y1 - top, x0 - left:x1 - left] = picture[y0:y1, x0:x1]
    return out


def resized(picture, size):
    """A square RGBA picture brought to [size] pixels a side, its colour weighted by how solid it is so that edges keep theirs."""
    solid = picture.copy()
    solid[..., :3] *= solid[..., 3:4]
    image = bpy.data.images.new("resize", solid.shape[1], solid.shape[0], alpha=True, float_buffer=True)
    image.pixels.foreach_set(solid[::-1].ravel())
    image.scale(size, size)
    out = np.empty(size * size * 4, dtype=np.float32)
    image.pixels.foreach_get(out)
    bpy.data.images.remove(image)
    out = out.reshape((size, size, 4))[::-1].copy()
    out[..., :3] /= np.maximum(out[..., 3:4], 1e-4)
    return np.clip(out, 0.0, 1.0)
