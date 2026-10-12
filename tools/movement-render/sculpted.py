"""
The lifter as Blender Studio's realistic male (Human Base Meshes bundle, CC0): a sculpted body,
which this gives a skeleton, skins to it and stands in the studio's stone.

The bundle is not in the repository (it is 50 MB). Get `human-base-meshes-bundle-v1.4.1.zip` from
https://www.blender.org/download/demo-files/ (Asset Bundles, Human Base Meshes) and put its
`human_base_meshes_bundle.blend` in `tools/movement-render/models/`. Without it, figure.py falls
back to the MakeHuman figure.

The body comes without bones. Its joints were measured off the mesh (it is one fixed model) and
are set down here; the skin is bound to them by Blender's own automatic weights on the model's
coarse cage, and carried from there to the detailed surface.
"""

import hashlib
import os

import bpy
import numpy as np
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "models", "human_base_meshes_bundle.blend")
BODY = "GEO-body_male_realistic"
EYES = (BODY + ".eye.L", BODY + ".eye.R")

# Where the armature's own origin is, above the floor: about the hips, as the MakeHuman rig's was, so that what was
# written against that one (heights up the trunk, where a bar lies) reads nearly the same on this.
RIG_Z = 0.818

# How much of the sculpt's detail is kept (of its three levels), and how far its relief is deepened.
LEVEL = 2
DEEPEN = 1.7

# The joints, measured off the mesh: (bone, its parent, head, tail), the left side's; the right is its mirror.
# In metres from the floor between the feet: x to the figure's left, -y the way it faces.
MIDDLE = [
    ("pelvis", None, (0.0, 0.01, 0.93), (0.0, 0.03, 1.02)),
    ("spine_01", "pelvis", (0.0, 0.03, 1.02), (0.0, 0.03, 1.09)),
    ("spine_02", "spine_01", (0.0, 0.03, 1.09), (0.0, 0.035, 1.17)),
    ("spine_03", "spine_02", (0.0, 0.035, 1.17), (0.0, 0.04, 1.32)),
    ("neck_01", "spine_03", (0.0, 0.01, 1.45), (0.0, -0.015, 1.53)),
    ("head", "neck_01", (0.0, -0.015, 1.53), (0.0, -0.01, 1.68)),
]
WRIST = (0.374, -0.06, 0.894)
SIDE = [
    ("clavicle_", "spine_03", (0.02, -0.03, 1.40), (0.185, 0.005, 1.375)),
    ("upperarm_", "clavicle_", (0.185, 0.005, 1.375), (0.285, -0.003, 1.105)),
    ("lowerarm_", "upperarm_", (0.285, -0.003, 1.105), WRIST),
    # The hand's bone runs to the middle of the line of the knuckles.
    ("hand_", "lowerarm_", WRIST, (0.4113, -0.0984, 0.8013)),
    ("thigh_", "pelvis", (0.092, 0.0, 0.905), (0.13, 0.0, 0.49)),
    ("calf_", "thigh_", (0.13, 0.0, 0.49), (0.175, 0.045, 0.08)),
    ("foot_", "calf_", (0.175, 0.045, 0.08), (0.225, -0.07, 0.02)),
    ("ball_", "foot_", (0.225, -0.07, 0.02), (0.25, -0.13, 0.015)),
]
# The fingers and the thumb: each one's joints from the hand out to the tip, down the middle of the finger as the mesh
# has it (the hand hangs palm to the thigh, the fingers a little spread and curled, the thumb forward).
DIGITS = {
    "thumb": [(0.374, -0.1009, 0.873), (0.3854, -0.1215, 0.8295), (0.3693, -0.1512, 0.8253), (0.3679, -0.1785, 0.8059)],
    "index": [(0.4122, -0.1328, 0.8121), (0.4189, -0.1499, 0.7798), (0.4201, -0.16, 0.7606), (0.4186, -0.169, 0.7411)],
    "middle": [(0.4206, -0.1048, 0.804), (0.4297, -0.1251, 0.7651), (0.432, -0.1353, 0.7415), (0.4334, -0.1457, 0.7179)],
    "ring": [(0.4234, -0.088, 0.8003), (0.4291, -0.0985, 0.7592), (0.4281, -0.1059, 0.7355), (0.4262, -0.1136, 0.7122)],
    "pinky": [(0.4103, -0.064, 0.7908), (0.4198, -0.0628, 0.7588), (0.4235, -0.0626, 0.7399), (0.4263, -0.0626, 0.7207)],
}


