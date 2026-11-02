# Writes a built car as the files the app draws: one mesh per material, with ambient occlusion
# baked into the vertices, at a tier's level of detail.
#
#   blender -b --python tools/car-model/export.py -- <car.blend> <out dir> <body|wheels|all> <high|mobile> [--samples 64]
#
# The format (little-endian), read by CarMesh.kt:
#   "HSCM" u32 version, u32 part count, 3 f32 scale (metres per unit of a position)
#   for each part: u8 name length, name, u32 vertices, u32 indices, u8 index size (2|4), padding to 4
#   then each part's vertices (12 bytes each: 3 i16 position, i16 occlusion, 2 i16 octahedral normal:
#   Apple's GL wants every attribute on a 4-byte boundary)
#   and indices.
# Parts are named for a material (paint, glass, rim, ...), matched to shading in CarMaterials.kt.
# Parts named with "@front" or "@rear" are a wheel, built about its own axle.
import math
import os
import struct
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build as builder  # noqa: E402
import stage  # noqa: E402
import wheels  # noqa: E402

POSITION_UNIT = 2.5 / 32767.0  # the car is within 2.5 m of its middle

# Body triangles kept on phones, by what they are. Paint keeps most: its reflections show every facet.
MOBILE_RATIO = {
    "paint": 0.22,  # of the cut-finer panels: about what the base mesh had
    "default": 0.25,
    "chrome": 0.5,
    "cabin": 0.45,
    "seat": 1.0,
    "plastic": 0.25,
    "head_lens": 1.0,
    "fog_lens": 1.0,
    "tail_outer_lens": 1.0,
    "tail_lens": 1.0,
    "lamp_chrome": 0.8,
    "lamp_silver": 0.8,
    "tail_red": 0.6,
    "tail_red_2": 0.6,
    "gloss_black": 0.6,
    "valance": 0.6,
    "lamp_housing": 1.0,
    "drl": 1.0,
    "projector": 1.0,
    "projector_ring": 1.0,
    "gloss_black_trim": 1.0,
    "badge": 1.0,
    "badge_underline": 1.0,
    "sensor_ring": 1.0,
    "spoiler": 1.0,
    "windshield": 1.0,
    "rear_glass": 1.0,
    "front_door_glass": 1.0,
    "rear_door_glass": 1.0,
    "rear_side_glass": 1.0,
}
MOBILE_MIN_FACES = 40
MOBILE_WHEEL_DETAIL = 0.3
SHADOW_SIZE = 512
SHADOW_HALF_SIZE = 4.0  # metres, as CarScene draws it


def arg(flag, default):
    return sys.argv[sys.argv.index(flag) + 1] if flag in sys.argv else default


def oct_encode(normals):
    """Octahedral normals, 16 bits a side: 8 bits would band in the paint's reflections."""
    n = normals / np.maximum(np.abs(normals).sum(axis=1, keepdims=True), 1e-9)
    x, y, z = n[:, 0], n[:, 1], n[:, 2]
    ox = np.where(z >= 0, x, (1 - np.abs(y)) * np.sign(np.where(x == 0, 1, x)))
    oy = np.where(z >= 0, y, (1 - np.abs(x)) * np.sign(np.where(y == 0, 1, y)))
    return np.clip(np.round(np.stack([ox, oy], axis=1) * 32767), -32767, 32767).astype(np.int16)


def mesh_arrays(obj, per_loop_occlusion=None):
    """Triangulated, split where normals differ. Returns {material: (positions, normals, occlusion, indices)}."""
    depsgraph = bpy.context.evaluated_depsgraph_get()
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh()
    mesh.calc_loop_triangles()
    tri_count = len(mesh.loop_triangles)
    if tri_count == 0:
        evaluated.to_mesh_clear()
        return {}
    loops = np.zeros(tri_count * 3, dtype=np.int32)
    mesh.loop_triangles.foreach_get("loops", loops)
    materials = np.zeros(tri_count, dtype=np.int32)
    mesh.loop_triangles.foreach_get("material_index", materials)
    vert_of_loop = np.zeros(len(mesh.loops), dtype=np.int32)
    mesh.loops.foreach_get("vertex_index", vert_of_loop)
    positions = np.zeros(len(mesh.vertices) * 3)
    mesh.vertices.foreach_get("co", positions)
    positions = positions.reshape(-1, 3)
    normals = np.zeros(len(mesh.loops) * 3)
    mesh.corner_normals.foreach_get("vector", normals)
    normals = normals.reshape(-1, 3)
    attr = mesh.color_attributes.get("occlusion")
    occlusion = np.ones(len(mesh.loops))
    if attr is not None:
        raw = np.zeros(len(attr.data) * 4)
        attr.data.foreach_get("color", raw)
        raw = raw.reshape(-1, 4)[:, 0]
        occlusion = raw[vert_of_loop] if attr.domain == "POINT" else raw

    matrix = np.array(obj.matrix_world)
    positions = positions @ matrix[:3, :3].T + matrix[:3, 3]
    normal_matrix = np.linalg.inv(matrix[:3, :3]).T
    normals = normals @ normal_matrix.T
    normals /= np.maximum(np.linalg.norm(normals, axis=1, keepdims=True), 1e-9)

    out = {}
    for index in np.unique(materials):
        material = mesh.materials[int(index)]
        tris = loops.reshape(-1, 3)[materials == index]
        # A loop is a new vertex only where its position, normal or occlusion differs.
        loop_ids, inverse = np.unique(tris.ravel(), return_inverse=True)
        v = vert_of_loop[loop_ids]
        key = np.concatenate(
            [v[:, None].astype(np.int64), np.round(normals[loop_ids] * 2047).astype(np.int64), np.round(occlusion[loop_ids] * 63).astype(np.int64)[:, None]],
            axis=1,
        )
        _, first, remap = np.unique(key, axis=0, return_index=True, return_inverse=True)
        remap = remap.ravel()
        out[material.name] = (positions[v[first]], normals[loop_ids[first]], occlusion[loop_ids[first]], remap[inverse.ravel()])
    evaluated.to_mesh_clear()
    return out


