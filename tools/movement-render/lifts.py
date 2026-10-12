"""
Renders a lift: the figure taken through one rep in true 3D, as two pictures a frame.

    blender --background --python tools/movement-render/lifts.py -- <lift> <camera> <output directory> [frame]

For each of the rep's key frames (evenly spread from the easy end of the rep to the hard one) it
writes `body-NN.png`, the lit stone figure on a clear ground, and `muscle-NN.png`, the same view
with only the working muscles in it: red for the prime movers, green the synergists, blue the
stabilizers. The app lays the second over the first in whatever colours it likes, as bright as
the rep is hard at that moment, so the frames themselves say nothing about colour or effort.

From those it packs what the app ships (`body.webp` and `muscle.webp`, the key frames as one
sheet each; `badge.webp`, a small still of the hard end; `anchors.json`, where a label for each
working head the camera sees points in every frame) and `preview.webp`: one rep as the app would
show it, as a sheet of frames to look at.

What is held is kept out of the body: a lift that could put a dumbbell or a bar through a leg or
the face finds how far it has to move it to clear the skin (`until_clear`), and `clip.py` beside
this checks every lift for anything still sunk in.

A lift is three things: what it is done on (`build`), how the body is held `t` of the way
through it (`move`, in the terms of pose.py: hips, what the feet and hands are on, balance),
and where it is looked at from.
"""

import json
import math
import os
import sys

import bpy
import numpy as np
from mathutils import Vector
from mathutils.kdtree import KDTree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import figure  # noqa: E402
import muscles  # noqa: E402
import pictures  # noqa: E402
import pose  # noqa: E402

SIZE = 800
# How big a frame the app is given.
FRAME = 512
KEY_FRAMES = 25
SHEET_COLUMNS = 5
PREVIEW_FRAMES = 64
REP_SECONDS = 3.2
SIDES = ((1.0, "l"), (-1.0, "r"))

# Where a bar lies on the body, in the rest pose (and is carried from there by the spine): across the shoulders
# behind the neck, against the chest, at the collarbones, and at the stomach. Read off the body's own skin (`spots`).
BAR_ON_BACK = Vector((0.0, 0.118, 0.535))
BAR_ON_CHEST = Vector((0.0, -0.2, 0.455))
BAR_AT_COLLAR = Vector((0.0, -0.19, 0.6))
STOMACH = Vector((0.0, -0.21, 0.3))


def spots(skin):
    """Finds those places on this body: the skin there, as the reference body's landmarks give it, and a bar's or a hand's thickness off it."""
    global BAR_ON_BACK, BAR_ON_CHEST, BAR_AT_COLLAR, STOMACH

    def off(kind, x, z, out):
        at = Vector(skin.place((kind, x, z), 1.0, "l")[0])
        return Vector((0.0, at.y + (-out if kind == "front" else out), at.z))

    BAR_ON_BACK = off("back", 0.07, 0.56, 0.022)
    BAR_ON_CHEST = off("front", 0.09, 0.47, 0.03)
    BAR_AT_COLLAR = off("front", 0.06, 0.6, 0.04)
    STOMACH = off("front", 0.04, 0.3, 0.06)


# The body's skin, for asking where it is in a pose (`Probe`); `main` sets it.
SKIN = None
ARMS = ("upperarm_", "lowerarm_", "hand_", "index_", "middle_", "ring_", "pinky_", "thumb_")


def round_points(centre, axis, radius, length):
    """Points all over a rod's surface (a bar, a plate, a dumbbell's head): its middle, the way it runs, how thick and how long."""
    axis = Vector(axis).normalized()
    one = axis.orthogonal().normalized()
    other = axis.cross(one)
    ring = lambda r: [one * (r * math.cos(a)) + other * (r * math.sin(a)) for a in np.linspace(0.0, 2.0 * math.pi, max(int(2.0 * math.pi * r / 0.015), 12), endpoint=False)]
    points = [Vector(centre) + axis * z + off for z in np.linspace(-length / 2.0, length / 2.0, max(int(length / 0.015), 2)) for off in ring(radius)]
    points += [Vector(centre) + axis * z + off for z in (-length / 2.0, length / 2.0) for r in np.arange(0.0, radius, 0.015) for off in (ring(r) if r > 0.0 else [Vector()])]
    return np.array([tuple(point) for point in points])


def rod_points(rig, piece):
    """The same, for a rod that is in the scene, in the armature's space."""
    radius, length = piece["rod"]
    to_rig = rig.matrix_world.inverted() @ piece.matrix_world
    return round_points(to_rig.translation, to_rig.to_3x3() @ Vector((0.0, 0.0, 1.0)), radius, length)


def dumbbell_points(rig, side, grip):
    """A dumbbell's two heads, held in that hand with its handle's middle at [grip]."""
    line = pose.knuckles(rig, side)
    return np.concatenate([round_points(grip + line * at, line, DUMBBELL_HEAD[0], DUMBBELL_HEAD[1]) for at in (DUMBBELL_HEAD[2], -DUMBBELL_HEAD[2])])


def bar_points(bar, length=1.0, radius=0.014):
    """A bar lying along x with its middle at [bar], as far out as could meet the body."""
    return round_points(bar, (1.0, 0.0, 0.0), radius, length)


class Probe:
    """
    The body as it stands in the pose the armature is in now, to ask how far things are sunk into
    it. The hands and forearms are never part of it (what a hand holds lies against it), nor the
    upper arms unless [arms] is asked for: so the arms can be moved about and asked again.
    """

    def __init__(self, rig, arms=False):
        left_out = ARMS if not arms else ARMS[1:]
        if left_out not in Probe.parts:
            Probe.parts[left_out] = np.nonzero(SKIN.share(*left_out) < 0.5)[0]
        self.part = Probe.parts[left_out]
        self.positions, self.normals = SKIN.posed(self.part)
        self.low = self.high = self.tree = None

    def sunk(self, points, named=False):
        """How far into the body the deepest of those points is (less than nothing when all of them are outside it: that far clear). With [named], also the bone the skin there hangs on."""
        low, high = points.min(axis=0), points.max(axis=0)
        if self.tree is None or np.any(low < self.low) or np.any(high > self.high):
            self.low, self.high = low - 0.3, high + 0.3
            self.near = np.nonzero(np.all((self.positions > self.low - 0.08) & (self.positions < self.high + 0.08), axis=1))[0]
            self.tree = KDTree(len(self.near))
            for index, at in enumerate(self.near):
                self.tree.insert(self.positions[at], index)
            self.tree.balance()
        deepest, where = -0.08, None
        for point in points:
            _, index, distance = self.tree.find(point)
            if index is not None and distance < 0.08:
                at = self.near[index]
                depth = -float((point - self.positions[at]) @ self.normals[at])
                if depth > deepest:
                    deepest, where = depth, at
        if not named:
            return deepest
        owners, weights = SKIN.bound()
        return deepest, SKIN.bones[owners[self.part[where], np.argmax(weights[self.part[where]])]] if where is not None else None


Probe.parts = {}


def until_clear(probe, attempt, low, high, margin=0.004, steps=10):
    """
    Finds how far something has to be moved for what is held to clear the body. [attempt] poses
    with a value between [low] and [high] and returns (the held thing's points, anything else);
    the least value that leaves the points [margin] clear of the body is found, and what the
    attempt at it returned beside the points is handed back with it.
    """
    points, result = attempt(low)
    if probe.sunk(points) <= -margin:
        return low, result
    inside, clear = low, None
    for step in range(1, steps + 1):
        value = low + (high - low) * step / steps
        points, result = attempt(value)
        if probe.sunk(points) <= -margin:
            clear = value
            break
        inside = value
    if clear is None:
        return high, result
    for _ in range(5):
        value = (inside + clear) / 2.0
        points, result = attempt(value)
        if probe.sunk(points) <= -margin:
            clear = value
        else:
            inside = value
    points, result = attempt(clear)
    return clear, result


def sweep(a, b, t):
    return a + (b - a) * t


def trunk(rig, hips, lean, head=1.0, stiff=False):
    """
    Puts the hips at [hips] and leans the trunk [lean] degrees forward from upright, the spine
    taking it bone by bone ([stiff]: all of a piece, as lying on a bench); [head] is how much of
    it the head goes with.
    """
    pose.point(rig, "pelvis", pose.turned(pose.rest_direction(rig, "pelvis"), "X", lean * (0.94 if stiff else 0.55)), location=hips)
    for name, share in (("spine_01", 0.7), ("spine_02", 0.9), ("spine_03", 1.0), ("neck_01", 0.45 + 0.55 * head), ("head", head)):
        pose.point(rig, name, pose.turned(pose.rest_direction(rig, name), "X", lean * (max(share, 0.97) if stiff and "spine" in name else share)))


