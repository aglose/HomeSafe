# Builds one car from the base mesh: real dimensions, real materials, the car's own wheels.
#
#   blender -b --python tools/car-model/build.py -- <scene.gltf> <out.blend> <car>
#
# car: "blue" (2023 Model Y Performance, Deep Blue) or "red" (2022 Long Range, Red Multi-Coat).
import json
import math
import os
import sys

import bmesh
import bpy
import numpy as np
from mathutils import Vector
from mathutils.bvhtree import BVHTree
from mathutils.kdtree import KDTree

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import details  # noqa: E402
import materials as M  # noqa: E402
import wheels  # noqa: E402

# Tesla's figures for 2020-2024 cars, in metres.
LENGTH = 4.751
WIDTH_BODY = 1.921
HEIGHT = 1.624  # the Performance sits about 1.5 cm lower: the renderer moves the body down

TRACK = 1.636  # wheel centre to wheel centre
FRONT_AXLE, REAR_AXLE = -1.468, 1.422  # where the base's arches are, 2.890 apart
HANDED = {"blue": 1.0, "red": -1.0}
BASE_RIMS = {"Object_13", "Object_21", "Object_23", "Object_25"}
BASE_AXLES = (-1.47, 1.42)
WELD = 1e-5
FRIT_DEPTH = 0.012
FITTED_SHEETS = {"gloss_black_trim", "windshield", "rear_glass", "rear_door_glass", "rear_side_glass", "front_door_glass", "head_lens"}
FIT_RADII = (0.09, 0.14, 0.2)
SHEET_RADII = (0.2, 0.32, 0.5)
NORMAL_PASSES = 40
RELAX_SAME_PANEL = math.cos(math.radians(16))
FIT_MINIMUM = 30
FIT_SAME_PANEL = math.cos(math.radians(28))
FIT_KEEP_CORNER = math.cos(math.radians(32))
PANEL_SAME = math.cos(math.radians(6))
PANEL_PASSES = 120
FLAT_PANEL_PASSES = 900
FLAT_PANEL = math.cos(math.radians(30))
PANEL_TRUST, PANEL_DOUBT = math.radians(5), math.radians(14)
BADGE_PIXEL = (568, 514)
REAR_SENSORS = ((-0.62, 0.62), (-0.26, 0.60), (0.26, 0.60), (0.62, 0.62))
FRONT_SENSORS = ((-0.66, 0.60), (-0.28, 0.56), (0.28, 0.56), (0.66, 0.60))
CABIN = {"blue": (0.02, 0.02, 0.021), "red": (0.02, 0.02, 0.021)}

PAINTS = {
    "blue": dict(color=(0.004, 0.014, 0.115), flake_color=(0.035, 0.11, 0.52), flake_amount=0.3),
    "red": dict(color=(0.30, 0.004, 0.009), flake_color=(0.75, 0.05, 0.04), flake_amount=0.28),
}


def import_base(path):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=path)
    bpy.context.view_layer.update()
    meshes = [o for o in bpy.data.objects if o.type == "MESH"]
    # Bake every transform in, then drop the glTF's empties.
    for obj in meshes:
        world = obj.matrix_world.copy()
        obj.parent = None
        obj.matrix_world = world
    for obj in [o for o in bpy.data.objects if o.type != "MESH"]:
        bpy.data.objects.remove(obj)
    bpy.ops.object.select_all(action="DESELECT")
    for obj in meshes:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = meshes[0]
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return meshes


def bounds(objs):
    lo = Vector((1e9, 1e9, 1e9))
    hi = Vector((-1e9, -1e9, -1e9))
    for obj in objs:
        for v in obj.data.vertices:
            for i in range(3):
                lo[i] = min(lo[i], v.co[i])
                hi[i] = max(hi[i], v.co[i])
    return lo, hi