# The forearm turns along its length when the hand is turned palm up or palm down: the skin by the elbow stays with
# the elbow, the skin by the wrist goes with the hand. Three bones down the forearm take a third, two thirds and all
# of the hand's turn; the skin from each of these shares of the way down it belongs to the next (`twist_weights`).
TWISTS = (("lowerarm_twist_01_", 0.25, 0.5), ("lowerarm_twist_02_", 0.5, 0.75), ("lowerarm_twist_03_", 0.75, 1.0))


def twists():
    elbow, wrist = Vector(SIDE[2][2]), Vector(SIDE[2][3])
    return [(name, "lowerarm_", tuple(elbow.lerp(wrist, start)), tuple(elbow.lerp(wrist, end))) for name, start, end in TWISTS]


def twist_weights(carried, names, fine):
    """Shares the forearm's skin out between the forearm's bone and the three that turn with the hand, by how far down the forearm it is. Returns the weights with a column for each new bone, and the names with them."""
    columns, added = [carried], []
    for sign, side in ((1.0, "l"), (-1.0, "r")):
        elbow = np.array((SIDE[2][2][0] * sign, SIDE[2][2][1], SIDE[2][2][2]))
        wrist = np.array((SIDE[2][3][0] * sign, SIDE[2][3][1], SIDE[2][3][2]))
        along = np.clip(((fine - elbow) @ (wrist - elbow)) / ((wrist - elbow) @ (wrist - elbow)), 0.0, 1.0)
        whole = carried[:, names.index("lowerarm_" + side)].copy()
        left = whole.copy()
        for name, start, end in TWISTS:
            # Rising from the last bone's share to this one's between [start] and [end], and falling to the next's after.
            rise = np.clip((along - start) / (end - start), 0.0, 1.0)
            fall = np.clip((along - end) / (end - start), 0.0, 1.0) if name != TWISTS[-1][0] else 0.0
            share = whole * (rise - fall)
            columns.append(share[:, None])
            added.append(name + side)
            left -= share
        carried[:, names.index("lowerarm_" + side)] = left
    return np.concatenate(columns, axis=1), names + added


def fingers():
    """The bones of the fingers and the thumb, three to each, hung on the hand."""
    bones = []
    for digit, joints in DIGITS.items():
        parent = "hand_"
        for number, (head, tail) in enumerate(zip(joints, joints[1:]), start=1):
            name = "%s_%02d_" % (digit, number)
            bones.append((name, parent, head, tail))
            parent = name
    return bones


def cache_path():
    """Where the built figure is kept, so that every render needn't build it again: a new one whenever this file or the model changes."""
    key = hashlib.sha1(open(os.path.abspath(__file__), "rb").read() + str(os.path.getmtime(MODEL)).encode()).hexdigest()[:12]
    return os.path.join(HERE, ".cache", "figure-" + key + ".blend")


def available():
    return os.path.isfile(MODEL)


def build(stone):
    """The sculpted lifter and its armature, from the cache or made afresh. [stone] makes its material."""
    cached = cache_path()
    if os.path.isfile(cached):
        bpy.ops.wm.open_mainfile(filepath=cached)
        return bpy.data.objects["Body"], bpy.data.objects["Rig"]
    body = load()
    low = cage(body)
    detail(body)
    rig = skeleton()
    skin(body, low, rig)
    for polygon in body.data.polygons:
        polygon.use_smooth = True
    body.data.materials.clear()
    body.data.materials.append(stone())
    body.name = "Body"
    # Out of the working view: posing asks where the bones are many times a frame, and would otherwise re-skin the
    # whole mesh each time. A render still sees it.
    body.hide_viewport = True
    os.makedirs(os.path.dirname(cached), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=cached, copy=True)
    return body, rig


