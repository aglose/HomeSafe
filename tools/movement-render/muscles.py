"""
The superficial muscles, head by head, on the figure's skin.

Each head is one or more strands running from its origin to its insertion, with how far its
belly reaches to either side of them. A strand's points are given the way an anatomy text
places things: so far up the trunk and so far round it, or so far down a limb and so far round
that (0 degrees the front, 90 the outer side, 180 the back, 270 the inner side), and the skin
is found by looking outward from the bone. The same heads, by the same names, as the app's own
`HeadId`.

From them: a field over the skin for every head (how much of that head each vertex is), which
sculpts the body (every belly raised a little, so the borders between heads fall into grooves),
paints the roles a lift gives them, and says where each head's middle is for a label.
"""

import colorsys
import math

import numpy as np
from mathutils import Vector
from mathutils.bvhtree import BVHTree

PRIME, SYNERGIST, STABILIZER = "prime", "synergist", "stabilizer"

# name: (how far the belly reaches from a strand, in metres; strands). Points, in the rig's rest pose (x toward the
# figure's own side, -y the front, z up from the hips): ("front", x, z) and ("back", x, z), the skin as it is seen
# from in front or from behind at that place, which is how a trunk's muscles are read off an anatomy plate;
# ("trunk", z, degrees round), for the flanks; ("<bone>_", share of the way down the bone, degrees round), for a
# limb; or ("at", x, y, z) for a spot named outright.
#
# The body's own landmarks, for placing them: the pit of the neck at z 0.585 and the collarbone out from it to the
# point of the shoulder at x 0.24; the nipple at x 0.10, z 0.44 and the chest's lower edge at 0.40; the bottom of the
# ribs at 0.33, the navel at 0.25, the hip bone's crest at 0.21; the shoulder blade from x 0.07 to 0.21 and z 0.43
# to 0.56; the nape at 0.62 and the base of the skull at 0.72.
HEADS = {
    "STERNOCLEIDOMASTOID": (0.014, [[("neck_01", 0.9, 115), ("neck_01", 0.45, 60), ("front", 0.025, 0.592)]]),
    # A kite over the upper back: down the neck and out to the point of the shoulder, straight across to the
    # shoulder blade's spine, and up to it from the last of the ribs.
    "TRAPEZIUS_UPPER": (0.03, [
        [("back", 0.025, 0.72), ("back", 0.05, 0.655), ("back", 0.13, 0.605), ("back", 0.22, 0.582)],
        [("back", 0.02, 0.665), ("back", 0.08, 0.62), ("back", 0.2, 0.578)],
        [("at", 0.055, 0.01, 0.665), ("at", 0.12, 0.0, 0.63), ("at", 0.2, -0.01, 0.6)],
    ]),
    "TRAPEZIUS_MIDDLE": (0.026, [
        [("back", 0.018, 0.6), ("back", 0.1, 0.588), ("back", 0.205, 0.572)],
        [("back", 0.018, 0.56), ("back", 0.1, 0.558), ("back", 0.19, 0.562)],
    ]),
    "TRAPEZIUS_LOWER": (0.024, [
        [("back", 0.018, 0.51), ("back", 0.06, 0.528), ("back", 0.115, 0.548)],
        [("back", 0.016, 0.44), ("back", 0.045, 0.485), ("back", 0.1, 0.538)],
        [("back", 0.014, 0.375), ("back", 0.03, 0.43), ("back", 0.075, 0.51)],
    ]),
    # Three heads from the collarbone, the point of the shoulder and the shoulder blade's spine, to one place half way down the arm.
    "DELTOID_ANTERIOR": (0.03, [
        [("front", 0.185, 0.568), ("upperarm_", 0.12, 20), ("upperarm_", 0.3, 40), ("upperarm_", 0.48, 70)],
        [("front", 0.225, 0.572), ("upperarm_", 0.1, 50), ("upperarm_", 0.3, 60), ("upperarm_", 0.48, 75)],
    ]),
    "DELTOID_LATERAL": (0.034, [[("upperarm_", -0.08, 90), ("upperarm_", 0.15, 90), ("upperarm_", 0.35, 90), ("upperarm_", 0.52, 85)]]),
    "DELTOID_POSTERIOR": (0.03, [
        [("back", 0.17, 0.562), ("upperarm_", 0.12, 160), ("upperarm_", 0.3, 140), ("upperarm_", 0.48, 105)],
        [("back", 0.225, 0.568), ("upperarm_", 0.1, 130), ("upperarm_", 0.3, 120), ("upperarm_", 0.48, 100)],
    ]),
    # A fan: from the inner half of the collarbone, and from the breastbone down to the cartilage of the lower
    # ribs, every fibre to the arm under the front of the shoulder.
    "PECTORALIS_CLAVICULAR": (0.026, [
        [("front", 0.04, 0.556), ("front", 0.13, 0.54), ("front", 0.225, 0.508)],
        [("front", 0.11, 0.566), ("front", 0.17, 0.55), ("front", 0.23, 0.518)],
    ]),
    "PECTORALIS_STERNAL": (0.036, [
        [("front", 0.03, 0.515), ("front", 0.12, 0.508), ("front", 0.225, 0.498)],
        [("front", 0.03, 0.482), ("front", 0.12, 0.482), ("front", 0.225, 0.492)],
        [("front", 0.03, 0.448), ("front", 0.12, 0.455), ("front", 0.22, 0.486)],
        [("front", 0.035, 0.416), ("front", 0.12, 0.428), ("front", 0.215, 0.478)],
        [("front", 0.07, 0.402), ("front", 0.14, 0.414), ("front", 0.21, 0.47)],
    ]),
    "SERRATUS_ANTERIOR": (0.011, [
        [("trunk", 0.418, 80), ("trunk", 0.4, 56)],
        [("trunk", 0.39, 80), ("trunk", 0.372, 57)],
        [("trunk", 0.362, 78), ("trunk", 0.345, 58)],
    ]),
    # One sheet down each side of the midline; the tendons across it are cut into its field (CUTS).
    "RECTUS_ABDOMINIS": (0.04, [[("front", 0.045, 0.385), ("front", 0.045, 0.25), ("front", 0.035, 0.05)]]),
    "EXTERNAL_OBLIQUE": (0.036, [[("trunk", 0.34, 58), ("trunk", 0.27, 62), ("trunk", 0.2, 55)]]),
    # Its belly: from the level of the shoulder blade's lower corner down the flank, every fibre up and out to the
    # armpit. The sheet of tendon it rises from, over the small of the back, is left out.
    "LATISSIMUS_DORSI": (0.034, [
        [("back", 0.035, 0.415), ("back", 0.11, 0.422), ("back", 0.2, 0.465)],
        [("back", 0.04, 0.365), ("back", 0.12, 0.39), ("back", 0.205, 0.455)],
        [("back", 0.055, 0.31), ("back", 0.12, 0.355), ("back", 0.2, 0.44)],
        [("back", 0.07, 0.27), ("back", 0.1, 0.33), ("back", 0.16, 0.39), ("back", 0.2, 0.432)],
        [("trunk", 0.36, 84), ("trunk", 0.43, 88)],
    ]),
    "TERES_MAJOR": (0.02, [[("back", 0.1, 0.44), ("back", 0.16, 0.468), ("back", 0.215, 0.492)]]),
    "TERES_MINOR": (0.014, [[("back", 0.13, 0.478), ("back", 0.205, 0.51)]]),
    "INFRASPINATUS": (0.024, [
        [("back", 0.09, 0.522), ("back", 0.15, 0.528), ("back", 0.215, 0.538)],
        [("back", 0.09, 0.49), ("back", 0.15, 0.503), ("back", 0.21, 0.528)],
    ]),
    "ERECTOR_SPINAE": (0.022, [[("back", 0.035, 0.19), ("back", 0.035, 0.27), ("back", 0.035, 0.345)]]),
    "BICEPS_LONG": (0.02, [[("upperarm_", 0.3, 40), ("upperarm_", 0.6, 35), ("upperarm_", 0.9, 20)]]),
    "BICEPS_SHORT": (0.02, [[("upperarm_", 0.3, 350), ("upperarm_", 0.6, 345), ("upperarm_", 0.9, 0)]]),
    "BRACHIALIS": (0.014, [[("upperarm_", 0.6, 80), ("upperarm_", 0.95, 60)]]),
    "TRICEPS_LONG": (0.022, [[("upperarm_", 0.2, 215), ("upperarm_", 0.55, 205), ("upperarm_", 0.75, 190)]]),
    "TRICEPS_LATERAL": (0.02, [[("upperarm_", 0.3, 140), ("upperarm_", 0.6, 150), ("upperarm_", 0.75, 165)]]),
    "TRICEPS_MEDIAL": (0.014, [[("upperarm_", 0.7, 230), ("upperarm_", 0.92, 215)]]),
    "BRACHIORADIALIS": (0.016, [[("upperarm_", 0.85, 75), ("lowerarm_", 0.2, 60), ("lowerarm_", 0.6, 45)]]),
    "WRIST_FLEXORS": (0.016, [[("lowerarm_", 0.1, 330), ("lowerarm_", 0.6, 340)]]),
    "FLEXOR_CARPI_ULNARIS": (0.014, [[("lowerarm_", 0.1, 280), ("lowerarm_", 0.7, 270)]]),
    "EXTENSOR_CARPI_RADIALIS": (0.014, [[("lowerarm_", 0.1, 100), ("lowerarm_", 0.6, 95)]]),
    "EXTENSOR_DIGITORUM": (0.014, [[("lowerarm_", 0.15, 150), ("lowerarm_", 0.7, 150)]]),
    "EXTENSOR_CARPI_ULNARIS": (0.013, [[("lowerarm_", 0.15, 205), ("lowerarm_", 0.7, 210)]]),
    "GLUTEUS_MEDIUS": (0.04, [[("trunk", 0.205, 112), ("trunk", 0.155, 105), ("thigh_", 0.0, 95)]]),
    "GLUTEUS_MAXIMUS": (0.066, [
        [("back", 0.045, 0.165), ("thigh_", 0.02, 150), ("thigh_", 0.2, 125)],
        [("back", 0.04, 0.08), ("thigh_", 0.1, 178), ("thigh_", 0.26, 160)],
    ]),
    "TENSOR_FASCIAE_LATAE": (0.022, [[("trunk", 0.2, 62), ("thigh_", 0.1, 70), ("thigh_", 0.25, 80)]]),
    "SARTORIUS": (0.013, [[("trunk", 0.19, 50), ("thigh_", 0.3, 10), ("thigh_", 0.6, 320), ("thigh_", 0.95, 290)]]),
    "RECTUS_FEMORIS": (0.032, [[("thigh_", 0.1, 10), ("thigh_", 0.5, 5), ("thigh_", 0.82, 5)]]),
    "VASTUS_LATERALIS": (0.038, [[("thigh_", 0.25, 56), ("thigh_", 0.6, 53), ("thigh_", 0.88, 44)]]),
    "VASTUS_MEDIALIS": (0.032, [[("thigh_", 0.6, 325), ("thigh_", 0.9, 320)]]),
    "ADDUCTOR_LONGUS": (0.026, [[("thigh_", 0.08, 330), ("thigh_", 0.4, 310)]]),
    "ADDUCTOR_MAGNUS": (0.03, [[("thigh_", 0.3, 250), ("thigh_", 0.55, 255)]]),
    "GRACILIS": (0.014, [[("thigh_", 0.1, 285), ("thigh_", 0.6, 280), ("thigh_", 0.95, 275)]]),
    "BICEPS_FEMORIS_LONG": (0.03, [[("thigh_", 0.34, 165), ("thigh_", 0.6, 150), ("thigh_", 0.92, 125)]]),
    "BICEPS_FEMORIS_SHORT": (0.014, [[("thigh_", 0.6, 120), ("thigh_", 0.9, 115)]]),
    "SEMITENDINOSUS": (0.024, [[("thigh_", 0.36, 195), ("thigh_", 0.6, 205), ("thigh_", 0.92, 230)]]),
    "SEMIMEMBRANOSUS": (0.02, [[("thigh_", 0.5, 225), ("thigh_", 0.9, 240)]]),
    "TIBIALIS_ANTERIOR": (0.018, [[("calf_", 0.1, 25), ("calf_", 0.5, 20), ("calf_", 0.85, 5)]]),
    "EXTENSOR_DIGITORUM_LONGUS": (0.012, [[("calf_", 0.12, 55), ("calf_", 0.6, 50)]]),
    "PERONEUS_LONGUS": (0.014, [[("calf_", 0.1, 90), ("calf_", 0.6, 95)]]),
    "GASTROCNEMIUS_LATERAL": (0.028, [[("calf_", 0.05, 150), ("calf_", 0.3, 150), ("calf_", 0.45, 165)]]),
    "GASTROCNEMIUS_MEDIAL": (0.03, [[("calf_", 0.05, 215), ("calf_", 0.32, 210), ("calf_", 0.5, 195)]]),
    "SOLEUS": (0.018, [[("calf_", 0.4, 120), ("calf_", 0.7, 135)], [("calf_", 0.45, 235), ("calf_", 0.72, 220)]]),
}