def merge(parts):
    """Joins pieces of one material into one vertex and index list."""
    merged = {}
    for chunk in parts:
        for name, (p, n, o, i) in chunk.items():
            merged.setdefault(name, []).append((p, n, o, i))
    out = {}
    for name, pieces in merged.items():
        offset = 0
        ps, ns, os_, indices = [], [], [], []
        for p, n, o, i in pieces:
            ps.append(p)
            ns.append(n)
            os_.append(o)
            indices.append(i + offset)
            offset += len(p)
        out[name] = (np.concatenate(ps), np.concatenate(ns), np.concatenate(os_), np.concatenate(indices))
    return out


def write(path, parts):
    names = sorted(parts)
    with open(path, "wb") as f:
        f.write(b"HSCM")
        f.write(struct.pack("<II", 1, len(names)))
        f.write(struct.pack("<f", POSITION_UNIT))
        for name in names:
            p, _, _, i = parts[name]
            encoded = name.encode()
            size = 2 if len(p) <= 65535 else 4
            head = struct.pack("<B", len(encoded)) + encoded + struct.pack("<IIB", len(p), len(i), size)
            head += b"\0" * (-len(head) % 4)
            f.write(head)
        for name in names:
            p, n, o, i = parts[name]
            size = 2 if len(p) <= 65535 else 4
            vertices = np.zeros(len(p), dtype=[("p", "<i2", 3), ("o", "<i2"), ("n", "<i2", 2)])
            vertices["p"] = np.clip(np.round(p / POSITION_UNIT), -32767, 32767)
            vertices["n"] = oct_encode(n)
            vertices["o"] = np.clip(np.round(o * 32767), 0, 32767)
            f.write(vertices.tobytes())
            f.write(i.astype("<u2" if size == 2 else "<u4").tobytes())
    triangles = sum(len(parts[n][3]) for n in names) // 3
    print(f"{os.path.basename(path)}: {triangles} triangles, {os.path.getsize(path) / 1e6:.1f} MB, parts: {names}")


def decimate(obj, ratio):
    if ratio >= 1.0 or len(obj.data.polygons) < MOBILE_MIN_FACES:
        return
    # The full mesh's normals are what the reflections are made of: the fewer triangles keep them.
    source = obj.copy()
    source.data = obj.data.copy()
    bpy.context.scene.collection.objects.link(source)
    modifier = obj.modifiers.new("lod", "DECIMATE")
    modifier.decimate_type = "COLLAPSE"
    modifier.ratio = ratio
    modifier.use_collapse_triangulate = True
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.modifier_apply(modifier=modifier.name)
    transfer = obj.modifiers.new("normals", "DATA_TRANSFER")
    transfer.object = source
    transfer.use_loop_data = True
    transfer.data_types_loops = {"CUSTOM_NORMAL"}
    transfer.loop_mapping = "POLYINTERP_NEAREST"
    bpy.ops.object.modifier_apply(modifier=transfer.name)
    bpy.data.objects.remove(source)


def bake_shadow(out_dir, samples):
    """What the car takes from the sky above the road: the soft shadow it sits on, as 8-bit grey."""
    plane = bpy.data.objects["bake_ground"]
    image = bpy.data.images.new("shadow", SHADOW_SIZE, SHADOW_SIZE, float_buffer=True)
    material = bpy.data.materials.new("shadow")
    material.use_nodes = True
    node = material.node_tree.nodes.new("ShaderNodeTexImage")
    node.image = image
    material.node_tree.nodes.active = node
    plane.data.materials.append(material)
    bpy.context.scene.world.light_settings.distance = 2.4
    bpy.context.scene.cycles.samples = samples * 4
    bpy.context.scene.render.bake.target = "IMAGE_TEXTURES"
    bpy.context.scene.render.bake.margin = 0
    bpy.ops.object.select_all(action="DESELECT")
    plane.select_set(True)
    bpy.context.view_layer.objects.active = plane
    bpy.ops.object.bake(type="AO", use_clear=True, target="IMAGE_TEXTURES")
    pixels = np.array(image.pixels[:]).reshape(SHADOW_SIZE, SHADOW_SIZE, 4)[:, :, 0]
    with open(os.path.join(out_dir, "shadow.bin"), "wb") as f:
        f.write(np.clip(np.round(pixels * 255), 0, 255).astype(np.uint8).tobytes())