def normalise(meshes, car):
    """Scale each axis to Tesla's figures. The mirrors stay out of the width."""
    paint_objs = [o for o in meshes if o.material_slots[0].material.name == "Carro_Pintura"]
    lo, hi = bounds(meshes)
    body_lo, body_hi = bounds([o for o in paint_objs if not _is_mirror(o)])
    lo.z = 0.0  # the tyres have gone, but the ground hasn't moved
    scale = Vector(
        (
            WIDTH_BODY / (body_hi.x - body_lo.x),
            LENGTH / (hi.y - lo.y),
            HEIGHT / (hi.z - lo.z),
        )
    )
    centre_y = (hi.y + lo.y) / 2
    for obj in meshes:
        for v in obj.data.vertices:
            v.co.x *= scale.x
            v.co.y = (v.co.y - centre_y) * scale.y
            v.co.z = (v.co.z - lo.z) * scale.z
        obj.data.update()
    print("scale", scale)


def _is_mirror(obj):
    xs = [abs(v.co.x) for v in obj.data.vertices]
    return min(xs) > 0.8 and (max(v.co.z for v in obj.data.vertices) - min(v.co.z for v in obj.data.vertices)) < 0.2


def _split_islands(obj):
    """Each connected piece of an object as its own face list, with its bounds."""
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    bm.faces.ensure_lookup_table()
    seen = set()
    islands = []
    for first in bm.faces:
        if first.index in seen:
            continue
        stack = [first]
        seen.add(first.index)
        faces = []
        while stack:
            face = stack.pop()
            faces.append(face)
            for edge in face.edges:
                for other in edge.link_faces:
                    if other.index not in seen:
                        seen.add(other.index)
                        stack.append(other)
        co = [v.co for f in faces for v in f.verts]
        lo = Vector((min(c[i] for c in co) for i in range(3)))
        hi = Vector((max(c[i] for c in co) for i in range(3)))
        islands.append((faces, lo, hi))
    return bm, islands


def glass_part(lo, hi):
    """Which window or lens a piece of the glass objects is, by where it sits."""
    size = hi - lo
    if lo.y > 1.75 and hi.z < 1.2:
        return "tail_lens"
    if lo.y < -1.6 and hi.z < 0.6:
        return "fog_lens"
    if lo.y < -1.6:
        return "head_lens"
    if size.x > 1.2 and hi.y < 0:
        return "windshield"
    if size.x > 0.8:
        return "rear_glass"
    if lo.y > 1.1:
        return "rear_side_glass"
    if lo.y > 0.3:
        return "rear_door_glass"
    return "front_door_glass"


def assign_by_island(obj, materials, part_of):
    """Splits an object's islands onto the materials part_of names. Welds it as a side effect."""
    bm, islands = _split_islands(obj)
    mesh = obj.data
    names = sorted({part_of(lo, hi) for _, lo, hi in islands})
    mesh.materials.clear()
    for name in names:
        mesh.materials.append(materials[name])
    for faces, lo, hi in islands:
        index = names.index(part_of(lo, hi))
        for face in faces:
            face.material_index = index
    bm.to_mesh(mesh)
    bm.free()


def in_head_lamp(lo, hi):
    return lo.z > 0.7 and hi.y < -1.6 and abs(lo.x + hi.x) > 0.9 and hi.x - lo.x < 0.45


def seat(lo, hi):
    size = hi - lo
    # The cabin's seats and headrests; the tub they sit in is a far larger piece.
    is_seat = size.x < 1.45 and size.y < 0.62 and lo.y > -0.65 and lo.z > 0.45 and hi.y < 1.3
    return "seat" if is_seat else "cabin"