# Which heads each lift works, by the roles kinesiology gives them. The same table as the app's `Movement.work()`.
WORK = {
    "squat": {
        "VASTUS_LATERALIS": PRIME, "VASTUS_MEDIALIS": PRIME, "GLUTEUS_MAXIMUS": PRIME,
        "RECTUS_FEMORIS": SYNERGIST, "ADDUCTOR_MAGNUS": SYNERGIST, "SOLEUS": SYNERGIST,
        "BICEPS_FEMORIS_LONG": STABILIZER, "SEMITENDINOSUS": STABILIZER, "GASTROCNEMIUS_LATERAL": STABILIZER,
        "GLUTEUS_MEDIUS": STABILIZER, "ERECTOR_SPINAE": STABILIZER, "RECTUS_ABDOMINIS": STABILIZER, "EXTERNAL_OBLIQUE": STABILIZER,
    },
    "incline_press": {
        "PECTORALIS_CLAVICULAR": PRIME, "DELTOID_ANTERIOR": PRIME,
        "PECTORALIS_STERNAL": SYNERGIST, "TRICEPS_LATERAL": SYNERGIST, "TRICEPS_MEDIAL": SYNERGIST, "TRICEPS_LONG": SYNERGIST,
        "SERRATUS_ANTERIOR": STABILIZER, "DELTOID_LATERAL": STABILIZER,
    },
    "lat_pulldown": {
        "LATISSIMUS_DORSI": PRIME, "TERES_MAJOR": PRIME,
        "DELTOID_POSTERIOR": SYNERGIST, "TRAPEZIUS_LOWER": SYNERGIST, "TRAPEZIUS_MIDDLE": SYNERGIST,
        "BRACHIORADIALIS": SYNERGIST, "BICEPS_LONG": SYNERGIST, "BICEPS_SHORT": SYNERGIST, "BRACHIALIS": SYNERGIST,
        "TRICEPS_LONG": STABILIZER, "INFRASPINATUS": STABILIZER, "TERES_MINOR": STABILIZER,
    },
    "lateral_raise": {
        "DELTOID_LATERAL": PRIME,
        "DELTOID_ANTERIOR": SYNERGIST, "TRAPEZIUS_UPPER": SYNERGIST, "SERRATUS_ANTERIOR": SYNERGIST,
        "BRACHIORADIALIS": STABILIZER,
    },
    "bench_press": {
        "PECTORALIS_STERNAL": PRIME,
        "PECTORALIS_CLAVICULAR": SYNERGIST, "DELTOID_ANTERIOR": SYNERGIST, "TRICEPS_LATERAL": SYNERGIST, "TRICEPS_MEDIAL": SYNERGIST, "TRICEPS_LONG": SYNERGIST,
        "SERRATUS_ANTERIOR": STABILIZER,
    },
    "overhead_press": {
        "DELTOID_ANTERIOR": PRIME, "DELTOID_LATERAL": PRIME,
        "TRICEPS_LATERAL": SYNERGIST, "TRICEPS_MEDIAL": SYNERGIST, "TRICEPS_LONG": SYNERGIST, "TRAPEZIUS_UPPER": SYNERGIST, "SERRATUS_ANTERIOR": SYNERGIST, "PECTORALIS_CLAVICULAR": SYNERGIST,
        "RECTUS_ABDOMINIS": STABILIZER, "EXTERNAL_OBLIQUE": STABILIZER,
    },
    "chest_fly": {
        "PECTORALIS_STERNAL": PRIME, "PECTORALIS_CLAVICULAR": PRIME,
        "DELTOID_ANTERIOR": SYNERGIST,
        "BICEPS_SHORT": STABILIZER, "SERRATUS_ANTERIOR": STABILIZER,
    },
    "rear_delt_fly": {
        "DELTOID_POSTERIOR": PRIME,
        "TRAPEZIUS_MIDDLE": SYNERGIST, "INFRASPINATUS": SYNERGIST, "TERES_MINOR": SYNERGIST, "TRAPEZIUS_LOWER": SYNERGIST,
        "TRICEPS_LONG": STABILIZER,
    },
    "front_raise": {
        "DELTOID_ANTERIOR": PRIME,
        "PECTORALIS_CLAVICULAR": SYNERGIST, "DELTOID_LATERAL": SYNERGIST, "TRAPEZIUS_UPPER": SYNERGIST, "SERRATUS_ANTERIOR": SYNERGIST,
    },
    "shrug": {
        "TRAPEZIUS_UPPER": PRIME,
        "TRAPEZIUS_MIDDLE": SYNERGIST,
        "WRIST_FLEXORS": STABILIZER, "FLEXOR_CARPI_ULNARIS": STABILIZER, "ERECTOR_SPINAE": STABILIZER,
    },
    "biceps_curl": {
        "BICEPS_LONG": PRIME, "BICEPS_SHORT": PRIME,
        "BRACHIALIS": SYNERGIST, "BRACHIORADIALIS": SYNERGIST,
        "DELTOID_ANTERIOR": STABILIZER, "WRIST_FLEXORS": STABILIZER,
    },
    "triceps_pushdown": {
        "TRICEPS_LATERAL": PRIME, "TRICEPS_MEDIAL": PRIME,
        "TRICEPS_LONG": SYNERGIST,
        "LATISSIMUS_DORSI": STABILIZER, "RECTUS_ABDOMINIS": STABILIZER, "EXTENSOR_CARPI_ULNARIS": STABILIZER,
    },
    "dip": {
        "TRICEPS_LATERAL": PRIME, "TRICEPS_MEDIAL": PRIME, "TRICEPS_LONG": PRIME, "PECTORALIS_STERNAL": PRIME,
        "DELTOID_ANTERIOR": SYNERGIST,
        "LATISSIMUS_DORSI": STABILIZER, "TRAPEZIUS_LOWER": STABILIZER,
    },
    "pull_up": {
        "LATISSIMUS_DORSI": PRIME, "TERES_MAJOR": PRIME,
        "DELTOID_POSTERIOR": SYNERGIST, "TRAPEZIUS_LOWER": SYNERGIST, "TRAPEZIUS_MIDDLE": SYNERGIST,
        "BRACHIORADIALIS": SYNERGIST, "BICEPS_LONG": SYNERGIST, "BICEPS_SHORT": SYNERGIST, "BRACHIALIS": SYNERGIST,
        "RECTUS_ABDOMINIS": STABILIZER, "INFRASPINATUS": STABILIZER, "TERES_MINOR": STABILIZER,
    },
    "seated_row": {
        "LATISSIMUS_DORSI": PRIME, "TRAPEZIUS_MIDDLE": PRIME,
        "TERES_MAJOR": SYNERGIST, "DELTOID_POSTERIOR": SYNERGIST, "TRAPEZIUS_LOWER": SYNERGIST,
        "BICEPS_LONG": SYNERGIST, "BICEPS_SHORT": SYNERGIST, "BRACHIALIS": SYNERGIST, "BRACHIORADIALIS": SYNERGIST,
        "ERECTOR_SPINAE": STABILIZER,
    },
    "leg_press": {
        "VASTUS_LATERALIS": PRIME, "VASTUS_MEDIALIS": PRIME, "GLUTEUS_MAXIMUS": PRIME,
        "RECTUS_FEMORIS": SYNERGIST, "ADDUCTOR_MAGNUS": SYNERGIST, "SOLEUS": SYNERGIST,
        "BICEPS_FEMORIS_LONG": STABILIZER, "SEMITENDINOSUS": STABILIZER,
    },
    "leg_extension": {
        "RECTUS_FEMORIS": PRIME, "VASTUS_LATERALIS": PRIME, "VASTUS_MEDIALIS": PRIME,
        "TIBIALIS_ANTERIOR": STABILIZER,
    },
    "leg_curl": {
        "BICEPS_FEMORIS_LONG": PRIME, "SEMITENDINOSUS": PRIME, "SEMIMEMBRANOSUS": PRIME, "BICEPS_FEMORIS_SHORT": PRIME,
        "GASTROCNEMIUS_LATERAL": SYNERGIST, "GASTROCNEMIUS_MEDIAL": SYNERGIST,
        "GLUTEUS_MAXIMUS": STABILIZER,
    },
    "calf_raise": {
        "GASTROCNEMIUS_LATERAL": PRIME, "GASTROCNEMIUS_MEDIAL": PRIME, "SOLEUS": PRIME,
        "PERONEUS_LONGUS": SYNERGIST,
        "TIBIALIS_ANTERIOR": STABILIZER,
    },
    "hip_hinge": {
        "GLUTEUS_MAXIMUS": PRIME, "BICEPS_FEMORIS_LONG": PRIME, "SEMITENDINOSUS": PRIME, "SEMIMEMBRANOSUS": PRIME,
        "ERECTOR_SPINAE": SYNERGIST, "ADDUCTOR_MAGNUS": SYNERGIST,
        "LATISSIMUS_DORSI": STABILIZER, "TRAPEZIUS_UPPER": STABILIZER, "WRIST_FLEXORS": STABILIZER,
    },
    "hip_abduction": {
        "GLUTEUS_MEDIUS": PRIME,
        "TENSOR_FASCIAE_LATAE": SYNERGIST, "GLUTEUS_MAXIMUS": SYNERGIST,
    },
    "hip_adduction": {
        "ADDUCTOR_LONGUS": PRIME, "ADDUCTOR_MAGNUS": PRIME, "GRACILIS": PRIME,
    },
    "crunch": {
        "RECTUS_ABDOMINIS": PRIME,
        "EXTERNAL_OBLIQUE": SYNERGIST,
        "RECTUS_FEMORIS": STABILIZER, "TENSOR_FASCIAE_LATAE": STABILIZER,
    },
}