def feet(rig, ankles, knees, toe_out=14.0):
    """Stands the feet flat with the ankles at [ankles] (the left one; the right is its mirror), the knees pointing [knees], the toes turned out."""
    for sign, side in SIDES:
        ankle = Vector((ankles[0] * sign, ankles[1], ankles[2]))
        pose.limb(rig, "thigh_" + side, "calf_" + side, ankle, (knees[0] * sign, knees[1], knees[2]))
        for name in ("foot_", "ball_"):
            # Turned out by that much from straight ahead, whatever the body's own feet do at rest.
            rest = pose.rest_direction(rig, name + side)
            own = math.degrees(math.atan2(rest.x * sign, -rest.y))
            pose.point(rig, name + side, pose.turned(rest, "Z", (toe_out - own) * sign))
            pose.level(rig, name + side)


def shoulders(rig):
    """The middle of the two shoulder joints, and an arm's full reach."""
    left = pose.root_of(rig, "upperarm_l")
    right = pose.root_of(rig, "upperarm_r")
    return (left + right) / 2.0, rig.data.bones["upperarm_l"].length + rig.data.bones["lowerarm_l"].length


def hold_bar(rig, bar, half_width, elbows, hand=(0.0, -0.3, 1.0), palm=(0.0, -1.0, -0.4), radius=0.014):
    """Brings both hands to a bar lying along x at [bar]: each hand along [hand] with its palm facing [palm], the bar in the palm and the fingers round it."""
    for sign, side in SIDES:
        wrist = Vector(bar) + Vector((half_width * sign, 0.0, 0.0)) - pose.grip_reach(sign, hand, palm, radius)
        pose.limb(rig, "upperarm_" + side, "lowerarm_" + side, wrist, (elbows[0] * sign, elbows[1], elbows[2]))
        pose.grip(rig, side, sign, hand, palm, radius)


def hold(rig, sign, side, wrist, elbow, hand, palm, radius=0.014):
    """Brings one hand to a handle: the wrist to [wrist], the elbow toward [elbow], the hand closed round it as `pose.grip` takes it. Returns the middle of the handle."""
    pose.limb(rig, "upperarm_" + side, "lowerarm_" + side, wrist, elbow)
    return pose.grip(rig, side, sign, hand, palm, radius)


def carry(rig, things, side, grip):
    """Lays a dumbbell in a hand that has closed on it: its handle's middle at [grip], along the line of the knuckles."""
    thing = things["dumbbell_" + side]
    thing.location = rig.matrix_world @ grip
    thing.rotation_euler = pose.knuckles(rig, side).to_track_quat("X", "Z").to_euler()


def hang(rig, things, out=4.0, found=None):
    """
    Lets both arms hang with a dumbbell in each hand, palms to the thighs, as far out from the
    body as the dumbbells need to hang clear of the legs. Returns what they weigh, for balance.
    [found] remembers how far out that was, for a lift that poses the arms more than once a frame.
    """
    _, reach = shoulders(rig)
    loads = []
    probe = Probe(rig) if found is None or "out" not in found else None
    for sign, side in SIDES:
        def attempt(angle):
            down = Vector((math.sin(math.radians(angle)) * sign, 0.0, -math.cos(math.radians(angle))))
            wrist = pose.root_of(rig, "upperarm_" + side) + down * reach * 0.985
            grip = hold(rig, sign, side, wrist, (0.0, 1.0, 0.2), down, (-sign, 0.0, 0.0))
            return dumbbell_points(rig, side, grip), grip

        if probe is None:
            grip = attempt(found["out"])[1]
        else:
            angle, grip = until_clear(probe, attempt, out, out + 30.0)
            if found is not None and sign < 0:
                found["out"] = angle
        carry(rig, things, side, grip)
        loads.append((0.08, grip))
    return loads


def stand(rig, lean, drop, arms, toe_out=10.0, head=0.2, knees=(0.3, -1.0, 0.0), over=None, widen=0.01, heels=0.0):
    """
    Stands the lifter up: the trunk leaning [lean], the hips [drop] below where they stand at
    rest, and as far back as puts the weight of the body, and of what [arms] says it carries,
    over the feet. [arms] poses the arms once the trunk is placed and returns the loads.
    [heels] lifts the heels that far, the balls of the feet staying down.
    """
    hips_rest = Vector(rig.data.bones["pelvis"].head_local)
    ankle = Vector(rig.data.bones["calf_l"].tail_local) + Vector((widen, -0.02 * heels / 0.08 if heels else 0.0, heels))

    def place(back):
        pose.rest(rig)
        trunk(rig, hips_rest + Vector((0.0, back, heels - drop)), lean, head=head)
        feet(rig, ankle, knees, toe_out)
        if heels:
            for sign, side in SIDES:
                ball = Vector(rig.data.bones["foot_" + side].tail_local) + Vector((widen * sign, 0.0, 0.0))
                pose.point(rig, "foot_" + side, ball - rig.pose.bones["foot_" + side].head)
                pose.point(rig, "ball_" + side, pose.rest_direction(rig, "ball_" + side))
        return rig, arms() or []

    pose.balanced(place, pose.mid_foot(rig) if over is None else over)


def sit(rig, seat, lean, head=0.4, ankles=None, knees=(0.15, -0.2, 1.0), stiff=False):
    """Sits the lifter on a seat [seat] above the floor, the trunk leaning [lean], the feet on the floor in front unless [ankles] says where (False: left for the lift to place)."""
    pose.rest(rig)
    hips = Vector((0.0, 0.0, pose.floor(rig) + seat + 0.11))
    trunk(rig, hips, lean, head=head, stiff=stiff)
    if ankles is not False:
        feet(rig, ankles or (0.2, hips.y - 0.43, pose.floor(rig) + pose.ankle_height(rig)), knees, toe_out=8.0)
    return hips


HANDLE = (0.016, 0.14)


def grasp(rig, things, side, grip, foot):
    """Puts a machine's handle in a hand that has closed on it: its middle at [grip], along the line of the knuckles, on a stem that runs to [foot] on the machine."""
    if "grip_" + side not in things:
        return
    world = rig.matrix_world
    line = pose.knuckles(rig, side)
    things["grip_" + side].location = world @ grip
    things["grip_" + side].rotation_euler = line.to_track_quat("Z", "Y").to_euler()
    # The stem leaves from whichever end of the handle is nearer the machine.
    end = min((grip + line * HANDLE[1] / 2.0, grip - line * HANDLE[1] / 2.0), key=lambda at: (at - foot).length)
    stretch(things["stem_" + side], world @ end, world @ foot)
    things["stem_" + side].scale = (0.028, 0.028, (end - foot).length)


def rest_hands(rig, hips, things, out=0.3, forward=0.0, drop=0.02):
    """Puts the hands on a machine's handles beside the seat, the handles as far out as clears the legs."""
    probe = Probe(rig)
    for sign, side in SIDES:
        def attempt(wide):
            grip = hold(rig, sign, side, hips + Vector((wide * sign, -forward, -drop + 0.06)), (0.3 * sign, 1.0, 0.2), (0.0, -0.3, -1.0), (-sign, 0.0, 0.0), radius=HANDLE[0])
            return round_points(grip, pose.knuckles(rig, side), *HANDLE), grip

        _, grip = until_clear(probe, attempt, out, out + 0.2, margin=0.01)
        grasp(rig, things, side, grip, hips + Vector((0.16 * sign, 0.02, -0.15)))


# --- The lifts -------------------------------------------------------------------------------------------------


def squat(rig, t, things):
    """A high-bar back squat: 0 standing, 1 at the bottom. The hips sit back as far as keeps the lifter and the bar over the feet."""
    # The trunk leans as the hips fold, most of it early: the hips break first.
    lean = 4.0 + 30.0 * math.sin(t * math.pi / 2.0) ** 1.2

    def arms():
        bar = pose.carried(rig, "spine_03", BAR_ON_BACK)
        hold_bar(rig, bar, 0.38, elbows=(0.2, 0.5, -1.0))
        # A bar of about the lifter's own weight.
        return [(1.0, bar)]

    stand(rig, lean, 0.015 + 0.47 * t, arms, toe_out=16.0, head=0.1, knees=(0.6, -1.0, 0.0), widen=0.035)
    things["bar"].location = rig.matrix_world @ pose.carried(rig, "spine_03", BAR_ON_BACK)


