# The small things that tell the eye it's a real car: raised lettering, badges, parking sensors.
import math
import os

import bpy
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree

# Macs have it; another machine gets Blender's own font, near enough at this size.
FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
DRL_BEHIND, DRL_ABOVE, DRL_HEIGHT = 0.012, 0.016, 0.0042
PROJECTOR = (0.615, 0.815)
PROJECTOR_RADIUS = 0.024


def flat_text(text, height, stretch=1.0, tracking=1.0, extrude=0.0):
    """Text as (vertices, faces), centred on the origin and lying in XY, `height` tall in capitals."""
    curve = bpy.data.curves.new("text", "FONT")
    curve.body = text
    if os.path.exists(FONT):
        curve.font = bpy.data.fonts.load(FONT, check_existing=True)
    curve.size = height / 0.72  # a font's capitals are about 0.72 of its size
    curve.space_character = tracking
    curve.align_x = "CENTER"
    curve.align_y = "CENTER"
    curve.resolution_u = 2
    curve.extrude = extrude
    curve.fill_mode = "BOTH"
    obj = bpy.data.objects.new("text", curve)
    bpy.context.scene.collection.objects.link(obj)
    bpy.context.view_layer.update()
    mesh = bpy.data.meshes.new_from_object(obj.evaluated_get(bpy.context.evaluated_depsgraph_get()))
    verts = [(v.co.x * stretch, v.co.y, v.co.z) for v in mesh.vertices]
    faces = [tuple(p.vertices) for p in mesh.polygons]
    bpy.data.objects.remove(obj)
    bpy.data.curves.remove(curve)
    bpy.data.meshes.remove(mesh)
    return verts, faces


def text_on_sidewall(builder, material, text, height, radius, axial, centre_angle, tracking=1.15):
    """Raised letters following the tyre's curve, tops towards the tread, readable from outside."""
    verts, faces = flat_text(text, height, tracking=tracking, extrude=0.0005)
    start = len(builder.verts)
    for x, y, z in verts:
        angle = centre_angle - x / radius
        r = radius + y
        builder.verts.append((axial + z, math.cos(angle) * r, math.sin(angle) * r))
    for face in faces:
        builder.faces.append(tuple(start + i for i in face))
        builder.mats.append(material)


class Surface:
    """Rays against the painted panels, to lay small things on them."""

    def __init__(self, meshes):
        verts, polys = [], []
        for obj in meshes:
            offset = len(verts)
            verts.extend(v.co.copy() for v in obj.data.vertices)
            polys.extend([offset + i for i in p.vertices] for p in obj.data.polygons)
        self.tree = BVHTree.FromPolygons(verts, polys)

    def hit(self, origin, direction):
        location, normal, _, _ = self.tree.ray_cast(origin, direction)
        if location is None:
            return None, None
        # A ray from outside sees the panel's outer face, however its triangles are wound.
        if normal.dot(direction) > 0:
            normal = -normal
        return location, normal


def lay_on(surface, verts, faces, origin, right, up, lift):
    """Lays flat (x, y, z) shapes on the surface around `origin`, each point dropped straight on to it."""
    normal = right.cross(up).normalized()
    placed = []
    for x, y, z in verts:
        above = origin + right * x + up * y + normal * 0.05
        location, n = surface.hit(above, -normal)
        if location is None:
            location, n = origin + right * x + up * y, normal
        placed.append(location + n * (lift + z))
    return placed, faces


def new_part(name, verts, faces, material):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([tuple(v) for v in verts], [], faces)
    mesh.materials.append(material)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    mesh.shade_flat()
    return obj


def badge(surface, origin, normal_hint, chrome, underline_material):
    """DUAL MOTOR in chrome. The Performance's has a red line under it."""
    # Seen from behind, the text reads towards the car's right, which is -X.
    right = Vector((-1, 0, 0))
    right = (right - normal_hint * right.dot(normal_hint)).normalized()
    up = normal_hint.cross(right).normalized()
    if up.z < 0:
        up = -up
    verts, faces = flat_text("DUAL MOTOR", 0.0085, stretch=1.28, tracking=1.08, extrude=0.0004)
    placed, faces = lay_on(surface, verts, faces, origin, right, up, 0.0012)
    new_part("badge", placed, faces, chrome)
    if underline_material is not None:
        half = max(abs(v[0]) for v in verts)
        strip = [(-half, -0.0085, 0.0), (half, -0.0085, 0.0), (half, -0.0068, 0.0), (-half, -0.0068, 0.0)]
        # Enough points along it to follow the tailgate's curve.
        steps = 12
        line = []
        for i in range(steps + 1):
            x = -half + 2 * half * i / steps
            line += [(x, strip[0][1], 0.0), (x, strip[2][1], 0.0)]
        quads = [(2 * i, 2 * i + 2, 2 * i + 3, 2 * i + 1) for i in range(steps)]
        placed_line, quads = lay_on(surface, line, quads, origin, right, up, 0.0012)
        new_part("badge_underline", placed_line, quads, underline_material)