# Tendon across a head, where its field is cut nearly through: heights above the hips, and whether the midline is one too.
CUTS = {
    "RECTUS_ABDOMINIS": ([0.35, 0.3, 0.25], True),
    "PECTORALIS_STERNAL": ([], True),
    "PECTORALIS_CLAVICULAR": ([], True),
    "TRAPEZIUS_UPPER": ([], True),
    "TRAPEZIUS_MIDDLE": ([], True),
    "TRAPEZIUS_LOWER": ([], True),
    "ERECTOR_SPINAE": ([], True),
}

SIDES = ((1.0, "l"), (-1.0, "r"))

# The height (as the heads above are set down) below which a head of the belly is cut off.
ENDS = {"RECTUS_ABDOMINIS": 0.035, "EXTERNAL_OBLIQUE": 0.06}

# The landmarks the heads above were set down against: the hip joint's height, and the shoulder joint's (how far out, how high).
REFERENCE_HIP = 0.11
REFERENCE_SHOULDER = (0.239, 0.562)
# And how thick its limbs are: the skin's mean distance from the bone, half and three quarters of the way down it.
REFERENCE_LIMB = {"upperarm_": 0.0589, "lowerarm_": 0.0293, "thigh_": 0.0789, "calf_": 0.0427}


class Skin:
    """The body's skin in the rest pose, in the rig's own space: its vertices, which way each faces, and a way to find it."""

    def __init__(self, body, rig):
        # The body hangs on the armature: where it is in the armature's space is its own placing under it. (Its place
        # in the world is not asked for: a body kept out of the working view isn't always told it.)
        to_rig = body.matrix_parent_inverse @ body.matrix_basis if body.parent == rig else rig.matrix_world.inverted() @ body.matrix_world
        mesh = body.data
        self.body = body
        # MakeHuman's mesh carries hidden helper shapes beside the skin, marked off by a group; a sculpt is all skin.
        if "body" in body.vertex_groups:
            group = body.vertex_groups["body"].index
            self.is_skin = np.array([any(g.group == group for g in v.groups) for v in mesh.vertices])
        else:
            self.is_skin = np.ones(len(mesh.vertices), dtype=bool)
        # The heads are set down against the MakeHuman lifter's landmarks. On another body they are carried to its
        # own: heights by where its hips and shoulders are, widths by how broad its shoulders are, and how far a
        # belly reaches by a little less than that.
        hip = rig.data.bones["thigh_l"].head_local
        shoulder = rig.data.bones["upperarm_l"].head_local
        self.wide = shoulder.x / REFERENCE_SHOULDER[0]
        self.tall = (shoulder.z - hip.z) / (REFERENCE_SHOULDER[1] - REFERENCE_HIP)
        self.hip = hip.z
        self.girth = self.wide ** 0.6
        self.limbs = {}
        # A sculpt already shows where each belly ends, so the map fills the forms out to their edges; on a body
        # the map itself raises (`sculpt`), wider bellies would run together.
        self.fill = 1.0 if "body" in body.vertex_groups else 1.45
        self.positions = np.array([tuple(to_rig @ v.co) for v in mesh.vertices], dtype=np.float64)
        self.normals = np.array([tuple(to_rig.to_3x3() @ v.normal) for v in mesh.vertices], dtype=np.float64)
        polygons = [tuple(p.vertices) for p in mesh.polygons if all(self.is_skin[i] for i in p.vertices)]
        self.tree = BVHTree.FromPolygons([Vector(p) for p in self.positions], polygons)
        self.rig = rig
        self.weights = None
        edges = np.array([tuple(e.vertices) for e in mesh.edges])
        self.edges = np.concatenate([edges, edges[:, ::-1]])
        self.neighbours = np.maximum(np.bincount(self.edges[:, 0], minlength=len(mesh.vertices)), 1)

    def bound(self):
        """How the skin hangs on the bones: for every vertex, the bones that move it (their places in `self.bones`) and by how much."""
        if self.weights is None:
            names = [group.name for group in self.body.vertex_groups]
            self.bones = [name for name in names if name in self.rig.data.bones]
            place = {names.index(name): at for at, name in enumerate(self.bones)}
            mesh = self.body.data
            most = max(len(vertex.groups) for vertex in mesh.vertices)
            self.owners = np.zeros((len(mesh.vertices), most), dtype=np.int64)
            self.weights = np.zeros((len(mesh.vertices), most))
            for vertex in mesh.vertices:
                for slot, group in enumerate(vertex.groups):
                    if group.group in place:
                        self.owners[vertex.index, slot] = place[group.group]
                        self.weights[vertex.index, slot] = group.weight
            self.weights /= np.maximum(self.weights.sum(axis=1, keepdims=True), 1e-9)
        return self.owners, self.weights

    def share(self, *prefixes):
        """How much of each vertex is moved by the bones whose names start so."""
        owners, weights = self.bound()
        chosen = np.array([name.startswith(prefixes) for name in self.bones])
        return (weights * chosen[owners]).sum(axis=1)

    def posed(self, which=None):
        """Where the skin is now, as the armature's pose has it (all of it, or the vertices [which]), and which way it faces there: the same skinning Blender does, without asking it to."""
        owners, weights = self.bound()
        if which is not None:
            owners, weights = owners[which], weights[which]
        rest_positions = self.positions if which is None else self.positions[which]
        rest_normals = self.normals if which is None else self.normals[which]
        moves = np.array([np.array(self.rig.pose.bones[name].matrix @ self.rig.data.bones[name].matrix_local.inverted()) for name in self.bones])
        blended = np.einsum("nk,nkij->nij", weights, moves[owners])
        positions = np.einsum("nij,nj->ni", blended[:, :3, :3], rest_positions) + blended[:, :3, 3]
        normals = np.einsum("nij,nj->ni", blended[:, :3, :3], rest_normals)
        return positions, normals / np.maximum(np.linalg.norm(normals, axis=1, keepdims=True), 1e-9)

    def smoothed(self, field, times=3):
        """A field over the vertices with its ridges and steps taken off: each vertex drawn half way to the mean of those round it, a few times."""
        for _ in range(times):
            around = np.bincount(self.edges[:, 0], weights=field[self.edges[:, 1]], minlength=len(field)) / self.neighbours
            field = 0.5 * field + 0.5 * around
        return field

    def outward(self, origin, direction):
        """The skin, looking out from inside the body: where, and which way it faces there."""
        hit = self.tree.ray_cast(origin, direction)
        if hit[0] is None:
            hit = self.tree.find_nearest(origin)
        normal = hit[1] if hit[1].dot(direction) > 0.0 else -hit[1]
        return np.array(hit[0]), np.array(normal)

    def nearest(self, point):
        hit = self.tree.find_nearest(point)
        return np.array(hit[0]), np.array(hit[1])

    def limb(self, bone):
        """How thick this body's limb is beside the reference body's: what a belly lying on it reaches by."""
        if bone not in self.limbs:
            at = self.rig.data.bones[bone + "l"]
            axis = np.array((at.tail_local - at.head_local).normalized())
            radii = []
            for fraction in (0.5, 0.75):
                centre = np.array(at.head_local.lerp(at.tail_local, fraction))
                for degrees in range(0, 360, 30):
                    off = self.place((bone, fraction, degrees), 1.0, "l")[0] - centre
                    radii.append(np.linalg.norm(off - axis * (off @ axis)))
            self.limbs[bone] = float(np.mean(radii)) / REFERENCE_LIMB[bone] * self.fill
        return self.limbs[bone]

    def height(self, z):
        """A height up the reference body's trunk, as it is on this one."""
        return self.hip + (z - REFERENCE_HIP) * self.tall

    def place(self, spec, sign, side):
        """A strand's point on the skin, for one side of the body."""
        kind = spec[0]
        if kind == "at":
            return self.nearest(Vector((spec[1] * self.wide * sign, spec[2], self.height(spec[3]))))
        if kind in ("front", "back", "trunk"):
            spec = (kind, spec[1] * self.wide, self.height(spec[2])) if kind != "trunk" else (kind, self.height(spec[1]), spec[2])
        if kind in ("front", "back"):
            # Looked at from in front or from behind: the first skin a line of sight at that place meets.
            toward = 1.0 if kind == "front" else -1.0
            hit = self.tree.ray_cast(Vector((spec[1] * sign, -toward, spec[2])), Vector((0.0, toward, 0.0)))
            if hit[0] is None:
                return self.nearest(Vector((spec[1] * sign, -0.1 * toward, spec[2])))
            normal = hit[1] if hit[1].y * toward < 0.0 else -hit[1]
            return np.array(hit[0]), np.array(normal)
        angle = math.radians(spec[2])
        if kind == "trunk":
            # Looking out from a little to that side of the spine, so as not to start in the cleft between the buttocks.
            start = Vector((0.06 * self.wide * math.sin(angle) * sign, -0.03 * math.cos(angle), spec[1]))
            return self.outward(start, Vector((math.sin(angle) * sign, -math.cos(angle), 0.0)))
        bone = self.rig.data.bones[kind + side if kind.endswith("_") else kind]
        axis = (bone.tail_local - bone.head_local).normalized()
        front = Vector((0.0, -1.0, 0.0))
        front = (front - axis * front.dot(axis)).normalized()
        outer = axis.cross(front)
        if outer.dot(Vector((sign, 0.0, 0.0))) < 0.0:
            outer = -outer
        return self.outward(bone.head_local.lerp(bone.tail_local, spec[1]), front * math.cos(angle) + outer * math.sin(angle))