def assign(meshes, car):
    glass = {
        "windshield": M.glass("windshield", (0.70, 0.76, 0.74)),
        "front_door_glass": M.glass("front_door_glass", (0.36, 0.39, 0.38)),
        "rear_door_glass": M.glass("rear_door_glass", (0.10, 0.11, 0.11)),
        "rear_side_glass": M.glass("rear_side_glass", (0.10, 0.11, 0.11)),
        "rear_glass": M.glass("rear_glass", (0.07, 0.075, 0.075)),
        "head_lens": M.glass("head_lens", (0.97, 0.97, 0.97)),
        "fog_lens": M.glass("fog_lens", (0.45, 0.45, 0.45)),
        "tail_lens": M.glass("tail_outer_lens", (0.92, 0.80, 0.80)),
    }
    cabin = {
        "seat": M.simple("seat", CABIN[car], roughness=0.55),
        "cabin": M.simple("cabin", (0.018, 0.018, 0.019), roughness=0.6),
    }
    table = {
        "Carro_Pintura": M.paint("paint", **PAINTS[car]),
        "Carro_Metal_Preto": M.simple("black_metal", (0.02, 0.02, 0.022), roughness=0.4, metallic=0.2),
        "Carro_Plastico": M.simple("plastic", (0.012, 0.012, 0.013), roughness=0.62),
        "Carro_Plastico_Brilho": M.simple("gloss_black", (0.005, 0.005, 0.006), roughness=0.08, coat=1.0),
        "Carro_Plastico_Brilho_1": M.simple("gloss_black_trim", (0.005, 0.005, 0.006), roughness=0.05, coat=1.0),
        "Carro_Espelhos": M.simple("mirror", (0.9, 0.9, 0.9), roughness=0.02, metallic=1.0),
        "Carro_Cromado": M.simple("chrome", (0.9, 0.9, 0.9), roughness=0.05, metallic=1.0),
        "Carro_Metal_Farol": M.simple("lamp_chrome", (0.86, 0.86, 0.88), roughness=0.06, metallic=1.0),
        "Carro_Metal_Farol_1": M.simple("lamp_silver", (0.62, 0.63, 0.66), roughness=0.22, metallic=1.0),
        "Carro_Metal_Vermelho": M.simple("tail_red", (0.62, 0.012, 0.016), roughness=0.14, coat=1.0),
        "Carro_Metal_Vermelho_1": M.simple("tail_red_2", (0.62, 0.012, 0.016), roughness=0.14, coat=1.0),
        "Carro_Metal_Vermelho_2": M.simple("reflector", (0.42, 0.012, 0.012), roughness=0.15, coat=1.0),
        "Carro_Metal_Vermelho_3": M.simple("tail_dark", (0.05, 0.004, 0.006), roughness=0.15, coat=1.0),
        "Carro_Vidro_Vermelho2": M.glass("tail_lens", (0.95, 0.62, 0.62)),
    }
    # The base shines the lamp's housing black and the rear valance like piano trim: the real ones
    # are a dark silver and a matte moulding.
    housing = M.simple("lamp_housing", (0.09, 0.09, 0.095), roughness=0.35, metallic=0.6)
    valance = M.simple("valance", (0.022, 0.022, 0.024), roughness=0.55)
    for obj in meshes:
        name = obj.material_slots[0].material.name
        if name == "Carro_Vidros2":
            assign_by_island(obj, glass, lambda lo, hi: glass_part(lo, hi))
        elif name == "Carro_Interno":
            assign_by_island(obj, cabin, seat)
        elif name == "Carro_Plastico" and any(v.co.z > 0.7 and v.co.y < -1.6 for v in obj.data.vertices):
            parts = {"plastic": table[name], "lamp_housing": housing}
            assign_by_island(obj, parts, lambda lo, hi: "lamp_housing" if in_head_lamp(lo, hi) else "plastic")
        elif name == "Carro_Plastico_Brilho" and min(v.co.y for v in obj.data.vertices) > 1.8:
            obj.material_slots[0].material = valance
        elif name in table:
            obj.material_slots[0].material = table[name]


