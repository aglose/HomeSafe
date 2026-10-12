"""
Posing the figure the way a body is held: by where its hips are, what its hands and feet are
on, and its weight kept over its feet.

A pose is not a list of joint angles. A lift says where the hips are and how far the trunk
leans, what the feet stand on and what the hands hold; the knees and elbows are found from that
(`limb`), and where the lift leaves it open, the hips are shifted until the weight of the body
and of what it carries is over the middle of the feet (`balanced`, `centre_of_mass`), as a
lifter's are.

Everything is in the armature's own space: z up, the figure facing -y, its left side at +x.
"""

import math

import bpy
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree


def rest(rig):
    """Back to the pose the figure was made in."""
    for bone in rig.pose.bones:
        bone.matrix_basis = Matrix.Identity(4)
    bpy.context.view_layer.update()


def inherited(pose_bone):
    """Where a bone would lie with no turn of its own, given where its parent now is."""
    bone = pose_bone.bone
    if pose_bone.parent is None:
        return bone.matrix_local.copy()
    return pose_bone.parent.matrix @ pose_bone.parent.bone.matrix_local.inverted() @ bone.matrix_local


def point(rig, name, direction, location=None):
    """Turns a bone to lie along [direction] by the shortest way from where it would lie, and moves its root to [location] if given."""
    pose_bone = rig.pose.bones[name]
    base = inherited(pose_bone)
    along = base.to_3x3() @ Vector((0.0, 1.0, 0.0))
    turn = along.rotation_difference(Vector(direction).normalized()).to_matrix().to_4x4()
    origin = Vector(location) if location is not None else base.translation
    pose_bone.matrix = Matrix.Translation(origin) @ turn @ base.to_3x3().to_4x4()
    bpy.context.view_layer.update()


def rest_direction(rig, name):
    bone = rig.data.bones[name]
    return (bone.tail_local - bone.head_local).normalized()


def root_of(rig, name):
    """Where a bone's root is now, before the bone itself is turned."""
    return inherited(rig.pose.bones[name]).translation.copy()


def carried(rig, name, rest_point):
    """A point given in the rest pose, where the bone it is fixed to has carried it."""
    pose_bone = rig.pose.bones[name]
    return pose_bone.matrix @ pose_bone.bone.matrix_local.inverted() @ Vector(rest_point)


def fold(root, target, first, second, pole):
    """The middle joint of a two-bone limb from [root] reaching for [target], bending toward [pole]; and where its end gets to."""
    axis = Vector(target) - Vector(root)
    distance = min(max(axis.length, abs(first - second) + 1e-4), first + second - 1e-4)
    axis.normalize()
    along = (first * first - second * second + distance * distance) / (2.0 * distance)
    out = math.sqrt(max(first * first - along * along, 0.0))
    side = Vector(pole) - axis * Vector(pole).dot(axis)
    side.normalize()
    return Vector(root) + axis * along + side * out, Vector(root) + axis * distance


def limb(rig, upper, lower, target, pole):
    """Bends a leg or an arm so that its end is at [target]; returns where the end got to."""
    start = root_of(rig, upper)
    joint, end = fold(start, target, rig.data.bones[upper].length, rig.data.bones[lower].length, pole)
    point(rig, upper, joint - start)
    point(rig, lower, end - joint)
    hinge(rig, upper, lower, pole)
    return end


def front_at_rest(rig, upper):
    """The side of an upper arm or a thigh that faces forward in the rest pose: the biceps', the kneecap's (the knees of a body standing at ease are turned out a little, with the feet)."""
    bone = rig.data.bones[upper]
    along = (bone.tail_local - bone.head_local).normalized()
    forward = Vector((0.0, -1.0, 0.0))
    if upper.startswith("thigh"):
        forward = turned(forward, "Z", 8.0 if bone.head_local.x > 0.0 else -8.0)
    return (forward - along * forward.dot(along)).normalized()


def roll(rig, name, reference, toward):
    """Turns a bone about its own length until the direction that was [reference] in the rest pose faces [toward], as nearly as it can."""
    pose_bone = rig.pose.bones[name]
    along = (pose_bone.matrix.to_3x3() @ Vector((0.0, 1.0, 0.0))).normalized()
    now = pose_bone.matrix.to_3x3() @ pose_bone.bone.matrix_local.to_3x3().inverted() @ Vector(reference)
    now = now - along * now.dot(along)
    want = Vector(toward) - along * Vector(toward).dot(along)
    if now.length < 1e-4 or want.length < 1e-4:
        return
    angle = now.angle(want)
    if now.cross(want).dot(along) < 0.0:
        angle = -angle
    pose_bone.matrix = Matrix.Translation(pose_bone.matrix.translation) @ Matrix.Rotation(angle, 4, along) @ pose_bone.matrix.to_3x3().to_4x4()
    bpy.context.view_layer.update()