def load():
    with bpy.data.libraries.load(MODEL, link=False) as (source, target):
        target.objects = [BODY, *EYES]
    body = target.objects[0]
    origin = body.location.copy()
    for thing in target.objects:
        bpy.context.scene.collection.objects.link(thing)
        # The eyes hang on the body in the file, with their places given in the world's terms: let go of it first, or
        # moving both moves them twice.
        thing.parent = None
        thing.matrix_parent_inverse = Matrix.Identity(4)
        thing.location = thing.location - origin
    return body


def cage(body):
    """The model's coarse cage, apart: what the automatic weights are worked out on."""
    low = body.copy()
    low.data = body.data.copy()
    low.name = "Cage"
    bpy.context.scene.collection.objects.link(low)
    low.modifiers.clear()
    return low


def positions(mesh):
    co = np.empty(len(mesh.vertices) * 3)
    mesh.vertices.foreach_get("co", co)
    return co.reshape((-1, 3))


def neighbours(mesh):
    edges = np.empty(len(mesh.edges) * 2, dtype=np.int64)
    mesh.edges.foreach_get("vertices", edges)
    edges = edges.reshape((-1, 2))
    both = np.concatenate([edges, edges[:, ::-1]])
    return both, np.maximum(np.bincount(both[:, 0], minlength=len(mesh.vertices)), 1)


def smoothed(values, both, degree, times):
    """Values over the vertices (one or more to each), each drawn half way to the mean of those round it, [times] over."""
    flat = values.ndim == 1
    values = values[:, None] if flat else values
    for _ in range(times):
        around = np.stack([np.bincount(both[:, 0], weights=values[both[:, 1], k], minlength=len(values)) for k in range(values.shape[1])], axis=1) / degree[:, None]
        values = 0.5 * values + 0.5 * around
    return values[:, 0] if flat else values


def detail(body):
    """Makes the sculpt's detail the mesh itself, deepens its relief, and joins the eyes to it."""
    for modifier in body.modifiers:
        if modifier.type == "MULTIRES":
            modifier.levels = LEVEL
    bpy.context.view_layer.objects.active = body
    with bpy.context.temp_override(object=body, active_object=body, selected_objects=[body]):
        for modifier in list(body.modifiers):
            bpy.ops.object.modifier_apply(modifier=modifier.name)
    mesh = body.data
    co = positions(mesh)
    both, degree = neighbours(mesh)
    # Every vertex moved further from where a smoothed copy of the body has it: the muscles stand out, the build is
    # the same. The head, the hands and the feet keep their own shape.
    smooth = smoothed(co, both, degree, 40)
    height = co[:, 2].max()
    keep = np.clip((co[:, 2] - 0.07 * height) / (0.05 * height), 0.0, 1.0) * np.clip((0.86 * height - co[:, 2]) / (0.03 * height), 0.0, 1.0)
    keep *= np.clip((0.36 - np.abs(co[:, 0])) / 0.04, 0.0, 1.0)
    co = co + (co - smooth) * DEEPEN * keep[:, None]
    # The groin is smoothed to a plain form, as on a mannequin: only the skin in front of and under the pelvis, fading
    # out to the thighs and the belly.
    plain = np.clip((0.085 - np.abs(co[:, 0])) / 0.03, 0.0, 1.0) * np.clip((co[:, 2] - 0.66) / 0.03, 0.0, 1.0)
    plain *= np.clip((0.9 - co[:, 2]) / 0.04, 0.0, 1.0) * np.clip((0.0 - co[:, 1]) / 0.04, 0.0, 1.0)
    there = np.nonzero(plain > 0.0)[0]
    local = np.full(len(co), -1)
    local[there] = np.arange(len(there))
    inside = both[(local[both[:, 0]] >= 0)]
    for _ in range(1200):
        around = np.stack([np.bincount(local[inside[:, 0]], weights=co[inside[:, 1], k], minlength=len(there)) for k in range(3)], axis=1)
        co[there] += (around / degree[there, None] - co[there]) * 0.5 * plain[there, None]
    mesh.vertices.foreach_set("co", co.ravel())
    mesh.update()
    eyes = [bpy.data.objects[name] for name in EYES if name in bpy.data.objects]
    if eyes:
        with bpy.context.temp_override(object=body, active_object=body, selected_objects=[body, *eyes], selected_editable_objects=[body, *eyes]):
            bpy.ops.object.join()