def press(lean, seat, low, elbows, stiff=False, grip=0.36):
    """A barbell press from a bench set at [lean]: 0 with the bar at [low] on the body, 1 at arms' length over the shoulders."""
    def move(rig, t, things):
        hips = sit(rig, seat, lean, head=1.0, ankles=(0.25, -0.48 if not stiff else -0.42, pose.floor(rig) + pose.ankle_height(rig)), knees=(0.3, -0.4, 1.0), stiff=stiff)
        middle, reach = shoulders(rig)
        bottom = pose.carried(rig, "spine_03", low)
        top = middle + Vector((0.0, -0.02, reach + 0.06))
        ahead = (pose.carried(rig, "spine_03", Vector((0.0, -1.0, 0.0))) - pose.carried(rig, "spine_03", Vector())).normalized()

        def attempt(forward):
            bar = bottom.lerp(top, t) + ahead * forward
            hold_bar(rig, bar, grip, elbows=elbows, hand=(0.0, -0.1, 1.0), palm=(0.0, -1.0, -0.5))
            return bar_points(bar), bar

        # The bar goes round the face, not through it.
        _, bar = until_clear(Probe(rig), attempt, 0.0, 0.2, margin=0.01)
        things["bar"].location = rig.matrix_world @ bar
        place_bench(rig, things, hips, lean)
    return move


def lat_pulldown(rig, t, things):
    """A wide-grip pulldown: 0 with the arms overhead, 1 with the bar at the top of the chest, the trunk leaning back a little to let it by."""
    hips = sit(rig, 0.44, -4.0 - 10.0 * t)
    middle, reach = shoulders(rig)
    half = 0.43
    out = half - pose.root_of(rig, "upperarm_l").x
    high = Vector((0.0, middle.y - 0.1, middle.z + math.sqrt(max(reach * reach - out * out - 0.01, 0.0)) + 0.04))
    low = pose.carried(rig, "spine_03", BAR_AT_COLLAR)

    def attempt(forward):
        bar = high.lerp(low, t) + Vector((0.0, -forward, 0.0))
        hold_bar(rig, bar, half, elbows=(0.9, 0.55, -0.6), hand=(0.0, -0.1, 1.0), palm=(0.0, -1.0, -0.5))
        return bar_points(bar), bar

    # The bar comes down in front of the face.
    _, bar = until_clear(Probe(rig), attempt, 0.0, 0.25, margin=0.01)
    things["bar"].location = rig.matrix_world @ bar
    place_tower(rig, things, hips.y - 0.85, bar, Vector((0.0, bar.y, pose.floor(rig) + 2.3)))
    things["seat"].location = rig.matrix_world @ (hips + Vector((0.0, -0.1, -0.15)))


def pull_up(rig, t, things):
    """
    A pull-up: 0 at a dead hang, 1 with the chin over the bar. The bar stays; the body goes. At
    the hang the arms are long and the shoulders are drawn up by the ears; the pull brings the
    shoulder blades down and back and the elbows down to the ribs, the chest lifting to the bar
    as the trunk leans back, and the body hangs with its weight under the hands all the way.
    """
    bar = Vector((0.0, -0.06, pose.floor(rig) + 2.3))
    half = 0.29
    hand, palm = (0.0, -0.1, 1.0), (0.0, -1.0, -0.5)
    pose.rest(rig)
    _, reach = shoulders(rig)

    def hung(pulled, gap, back=0.0):
        """The body [pulled] of the way up, its shoulders [gap] below the bar (None: as far below it as straight arms hang them), hanging [back] behind where its weight would hang it. Returns that gap."""
        hips = Vector((0.0, 0.0, bar.z - 1.0))
        for _ in range(4):
            pose.rest(rig)
            trunk(rig, hips, -3.0 - 19.0 * pulled, head=0.15 + 0.25 * pulled)
            for sign, side in SIDES:
                # The shoulder blades: up by the ears at the hang, drawn down and back by the top.
                pose.point(rig, "clavicle_" + side, pose.rest_direction(rig, "clavicle_" + side) + Vector((0.0, 0.16 * pulled, 0.5 - 0.62 * pulled)))
                pose.point(rig, "thigh_" + side, (0.03 * sign, -0.06 - 0.16 * pulled, -1.0))
                pose.point(rig, "calf_" + side, (0.0, 0.42 + 0.1 * pulled, -1.0))
                pose.hinge(rig, "thigh_" + side, "calf_" + side)
            middle, _ = shoulders(rig)
            wrist = bar + Vector((half, 0.0, 0.0)) - pose.grip_reach(1.0, hand, palm)
            joint = pose.root_of(rig, "upperarm_l")
            level = math.hypot(wrist.x - joint.x, wrist.y - joint.y)
            below = math.sqrt(max((reach * 0.985) ** 2 - level ** 2, 0.0)) + (bar.z - wrist.z) if gap is None else gap
            hips.z += (bar.z - below) - middle.z
            # The elbows come down beside the ribs, a little behind the body, not out level with the shoulders.
            hold_bar(rig, bar, half, elbows=(0.5, 0.55, -1.0), hand=hand, palm=palm)
            # A body hanging from its hands hangs with its weight under them.
            hips.y += bar.y + back - pose.centre_of_mass(rig, []).y
        return below

    hang = hung(0.0, None)
    gap = sweep(hang, 0.16, t)
    # The face goes up behind the bar, not through it: the body swings back as far as that takes.
    clear = lambda back: hung(t, gap, back) and Probe(rig).sunk(bar_points(bar)) <= -0.015
    if not clear(0.0):
        near, far = 0.0, next((step for step in (0.04, 0.08, 0.12, 0.16, 0.2) if clear(step)), 0.2)
        near = far - 0.04
        for _ in range(4):
            middle = (near + far) / 2.0
            near, far = (near, middle) if clear(middle) else (middle, far)
        hung(t, gap, far)
    things["bar"].location = rig.matrix_world @ bar
    for sign, name in SIDES:
        things["post_" + name].location = rig.matrix_world @ Vector((0.72 * sign, bar.y, (bar.z + pose.floor(rig)) / 2.0))


def seated_row(rig, t, things):
    """A seated cable row: 0 with the arms out long to the handle, 1 with it drawn in to the stomach, the chest lifting to meet it."""
    hips = sit(rig, 0.32, 4.0 - 10.0 * t, ankles=False)
    plate = Vector((0.0, hips.y - 0.82, hips.z - 0.02))
    feet(rig, (0.14, plate.y + 0.06, plate.z), knees=(0.2, -0.1, 1.0), toe_out=6.0)
    for sign, side in SIDES:
        pose.point(rig, "foot_" + side, (0.0, -0.35, 1.0))
        pose.point(rig, "ball_" + side, (0.0, -0.35, 1.0))
    middle, reach = shoulders(rig)
    far = Vector((0.0, middle.y - reach * 0.93, middle.z - 0.26))
    near = pose.carried(rig, "spine_01", STOMACH)

    def attempt(higher):
        bar = far.lerp(near, t) + Vector((0.0, 0.0, higher))
        hold_bar(rig, bar, 0.2, elbows=(0.45, 1.0, -0.5), hand=(0.0, -1.0, 0.0), palm=(0.0, 0.0, -1.0))
        return bar_points(bar, 0.5), bar

    # The handle is drawn in over the knees.
    _, bar = until_clear(Probe(rig), attempt, 0.0, 0.3, margin=0.008)
    world = rig.matrix_world
    things["bar"].location = world @ bar
    stretch(things["cable"], world @ bar, world @ Vector((0.0, hips.y - 1.25, hips.z + 0.05)))
    things["seat"].location = world @ (hips + Vector((0.0, -0.15, -0.15)))
    things["plate"].location = world @ (plate + Vector((0.0, -0.08, 0.02)))
    things["tower"].location = world @ Vector((0.0, hips.y - 1.3, pose.floor(rig) + 0.55))


def fly(reverse):
    """A machine fly, seated: the arms swept level from wide to together in front (the chest's), or [reverse], from in front out wide and back (the rear shoulder's)."""
    def move(rig, t, things):
        lean = 6.0 if reverse else -6.0
        hips = sit(rig, 0.46, lean, head=0.6)
        _, reach = shoulders(rig)
        angle = math.radians(sweep(12.0, 100.0, t) if reverse else sweep(96.0, 0.0, t))
        for sign, side in SIDES:
            out = Vector((math.sin(angle) * sign, -math.cos(angle), -0.06)).normalized()
            closing = Vector((-math.cos(angle) * sign, -math.sin(angle), 0.0)) * (-1.0 if reverse else 1.0)
            wrist = pose.root_of(rig, "upperarm_" + side) + out * reach * 0.94
            grip = hold(rig, sign, side, wrist, (1.0 * sign, 0.6, -0.35), out, closing, radius=0.016)
            things["handle_" + side].location = rig.matrix_world @ grip
        pad = hips + (pose.turned((0.0, 0.0, 1.0), "X", lean) * 0.42) + pose.turned((0.0, -1.0 if reverse else 1.0, 0.0), "X", lean) * 0.18
        things["back"].location = rig.matrix_world @ pad
        things["back"].rotation_euler = (math.radians(lean), 0.0, 0.0)
        things["seat"].location = rig.matrix_world @ (hips + Vector((0.0, -0.1, -0.15)))
        things["post"].location = rig.matrix_world @ Vector((0.0, hips.y + (-0.35 if reverse else 0.3), pose.floor(rig) + 0.75))
    return move