def hinge(rig, upper, lower, pole=None):
    """
    Turns an upper arm or a thigh about its own length to where its joint's hinge has to be for the
    bend the limb now makes: an elbow folds the forearm toward the biceps, a knee folds the shin
    away from the kneecap. Without it the bone keeps whatever turn the shortest way from the rest
    pose gave it, and the muscles painted on its skin face the wrong way. A limb held straight
    takes its turn from [pole], where the elbow or the knee points.
    """
    posed = rig.pose.bones
    along = (posed[upper].matrix.to_3x3() @ Vector((0.0, 1.0, 0.0))).normalized()
    held = (posed[lower].matrix.to_3x3() @ Vector((0.0, 1.0, 0.0))).normalized()
    folds = 1.0 if upper.startswith("upperarm") else -1.0
    bend = held - along * held.dot(along)
    share = min(max((bend.length - 0.09) / 0.3, 0.0), 1.0)
    front = bend.normalized() * folds * share if bend.length > 1e-4 else Vector()
    if pole is not None and share < 1.0:
        out = Vector(pole) - along * Vector(pole).dot(along)
        if out.length > 1e-4:
            front = front - out.normalized() * folds * (1.0 - share)
    if front.length < 1e-3:
        return
    roll(rig, upper, front_at_rest(rig, upper), front)
    point(rig, lower, held)


def level(rig, name, up=(0.0, 0.0, 1.0)):
    """Turns a foot about its own length so that its top faces [up]: the sole flat to whatever it stands on."""
    roll(rig, name, (0.0, 0.0, 1.0), up)


def turned(vector, axis, angle_degrees):
    return Matrix.Rotation(math.radians(angle_degrees), 3, axis) @ Vector(vector)


# The hand, measured off the sculpt: half the thickness of each finger, how far the palm's skin stands off the hand's
# bone where a bar lies in it, how far along the hand from the wrist that is, and how far to the thumb's side of the
# hand's bone the middle of a closed fist is.
FINGER_RADIUS = {"thumb": 0.0108, "index": 0.0083, "middle": 0.0085, "ring": 0.008, "pinky": 0.0069}
PALM = 0.016
GRIP_ALONG = 0.09
GRIP_ACROSS = -0.014
# How far each joint of a finger, and of the thumb past its first, can fold.
FINGER_FOLDS = (95.0, 110.0, 80.0)
THUMB_FOLDS = (70.0, 80.0)


def hand_frame(sign, direction, toward):
    """A hand's three lines when it lies along [direction] with its palm facing [toward]: along it, across the knuckles from the index finger to the little one, and out of the palm."""
    along = Vector(direction).normalized()
    palm = Vector(toward)
    palm = (palm - along * palm.dot(along)).normalized()
    return along, -sign * palm.cross(along), palm


def rest_hand_frame(rig, side, sign):
    bones = rig.data.bones
    hand = bones["hand_" + side]
    along = (hand.tail_local - hand.head_local).normalized()
    across = bones["pinky_01_" + side].head_local - bones["index_01_" + side].head_local
    across = (across - along * across.dot(along)).normalized()
    return along, across, -sign * along.cross(across)


def grip_reach(sign, direction, toward, radius=0.014):
    """From the wrist to the middle of a bar of that [radius] held in the hand (`grip`): where a wrist has to be for a bar, or a thing for a hand."""
    along, across, palm = hand_frame(sign, direction, toward)
    return along * GRIP_ALONG + palm * (PALM + radius) + across * GRIP_ACROSS


def lay_hand(rig, side, sign, direction, toward):
    """Turns the whole hand to lie along [direction], palm facing [toward]. Returns its three lines (`hand_frame`)."""
    frame = hand_frame(sign, direction, toward)
    was = rest_hand_frame(rig, side, sign)
    turn = Matrix((frame[0], frame[1], frame[2])).transposed() @ Matrix((was[0], was[1], was[2]))
    hand = rig.pose.bones["hand_" + side]
    hand.matrix = Matrix.Translation(inherited(hand).translation) @ (turn @ hand.bone.matrix_local.to_3x3()).to_4x4()
    bpy.context.view_layer.update()
    turn_forearm(rig, side)
    return frame