def sensors(surface, spots, direction, material):
    """Parking sensors: dark rings on the bumper, the only part of them that shows."""
    verts, faces = [], []
    segments = 18
    outer, inner = 0.0118, 0.0102
    for x, z in spots:
        origin = Vector((x, -direction.y * 4, z))
        location, normal = surface.hit(origin, direction)
        if location is None:
            continue
        rotation = normal.to_track_quat("Z", "Y").to_matrix()
        start = len(verts)
        for i in range(segments):
            angle = 2 * math.pi * i / segments
            c, s = math.cos(angle), math.sin(angle)
            for radius in (outer, inner):
                point = rotation @ Vector((c * radius, s * radius, 0.0))
                verts.append(location + point + normal * 0.0007)
        for i in range(segments):
            a, b, c2, d = start + 2 * i, start + 2 * ((i + 1) % segments), start + 2 * ((i + 1) % segments) + 1, start + 2 * i + 1
            faces.append((a, b, c2, d))
    if verts:
        new_part("sensor_ring", verts, faces, material)


def head_lamps(lenses, drl, lens_material, ring_material):
    """What the base's lamps lack: the daytime light's thin line along the lens's lower edge, and a projector."""
    for side in (1, -1):
        points = []
        for obj in lenses:
            names = [slot.material.name for slot in obj.material_slots]
            for poly in obj.data.polygons:
                if names[poly.material_index] == "head_lens":
                    points.extend(obj.data.vertices[i].co.copy() for i in poly.vertices)
        points = [Vector((p.x * side, p.y, p.z)) for p in points if p.x * side > 0]
        line = []
        steps = 28
        for i in range(steps + 1):
            x = 0.535 + (0.815 - 0.535) * i / steps
            near = [p for p in points if abs(p.x - x) < 0.018]
            lowest = min(near, key=lambda p: p.z)
            line.append(Vector((x, lowest.y + DRL_BEHIND, lowest.z + DRL_ABOVE)))
        for _ in range(8):
            line = [line[0]] + [(line[i - 1] + line[i] * 2 + line[i + 1]) / 4 for i in range(1, steps)] + [line[-1]]
        verts, faces = [], []
        for i, point in enumerate(line):
            taper = min(1.0, min(i, steps - i) / 3 + 0.25)
            verts.append((point.x * side, point.y, point.z - DRL_HEIGHT * taper / 2))
            verts.append((point.x * side, point.y, point.z + DRL_HEIGHT * taper / 2))
        faces = [(2 * i, 2 * i + 2, 2 * i + 3, 2 * i + 1) for i in range(steps)]
        new_part("drl", verts, faces, drl)
        # The projector, towards the middle of the car, behind the lens.
        here = min(points, key=lambda p: (p.x - PROJECTOR[0]) ** 2 + (p.z - PROJECTOR[1]) ** 2)
        centre = Vector((PROJECTOR[0] * side, here.y + 0.052, PROJECTOR[1]))
        lens_verts, lens_faces = dome(centre, PROJECTOR_RADIUS)
        new_part("projector", lens_verts, lens_faces, lens_material).data.shade_smooth()
        ring_verts, ring_faces = ring(centre + Vector((0, 0.006, 0)), PROJECTOR_RADIUS * 0.96, PROJECTOR_RADIUS * 1.32)
        new_part("projector_ring", ring_verts, ring_faces, ring_material)


def dome(centre, radius, rows=6, segments=24):
    """A cap of a sphere, bulging forward (-Y)."""
    verts = [(centre.x, centre.y - radius * 0.55, centre.z)]
    for row in range(1, rows + 1):
        angle = row / rows * math.radians(76)
        for i in range(segments):
            around = 2 * math.pi * i / segments
            r = math.sin(angle) * radius
            verts.append((centre.x + r * math.cos(around), centre.y - radius * 0.55 * math.cos(angle), centre.z + r * math.sin(around)))
    faces = [(0, 1 + i, 1 + (i + 1) % segments) for i in range(segments)]
    for row in range(rows - 1):
        for i in range(segments):
            a, b = 1 + row * segments + i, 1 + row * segments + (i + 1) % segments
            faces.append((a, a + segments, b + segments, b))
    return verts, faces


def ring(centre, inner, outer, segments=24):
    verts, faces = [], []
    for i in range(segments):
        around = 2 * math.pi * i / segments
        for radius in (inner, outer):
            verts.append((centre.x + radius * math.cos(around), centre.y, centre.z + radius * math.sin(around)))
    for i in range(segments):
        a, b = 2 * i, 2 * ((i + 1) % segments)
        faces.append((a, b, b + 1, a + 1))
    return verts, faces


def photo_ray(camera, pixel, width):
    """Where a pixel of a reference photograph looks, from its fitted camera (cameras.json)."""
    eye = Vector(camera["position"])
    target = Vector(camera["target"])
    forward = (target - eye).normalized()
    right = forward.cross(Vector((0, 0, 1))).normalized()
    up = right.cross(forward)
    roll = camera["roll"]
    turned = Matrix.Rotation(roll, 3, forward)
    right, up = turned @ right, turned @ up
    focal = camera["lens"] / 36.0 * width
    height = width / camera["aspect"]
    return eye, (forward * focal + right * (pixel[0] - width / 2) + up * (height / 2 - pixel[1])).normalized()