def lateral_raise(rig, t, things):
    """A dumbbell lateral raise: 0 with the arms hanging, 1 with them out level, a little forward of the body, the elbows just unlocked."""
    found = {}

    def arms():
        _, reach = shoulders(rig)
        loads = []
        probe = Probe(rig) if "angle" not in found else None
        for sign, side in SIDES:
            def attempt(degrees):
                angle = math.radians(degrees)
                out = Vector((math.sin(angle) * sign, -0.2 * math.sin(angle), -math.cos(angle))).normalized()
                palm = Vector((-math.cos(angle) * sign, 0.0, -math.sin(angle)))
                wrist = pose.root_of(rig, "upperarm_" + side) + out * reach * 0.97
                grip = hold(rig, sign, side, wrist, (0.0, 1.0, 0.25), out, palm)
                return dumbbell_points(rig, side, grip), grip

            # No nearer the body than lets the dumbbell hang clear of the leg.
            if probe is None:
                grip = attempt(found["angle"])[1]
            else:
                found["angle"], grip = until_clear(probe, attempt, 4.0 + 82.0 * t, 40.0 + 46.0 * t)
            carry(rig, things, side, grip)
            loads.append((0.08, grip))
        return loads

    stand(rig, 2.0, 0.012, arms)


def front_raise(rig, t, things):
    """A dumbbell front raise: 0 with the arms hanging in front of the thighs, 1 with them straight out ahead at shoulder height."""
    found = {}

    def arms():
        _, reach = shoulders(rig)
        loads = []
        probe = Probe(rig) if "angle" not in found else None
        for sign, side in SIDES:
            def attempt(degrees):
                angle = math.radians(degrees)
                out = Vector((0.04 * sign, -math.sin(angle), -math.cos(angle))).normalized()
                palm = Vector((0.0, math.cos(angle), -math.sin(angle)))
                wrist = pose.root_of(rig, "upperarm_" + side) + out * reach * 0.975
                grip = hold(rig, sign, side, wrist, (0.6 * sign, 0.6, 0.2), out, palm)
                return dumbbell_points(rig, side, grip), grip

            # No nearer the thighs than lets the dumbbell lie in front of them.
            if probe is None:
                grip = attempt(found["angle"])[1]
            else:
                found["angle"], grip = until_clear(probe, attempt, 4.0 + 84.0 * t, 44.0 + 44.0 * t)
            carry(rig, things, side, grip)
            loads.append((0.08, grip))
        return loads

    stand(rig, 1.0 - 3.0 * t, 0.012, arms)


def shrug(rig, t, things):
    """A dumbbell shrug: the arms hang, and the shoulders are drawn straight up toward the ears."""
    found = {}

    def arms():
        for sign, side in SIDES:
            pose.point(rig, "clavicle_" + side, pose.rest_direction(rig, "clavicle_" + side) + Vector((0.0, 0.0, 0.3 * t)))
        return hang(rig, things, found=found)

    stand(rig, 1.0, 0.012, arms)


def curl(rig, t, things):
    """A standing dumbbell curl: the upper arms stay by the ribs, the forearms come up from hanging until the weights are by the shoulders, palms up."""
    found = {}

    def arms():
        loads = []
        for sign, side in SIDES:
            pose.point(rig, "upperarm_" + side, (0.07 * sign, -0.05 - 0.06 * t, -1.0))
        for sign, side in SIDES:
            def attempt(degrees):
                fold = math.radians(degrees)
                forward = Vector((0.03 * sign, -math.sin(fold), -math.cos(fold))).normalized()
                pose.point(rig, "lowerarm_" + side, forward)
                pose.hinge(rig, "upperarm_" + side, "lowerarm_" + side, (0.0, 1.0, 0.0))
                grip = pose.grip(rig, side, sign, forward, (0.0, -math.cos(fold), math.sin(fold)))
                return dumbbell_points(rig, side, grip), grip

            # The weights start in front of the thighs, no lower than clears them, and come up no nearer the
            # shoulders than clears those.
            if "low" not in found:
                found["low"] = until_clear(Probe(rig), attempt, 6.0, 50.0)[0]
                found["high"] = 140.0 - until_clear(Probe(rig, arms=True), lambda less: attempt(140.0 - less), 0.0, 50.0)[0]
            grip = attempt(sweep(found["low"], found["high"], t))[1]
            carry(rig, things, side, grip)
            loads.append((0.08, grip))
        return loads

    stand(rig, 1.0, 0.012, arms)


def pushdown(rig, t, things):
    """A cable pushdown: the elbows stay at the sides, and the bar is pressed from the chest down to the thighs."""
    grips = {}

    def arms():
        elbow = (pose.root_of(rig, "upperarm_l") + pose.root_of(rig, "upperarm_r")) / 2.0 + Vector((0.0, -0.04, -rig.data.bones["upperarm_l"].length))

        def attempt(degrees):
            fold = math.radians(degrees)
            forearm = Vector((0.0, -math.sin(fold), -math.cos(fold)))
            bar = elbow + forearm * (rig.data.bones["lowerarm_l"].length + pose.GRIP_ALONG)
            hold_bar(rig, bar, 0.15, elbows=(0.25, 1.0, 0.0), hand=tuple(forearm), palm=(0.0, math.cos(fold), -math.sin(fold)))
            return bar_points(bar, 0.5), bar

        # Pressed down until the arms are straight, or the bar is at the thighs.
        if "low" not in grips:
            grips["low"] = until_clear(Probe(rig), attempt, 6.0, 50.0)[0]
        grips["bar"] = attempt(sweep(112.0, grips["low"], t))[1]
        return []

    stand(rig, 9.0, 0.03, arms, head=0.3)
    bar = grips["bar"]
    things["bar"].location = rig.matrix_world @ bar
    place_tower(rig, things, bar.y - 0.55, bar, Vector((0.0, bar.y - 0.35, pose.floor(rig) + 2.15)))


def dip(rig, t, things):
    """A parallel-bar dip: 0 held up at arms' length, 1 let down until the upper arms are level."""
    rail = Vector((0.3, 0.0, pose.floor(rig) + 1.12))
    pose.rest(rig)
    _, reach = shoulders(rig)
    # The hands' wrists stand a little over the rails; at the bottom the forearms are upright over them and the upper arms level.
    height = sweep(rail.z + 0.05 + reach - 0.015, rail.z + 0.06 + rig.data.bones["lowerarm_l"].length, t)
    lean = 10.0 + 14.0 * t
    hips = Vector((0.0, 0.1 + 0.05 * t, height - 0.45))
    for _ in range(2):
        pose.rest(rig)
        trunk(rig, hips, lean, head=0.3)
        hips.z += height - shoulders(rig)[0].z
    for sign, side in SIDES:
        pose.point(rig, "thigh_" + side, (0.03 * sign, -0.18, -1.0))
        pose.point(rig, "calf_" + side, (0.0, 0.9, -0.8))
        pose.hinge(rig, "thigh_" + side, "calf_" + side)
        # The hands lie across the rails, the fingers over their outer sides.
        hand, palm = (0.9 * sign, -0.15, -0.3), (0.0, 0.0, -1.0)
        wrist = Vector((rail.x * sign, rail.y - 0.05, rail.z)) - pose.grip_reach(sign, hand, palm, 0.02)
        hold(rig, sign, side, wrist, (0.15 * sign, 1.0, 0.3), hand, palm, radius=0.02)
        things["rail_" + side].location = rig.matrix_world @ Vector((rail.x * sign, rail.y - 0.05, rail.z))
        things["post_" + side].location = rig.matrix_world @ Vector((rail.x * sign, rail.y + 0.3, (rail.z + pose.floor(rig)) / 2.0))


def leg_press(rig, t, things):
    """A leg press: 0 with the sled pressed out, 1 with it let down until the knees come to the chest."""
    lean = -52.0
    hips = sit(rig, 0.26, lean, head=1.0, ankles=False)
    push = Vector((0.0, -math.cos(math.radians(38.0)), math.sin(math.radians(38.0))))
    up_plate = Vector((0.0, push.z, -push.y))
    far = rig.data.bones["thigh_l"].length + rig.data.bones["calf_l"].length
    out = sweep(far * 0.93, far * 0.56, t)
    hip = pose.root_of(rig, "thigh_l")
    ankle = Vector((0.0, hip.y, hip.z)) + push * out
    for sign, side in SIDES:
        pose.limb(rig, "thigh_" + side, "calf_" + side, ankle + Vector((0.17 * sign, 0.0, 0.0)), (0.45 * sign, 0.3, 1.0))
        for name in ("foot_", "ball_"):
            pose.point(rig, name + side, up_plate + Vector((0.12 * sign, 0.0, 0.0)))
            pose.level(rig, name + side, -push)
    rest_hands(rig, hips, things, out=0.31, forward=0.05, drop=0.1)
    place_bench(rig, things, hips, lean)
    things["sled"].location = rig.matrix_world @ (ankle + push * 0.1 + up_plate * 0.09)
    things["sled"].rotation_euler = (math.radians(-38.0), 0.0, 0.0)