def fields(skin):
    """For every head on each side, how much of it each vertex is, 0 to 1: {(name, side): array over the vertices}."""
    result = {}
    for name, (reach, strands) in HEADS.items():
        on = strands[0][0][0]
        reach = reach * (skin.limb(on) if on in REFERENCE_LIMB else skin.girth)
        for sign, side in SIDES:
            field = np.zeros(len(skin.positions))
            for strand in strands:
                placed = [skin.place(spec, sign, side) for spec in strand]
                for (a, na), (b, nb) in zip(placed, placed[1:]):
                    line = b - a
                    along = np.clip(((skin.positions - a) @ line) / max(line @ line, 1e-12), 0.0, 1.0)
                    distance = np.linalg.norm(skin.positions - (a + along[:, None] * line), axis=1)
                    weight = np.clip(1.0 - (distance / reach) ** 2, 0.0, 1.0) ** 1.5
                    # Only skin that faces the way the muscle's own skin does: not the far side of a thin limb.
                    facing = np.einsum("ij,ij->i", skin.normals, na + along[:, None] * (nb - na))
                    weight *= np.clip((facing + 0.1) / 0.5, 0.0, 1.0)
                    field = np.maximum(field, weight)
            field = np.clip(skin.smoothed(field) * 1.08, 0.0, 1.0)
            if name in CUTS:
                heights, midline = CUTS[name]
                for height in heights:
                    field *= 1.0 - 0.85 * np.exp(-((skin.positions[:, 2] - skin.height(height)) / 0.007) ** 2)
                if midline:
                    field *= 1.0 - 0.9 * np.exp(-(skin.positions[:, 0] / 0.008) ** 2)
            if name in ENDS:
                # A muscle of the belly stops at the pelvis: it is not let run on over the groin onto the thighs.
                field = field * np.clip((skin.positions[:, 2] - skin.height(ENDS[name])) / 0.02, 0.0, 1.0) * np.clip(1.0 - 2.5 * skin.share("thigh_"), 0.0, 1.0)
            result[(name, side)] = field * skin.is_skin
    return result


