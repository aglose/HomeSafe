# The two cars' wheels, built from numbers rather than modelled: an Überturbine and an Induction,
# with tyre, brake disc and caliper. `detail` scales every count, so the phones' wheels are the
# same shape with fewer triangles.
#
# Built at the origin with the axle along X. +X is the outer face; the rotation about X is the
# sweep of the blades as seen from outside.
import math
from dataclasses import dataclass

import bmesh
import bpy

import details


@dataclass(frozen=True)
class Spec:
    name: str
    blades: int
    root_radius: float
    tip_radius: float
    sweep: float  # degrees from root to tip
    sweep_bias: float  # >1: the turn comes late, towards the rim
    widths: tuple  # half width (metres) at root, middle, tip
    face: tuple  # axial height of the blade's face at root, middle, tip
    twist: float  # how much one edge stands proud of the other at the tip (metres)
    crown: float
    thickness: float
    lip_radius: float
    tyre_radius: float
    tyre_width: float
    caliper: tuple  # disc outer radius, block span in degrees
    cap_radius: float
    ridge: float = 0.0  # a crest along one edge of each spoke, what catches the light
    brand: str = "MICHELIN"
    model: str = "PILOT SPORT"


UBERTURBINE = Spec(
    "uberturbine", blades=10, root_radius=0.074, tip_radius=0.252, sweep=30.0, sweep_bias=0.9,
    widths=(0.018, 0.034, 0.056), face=(0.071, 0.086, 0.097), twist=0.022, crown=0.002, thickness=0.020,
    lip_radius=0.2880, tyre_radius=0.356, tyre_width=0.255, caliper=(0.178, 58.0), cap_radius=0.066,
    brand="MICHELIN", model="PILOT SPORT ALL SEASON 4",
)
# Measured off a head-on photograph of the wheel (1188223-00-B): ten spokes of nearly even width
# with a crest down one side, between wedge-shaped windows, flush with a wide rim face.
INDUCTION = Spec(
    "induction", blades=10, root_radius=0.078, tip_radius=0.238, sweep=7.0, sweep_bias=1.0,
    widths=(0.0245, 0.030, 0.040), face=(0.084, 0.088, 0.094), twist=0.0, crown=0.0, thickness=0.018,
    lip_radius=0.2710, tyre_radius=0.356, tyre_width=0.255, caliper=(0.165, 42.0), cap_radius=0.036,
    ridge=0.012, brand="MICHELIN", model="PILOT SPORT EV",
)


def _lerp3(values, t):
    a, b, c = values
    if t < 0.5:
        s = t * 2
        s = s * s * (3 - 2 * s)
        return a + (b - a) * s
    s = (t - 0.5) * 2
    s = s * s * (3 - 2 * s)
    return b + (c - b) * s


class Builder:
    """Collects grids of points into one mesh, with one material per part."""

    def __init__(self):
        self.verts = []
        self.faces = []
        self.mats = []

    def grid(self, rows, mat, wrap_u=False, wrap_v=False, flip=False):
        """rows[u][v] = (x, y, z); wraps close the grid into a ring."""
        start = len(self.verts)
        nu, nv = len(rows), len(rows[0])
        for row in rows:
            self.verts.extend(row)
        last_u = nu if wrap_u else nu - 1
        last_v = nv if wrap_v else nv - 1
        for u in range(last_u):
            for v in range(last_v):
                a = start + u * nv + v
                b = start + ((u + 1) % nu) * nv + v
                c = start + ((u + 1) % nu) * nv + (v + 1) % nv
                d = start + u * nv + (v + 1) % nv
                self.faces.append((a, b, c, d) if not flip else (d, c, b, a))
                self.mats.append(mat)

    def lathe(self, profile, segments, mat, flip=False, start=0.0, span=2 * math.pi, offset=0.0, ripple=None):
        """Sweeps (radius, axial) around the axle. ripple(i_profile, angle) adds to the radius."""
        closed = abs(span - 2 * math.pi) < 1e-6
        rows = []
        for i in range(segments if closed else segments + 1):
            angle = start + span * i / segments
            ca, sa = math.cos(angle), math.sin(angle)
            row = []
            for j, (r, a) in enumerate(profile):
                rr = r + (ripple(j, angle) if ripple else 0.0)
                row.append((a + offset, ca * rr, sa * rr))
            rows.append(row)
        self.grid(rows, mat, wrap_u=closed, flip=flip)

    def to_object(self, name, materials):
        mesh = bpy.data.meshes.new(name)
        mesh.from_pydata(self.verts, [], self.faces)
        for mat in materials:
            mesh.materials.append(mat)
        mesh.polygons.foreach_set("material_index", self.mats)
        mesh.polygons.foreach_set("use_smooth", [True] * len(self.faces))
        mesh.update()
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(mesh)
        bm.free()
        mesh.set_sharp_from_angle(angle=math.radians(52))
        obj = bpy.data.objects.new(name, mesh)
        bpy.context.scene.collection.objects.link(obj)
        return obj