def leg_extension(rig, t, things):
    """A leg extension: sitting, the shins swung up from hanging until the legs are out straight."""
    hips = sit(rig, 0.6, -12.0, head=0.7, ankles=False)
    swing = math.radians(sweep(-12.0, 80.0, t))
    for sign, side in SIDES:
        pose.point(rig, "thigh_" + side, (0.06 * sign, -1.0, -0.03))
        pose.point(rig, "calf_" + side, (0.0, -math.sin(swing), -math.cos(swing)))
        pose.hinge(rig, "thigh_" + side, "calf_" + side, (0.0, 0.0, 1.0))
    rest_hands(rig, hips, things)
    place_bench(rig, things, hips, -12.0)
    ankles = (rig.pose.bones["foot_l"].head + rig.pose.bones["foot_r"].head) / 2.0
    things["roller"].location = rig.matrix_world @ (ankles + Vector((0.0, -math.cos(swing), math.sin(swing))) * 0.075 + Vector((0.0, math.sin(swing), math.cos(swing))) * 0.06)


def leg_curl(rig, t, things):
    """A lying leg curl: face down on the bench, the heels brought up and over toward the seat."""
    pose.rest(rig)
    hips = Vector((0.0, 0.0, pose.floor(rig) + 0.58))
    trunk(rig, hips, 86.0, head=0.75, stiff=True)
    swing = math.radians(sweep(6.0, 118.0, t))
    for sign, side in SIDES:
        pose.point(rig, "thigh_" + side, (0.02 * sign, 1.0, -0.06))
        pose.point(rig, "calf_" + side, (0.0, math.cos(swing), math.sin(swing)))
        pose.hinge(rig, "thigh_" + side, "calf_" + side, (0.0, 0.0, -1.0))
    middle, _ = shoulders(rig)
    for sign, side in SIDES:
        grip = hold(rig, sign, side, Vector((0.27 * sign, middle.y - 0.2, hips.z - 0.2)), (0.6 * sign, 0.3, 0.4), (0.0, -0.4, -1.0), (-sign, 0.0, 0.0), radius=HANDLE[0])
        grasp(rig, things, side, grip, Vector((0.17 * sign, grip.y + 0.1, hips.z - 0.15)))
    world = rig.matrix_world
    things["pad"].location = world @ (hips + Vector((0.0, -0.33, -0.13)))
    things["post"].location = world @ Vector((0.0, hips.y - 0.3, (hips.z - 0.16 + pose.floor(rig)) / 2.0))
    things["post"].scale = (0.09, 0.09, hips.z - 0.16 - pose.floor(rig))
    things["rail"].location = world @ Vector((0.0, hips.y - 0.2, pose.floor(rig) + 0.03))
    ankles = (rig.pose.bones["foot_l"].head + rig.pose.bones["foot_r"].head) / 2.0
    behind = Vector((0.0, -math.sin(swing), math.cos(swing)))
    probe = Probe(rig)
    off, at = until_clear(probe, lambda far: (round_points(ankles + behind * far, (1.0, 0.0, 0.0), 0.05, 0.5), ankles + behind * far), 0.06, 0.16, margin=-0.004)
    things["roller"].location = world @ at


def calf_raise(rig, t, things):
    """A standing calf raise: up onto the balls of the feet, a dumbbell in each hand."""
    ball = rig.data.bones["foot_l"].tail_local.y
    found = {}
    stand(rig, 1.0, 0.0, lambda: hang(rig, things, found=found), over=sweep(pose.mid_foot(rig), ball + 0.03, t), heels=0.085 * t, knees=(0.3, -1.0, 0.0))


def hinge(rig, t, things):
    """A Romanian deadlift: the bar hangs from straight arms, and the hips go back as the trunk comes over until it is nearly level, the knees only soft."""
    def arms():
        middle, reach = shoulders(rig)

        def attempt(degrees):
            # The bar hangs from straight arms, swung forward that far.
            swing = math.radians(degrees)
            bar = middle + Vector((0.0, -math.sin(swing), -math.cos(swing))) * (reach + 0.05)
            hold_bar(rig, bar, 0.27, elbows=(0.3, 1.0, 0.0), hand=(0.0, -math.sin(swing), -math.cos(swing)), palm=(0.0, math.cos(swing), -math.sin(swing)))
            return bar_points(bar), bar

        # It rides down the front of the legs: no nearer them than touching. (Asked afresh each time the hips
        # are shifted for balance: where the legs are under the shoulders changes with it.)
        _, bar = until_clear(Probe(rig), attempt, 0.0, 45.0, margin=0.002)
        things["bar"].location = rig.matrix_world @ bar
        return [(0.9, bar)]

    stand(rig, 4.0 + 62.0 * t, 0.012 + 0.08 * t, arms, head=0.45)


def hip_machine(adduct):
    """The seated hip machine: the knees pressed apart against its pads (abduction), or [adduct], drawn together from wide."""
    def move(rig, t, things):
        hips = sit(rig, 0.5, -16.0, head=0.8, ankles=False)
        wide = sweep(0.78, 0.1, t) if adduct else sweep(0.08, 0.72, t)
        for sign, side in SIDES:
            pose.point(rig, "thigh_" + side, (wide * sign, -1.0, -0.02))
            pose.point(rig, "calf_" + side, (0.02 * sign, -0.12, -1.0))
            pose.hinge(rig, "thigh_" + side, "calf_" + side, (0.0, 0.0, 1.0))
            knee = rig.pose.bones["calf_" + side].head
            things["pad_" + side].location = rig.matrix_world @ (knee + Vector(((-0.09 if adduct else 0.09) * sign, 0.04, 0.0)))
        rest_hands(rig, hips, things, out=0.3, forward=0.04, drop=0.02)
        place_bench(rig, things, hips, -16.0)
        # This machine's back pad is a low one: the hips, which are what it works, show past it from behind.
        up = pose.turned((0.0, 0.0, 1.0), "X", -16.0)
        behind = pose.turned((0.0, 1.0, 0.0), "X", -16.0)
        things["back"].location = rig.matrix_world @ (hips + up * 0.3 + behind * 0.17)
        things["back"].scale = (0.3, 0.07, 0.3)
    return move


def crunch(rig, t, things):
    """A crunch: on the back with the knees up, the shoulders curled off the floor toward the hips."""
    pose.rest(rig)
    hips = Vector((0.0, 0.0, pose.floor(rig) + 0.115))
    pose.point(rig, "pelvis", pose.turned(pose.rest_direction(rig, "pelvis"), "X", -84.0 + 6.0 * t), location=hips)
    for name, lean in (("spine_01", -88.0 + 12.0 * t), ("spine_02", -88.0 + 26.0 * t), ("spine_03", -88.0 + 42.0 * t), ("neck_01", -80.0 + 50.0 * t), ("head", -74.0 + 50.0 * t)):
        pose.point(rig, name, pose.turned(pose.rest_direction(rig, name), "X", lean))
    feet(rig, (0.14, hips.y - 0.52, pose.floor(rig) + pose.ankle_height(rig)), knees=(0.1, -0.2, 1.0), toe_out=6.0)
    # The arms crossed on the chest, each hand on the far shoulder.
    for sign, side in SIDES:
        across = pose.carried(rig, "spine_03", BAR_ON_CHEST + Vector((-0.07 * sign, -(0.03 if sign > 0 else 0.0), 0.045)))
        down = pose.carried(rig, "spine_03", Vector((0.3 * sign, -0.2, 0.3))) - pose.carried(rig, "spine_03", Vector((0.3 * sign, 0.0, 0.5)))
        # Each hand lies toward the far shoulder, along the chest.
        inward = pose.root_of(rig, "upperarm_" + ("r" if side == "l" else "l")) - across
        pose.limb(rig, "upperarm_" + side, "lowerarm_" + side, across, down)
        pose.point(rig, "hand_" + side, inward)
        pose.ease_hand(rig, side, sign)
    things["mat"].location = rig.matrix_world @ Vector((0.0, hips.y + 0.2, pose.floor(rig) + 0.01))


# --- What they are done on --------------------------------------------------------------------------------------


def box(name, size, material):
    bpy.ops.mesh.primitive_cube_add(size=1.0)
    thing = bpy.context.active_object
    thing.name = name
    thing.scale = size
    thing.data.materials.append(material)
    bevel = thing.modifiers.new("Soft", "BEVEL")
    bevel.width = 0.015
    bevel.segments = 3
    return thing