def sculpt(skin, head_fields, depth=0.3):
    """
    Raises every belly out of the skin by a share of its reach, so that the body shows its muscles
    and the lines between them. Moves the mesh itself; returns nothing.
    """
    lift = np.zeros(len(skin.positions))
    for (name, _), field in head_fields.items():
        lift = np.maximum(lift, field * HEADS[name][0] * depth)
    to_body = (skin.body.matrix_world.inverted() @ skin.rig.matrix_world).to_3x3()
    for index, vertex in enumerate(skin.body.data.vertices):
        if lift[index] > 0.0:
            vertex.co += to_body @ Vector(skin.normals[index]) * lift[index]
    skin.body.data.update()


def color_attribute(body, name, colors):
    layer = body.data.color_attributes.get(name) or body.data.color_attributes.new(name, "FLOAT_COLOR", "POINT")
    flat = np.concatenate([colors, np.ones((len(colors), 1))], axis=1).astype(np.float32).ravel()
    layer.data.foreach_set("color", flat)


def paint_roles(body, head_fields, lift, attribute="muscle"):
    """Paints what a lift asks of the heads: red where a prime mover lies, green a synergist, blue a stabilizer."""
    channel = {PRIME: 0, SYNERGIST: 1, STABILIZER: 2}
    colors = np.zeros((len(body.data.vertices), 3))
    for (name, _), field in head_fields.items():
        role = WORK[lift].get(name)
        if role is not None:
            colors[:, channel[role]] = np.maximum(colors[:, channel[role]], field)
    color_attribute(body, attribute, colors)