def turn_forearm(rig, side):
    """
    Carries the hand's turn (palm up, palm down) back up the forearm, as the two bones of a real
    forearm do: the bones down its length (`lowerarm_twist_…`, if the armature has them) turn about
    it by a third, two thirds and all of what the hand is turned about it. Without them the whole
    turn is taken in the skin at the wrist.
    """
    posed = rig.pose.bones
    names = [name for name in ("lowerarm_twist_01_" + side, "lowerarm_twist_02_" + side, "lowerarm_twist_03_" + side) if name in posed]
    if not names:
        return
    hand = posed["hand_" + side]
    along = (posed["lowerarm_" + side].matrix.to_3x3() @ Vector((0.0, 1.0, 0.0))).normalized()
    turned_by = (hand.matrix.to_3x3() @ inherited(hand).to_3x3().inverted()).to_quaternion()
    angle = 2.0 * math.atan2(Vector((turned_by.x, turned_by.y, turned_by.z)).dot(along), turned_by.w)
    angle = (angle + math.pi) % (2.0 * math.pi) - math.pi
    angle = min(max(angle, math.radians(-150.0)), math.radians(150.0))
    for step, name in enumerate(names, start=1):
        base = inherited(posed[name])
        posed[name].matrix = Matrix.Translation(base.translation) @ Matrix.Rotation(angle * step / len(names), 4, along) @ base.to_3x3().to_4x4()
    bpy.context.view_layer.update()


def knuckles(rig, side):
    """The line of a hand's knuckles as it lies now, from the index finger to the little one: the way a bar in the hand runs."""
    posed = rig.pose.bones
    hand = posed["hand_" + side]
    along = (hand.matrix.to_3x3() @ Vector((0.0, 1.0, 0.0))).normalized()
    across = inherited(posed["pinky_01_" + side]).translation - inherited(posed["index_01_" + side]).translation
    return (across - along * across.dot(along)).normalized()


def to_segment(point, start, end):
    """How far a point is from a stretch of line, in the flat."""
    line = (end[0] - start[0], end[1] - start[1])
    span = line[0] * line[0] + line[1] * line[1]
    share = min(max(((point[0] - start[0]) * line[0] + (point[1] - start[1]) * line[1]) / span, 0.0), 1.0) if span > 0.0 else 0.0
    return math.hypot(point[0] - start[0] - line[0] * share, point[1] - start[1] - line[1] * share)


def wrap(joint, lengths, start, folds, way, clear):
    """
    Folds a chain of bones round a bar, in the flat across the bar (the bar's middle at the origin):
    each bone in turn swings on from the last one's line, the [way] it folds, until it lies against
    the bar ([clear] from its middle) or its joint will fold no further. Returns each bone's angle.
    """
    angles, angle = [], start
    for length, most in zip(lengths, folds):
        swung = 0.0
        while swung < most:
            end = (joint[0] + length * math.cos(math.radians(angle)), joint[1] + length * math.sin(math.radians(angle)))
            if to_segment((0.0, 0.0), joint, end) <= clear:
                break
            angle += way
            swung += 1.0
        joint = (joint[0] + length * math.cos(math.radians(angle)), joint[1] + length * math.sin(math.radians(angle)))
        angles.append(angle)
    return angles