def rod(name, radius, length, material, parent=None, at=0.0, along="x"):
    """A cylinder lying along x (or [along]), [at] along it from its parent's middle."""
    turn = {"x": (0.0, math.radians(90.0), 0.0), "y": (math.radians(90.0), 0.0, 0.0), "z": (0.0, 0.0, 0.0)}[along]
    bpy.ops.mesh.primitive_cylinder_add(vertices=64, radius=radius, depth=length, location=(at, 0.0, 0.0) if parent is not None else (0.0, 0.0, 0.0), rotation=turn)
    thing = bpy.context.active_object
    thing.name = name
    thing["rod"] = (radius, length)
    bpy.ops.object.shade_smooth()
    thing.data.materials.append(material)
    if parent is not None:
        thing.parent = parent
    return thing


def carrier(name):
    thing = bpy.data.objects.new(name, None)
    bpy.context.scene.collection.objects.link(thing)
    return thing


def barbell(materials, length=1.72, plates=True):
    """A bar lying along x, with plates at each end. (Shorter than a real one: at full length the lifter is a small thing in the middle of the picture.)"""
    whole = carrier("Barbell")
    rod("Bar", 0.014, length, materials["steel"], whole)
    if plates:
        for at, radius, width in ((0.72, 0.225, 0.045), (-0.72, 0.225, 0.045), (0.775, 0.19, 0.035), (-0.775, 0.19, 0.035)):
            rod("Plate", radius, width, materials["steel"], whole, at)
    return whole


# A dumbbell's head: how thick, how long, and how far along the handle from its middle.
DUMBBELL_HEAD = (0.05, 0.055, 0.09)


def dumbbell(materials):
    """A dumbbell, its handle along its own x; `carry` lays it in a hand."""
    whole = carrier("Dumbbell")
    rod("Dumbbell", 0.014, 0.16, materials["steel"], whole, 0.0)
    for at in (DUMBBELL_HEAD[2], -DUMBBELL_HEAD[2]):
        rod("Dumbbell", DUMBBELL_HEAD[0], DUMBBELL_HEAD[1], materials["steel"], whole, at)
    return whole


def dumbbells(materials):
    return {"dumbbell_l": dumbbell(materials), "dumbbell_r": dumbbell(materials)}


def stretch(thing, start, end):
    """Lays a unit box out from one point to another, as a cable."""
    line = Vector(end) - Vector(start)
    thing.location = (Vector(start) + Vector(end)) / 2.0
    thing.rotation_euler = line.to_track_quat("Z", "Y").to_euler()
    thing.scale = (0.008, 0.008, line.length)


def bench(m, extra=None):
    """A bench's parts: a back pad, a seat, a post and a rail along the floor; and whatever else a lift adds."""
    def build(materials):
        things = {
            "back": box("Back", (0.34, 0.07, 0.98), materials["pad"]),
            "seat": box("Seat", (0.34, 0.38, 0.07), materials["pad"]),
            "post": box("Post", (0.09, 0.09, 0.4), materials["steel"]),
            "rail": box("Rail", (0.09, 1.2, 0.05), materials["steel"]),
        }
        if extra is not None:
            things.update(extra(materials))
        return things
    return build(m) if m is not None else build


def place_bench(rig, things, hips, lean):
    """Puts the bench under a lifter whose hips are at [hips] and whose trunk leans [lean] degrees."""
    world = rig.matrix_world
    up = pose.turned((0.0, 0.0, 1.0), "X", lean)
    behind = pose.turned((0.0, 1.0, 0.0), "X", lean)
    things["back"].location = world @ (hips + up * 0.42 + behind * 0.17)
    things["back"].rotation_euler = (math.radians(lean), 0.0, 0.0)
    things["seat"].location = world @ (hips + Vector((0.0, -0.13, -0.15)))
    under = hips + Vector((0.0, 0.1, -0.15))
    things["post"].location = world @ Vector((0.0, under.y, (under.z + pose.floor(rig)) / 2.0))
    things["post"].scale = (0.09, 0.09, max(under.z - pose.floor(rig), 0.02))
    things["rail"].location = world @ Vector((0.0, hips.y + 0.15, pose.floor(rig) + 0.03))


def handles(materials):
    """A machine's two handles, each a grip on a stem; `grasp` puts them in the hands."""
    things = {}
    for side in "lr":
        things["grip_" + side] = rod("Grip", HANDLE[0], HANDLE[1], materials["pad"], along="z")
        things["stem_" + side] = box("Stem", (0.028, 0.028, 1.0), materials["steel"])
    return things


def tower(materials):
    """A cable machine's parts: the upright, the arm over the lifter and the cable down from it."""
    return {
        "cable": box("Cable", (0.008, 0.008, 1.0), materials["steel"]),
        "tower": box("Tower", (0.1, 0.1, 2.34), materials["steel"]),
        "arm": box("Arm", (0.07, 0.9, 0.07), materials["steel"]),
    }


def place_tower(rig, things, at, handle, pulley):
    """Stands the cable machine [at] along y, its arm reaching to the pulley, the cable from there to the handle."""
    world = rig.matrix_world
    stretch(things["cable"], world @ handle, world @ pulley)
    things["tower"].location = world @ Vector((0.0, at, pose.floor(rig) + 1.17))
    things["arm"].location = world @ Vector((0.0, (at + pulley.y) / 2.0, pulley.z + 0.02))
    things["arm"].scale = (0.07, abs(at - pulley.y) + 0.1, 0.07)


def with_things(*builders):
    def build(materials):
        things = {}
        for builder in builders:
            things.update(builder(materials))
        return things
    return build


BENCH = bench(None)

# Each lift: how it moves, what it is done on, where it is seen from (the first camera is the one the app shows).
LIFTS = {
    "squat": (squat, lambda m: {"bar": barbell(m)}, {"front": ((-1.25, -4.1, 1.6), (0.0, 0.0, 0.8), 74.0), "back": ((1.9, 3.8, 1.35), (0.0, 0.0, 0.8), 74.0)}),
    "incline_press": (press(-48.0, 0.41, BAR_ON_CHEST, (0.8, 0.25, -1.0)), with_things(BENCH, lambda m: {"bar": barbell(m)}), {"front": ((-2.5, -3.4, 1.75), (0.0, 0.1, 0.95), 70.0)}),
    "bench_press": (press(-86.0, 0.36, BAR_ON_CHEST, (0.9, 0.0, -1.0), stiff=True), with_things(BENCH, lambda m: {"bar": barbell(m)}), {"front": ((-2.9, -2.9, 1.9), (0.0, 0.3, 0.7), 70.0)}),
    "overhead_press": (press(-8.0, 0.46, BAR_AT_COLLAR, (0.7, -0.45, -1.0), grip=0.32), with_things(BENCH, lambda m: {"bar": barbell(m)}), {"front": ((-1.5, -4.3, 1.7), (0.0, 0.0, 1.1), 62.0)}),
    "chest_fly": (fly(False), with_things(BENCH, lambda m: {"handle_l": rod("Handle", 0.016, 0.16, m["steel"], along="z"), "handle_r": rod("Handle", 0.016, 0.16, m["steel"], along="z")}), {"front": ((-1.6, -4.0, 1.6), (0.0, -0.1, 1.0), 66.0)}),
    "rear_delt_fly": (fly(True), with_things(BENCH, lambda m: {"handle_l": rod("Handle", 0.016, 0.16, m["steel"], along="z"), "handle_r": rod("Handle", 0.016, 0.16, m["steel"], along="z")}), {"back": ((1.7, 3.9, 1.75), (0.0, 0.0, 1.05), 66.0)}),
    "lat_pulldown": (lat_pulldown, with_things(tower, lambda m: {"bar": barbell(m, 1.2, plates=False), "seat": box("Seat", (0.36, 0.4, 0.07), m["pad"])}), {"back": ((1.5, 3.9, 1.6), (0.0, -0.1, 1.2), 62.0)}),
    "pull_up": (pull_up, lambda m: {"bar": barbell(m, 1.5, plates=False), "post_l": box("Post", (0.07, 0.07, 2.3), m["steel"]), "post_r": box("Post", (0.07, 0.07, 2.3), m["steel"])}, {"back": ((1.5, 4.4, 1.5), (0.0, 0.0, 1.35), 54.0), "side": ((3.4, -3.0, 1.5), (0.0, 0.0, 1.35), 54.0)}),
    "seated_row": (seated_row, lambda m: {"bar": barbell(m, 0.5, plates=False), "cable": box("Cable", (0.008, 0.008, 1.0), m["steel"]), "seat": box("Seat", (0.34, 0.7, 0.07), m["pad"]), "plate": box("Plate", (0.5, 0.05, 0.3), m["steel"]), "tower": box("Tower", (0.1, 0.1, 1.1), m["steel"])}, {"back": ((2.6, 2.9, 1.5), (0.0, -0.3, 0.7), 66.0)}),
    "lateral_raise": (lateral_raise, dumbbells, {"front": ((-1.0, -4.6, 1.55), (0.0, 0.0, 0.9), 66.0)}),
    "front_raise": (front_raise, dumbbells, {"front": ((-2.4, -3.8, 1.55), (0.0, -0.2, 0.9), 66.0)}),
    "shrug": (shrug, dumbbells, {"back": ((1.2, 4.3, 1.6), (0.0, 0.0, 0.95), 66.0)}),
    "biceps_curl": (curl, dumbbells, {"front": ((-1.6, -4.2, 1.5), (0.0, 0.0, 0.9), 66.0)}),
    "triceps_pushdown": (pushdown, with_things(tower, lambda m: {"bar": barbell(m, 0.5, plates=False)}), {"back": ((3.6, 1.5, 1.45), (0.0, -0.25, 1.05), 60.0)}),
    "dip": (dip, lambda m: {"rail_l": rod("Rail", 0.02, 0.7, m["steel"], along="y"), "rail_r": rod("Rail", 0.02, 0.7, m["steel"], along="y"), "post_l": box("Post", (0.05, 0.05, 1.12), m["steel"]), "post_r": box("Post", (0.05, 0.05, 1.12), m["steel"])}, {"back": ((2.3, 3.5, 1.6), (0.0, 0.0, 1.1), 60.0)}),
    "leg_press": (leg_press, with_things(BENCH, handles, lambda m: {"sled": box("Sled", (0.6, 0.05, 0.5), m["steel"])}), {"front": ((-4.0, -0.7, 1.35), (0.0, -0.1, 0.75), 60.0)}),
    "leg_extension": (leg_extension, with_things(BENCH, handles, lambda m: {"roller": rod("Roller", 0.05, 0.5, m["pad"])}), {"front": ((-2.2, -3.6, 1.45), (0.0, -0.3, 0.8), 66.0)}),
    "leg_curl": (leg_curl, with_things(handles, lambda m: {"pad": box("Pad", (0.4, 1.35, 0.08), m["pad"]), "post": box("Post", (0.09, 0.09, 0.4), m["steel"]), "rail": box("Rail", (0.09, 1.4, 0.05), m["steel"]), "roller": rod("Roller", 0.05, 0.5, m["pad"])}), {"back": ((2.9, 2.6, 1.9), (0.0, 0.1, 0.7), 66.0)}),
    "calf_raise": (calf_raise, dumbbells, {"back": ((2.0, 3.6, 0.95), (0.0, 0.0, 0.75), 66.0)}),
    "hip_hinge": (hinge, lambda m: {"bar": barbell(m)}, {"back": ((2.9, 2.9, 1.25), (0.0, -0.1, 0.8), 70.0)}),
    "hip_abduction": (hip_machine(False), with_things(BENCH, handles, lambda m: {"pad_l": box("Pad", (0.05, 0.16, 0.2), m["pad"]), "pad_r": box("Pad", (0.05, 0.16, 0.2), m["pad"])}), {"back": ((2.5, 3.3, 1.5), (0.0, -0.15, 0.8), 66.0)}),
    "hip_adduction": (hip_machine(True), with_things(BENCH, handles, lambda m: {"pad_l": box("Pad", (0.05, 0.16, 0.2), m["pad"]), "pad_r": box("Pad", (0.05, 0.16, 0.2), m["pad"])}), {"front": ((-0.9, -4.2, 1.55), (0.0, -0.2, 0.85), 66.0)}),
    "crunch": (crunch, lambda m: {"mat": box("Mat", (0.7, 1.9, 0.02), m["pad"])}, {"front": ((-3.1, -1.9, 1.35), (0.0, 0.3, 0.3), 70.0)}),
}