def sink_frits(meshes):
    """
    The black bands round the windshield and rear glass, and the cabin's shell, lie just under the
    glass in the real car. In the base they touch it and poke through, tearing holes in its
    reflection, so whatever comes too close is pushed down below it.
    """
    sheets = [
        obj for obj in meshes
        if {s.material.name for s in obj.material_slots if s.material} & {"windshield", "rear_glass"}
    ]
    verts, polys = [], []
    for obj in sheets:
        names = [slot.material.name for slot in obj.material_slots]
        offset = len(verts)
        verts.extend(v.co.copy() for v in obj.data.vertices)
        polys.extend(
            [offset + i for i in p.vertices] for p in obj.data.polygons if names[p.material_index] in ("windshield", "rear_glass")
        )
    glass = BVHTree.FromPolygons(verts, polys)
    for obj in meshes:
        if "plastic" not in {slot.material.name for slot in obj.material_slots}:
            continue
        bm, islands = _split_islands(obj)
        for faces, lo, hi in islands:
            if hi.x - lo.x < 0.85 or lo.z < 0.4 or hi.z < 1.3:
                continue
            for vert in {v for face in faces for v in face.verts}:
                location, normal, _, distance = glass.find_nearest(vert.co)
                if location is None or distance > FRIT_DEPTH * 2:
                    continue
                up = normal if normal.z > 0 else -normal
                above = (vert.co - location).dot(up)
                if above > -FRIT_DEPTH:
                    vert.co -= up * (above + FRIT_DEPTH)
        bm.to_mesh(obj.data)
        bm.free()


def remove_old_wheels(meshes):
    """The base's wheels and brakes go; ours are built to the cars' real sizes."""
    doomed = {"Carro_Pneu", "Carro_Freio", "Carro_Freio_Disco"}
    keep = []
    for obj in meshes:
        if obj.material_slots[0].material.name in doomed or obj.name in BASE_RIMS:
            bpy.data.objects.remove(obj)
        else:
            keep.append(obj)
    # Hubs and struts in with the black metal: whatever is wholly inside a wheel's cylinder.
    for obj in keep:
        if obj.material_slots[0].material.name != "Carro_Metal_Preto":
            continue
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        gone = []
        for face in bm.faces:
            inside = all(
                abs(v.co.x) > 0.55
                and min(math.hypot(v.co.y - axle, v.co.z - 0.40) for axle in BASE_AXLES) < 0.36
                for v in face.verts
            )
            if inside:
                gone.append(face)
        bmesh.ops.delete(bm, geom=gone, context="FACES")
        bm.to_mesh(obj.data)
        bm.free()
    return [o for o in keep if len(o.data.polygons) > 0]


def clean_surfaces(meshes):
    """Welds the triangle soup into surfaces and shades them from their own shape."""
    for obj in meshes:
        mesh = obj.data
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=WELD)
        bm.to_mesh(mesh)
        bm.free()
        # The import's normals were split per triangle; the welded surface makes better ones.
        if mesh.has_custom_normals:
            mesh.free_normals_split() if hasattr(mesh, "free_normals_split") else None
        attr = mesh.attributes.get("custom_normal")
        if attr is not None:
            mesh.attributes.remove(attr)
        mesh.shade_smooth()
        mesh.set_sharp_from_angle(angle=math.radians(38))
        mesh.update()


class PanelFit:
    """A panel's own points, and the small quadratic surface they make around any place on it."""

    def __init__(self, mesh, radii):
        count = len(mesh.vertices)
        co = np.zeros(count * 3)
        mesh.vertices.foreach_get("co", co)
        self.co = co.reshape(-1, 3)
        vn = np.zeros(count * 3)
        mesh.vertex_normals.foreach_get("vector", vn)
        self.vn = vn.reshape(-1, 3)
        self.radii = radii
        self.tree = KDTree(count)
        for i, c in enumerate(self.co):
            self.tree.insert(c, i)
        self.tree.balance()

    def at(self, point, n):
        """The surface's normal there, and how far off it the point lies along n; None where it can't tell."""
        for radius in self.radii:
            found = np.array([hit[1] for hit in self.tree.find_range(point, radius)], dtype=np.int64)
            if len(found) < FIT_MINIMUM:
                continue
            same = found[self.vn[found] @ n > FIT_SAME_PANEL]
            if len(same) >= FIT_MINIMUM:
                break
        else:
            return None
        axis = np.array([1.0, 0.0, 0.0]) if abs(n[0]) < 0.8 else np.array([0.0, 1.0, 0.0])
        t = np.cross(n, axis)
        t /= np.linalg.norm(t)
        b = np.cross(n, t)
        q = self.co[same] - point
        x, y, z = q @ t, q @ b, q @ n
        weight = np.exp(-(x * x + y * y) / (radius * radius * 0.35))
        design = np.stack([x * x, x * y, y * y, x, y, np.ones_like(x)], axis=1) * weight[:, None]
        solution = np.linalg.lstsq(design, z * weight, rcond=None)[0]
        normal = n - solution[3] * t - solution[4] * b
        return normal / np.linalg.norm(normal), solution[5]