def skeleton():
    bones = list(MIDDLE)
    for sign, side in ((1.0, "l"), (-1.0, "r")):
        for name, parent, head, tail in SIDE + fingers() + twists():
            mirrored = lambda p: (p[0] * sign, p[1], p[2])
            bones.append((name + side, parent + side if parent.endswith("_") else parent, mirrored(head), mirrored(tail)))
    data = bpy.data.armatures.new("Rig")
    rig = bpy.data.objects.new("Rig", data)
    bpy.context.scene.collection.objects.link(rig)
    rig.location = (0.0, 0.0, RIG_Z)
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    down = Vector((0.0, 0.0, RIG_Z))
    for name, parent, head, tail in bones:
        bone = data.edit_bones.new(name)
        bone.head = Vector(head) - down
        bone.tail = Vector(tail) - down
        if parent is not None:
            bone.parent = data.edit_bones[parent]
        # The forearm's turning bones take no part in the automatic weights: their skin is shared out afterwards.
        bone.use_deform = "twist" not in name
    bpy.ops.object.mode_set(mode="OBJECT")
    rig.select_set(False)
    return rig


def skin(body, low, rig):
    """Binds the cage to the bones by Blender's automatic weights, carries the weights to the detailed mesh, and hangs that on the armature."""
    with bpy.context.temp_override(object=rig, active_object=rig, selected_objects=[low, rig], selected_editable_objects=[low, rig]):
        bpy.ops.object.parent_set(type="ARMATURE_AUTO")
    names = [group.name for group in low.vertex_groups]
    weights = np.zeros((len(low.data.vertices), len(names)))
    for vertex in low.data.vertices:
        for group in vertex.groups:
            weights[vertex.index, group.group] = group.weight
    unbound = int((weights.sum(axis=1) < 1e-6).sum())
    if unbound:
        print("SCULPTED", unbound, "cage vertices got no weights")
    faces = [tuple(p.vertices) for p in low.data.polygons]
    coarse = positions(low.data)
    tree = BVHTree.FromPolygons([Vector(p) for p in coarse], faces)
    fine = positions(body.data)
    carried = np.zeros((len(fine), len(names)))
    for index, point in enumerate(fine):
        hit = tree.find_nearest(Vector(point))
        corners = faces[hit[2]]
        near = np.array([1.0 / max(np.linalg.norm(coarse[c] - np.array(hit[0])), 1e-5) for c in corners])
        carried[index] = (weights[list(corners)] * (near / near.sum())[:, None]).sum(axis=0)
    both, degree = neighbours(body.data)
    carried = smoothed(carried, both, degree, 3)
    carried[carried < 0.01] = 0.0
    carried /= np.maximum(carried.sum(axis=1, keepdims=True), 1e-9)
    carried, names = twist_weights(carried, names, fine)
    for bone in rig.data.bones:
        bone.use_deform = True
    for column, name in enumerate(names):
        group = body.vertex_groups.new(name=name)
        for index in np.nonzero(carried[:, column])[0]:
            group.add([int(index)], float(carried[index, column]), "REPLACE")
    bpy.data.objects.remove(low)
    # The mesh is moved into the armature's own space and hung on it.
    body.data.transform(Matrix.Translation((0.0, 0.0, -RIG_Z)))
    body.parent = rig
    body.matrix_parent_inverse = Matrix.Identity(4)
    body.location = (0.0, 0.0, 0.0)
    deform = body.modifiers.new("Armature", "ARMATURE")
    deform.object = rig