# Close views of a hand, for checking a grip: which hand, and where the camera stands from it.
CLOSE = {
    "hand": ("l", (0.5, -0.75, 0.3)), "hand_back": ("l", (0.6, 0.65, 0.3)), "hand_in": ("r", (0.75, -0.5, 0.2)),
    "hand_under": ("l", (0.45, -0.5, -0.55)), "hand_top": ("l", (0.3, -0.3, 0.8)), "hand_front": ("l", (0.0, -1.0, 0.05)),
}


def metal(name, color, roughness, metallic):
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    shader = material.node_tree.nodes["Principled BSDF"]
    shader.inputs["Base Color"].default_value = (*color, 1.0)
    shader.inputs["Metallic"].default_value = metallic
    shader.inputs["Roughness"].default_value = roughness
    return material


def progress(seconds):
    """How far through the rep the figure is, 0 to 1 and back, eased the way a lift is. The app's own clock does the same."""
    s = seconds % REP_SECONDS
    ease = lambda x: x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
    if s < 0.3:
        return 0.0
    if s < 1.3:
        return ease((s - 0.3) / 1.0)
    if s < 1.6:
        return 1.0
    if s < 3.0:
        return 1.0 - ease((s - 1.6) / 1.4)
    return 0.0


def thing_points(rig, piece):
    """Points all over a piece of equipment, in the armature's space: close enough together to hide what is behind it."""
    if "rod" in piece:
        return rod_points(rig, piece)
    to_rig = rig.matrix_world.inverted() @ piece.matrix_world
    points = []
    for polygon in piece.data.polygons:
        corners = [np.array(to_rig @ piece.data.vertices[index].co) for index in polygon.vertices]
        if len(corners) != 4:
            points += corners
            continue
        a, b, c, d = corners
        across = max(int(np.linalg.norm(b - a) / 0.015), 1)
        down = max(int(np.linalg.norm(d - a) / 0.015), 1)
        for u in np.linspace(0.0, 1.0, across + 1):
            for v in np.linspace(0.0, 1.0, down + 1):
                points.append((a * (1.0 - u) + b * u) * (1.0 - v) + (d * (1.0 - u) + c * u) * v)
    return np.array(points)


class Sight:
    """What the camera can see of the body in the pose it is in now: for telling which muscles a picture shows."""

    def __init__(self, rig, camera, things):
        self.rig, self.camera = rig, camera
        depsgraph = bpy.context.evaluated_depsgraph_get()
        self.through = np.array(camera.calc_matrix_camera(depsgraph, x=SIZE, y=SIZE) @ camera.matrix_world.inverted() @ rig.matrix_world)
        self.eye = np.array(rig.matrix_world.inverted() @ camera.matrix_world.translation)
        self.positions, self.normals = SKIN.posed()
        x, y, depth = self.seen_at(self.positions)
        self.body = self.nearest(x, y, depth, 256)
        pieces = [piece for thing in things.values() for piece in [thing] + list(thing.children_recursive) if piece.type == "MESH"]
        self.things = self.nearest(*self.seen_at(np.concatenate([thing_points(rig, piece) for piece in pieces])), 128) if pieces else None

    def seen_at(self, points):
        """Where points (in the armature's space) fall in the picture, across and up as shares of it, and how far from the camera."""
        flat = np.concatenate([points, np.ones((len(points), 1))], axis=1) @ self.through.T
        return flat[:, 0] / flat[:, 3] * 0.5 + 0.5, flat[:, 1] / flat[:, 3] * 0.5 + 0.5, flat[:, 3]

    @staticmethod
    def nearest(x, y, depth, cells):
        grid = np.full((cells, cells), np.inf)
        inside = (x >= 0.0) & (x < 1.0) & (y >= 0.0) & (y < 1.0)
        np.minimum.at(grid, ((y[inside] * cells).astype(int), (x[inside] * cells).astype(int)), depth[inside])
        return grid

    def shows(self, which):
        """For each of those vertices: whether the camera sees it (it faces the camera, and nothing of the body or the equipment is in front of it); and where it is in the picture."""
        positions, normals = self.positions[which], self.normals[which]
        x, y, depth = self.seen_at(positions)
        inside = (x >= 0.0) & (x < 1.0) & (y >= 0.0) & (y < 1.0)
        to_eye = self.eye - positions
        seen = inside & (np.einsum("ij,ij->i", normals, to_eye) / np.linalg.norm(to_eye, axis=1) > 0.06)
        for grid in (self.body, self.things):
            if grid is not None:
                cells = len(grid)
                behind = grid[np.clip((y * cells).astype(int), 0, cells - 1), np.clip((x * cells).astype(int), 0, cells - 1)]
                seen &= depth <= behind + 0.03
        return seen, x, y