def grip(rig, side, sign, direction, toward, radius=0.014):
    """
    Closes a hand on a bar, once the arm has brought the wrist to it (`grip_reach` short of it): the
    hand lies along [direction] with its palm facing [toward], the bar across the palm along the
    line of the knuckles, and each finger folds round the bar until it lies against it, the thumb
    round the other way beside the index finger. Returns the middle of the bar under the hand.
    """
    posed = rig.pose.bones
    lengths = lambda digit: [rig.data.bones["%s_%02d_%s" % (digit, n, side)].length for n in (1, 2, 3)]
    along, across, palm = lay_hand(rig, side, sign, direction, toward)
    wrist = posed["hand_" + side].matrix.translation.copy()
    bar = wrist + along * GRIP_ALONG + palm * (PALM + radius)
    flat = lambda point: ((point - bar).dot(along), (point - bar).dot(palm))
    line = lambda angle: along * math.cos(math.radians(angle)) + palm * math.sin(math.radians(angle))

    for digit in ("index", "middle", "ring", "pinky"):
        knuckle = inherited(posed[digit + "_01_" + side]).translation
        angles = wrap(flat(knuckle), lengths(digit), -10.0, FINGER_FOLDS, 1.0, radius + FINGER_RADIUS[digit])
        for number, angle in enumerate(angles, start=1):
            point(rig, "%s_%02d_%s" % (digit, number, side), line(angle))

    if "thumb_01_" + side in posed:
        # The thumb's first bone swings out of the palm and across to beside the index finger, far enough to pass
        # the bar on the wrist's side of it; the rest of the thumb folds over the bar toward the fingers.
        clear = radius + FINGER_RADIUS["thumb"]
        root = inherited(posed["thumb_01_" + side]).translation
        first, second, third = lengths("thumb")
        beside = (inherited(posed["index_01_" + side]).translation - wrist).dot(across) - FINGER_RADIUS["index"] - FINGER_RADIUS["thumb"]
        over = min(max(beside - (root - wrist).dot(across), -0.8 * first), 0.8 * first)
        reach = math.sqrt(first * first - over * over)
        start = flat(root)
        swing = 0.0
        while swing < 110.0 and to_segment((0.0, 0.0), start, (start[0] + reach * math.cos(math.radians(swing)), start[1] + reach * math.sin(math.radians(swing)))) < clear + 0.002:
            swing += 1.0
        point(rig, "thumb_01_" + side, line(swing) * reach + across * over)
        knuckle = (start[0] + reach * math.cos(math.radians(swing)), start[1] + reach * math.sin(math.radians(swing)))
        angles = wrap(knuckle, (second, third), swing, THUMB_FOLDS, -1.0, clear)
        for number, angle in zip((2, 3), angles):
            point(rig, "thumb_%02d_%s" % (number, side), line(angle))
    return bar + across * GRIP_ACROSS


def ease_hand(rig, side, sign, folds=(18.0, 26.0, 16.0)):
    """Lets a free hand's fingers fall together and a little in toward the palm, wherever the hand has been turned to: a hand at ease, not spread flat."""
    posed = rig.pose.bones
    turn = posed["hand_" + side].matrix.to_3x3() @ posed["hand_" + side].bone.matrix_local.to_3x3().inverted()
    along, across, palm = (turn @ line for line in rest_hand_frame(rig, side, sign))
    for digit in ("index", "middle", "ring", "pinky"):
        angle = 0.0
        for number, fold in enumerate(folds, start=1):
            angle += fold
            point(rig, "%s_%02d_%s" % (digit, number, side), along * math.cos(math.radians(angle)) + palm * math.sin(math.radians(angle)))


# What each part of the body weighs, as a share of the whole, and how far along the bone its centre lies
# (Winter, Biomechanics and Motor Control of Human Movement, table of segment masses; the trunk split over its bones).
MASSES = [
    ("pelvis", 0.142, 0.5), ("spine_01", 0.07, 0.5), ("spine_02", 0.07, 0.5), ("spine_03", 0.216, 0.5), ("neck_01", 0.02, 0.5), ("head", 0.061, 0.5),
] + [
    (name + side, share, along)
    for side in ("l", "r")
    for name, share, along in (("upperarm_", 0.028, 0.436), ("lowerarm_", 0.016, 0.43), ("hand_", 0.006, 0.5), ("thigh_", 0.1, 0.433), ("calf_", 0.0465, 0.433), ("foot_", 0.0145, 0.5))
]


def centre_of_mass(rig, loads=()):
    """Where the body's weight is, as it is posed, with what it carries: [loads] are (weight as a share of the body's, where)."""
    total = Vector((0.0, 0.0, 0.0))
    weight = 0.0
    for name, share, along in MASSES:
        bone = rig.pose.bones[name]
        total += bone.head.lerp(bone.tail, along) * share
        weight += share
    for share, where in loads:
        total += Vector(where) * share
        weight += share
    return total / weight


def balanced(place, over, tries=8):
    """
    Finds how far back the hips must be for the body to stand. [place] poses the body with its
    hips shifted so far back and returns what it then carries (as `centre_of_mass` takes it);
    the shift is corrected until the weight of it all is over the line across the feet at y = [over].
    """
    shift = 0.0
    for _ in range(tries):
        rig, loads = place(shift)
        error = centre_of_mass(rig, loads).y - over
        if abs(error) < 0.003:
            break
        shift -= error * 1.15
    return shift


def floor(rig):
    """The floor's height in the armature's space."""
    return -rig.location.z


def ankle_height(rig):
    """How far above the floor an ankle is when the foot is flat on it."""
    return rig.data.bones["calf_l"].tail_local.z - floor(rig)


def mid_foot(rig):
    """The line across the middle of the feet as they stand in the rest pose: where a standing body's weight must be."""
    bones = rig.data.bones
    return (bones["calf_l"].tail_local.y + bones["ball_l"].tail_local.y) / 2.0 + 0.02