def set_fitted_normals(mesh, fitted):
    """Per corner: a corner on a fold, far from the fitted normal, keeps what it had."""
    corners = np.zeros(len(mesh.loops) * 3)
    mesh.corner_normals.foreach_get("vector", corners)
    corners = corners.reshape(-1, 3)
    vert_of_loop = np.zeros(len(mesh.loops), dtype=np.int32)
    mesh.loops.foreach_get("vertex_index", vert_of_loop)
    agree = np.sum(corners * fitted[vert_of_loop], axis=1) > FIT_KEEP_CORNER
    corners[agree] = fitted[vert_of_loop][agree]
    mesh.normals_split_custom_set([tuple(c) for c in corners])
    mesh.update()


def relax_normals(mesh, normals, passes=NORMAL_PASSES):
    """Evens out what's left of the fit's noise, neighbour by neighbour, never across a fold."""
    edges = np.zeros(len(mesh.edges) * 2, dtype=np.int32)
    mesh.edges.foreach_get("vertices", edges)
    edges = edges.reshape(-1, 2)
    for _ in range(passes):
        close = np.sum(normals[edges[:, 0]] * normals[edges[:, 1]], axis=1) > RELAX_SAME_PANEL
        pairs = edges[close]
        total = normals.copy()
        np.add.at(total, pairs[:, 0], normals[pairs[:, 1]])
        np.add.at(total, pairs[:, 1], normals[pairs[:, 0]])
        normals = total / np.maximum(np.linalg.norm(total, axis=1, keepdims=True), 1e-9)
    return normals


def even_out_panels(mesh, normals):
    """
    A panel's reflection wanders wherever the base's points do, by a degree or two, over stretches
    too long for a local fit to see. A pressed panel has no ripples that fine, so the normals are
    averaged over their neighbours for long enough to carry across some 10 cm, stopping where the
    surface really turns. Where that strays from the surface, at a character line or a fillet, the
    surface wins.
    """
    edges = np.zeros(len(mesh.edges) * 2, dtype=np.int32)
    mesh.edges.foreach_get("vertices", edges)
    edges = edges.reshape(-1, 2)
    edges = edges[np.sum(normals[edges[:, 0]] * normals[edges[:, 1]], axis=1) > PANEL_SAME]
    even = normals.copy()
    # A nearly flat panel magnifies its errors most: a whole reflection can land on it.
    mean = normals.mean(axis=0)
    flat = np.percentile(normals @ (mean / np.linalg.norm(mean)), 10) > FLAT_PANEL
    for _ in range(FLAT_PANEL_PASSES if flat else PANEL_PASSES):
        total = even.copy()
        for axis in range(3):
            total[:, axis] += np.bincount(edges[:, 0], even[edges[:, 1], axis], len(even))
            total[:, axis] += np.bincount(edges[:, 1], even[edges[:, 0], axis], len(even))
        even = total / np.maximum(np.linalg.norm(total, axis=1, keepdims=True), 1e-9)
    apart = np.arccos(np.clip(np.sum(even * normals, axis=1), -1, 1))
    share = np.clip((PANEL_DOUBT - apart) / (PANEL_DOUBT - PANEL_TRUST), 0, 1)[:, None]
    blend = normals * (1 - share) + even * share
    return blend / np.maximum(np.linalg.norm(blend, axis=1, keepdims=True), 1e-9)