def head_color(name):
    """A colour of its own for every head, neighbours well apart."""
    index = list(HEADS).index(name)
    return colorsys.hsv_to_rgb((index * 0.381966) % 1.0, 0.62 + 0.3 * ((index * 7) % 3) / 2.0, 1.0)


def paint_atlas(body, head_fields, attribute="atlas"):
    """Paints every head in its own colour, and how much of any head each vertex is in the alpha's place (blue is kept for colour)."""
    total = np.zeros((len(body.data.vertices), 3))
    weight = np.zeros(len(body.data.vertices))
    strongest = np.zeros(len(body.data.vertices))
    for (name, _), field in head_fields.items():
        sharp = field ** 3
        total += sharp[:, None] * np.array(head_color(name))
        weight += sharp
        strongest = np.maximum(strongest, field)
    colors = total / np.maximum(weight, 1e-9)[:, None] * np.clip(strongest * 1.6, 0.0, 1.0)[:, None]
    color_attribute(body, attribute, colors)


def anchors(skin, head_fields):
    """For every head: the bone its middle rides on, and where that middle is in the rest pose, to carry through a pose for a label."""
    groups = {group.index: group.name for group in skin.body.vertex_groups}
    bones = set(bone.name for bone in skin.rig.data.bones)
    result = []
    for (name, side), field in head_fields.items():
        if field.max() <= 0.0:
            continue
        middle = (field[:, None] * skin.positions).sum(axis=0) / field.sum()
        peak = int(np.argmax(field - np.linalg.norm(skin.positions - middle, axis=1)))
        weights = [(g.weight, groups[g.group]) for g in skin.body.data.vertices[peak].groups if groups[g.group] in bones]
        result.append({"head": name, "side": side, "bone": max(weights)[1] if weights else "pelvis", "rest": [float(c) for c in skin.positions[peak]]})
    return result