def label_points(head_fields, heads):
    """For each working head on each side, the vertices in the thick of it: where a label's line could end."""
    points = {}
    for (name, side), field in head_fields.items():
        if name in heads and field.max() > 0.0:
            thick = np.nonzero(field >= 0.5 * field.max())[0]
            points[(name, side)] = thick[::max(len(thick) // 500, 1)]
    return points


def labelled(points, head_fields, shown, roles):
    """
    Picks where each head's label points, from what the camera saw of it in every frame ([shown]:
    per frame, per head, `Sight.shows` of its `label_points`): one vertex for the whole rep, in
    the middle of the part of the muscle the pictures show, seen in as many frames as any. A head
    the pictures never show much of (the far side of the body) gets no label. Returns, for each head
    kept, its place in the picture in every frame.
    """
    places = {}
    for key, which in points.items():
        seen = np.array([frame[key][0] for frame in shown], dtype=float)
        field = head_fields[key][which]
        often = seen.mean(axis=0)
        # Kept if a fair part of it shows through the rep, or a good part of it at some moment of the rep; a prime
        # mover on less than the others, the lift being about it.
        ask = 0.5 if roles[key[0]] == muscles.PRIME else 1.0
        if (often * field).sum() < 0.2 * ask * field.sum() and (seen * field).sum(axis=1).max() < 0.35 * ask * field.sum():
            continue
        rest = SKIN.positions[which]
        middle = (rest * (often * field)[:, None]).sum(axis=0) / (often * field).sum()
        steady = np.nonzero(often >= 0.9 * often.max())[0]
        best = steady[np.argmin(np.linalg.norm(rest[steady] - middle, axis=1))]
        places[key] = [(float(frame[key][1][best]), float(frame[key][2][best])) for frame in shown]
    return places


def write_labels(out, name, points, head_fields, shown, left, top, side):
    """Writes `anchors.json`: for every frame, where each label points, as shares of the square the frames were cut down to."""
    places = labelled(points, head_fields, shown, muscles.WORK[name])
    placed = [
        [
            {"head": head, "side": which, "role": muscles.WORK[name][head],
             "x": round((at[frame][0] * SIZE - left) / side, 3), "y": round(((1.0 - at[frame][1]) * SIZE - top) / side, 3)}
            for (head, which), at in places.items()
        ]
        for frame in range(KEY_FRAMES)
    ]
    with open(os.path.join(out, "anchors.json"), "w") as file:
        json.dump(placed, file, separators=(",", ":"))


def cut_of(out):
    """The square a rendered lift's frames were cut down to (left, top, side, in the render's pixels): as it was noted, or else read back off the first frame, whole and cut."""
    noted = os.path.join(out, "cut.json")
    if os.path.isfile(noted):
        with open(noted) as file:
            return json.load(file)

    def box(picture):
        rows, columns = np.nonzero(picture[..., 3] > 0.5)
        return columns.min(), columns.max(), rows.min()

    whole = box(pictures.load(os.path.join(out, "body-00.png")))
    sheet = pictures.load(os.path.join(out, "body.webp"))
    frame = len(sheet) // SHEET_COLUMNS
    cut = box(sheet[:frame, :frame])
    scale = (whole[1] - whole[0]) / (cut[1] - cut[0])
    return [float(whole[0] - cut[0] * scale), float(whole[2] - cut[2] * scale), float(frame * scale)]


def halved(picture):
    """A picture at half its size, each point the mean of four."""
    return (picture[0::2, 0::2] + picture[1::2, 0::2] + picture[0::2, 1::2] + picture[1::2, 1::2]) / 4.0


def main():
    arguments = sys.argv[sys.argv.index("--") + 1:]
    name, view, out = arguments[0], arguments[1], arguments[2]
    # A fourth argument: one frame to render alone as a still, or `labels` to work the labels' places out again
    # for a lift that is already rendered, without rendering it again.
    relabel = len(arguments) > 3 and arguments[3] == "labels"
    only = int(arguments[3]) if len(arguments) > 3 and not relabel else None
    move, build, cameras = LIFTS[name]
    os.makedirs(out, exist_ok=True)

    figure.clear_scene()
    body, rig = figure.build_figure()
    global SKIN
    skin = SKIN = muscles.Skin(body, rig)
    head_fields = muscles.fields(skin)
    if not figure.is_sculpted(body):
        muscles.sculpt(skin, head_fields)
    spots(skin)
    muscles.paint_roles(body, head_fields, name)
    points = label_points(head_fields, muscles.WORK[name])
    figure.studio()
    figure.render_settings(SIZE, SIZE)
    stone = body.data.materials[0]
    materials = {"steel": metal("Steel", (0.2, 0.21, 0.24), 0.38, 1.0), "pad": metal("Pad", (0.03, 0.032, 0.04), 0.7, 0.0)}
    paint = pictures.emission("Muscle", attribute="muscle")
    black = pictures.emission("Black", color=(0.0, 0.0, 0.0))
    things = build(materials)
    if view in CLOSE:
        # A close look at one hand, to check a grip by.
        move(rig, (only or 0) / (KEY_FRAMES - 1), things)
        bpy.context.view_layer.update()
        side, off = CLOSE[view]
        target = rig.matrix_world @ rig.pose.bones["hand_" + side].tail
        location, lens = target + Vector(off) * 1.5, 80.0
    else:
        location, target, lens = cameras[view]
    camera = figure.camera(location, target, lens)
    kept = {}
    for thing in things.values():
        for piece in [thing] + list(thing.children_recursive):
            if piece.type == "MESH":
                kept[piece] = piece.data.materials[0]

    scene = bpy.context.scene
    shown = []
    for frame in ([only] if only is not None else range(KEY_FRAMES)):
        t = frame / (KEY_FRAMES - 1)
        move(rig, t, things)
        bpy.context.view_layer.update()
        sight = Sight(rig, camera, things)
        shown.append({key: sight.shows(which) for key, which in points.items()})
        if relabel:
            continue
        body.data.materials[0] = stone
        for piece, own in kept.items():
            piece.data.materials[0] = own
        scene.cycles.samples = 48
        scene.cycles.use_denoising = True
        figure.render_to(os.path.join(out, "body-%02d.png" % frame))
        body.data.materials[0] = paint
        for piece in kept:
            piece.data.materials[0] = black
        scene.cycles.samples = 12
        scene.cycles.use_denoising = False
        figure.render_to(os.path.join(out, "muscle-%02d.png" % frame))

    if only is not None:
        still = pictures.glowing(pictures.load(os.path.join(out, "body-%02d.png" % only)), pictures.load(os.path.join(out, "muscle-%02d.png" % only)), only / (KEY_FRAMES - 1))
        # Where the labels would point, marked on the still to check them by: white for a prime mover, grey a synergist.
        for (head, side), at in labelled(points, head_fields, shown, muscles.WORK[name]).items():
            role = muscles.WORK[name][head]
            print("LABEL %-22s %s %-10s %.3f %.3f" % (head, side, role, at[0][0], 1.0 - at[0][1]))
            if role != muscles.STABILIZER:
                x, y = int(at[0][0] * SIZE), int((1.0 - at[0][1]) * SIZE)
                still[max(y - 5, 0):y + 6, max(x - 5, 0):x + 6] = 0.0
                still[max(y - 3, 0):y + 4, max(x - 3, 0):x + 4] = 1.0 if role == muscles.PRIME else 0.55
        pictures.save(halved(still), os.path.join(out, "still-%02d.webp" % only))
        return

    if relabel:
        write_labels(out, name, points, head_fields, shown, *cut_of(out))
        return

    # The frames are cut down to the square the lifter and the equipment move within over the whole rep, so that the
    # figure fills what the app is given of it, and brought to the size the app shows.
    whole = [pictures.load(os.path.join(out, "body-%02d.png" % frame)) for frame in range(KEY_FRAMES)]
    left, top, side_ = pictures.square_around(whole)
    with open(os.path.join(out, "cut.json"), "w") as file:
        json.dump([left, top, side_], file)
    bodies = [pictures.resized(pictures.cut(picture, left, top, side_), FRAME) for picture in whole]
    del whole
    paints = [pictures.resized(pictures.cut(pictures.load(os.path.join(out, "muscle-%02d.png" % frame)), left, top, side_), FRAME) for frame in range(KEY_FRAMES)]
    write_labels(out, name, points, head_fields, shown, left, top, side_)
    # What the app ships: the frames as two sheets (the muscles at half the body's size: they are soft-edged), and a badge.
    pictures.save(pictures.sheet(bodies, SHEET_COLUMNS), os.path.join(out, "body.webp"), quality=82)
    pictures.save(pictures.sheet([halved(p)[..., :3] for p in paints], SHEET_COLUMNS), os.path.join(out, "muscle.webp"), quality=80)
    badge = pictures.glowing(bodies[-1], paints[-1], 1.0)
    pictures.save(halved(badge), os.path.join(out, "badge.webp"), quality=84)
    frames = []
    for index in range(PREVIEW_FRAMES):
        effort = progress(index * REP_SECONDS / PREVIEW_FRAMES)
        key = int(round(effort * (KEY_FRAMES - 1)))
        frames.append(halved(pictures.glowing(bodies[key], paints[key], effort)))
    pictures.save(pictures.sheet(frames, 8), os.path.join(out, "preview.webp"), quality=84)
    for frame in range(KEY_FRAMES):
        if frame not in (0, KEY_FRAMES // 2, KEY_FRAMES - 1):
            os.remove(os.path.join(out, "body-%02d.png" % frame))
            os.remove(os.path.join(out, "muscle-%02d.png" % frame))


if __name__ == "__main__":
    main()