def refine_surfaces(meshes):
    """
    The base's triangles are uneven, and a car's reflections show it: a sharp line in the sky comes
    back as a zigzag. So the paint is cut finer, and every point takes its normal from a quadratic
    surface fitted to the panel around it, then evened out over its neighbours. The points stay
    where they are: moving them dents the creases. Windows, being one gentle sheet, are fitted more
    widely and not cut. Folds keep their own normals.
    """
    for obj in meshes:
        names = {slot.material.name for slot in obj.material_slots if slot.material}
        mesh = obj.data
        if "paint" in names:
            fit = PanelFit(mesh, FIT_RADII)
            bm = bmesh.new()
            bm.from_mesh(mesh)
            bmesh.ops.subdivide_edges(bm, edges=bm.edges[:], cuts=1, use_grid_fill=True)
            bm.to_mesh(mesh)
            bm.free()
            mesh.shade_smooth()
            mesh.set_sharp_from_angle(angle=math.radians(38))
            mesh.update()
            count = len(mesh.vertices)
            co = np.zeros(count * 3)
            mesh.vertices.foreach_get("co", co)
            co = co.reshape(-1, 3)
            vn = np.zeros(count * 3)
            mesh.vertex_normals.foreach_get("vector", vn)
            vn = vn.reshape(-1, 3)
            fitted = vn.copy()
            for i in range(count):
                result = fit.at(co[i], vn[i])
                if result is not None:
                    fitted[i] = result[0]
            set_fitted_normals(mesh, relax_normals(mesh, even_out_panels(mesh, relax_normals(mesh, fitted)), passes=12))
        elif names & FITTED_SHEETS:
            fit = PanelFit(mesh, SHEET_RADII)
            fitted = fit.vn.copy()
            for i in range(len(mesh.vertices)):
                result = fit.at(fit.co[i], fit.vn[i])
                if result is not None:
                    fitted[i] = result[0]
            set_fitted_normals(mesh, relax_normals(mesh, fitted))
        elif "gloss_black" in names:
            # Thin pillars and surrounds: too narrow to fit a surface to, but they mirror the sky just the same.
            count = len(mesh.vertices)
            normals = np.zeros(count * 3)
            mesh.vertex_normals.foreach_get("vector", normals)
            set_fitted_normals(mesh, relax_normals(mesh, normals.reshape(-1, 3)))


def add_details(meshes, car):
    paint = [o for o in meshes if o.material_slots[0].material.name == "paint"]
    surface = details.Surface(paint)
    with open(os.path.join(HERE, "cameras.json")) as f:
        camera = json.load(f)["blue1"]
    # Where the badge sits in the rear three-quarter photograph.
    eye, direction = details.photo_ray(camera, BADGE_PIXEL, 1500)
    location, normal = surface.hit(eye, direction)
    chrome = M.simple("badge", (0.9, 0.9, 0.9), roughness=0.08, metallic=1.0)
    underline = M.simple("badge_underline", (0.6, 0.012, 0.018), roughness=0.2, coat=1.0)
    details.badge(surface, location, normal, chrome, underline)
    ring = M.simple("sensor_ring", (0.01, 0.01, 0.011), roughness=0.5)
    details.sensors(surface, REAR_SENSORS, Vector((0, -1, 0)), ring)
    details.sensors(surface, FRONT_SENSORS, Vector((0, 1, 0)), ring)
    lenses = [o for o in meshes if "head_lens" in {slot.material.name for slot in o.material_slots}]
    details.head_lamps(lenses, M.simple("drl", (0.8, 0.8, 0.8), roughness=0.4), M.simple("projector", (0.01, 0.01, 0.011), roughness=0.03, coat=1.0), M.simple("projector_ring", (0.55, 0.55, 0.57), roughness=0.18, metallic=1.0))