RIM, RUBBER, DISC, CALIPER, HUB, LIP, LETTERING = range(7)


def _tyre(b, spec, detail, width):
    half = width / 2
    lip = spec.lip_radius
    outer = spec.tyre_radius
    wall = outer - lip
    # Outer sidewall from the rim to the shoulder, across the tread with its grooves, and back.
    side = [
        (lip + 0.000, half * 0.90),
        (lip + 0.004, half * 0.975),
        (lip + wall * 0.14, half * 1.00),
        (lip + wall * 0.2, half * 1.01),  # the rim protector's ridge
        (lip + wall * 0.26, half * 1.0),
        (lip + wall * 0.5, half * 1.03),
        (lip + wall * 0.72, half * 1.02),
        (outer - 0.012, half * 0.975),
        (outer - 0.0035, half * 0.905),
        (outer - 0.0005, half * 0.83),
    ]
    grooves = []
    for centre in (0.52, 0.17, -0.17, -0.52):
        c = half * centre
        grooves += [(outer, c + 0.009), (outer - 0.008, c + 0.0065), (outer - 0.008, c - 0.0065), (outer, c - 0.009)]
    profile = side + grooves + [(r, -a) for r, a in reversed(side)]
    # Inside of the tyre, so nothing shows through the spokes.
    profile += [(lip - 0.012, -half * 0.85), (lip - 0.012, half * 0.85)]
    segments = max(48, int(384 * detail))
    shoulder = set(range(7, 10))
    blocks = 96 if detail >= 0.5 else 0

    def tread(index, angle):
        if blocks == 0:
            return 0.0
        mirrored = len(side) + len(grooves)
        in_shoulder = index in shoulder or (mirrored + (len(side) - 1 - 9) <= index <= mirrored + (len(side) - 1 - 7))
        if not in_shoulder:
            return 0.0
        return -0.0018 if (angle * blocks / (2 * math.pi)) % 1.0 < 0.18 else 0.0

    b.lathe(profile, segments, RUBBER, ripple=tread)


def _rim(b, spec, detail, width):
    half = width / 2
    lip = spec.lip_radius
    # The rim's flange: a dark lip round the outside, seen as a thin ring against the tyre.
    profile = [
        (lip - 0.009, half * 0.80),
        (lip - 0.0025, half * 0.90),
        (lip, half * 0.92),
        (lip + 0.0015, half * 0.895),
    ]
    segments = max(48, int(180 * detail))
    b.lathe(profile, segments, LIP)
    # The rim's face inside it, the same colour as the blades, which end in it.
    band = [
        (lip - 0.009, half * 0.80),
        (lip - 0.013, half * 0.79),
        (lip - 0.034, spec.face[2] - 0.001),
        (lip - 0.043, spec.face[2] - 0.005),
        (lip - 0.047, spec.face[2] - 0.022),
        (lip - 0.050, half * 0.30),
    ]
    b.lathe(band, segments, RIM)
    barrel = [
        (lip - 0.050, half * 0.30),
        (lip - 0.026, half * 0.45),
        (lip - 0.028, -half * 0.70),
        (lip - 0.010, -half * 0.90),
        (lip - 0.004, -half * 0.92),
    ]
    b.lathe(barrel, segments, RIM)


def _blade(b, spec, angle0, detail, handed):
    nu = max(8, int(28 * detail))
    nv = max(6, int(16 * detail))
    top = []
    under = []
    edge_a = []
    edge_b = []
    for iu in range(nu + 1):
        u = iu / nu
        r = spec.root_radius + (spec.tip_radius + 0.012 - spec.root_radius) * u
        hw = _lerp3(spec.widths, u)
        centre = angle0 + handed * math.radians(spec.sweep) * u ** spec.sweep_bias
        face = _lerp3(spec.face, u)
        row_top, row_under = [], []
        for iv in range(nv + 1):
            v = iv / nv
            s = 2 * v - 1  # -1 trailing edge, +1 leading edge
            angle = centre + s * hw / r
            crown = spec.crown * (1 - abs(s) ** 5)
            # Rises across the spoke to a crest near its leading edge, then falls away sharply.
            # Fades out at both ends: the spoke meets the hub and the rim's face level.
            fade = math.sin(math.pi * min(1.0, u * 1.02)) ** 0.5
            crest = 0.72
            rise = (s + 1) / (crest + 1) if s < crest else 1 - (s - crest) / (1 - crest) * 0.8
            crown += spec.ridge * fade * rise ** 1.4
            # One edge stands proud in the blade's middle; both meet the rim's face level.
            twist = spec.twist * math.sin(math.pi * u) * (s * 0.5)
            # The end of the blade sinks into the rim's face, leaving no step.
            sink = 0.006 * max(0.0, (u - 0.9) / 0.1)
            a = face + crown + twist - 0.003 * (abs(s) ** 8) - sink
            y, z = math.cos(angle) * r, math.sin(angle) * r
            row_top.append((a, y, z))
            row_under.append((a - spec.thickness * (1 - 0.5 * abs(s) ** 2), y, z))
        top.append(row_top)
        under.append(row_under)
        edge_a.append([row_top[0], row_under[0]])
        edge_b.append([row_top[-1], row_under[-1]])
    b.grid(top, RIM)
    b.grid(under, RIM, flip=True)
    b.grid(edge_a, RIM, flip=True)
    b.grid(edge_b, RIM)