def add_bake_ground():
    half = SHADOW_HALF_SIZE
    mesh = bpy.data.meshes.new("bake_ground")
    mesh.from_pydata([(-half, -half, 0), (half, -half, 0), (half, half, 0), (-half, half, 0)], [], [(0, 1, 2, 3)])
    uv = mesh.uv_layers.new(name="uv")
    for loop, corner in zip(uv.data, ((0, 0), (1, 0), (1, 1), (0, 1))):
        loop.uv = corner
    plane = bpy.data.objects.new("bake_ground", mesh)
    bpy.context.scene.collection.objects.link(plane)
    return plane


def bake_occlusion(objs, samples):
    """Cycles' ambient occlusion from the whole car and the road, into each mesh's vertices."""
    scene = bpy.context.scene
    stage.prepare_cycles(samples)
    scene.cycles.use_denoising = False
    scene.cycles.samples = samples
    scene.world = bpy.data.worlds.new("bake")
    scene.world.light_settings.distance = 0.55
    scene.render.bake.target = "VERTEX_COLORS"
    scene.render.bake.margin = 0
    for obj in objs:
        if "occlusion" not in obj.data.color_attributes:
            obj.data.color_attributes.new("occlusion", "FLOAT_COLOR", "POINT")
        obj.data.color_attributes.active_color = obj.data.color_attributes["occlusion"]
    bpy.ops.object.select_all(action="DESELECT")
    for obj in objs:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.bake(type="AO", use_clear=True, target="VERTEX_COLORS")
    for obj in objs:
        smooth_occlusion(obj)


def smooth_occlusion(obj, passes=3):
    """A bake's noise reads as mottling on smooth paint and rims. Occlusion has no fine detail to lose."""
    mesh = obj.data
    attr = mesh.color_attributes["occlusion"]
    count = len(mesh.vertices)
    raw = np.zeros(count * 4)
    attr.data.foreach_get("color", raw)
    values = raw.reshape(-1, 4)[:, 0]
    edges = np.zeros(len(mesh.edges) * 2, dtype=np.int32)
    mesh.edges.foreach_get("vertices", edges)
    edges = edges.reshape(-1, 2)
    links = np.bincount(edges.ravel(), minlength=count) + 1.0
    for _ in range(passes):
        total = values.copy()
        np.add.at(total, edges[:, 0], values[edges[:, 1]])
        np.add.at(total, edges[:, 1], values[edges[:, 0]])
        values = total / links
    raw = raw.reshape(-1, 4)
    raw[:, :3] = values[:, None]
    attr.data.foreach_set("color", raw.ravel())


def wheel_objects():
    return [o for o in bpy.data.objects if o.type == "MESH" and o.name.startswith("wheel_")]


def body_objects():
    return [
        o for o in bpy.data.objects
        if o.type == "MESH" and not o.name.startswith("wheel_") and not o.name.endswith("ground") and len(o.data.polygons) > 0
    ]


def main():
    argv = sys.argv[sys.argv.index("--") + 1 :]
    scene_path, out_dir, what, tier = argv[0], argv[1], argv[2], argv[3]
    car = "blue" if scene_path.endswith("blue.blend") else "red"
    samples = int(arg("--samples", "64"))
    os.makedirs(out_dir, exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=scene_path)
    if tier == "mobile":
        for obj in body_objects():
            names = [s.material.name for s in obj.material_slots if s.material]
            ratio = max(MOBILE_RATIO.get(n, MOBILE_RATIO["default"]) for n in names)
            decimate(obj, ratio)
        # The phones' wheels are the same numbers with fewer steps, not a decimated wheel.
        for obj in wheel_objects():
            bpy.data.objects.remove(obj)
        builder.add_wheels(car, detail=MOBILE_WHEEL_DETAIL)
    add_bake_ground()
    bake_occlusion(body_objects() + wheel_objects(), samples)
    if what == "shadow":
        bake_shadow(out_dir, samples)
    if what in ("body", "all"):
        write(os.path.join(out_dir, f"body_{tier}.car"), merge([mesh_arrays(o) for o in body_objects()]))
    if what in ("wheels", "all"):
        spec = wheels.UBERTURBINE if car == "blue" else wheels.INDUCTION
        for axle in ("front", "rear") if car == "blue" else ("front",):
            obj = bpy.data.objects[f"wheel_{axle}_left"]  # face towards +X, in place on the car's left
            saved = obj.matrix_world.copy()
            obj.matrix_world = np.eye(4).tolist()
            bpy.context.view_layer.update()
            write(os.path.join(out_dir, f"wheel_{spec.name}_{axle}_{tier}.car"), merge([mesh_arrays(obj)]))
            obj.matrix_world = saved


if __name__ == "__main__":
    main()