def add_spoiler(meshes):
    """The Performance's carbon lip, following the top edge of the tailgate. It ships in the body file; only the blue car draws it."""
    points = [
        v.co.copy()
        for obj in meshes
        if obj.material_slots[0].material.name == "paint"
        for v in obj.data.vertices
        if v.co.y > 1.9 and 1.0 < v.co.z < 1.3 and abs(v.co.x) < 0.78
    ]
    columns = 33
    half_span = 0.70
    edge = []
    for i in range(columns):
        x = -half_span + 2 * half_span * i / (columns - 1)
        near = [p for p in points if abs(p.x - x) < 0.03]
        last = max(near, key=lambda p: p.y)
        edge.append(last)
    # A carbon lip is smooth in plan: a few passes along it take out the mesh's steps.
    for _ in range(6):
        edge = [edge[0]] + [(edge[i - 1] + edge[i] * 2 + edge[i + 1]) / 4 for i in range(1, columns - 1)] + [edge[-1]]
    section = [(-0.08, 0.0005), (-0.04, 0.006), (0.004, 0.012), (0.017, 0.0135), (0.017, 0.0095), (0.0, 0.003), (-0.04, -0.002), (-0.08, -0.004)]
    rows = []
    for i, e in enumerate(edge):
        t = abs(2 * i / (columns - 1) - 1)
        taper = max(0.02, min(1.0, (1 - t) / 0.12))
        rows.append([(e.x, e.y + y * taper, e.z + z * taper) for y, z in section])
    verts = [p for row in rows for p in row]
    n = len(section)
    faces = [(i * n + j, i * n + (j + 1) % n, (i + 1) * n + (j + 1) % n, (i + 1) * n + j) for i in range(columns - 1) for j in range(n)]
    mesh = bpy.data.meshes.new("spoiler")
    mesh.from_pydata(verts, [], faces)
    mesh.materials.append(M.simple("spoiler", (0.012, 0.012, 0.014), roughness=0.16, metallic=0.2, coat=1.0))
    obj = bpy.data.objects.new("spoiler", mesh)
    bpy.context.scene.collection.objects.link(obj)
    mesh.shade_smooth()
    mesh.set_sharp_from_angle(angle=math.radians(50))
    return obj


def add_wheels(car, detail=1.0):
    mats = [
        M.simple("rim", (0.075, 0.075, 0.08), roughness=0.42, metallic=0.5) if car == "blue" else M.simple("rim", (0.012, 0.012, 0.013), roughness=0.46, metallic=0.2),
        M.simple("rubber", (0.016, 0.016, 0.017), roughness=0.72),
        M.simple("disc", (0.3, 0.3, 0.31), roughness=0.45, metallic=1.0),
        M.simple("caliper", (0.55, 0.012, 0.018), roughness=0.3, coat=1.0) if car == "blue" else M.simple("caliper", (0.03, 0.03, 0.032), roughness=0.4),
        M.simple("hub", (0.05, 0.05, 0.053) if car == "blue" else (0.012, 0.012, 0.013), roughness=0.4, metallic=0.5),
        M.simple("rim_lip", (0.012, 0.012, 0.013), roughness=0.25, coat=0.5),
        M.simple("tyre_lettering", (0.03, 0.03, 0.03), roughness=0.42),
    ]
    spec = wheels.UBERTURBINE if car == "blue" else wheels.INDUCTION
    objs = []
    for side in (-1, 1):  # the car's right (-X) and left
        for axle, y in (("front", FRONT_AXLE), ("rear", REAR_AXLE)):
            width = 0.275 if car == "blue" and axle == "rear" else 0.255
            # The same part on both sides, so a right wheel is turned round, not mirrored: its blades
            # sweep the same way as the left's when each is seen from outside.
            handed = HANDED[car]
            obj = wheels.build(spec, f"wheel_{axle}_{'right' if side < 0 else 'left'}", mats, width=width, detail=detail, handed=handed, brake_angle=math.radians(0))
            obj.location = (side * (TRACK / 2), y, spec.tyre_radius)
            if side < 0:
                obj.rotation_euler = (0.0, math.pi, 0.0)
            objs.append(obj)
    return objs


def main():
    argv = sys.argv[sys.argv.index("--") + 1 :]
    source, out, car = argv[0], argv[1], argv[2]
    meshes = import_base(source)
    meshes = remove_old_wheels(meshes)
    normalise(meshes, car)
    assign(meshes, car)
    sink_frits(meshes)
    clean_surfaces(meshes)
    # Both cars share the blue one's body file: the red's is only built for its wheels and look.py.
    if car == "blue":
        refine_surfaces(meshes)
    add_details(meshes, car)
    if car == "blue":
        add_spoiler(meshes)
    add_wheels(car)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.abspath(out))


if __name__ == "__main__":
    main()