def _hub(b, spec, detail):
    segments = max(24, int(72 * detail))
    r = spec.root_radius
    face = spec.face[0]
    cap = spec.cap_radius
    # The face of the hub the blades grow from, out to its shoulder and in to the cap.
    b.lathe(
        [
            (r + 0.008, face - 0.024),
            (r + 0.008, face - 0.004),
            (r + 0.004, face + 0.002),
            (r - 0.004, face + 0.005),
            (cap + 0.004, face + 0.007),
            (cap + 0.001, face + 0.004),
        ],
        segments,
        RIM,
    )
    b.lathe(
        [
            (cap, face + 0.003),
            (cap - 0.001, face + 0.008),
            (cap - 0.003, face + 0.0095),
            (cap * 0.6, face + 0.0105),
            (cap * 0.3, face + 0.011),
            (0.0005, face + 0.011),
        ],
        segments,
        HUB,
    )
    # A plate behind it all, so the middle isn't a hole, and the barrel closed at its back.
    b.lathe(
        [(spec.lip_radius - 0.03, -0.09), (0.10, -0.075), (0.07, -0.03), (r + 0.004, face - 0.03), (0.0005, face - 0.03)],
        segments,
        HUB,
    )


def _brake(b, spec, detail, centre_angle):
    outer, span = spec.caliper
    segments = max(24, int(96 * detail))
    # The disc, with its hat.
    b.lathe(
        [(0.06, -0.015), (0.09, 0.0), (0.09, 0.012), (outer - 0.07, 0.012), (outer - 0.002, 0.012), (outer, 0.010), (outer, -0.012), (outer - 0.002, -0.014), (0.09, -0.014)],
        segments,
        DISC,
        offset=-0.03,
    )
    # The caliper: a curved block straddling the disc's outer edge, with a rounded back.
    inner = outer - 0.062
    high = outer + 0.024
    half = 0.052
    section = [
        (inner, -half * 0.65),
        (inner, half * 0.85),
        (inner + 0.006, half),
        (high - 0.016, half),
        (high - 0.003, half * 0.8),
        (high, half * 0.2),
        (high, -half * 0.55),
        (high - 0.004, -half * 0.85),
        (high - 0.018, -half),
        (inner + 0.004, -half),
    ]
    start = centre_angle - math.radians(span) / 2
    nu = max(6, int(22 * detail))
    rows = []
    for i in range(nu + 1):
        t = i / nu
        angle = start + math.radians(span) * t
        # Rounded ends: the section shrinks towards its middle at each end.
        pinch = min(1.0, (min(t, 1 - t) / 0.12) ** 0.5) if 0 < t < 1 else 0.0
        mid_r, mid_a = (inner + high) / 2, 0.0
        row = []
        for r, a in section:
            rr = mid_r + (r - mid_r) * (0.35 + 0.65 * pinch)
            aa = mid_a + (a - mid_a) * (0.35 + 0.65 * pinch)
            row.append((aa - 0.03, math.cos(angle) * rr, math.sin(angle) * rr))
        rows.append(row)
    b.grid(rows, CALIPER, wrap_v=True)


def _lettering(b, spec, width):
    lip = spec.lip_radius
    wall = spec.tyre_radius - lip
    radius = lip + wall * 0.52
    axial = width / 2 * 1.03
    for turn in (0.0, math.pi):
        details.text_on_sidewall(b, LETTERING, spec.brand, 0.019, radius, axial, math.pi / 2 + turn)
        details.text_on_sidewall(b, LETTERING, spec.model, 0.009, radius, axial, math.pi / 2 + turn - 1.3, tracking=1.2)


def build(spec, name, materials, width=None, detail=1.0, handed=1.0, brake_angle=0.0):
    """One wheel, axle along X with its face towards +X."""
    width = width or spec.tyre_width
    b = Builder()
    _tyre(b, spec, detail, width)
    _rim(b, spec, detail, width)
    for i in range(spec.blades):
        _blade(b, spec, 2 * math.pi * i / spec.blades, detail, handed)
    _hub(b, spec, detail)
    _brake(b, spec, detail, brake_angle)
    _lettering(b, spec, width)
    return b.to_object(name, materials)
