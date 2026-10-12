package com.meticulouscreations.homesafe.fitness.ui.movement

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// A lifter to draw, as a jointed figure rather than a picture: two poses a movement goes between,
// and the limbs solved for anywhere in between. The muscles hang on its joints (MovementAnatomy.kt)
// and are painted in MovementStyles.kt. Everything here is in the figure's own space: x to the
// right, y up from the floor, one unit a standing body's height.

/** Which way the lifter is seen: from their side (facing right), from the front or from behind. */
internal enum class View { SIDE, FRONT, BACK }

/** What a bone belongs to, in the order the groups are drawn: each one overlaps those before it. */
internal enum class Group { FAR_LEG, BODY, LEG, ARM }

/** Which piece of the body a bone is. */
internal enum class BoneKind { HEAD, NECK, TRUNK, HIPS, GIRDLE, UPPER_ARM, FOREARM, HAND, THIGH, SHIN, FOOT }

/** One piece of the body: a rod from [a] to [b], [ra] thick at one end and [rb] at the other. */
internal class Bone(val a: Offset, val b: Offset, val ra: Float, val rb: Float, val group: Group, val kind: BoneKind)

/**
 * What a muscle can be fastened to: the parts of the body, and the four big joints. A point
 * fastened to a joint turns with it by as much of the joint's bend as it is asked to (see
 * [Joint]), so a muscle wraps the joint as it bends and doesn't swing with one bone.
 */
internal enum class Seg { TRUNK, UPPER_ARM, FOREARM, THIGH, SHIN, SHOULDER, ELBOW, HIP, KNEE }

/**
 * A body part's own coordinates. [at] takes how far along it (0 at the end nearer the trunk, 1
 * at the far end; for the trunk, 0 at the hips and 1 at the shoulders) and how far to one side
 * in body heights: toward the front in a side view, away from the midline in a front or back one.
 */
internal open class Frame(val origin: Offset, val axis: Offset, val across: Offset) {
    open fun at(along: Float, out: Float): Offset = origin + axis * along + across * out

    /** Which way the part runs at [along] it, one step long: the same all the way, on a part that doesn't bend. */
    open fun axisAt(along: Float): Offset = axis

    /** The part's side at [along] it. */
    open fun acrossAt(along: Float): Offset = across
}

/**
 * The trunk's frame, which is not stiff like a limb's. Its spine can curl: between [CURL_FROM]
 * and [CURL_TO] of the way up it turns steadily through [curl] (radians, as a turn of its line),
 * and the chest above goes with the end of that. And its shoulders can lift: by [shrug] body
 * heights at their points, less toward the neck, and nothing below the blades.
 */
private class Torso(origin: Offset, axis: Offset, across: Offset, private val curl: Float, private val shrug: Float) : Frame(origin, axis, across) {
    private fun turn(along: Float): Float = curl * ((along - CURL_FROM) / (CURL_TO - CURL_FROM)).coerceIn(0f, 1f)

    private fun spine(along: Float): Offset {
        if (abs(curl) < 1e-4f || along <= CURL_FROM) return origin + axis * along
        val bent = min(along, CURL_TO)
        // What a line turning steadily adds up to: an arc as long as the stretch of spine that bends.
        val quarter = (PI / 2).toFloat()
        val arc = (axis.spun(turn(bent) - quarter) - axis.spun(-quarter)) * ((CURL_TO - CURL_FROM) / curl)
        return origin + axis * CURL_FROM + arc + axis.spun(curl) * (along - bent)
    }

    override fun at(along: Float, out: Float): Offset =
        spine(along) + across.spun(turn(along)) * out + axis.unit() * (shrug * ramp(abs(out), 0.035f, 0.11f) * ramp(along, 0.8f, 1f))

    override fun axisAt(along: Float): Offset = axis.spun(turn(along))

    override fun acrossAt(along: Float): Offset = across.spun(turn(along))
}

/** The stretch of the trunk that bends when the spine curls: from above the hips to the bottom of the ribs. */
private const val CURL_FROM = 0.12f
private const val CURL_TO = 0.7f

/**
 * A joint, for what wraps it. [from] is the part above it, pointing down it, and [across] that
 * part's side; [angle] is how far the part below has turned from there. One step along is
 * [length] as the part below is seen and [full] as it is when it lies flat in the picture.
 * [wrap] 0–1 thins what lies over the middle of the bend on its outer side, which is stretched
 * over it; [reach] thins what lies on the part below, for an arm stretched overhead.
 */
internal class Joint(
    val origin: Offset,
    private val from: Offset,
    private val across: Offset,
    private val angle: Float,
    private val length: Float,
    private val full: Float = length,
    private val wrap: Float = 0f,
    private val reach: Float = 0f,
) {
    /** Which side of the joint the bend closes on, as the sign of an [at]'s `out`. */
    private val inside = angle * (from.x * across.y - from.y * across.x)

    /**
     * The point [along] and [out] from the joint that turns [turn] of the joint's bend: 0 stays
     * with the part above, 1 goes with the part below, and the ones between spread round the
     * joint as it bends, which is what keeps a buttock round over a folded hip.
     */
    fun at(turn: Float, along: Float, out: Float): Offset {
        val a = angle * turn
        // Only what is on the outside of the bend is stretched over it; what is inside it bunches, and keeps its depth.
        val stretched = if (out * inside < 0f) wrap * 4f * turn * (1f - turn) else 0f
        val thin = (1f - stretched) * (1f - reach * turn)
        // What is fastened above the joint keeps its place when the part below points out of the page; what is on that part shortens with it.
        val step = full + (length - full) * turn * turn
        return origin + from.spun(a) * (along * step) + across.spun(a) * (out * thin)
    }
}

/** One side of the body's frames and joints, by [Seg]: the near side in a side view, each of the two in a front or back one. */
internal class Limbs(private val frames: List<Frame>, private val joints: List<Joint>) {
    fun at(seg: Seg, along: Float, out: Float, turn: Float = 0.5f): Offset =
        if (seg.ordinal < frames.size) frames[seg.ordinal].at(along, out) else joints[seg.ordinal - frames.size].at(turn, along, out)
}

internal enum class PropKind {
    /** A steel tube of the bench or machine. */
    FRAME,

    /** Upholstery. */
    PAD,
    CABLE,

    /** Iron: a plate seen end on (a point, [Prop.r] its radius) or a weight stack (a rod). */
    WEIGHT,

    /** What the hand holds: seen end on (a point) or along its length. */
    GRIP,
}

/**
 * A piece of equipment: a rod from [a] to [b] with [r] half its thickness, or a disc when they
 * are the same point. It is behind the lifter unless it is in [front] of them, as a bar held at
 * the collarbones is.
 */
internal class Prop(val kind: PropKind, val a: Offset, val b: Offset, val r: Float, val front: Boolean = false) {
    constructor(kind: PropKind, at: Offset, r: Float) : this(kind, at, at, r)
}

/** The lifter and their equipment at one moment of a rep. [load] is where the weight is. */
internal class Figure(val view: View, val bones: List<Bone>, val limbs: List<Limbs>, val props: List<Prop>, val load: Offset)

/** How a pose's [Pose.hand] is read. */
internal enum class Hands {
    /** A point in the room: the bar travels in a straight line between the two poses. */
    ROOM,

    /**
     * The same, pushed up from under: the forearm stands upright below the hand, and the elbow
     * points as far out of the page as that leaves the upper arm looking short. [Pose.reach] is
     * the forearm's own shortening.
     */
    PRESS,

    /**
     * A point in the room, reached by an arm swung level, toward the viewer or away: the arm is
     * never folded to get there, it shows as short as the reach is, the elbow a little unlocked.
     * [Pose.reach] is not read.
     */
    SPREAD,

    /** Across the chest and up the spine from the shoulder: the hands ride with the trunk. */
    TRUNK,

    /** An angle out from hanging straight down (degrees) and a reach: the hand swings on an arc about the shoulder. */
    SWING,

    /**
     * The arm's own angles, in degrees: the upper arm's from hanging straight down, and the
     * forearm's from the upper arm's line. The arm is placed, not solved: an elbow held at the
     * side stays there while the forearm turns about it.
     */
    JOINTS,
}

/** How a pose's [Pose.foot] is read. */
internal enum class Feet {
    /** The ankle, as a point in the room. */
    ROOM,

    /**
     * The leg's own angles, in degrees, seen from the side: the thigh's from hanging straight
     * down, toward the front, and how far the knee is bent from straight. For legs that hang or
     * swing with nothing under the feet.
     */
    JOINTS,
}

/** Which point of the lifter a movement's weight is taken to be at: the one whose way through the rep is drawn. */
internal enum class Load { HAND, FOOT, KNEE, HEAD }

/**
 * One end of a rep. [lean] is the trunk's tilt from upright in degrees, toward the way the lifter
 * faces; [reach] under 1 shortens the arm, for elbows that point out of the page, and [thigh]
 * under 1 the thigh, for knees that do. [sole] is the foot's tilt in degrees, toes up: from flat
 * on the floor where the foot is a place in the room, from square to the shin where the leg is
 * given by its joints. [curl] is how far the spine is curled forward, in degrees, seen from the
 * side; [shrug] how far the shoulders are lifted, in body heights, seen from front or back.
 */
internal class Pose(
    val pelvis: Offset,
    val lean: Float,
    val hand: Offset,
    val foot: Offset,
    val reach: Float = 1f,
    val thigh: Float = 1f,
    val sole: Float = 0f,
    val curl: Float = 0f,
    val shrug: Float = 0f,
)

/**
 * A movement the figure can perform, named as the kind of exercise it is (MovementKind). [start]
 * is the easy end of the rep and [end] the hard one; [elbow] and [knee] say which way those
 * joints fold (the sign of the turn from the limb's line). [bounds] is the room the whole rep and
 * its equipment take up, y up.
 */
internal enum class Movement(
    val view: View,
    val start: Pose,
    val end: Pose,
    val hands: Hands,
    val elbow: Float,
    val knee: Float,
    val bounds: Rect,
    val feet: Feet = Feet.ROOM,
    val load: Load = Load.HAND,
) {
    INCLINE_PRESS(
        view = View.SIDE,
        start = Pose(Offset(0f, 0.30f), lean = -50f, hand = Offset(-0.177f, 0.537f), foot = Offset(0.30f, 0.045f), reach = 0.95f),
        end = Pose(Offset(0f, 0.30f), lean = -50f, hand = Offset(-0.20f, 0.785f), foot = Offset(0.30f, 0.045f)),
        hands = Hands.PRESS,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.47f, -0.03f, 0.47f, 0.91f),
    ),

    /** From behind, where the muscles it is for can be seen. */
    LAT_PULLDOWN(
        view = View.BACK,
        start = Pose(Offset(0f, 0.332f), lean = 0f, hand = Offset(0.2f, 0.894f), foot = Offset(0.07f, 0.045f), thigh = 0.14f),
        end = Pose(Offset(0f, 0.332f), lean = 0f, hand = Offset(0.2f, 0.617f), foot = Offset(0.07f, 0.045f), thigh = 0.14f),
        hands = Hands.ROOM,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.5f, -0.03f, 0.5f, 1.09f),
    ),
    SQUAT(
        view = View.SIDE,
        start = Pose(Offset(0f, 0.53f), lean = 4f, hand = Offset(-0.056f, 0.024f), foot = Offset(0.01f, 0.045f), reach = 0.88f),
        end = Pose(Offset(-0.17f, 0.275f), lean = 38f, hand = Offset(-0.056f, 0.024f), foot = Offset(0.01f, 0.045f), reach = 0.88f),
        hands = Hands.TRUNK,
        elbow = 1f,
        knee = 1f,
        bounds = Rect(-0.52f, -0.03f, 0.52f, 1.01f),
    ),

    /** The same squat from the front, the knees coming toward the viewer: where the quadriceps show. */
    SQUAT_FRONT(
        view = View.FRONT,
        start = Pose(Offset(0f, 0.548f), lean = 0f, hand = Offset(0.1f, 0.03f), foot = Offset(0.11f, 0.045f), reach = 0.75f),
        end = Pose(Offset(0f, 0.36f), lean = 0f, hand = Offset(0.1f, 0.03f), foot = Offset(0.11f, 0.045f), reach = 0.75f, thigh = 0.5f),
        hands = Hands.TRUNK,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.56f, -0.03f, 0.56f, 1.03f),
    ),
    LATERAL_RAISE(
        view = View.FRONT,
        start = Pose(Offset(0f, 0.548f), lean = 0f, hand = Offset(9f, 0.308f), foot = Offset(0.07f, 0.045f)),
        end = Pose(Offset(0f, 0.548f), lean = 0f, hand = Offset(84f, 0.304f), foot = Offset(0.07f, 0.045f)),
        hands = Hands.SWING,
        elbow = 1f,
        knee = 1f,
        bounds = Rect(-0.53f, -0.03f, 0.53f, 1.03f),
    ),

    /** Lying on a flat bench: the bar from the chest to arm's length. */
    BENCH_PRESS(
        view = View.SIDE,
        start = Pose(Offset(0.08f, 0.29f), lean = -90f, hand = Offset(-0.145f, 0.4f), foot = Offset(0.34f, 0.045f), reach = 0.95f),
        end = Pose(Offset(0.08f, 0.29f), lean = -90f, hand = Offset(-0.18f, 0.6f), foot = Offset(0.34f, 0.045f)),
        hands = Hands.PRESS,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.47f, -0.03f, 0.47f, 0.76f),
    ),

    /** Seated, from the front: the bar from the collarbones to overhead. */
    OVERHEAD_PRESS(
        view = View.FRONT,
        start = Pose(Offset(0f, 0.332f), lean = 0f, hand = Offset(0.165f, 0.615f), foot = Offset(0.09f, 0.045f), reach = 0.9f, thigh = 0.16f),
        end = Pose(Offset(0f, 0.332f), lean = 0f, hand = Offset(0.15f, 0.905f), foot = Offset(0.09f, 0.045f), thigh = 0.16f),
        hands = Hands.PRESS,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.56f, -0.03f, 0.56f, 1.05f),
    ),

    /** Seated between two cables, from the front: the arms from out wide to together ahead, which is toward the viewer. */
    CHEST_FLY(
        view = View.FRONT,
        start = Pose(Offset(0f, 0.332f), lean = 0f, hand = Offset(0.41f, 0.585f), foot = Offset(0.09f, 0.045f), thigh = 0.16f),
        end = Pose(Offset(0f, 0.332f), lean = 0f, hand = Offset(0.045f, 0.57f), foot = Offset(0.09f, 0.045f), thigh = 0.16f),
        hands = Hands.SPREAD,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.56f, -0.03f, 0.56f, 0.86f),
    ),

    /** Between parallel bars: from arms straight, down until the upper arms are level. The hands stay; the body goes. */
    DIP(
        view = View.SIDE,
        start = Pose(Offset(-0.014f, 0.643f), lean = 5f, hand = Offset(0.02f, 0.6f), foot = Offset(5f, 75f), sole = -20f),
        end = Pose(Offset(0.056f, 0.515f), lean = 25f, hand = Offset(0.02f, 0.6f), foot = Offset(16f, 82f), sole = -20f),
        hands = Hands.ROOM,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.5f, -0.03f, 0.5f, 1.13f),
        feet = Feet.JOINTS,
        load = Load.HEAD,
    ),
    FRONT_RAISE(
        view = View.SIDE,
        start = Pose(Offset(0f, 0.53f), lean = 2f, hand = Offset(6f, 0.31f), foot = Offset(0.01f, 0.045f)),
        end = Pose(Offset(0f, 0.53f), lean = 0f, hand = Offset(88f, 0.306f), foot = Offset(0.01f, 0.045f)),
        hands = Hands.SWING,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.42f, -0.03f, 0.5f, 1.01f),
    ),

    /** Seated between two cables, from behind: the arms from ahead, which is away from the viewer, to out wide. */
    REAR_DELT_FLY(
        view = View.BACK,
        start = Pose(Offset(0f, 0.332f), lean = 0f, hand = Offset(0.07f, 0.575f), foot = Offset(0.09f, 0.045f), thigh = 0.16f),
        end = Pose(Offset(0f, 0.332f), lean = 0f, hand = Offset(0.415f, 0.592f), foot = Offset(0.09f, 0.045f), thigh = 0.16f),
        hands = Hands.SPREAD,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.56f, -0.03f, 0.56f, 0.86f),
    ),
    SHRUG(
        view = View.BACK,
        start = Pose(Offset(0f, 0.548f), lean = 0f, hand = Offset(7f, 0.312f), foot = Offset(0.07f, 0.045f)),
        end = Pose(Offset(0f, 0.548f), lean = 0f, hand = Offset(7f, 0.312f), foot = Offset(0.07f, 0.045f), shrug = 0.042f),
        hands = Hands.SWING,
        elbow = 1f,
        knee = 1f,
        bounds = Rect(-0.42f, -0.03f, 0.42f, 1.03f),
    ),

    /** From hanging to the chin over the bar, seen from behind. The bar stays; the body goes. */
    PULL_UP(
        view = View.BACK,
        start = Pose(Offset(0f, 0.625f), lean = 0f, hand = Offset(0.2f, 1.19f), foot = Offset(0.06f, 0.13f), sole = -35f),
        end = Pose(Offset(0f, 0.86f), lean = 0f, hand = Offset(0.2f, 1.19f), foot = Offset(0.06f, 0.365f), sole = -35f),
        hands = Hands.ROOM,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.5f, -0.03f, 0.5f, 1.35f),
        load = Load.HEAD,
    ),
    SEATED_ROW(
        view = View.SIDE,
        start = Pose(Offset(-0.12f, 0.3f), lean = 8f, hand = Offset(0.19f, 0.43f), foot = Offset(0.3f, 0.16f), sole = 60f),
        end = Pose(Offset(-0.12f, 0.3f), lean = -8f, hand = Offset(-0.04f, 0.415f), foot = Offset(0.3f, 0.16f), sole = 60f),
        hands = Hands.ROOM,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.47f, -0.03f, 0.57f, 0.82f),
    ),
    BICEPS_CURL(
        view = View.SIDE,
        start = Pose(Offset(0f, 0.53f), lean = 2f, hand = Offset(2f, 8f), foot = Offset(0.01f, 0.045f)),
        end = Pose(Offset(0f, 0.53f), lean = 2f, hand = Offset(10f, 140f), foot = Offset(0.01f, 0.045f)),
        hands = Hands.JOINTS,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.44f, -0.03f, 0.44f, 1.01f),
    ),

    /** Facing a high cable: the bar from the chest down to the thighs, the elbows staying at the sides. */
    TRICEPS_PUSHDOWN(
        view = View.SIDE,
        start = Pose(Offset(-0.06f, 0.53f), lean = 8f, hand = Offset(5f, 110f), foot = Offset(-0.05f, 0.045f)),
        end = Pose(Offset(-0.06f, 0.53f), lean = 8f, hand = Offset(5f, 6f), foot = Offset(-0.05f, 0.045f)),
        hands = Hands.JOINTS,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.47f, -0.03f, 0.47f, 1.08f),
    ),

    /** Lying back under a sled: from the legs out to the knees at the chest. */
    LEG_PRESS(
        view = View.SIDE,
        start = Pose(Offset(-0.08f, 0.2f), lean = -55f, hand = Offset(0.03f, -0.25f), foot = Offset(0.262f, 0.542f), sole = 135f),
        end = Pose(Offset(-0.08f, 0.2f), lean = -55f, hand = Offset(0.03f, -0.25f), foot = Offset(0.114f, 0.394f), sole = 135f),
        hands = Hands.TRUNK,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.5f, -0.03f, 0.56f, 0.84f),
        load = Load.FOOT,
    ),
    LEG_EXTENSION(
        view = View.SIDE,
        start = Pose(Offset(-0.14f, 0.45f), lean = -15f, hand = Offset(0.02f, -0.25f), foot = Offset(87f, 87f)),
        end = Pose(Offset(-0.14f, 0.45f), lean = -15f, hand = Offset(0.02f, -0.25f), foot = Offset(87f, 5f), sole = 10f),
        hands = Hands.TRUNK,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.47f, -0.03f, 0.51f, 0.93f),
        feet = Feet.JOINTS,
        load = Load.FOOT,
    ),

    /** Lying face down: from the legs straight to the heels brought over toward the seat. */
    LEG_CURL(
        view = View.SIDE,
        start = Pose(Offset(0.05f, 0.42f), lean = 90f, hand = Offset(18f, 62f), foot = Offset(-91f, 6f), reach = 0.9f, sole = -25f),
        end = Pose(Offset(0.05f, 0.42f), lean = 90f, hand = Offset(18f, 62f), foot = Offset(-91f, 115f), reach = 0.9f, sole = -10f),
        hands = Hands.JOINTS,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.56f, -0.03f, 0.58f, 0.82f),
        feet = Feet.JOINTS,
        load = Load.FOOT,
    ),

    /** On the edge of a step, a weight in each hand: from the heels hanging to up on the balls of the feet. */
    CALF_RAISE(
        view = View.SIDE,
        start = Pose(Offset(0f, 0.558f), lean = 2f, hand = Offset(4f, 0.312f), foot = Offset(0.012f, 0.072f), sole = 14f),
        end = Pose(Offset(0.03f, 0.627f), lean = 2f, hand = Offset(4f, 0.312f), foot = Offset(0.047f, 0.141f), sole = -38f),
        hands = Hands.SWING,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.42f, -0.03f, 0.42f, 1.1f),
        load = Load.FOOT,
    ),

    /** The Romanian deadlift: from upright to the trunk nearly level, the bar hanging from straight arms. */
    HIP_HINGE(
        view = View.SIDE,
        start = Pose(Offset(0f, 0.53f), lean = 3f, hand = Offset(3f, 4f), foot = Offset(0.01f, 0.045f)),
        end = Pose(Offset(-0.14f, 0.5f), lean = 76f, hand = Offset(-4f, 3f), foot = Offset(0.01f, 0.045f)),
        hands = Hands.JOINTS,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.5f, -0.03f, 0.5f, 1.01f),
    ),

    /** Seated, from behind, where the muscles it is for can be seen: the knees from together to apart. */
    HIP_ABDUCTION(
        view = View.BACK,
        start = Pose(Offset(0f, 0.355f), lean = 0f, hand = Offset(0.035f, -0.262f), foot = Offset(0.075f, 0.045f), reach = 0.95f, thigh = 0.28f),
        end = Pose(Offset(0f, 0.355f), lean = 0f, hand = Offset(0.035f, -0.262f), foot = Offset(0.21f, 0.047f), reach = 0.95f, thigh = 0.62f),
        hands = Hands.TRUNK,
        elbow = 1f,
        knee = 1f,
        bounds = Rect(-0.45f, -0.03f, 0.45f, 0.84f),
        load = Load.KNEE,
    ),

    /** Seated, from the front: the knees from apart to together. */
    HIP_ADDUCTION(
        view = View.FRONT,
        start = Pose(Offset(0f, 0.355f), lean = 0f, hand = Offset(0.035f, -0.262f), foot = Offset(0.21f, 0.047f), reach = 0.95f, thigh = 0.62f),
        end = Pose(Offset(0f, 0.355f), lean = 0f, hand = Offset(0.035f, -0.262f), foot = Offset(0.085f, 0.045f), reach = 0.95f, thigh = 0.3f),
        hands = Hands.TRUNK,
        elbow = 1f,
        knee = 1f,
        bounds = Rect(-0.45f, -0.03f, 0.45f, 0.84f),
        load = Load.KNEE,
    ),

    /** Lying on the back with the knees up, the arms crossed on the chest: from flat to the shoulders curled up off the floor. */
    CRUNCH(
        view = View.SIDE,
        start = Pose(Offset(0.05f, 0.09f), lean = -90f, hand = Offset(0.082f, -0.012f), foot = Offset(0.35f, 0.045f), reach = 0.5f),
        end = Pose(Offset(0.05f, 0.09f), lean = -90f, hand = Offset(0.082f, -0.012f), foot = Offset(0.35f, 0.045f), reach = 0.5f, curl = 42f),
        hands = Hands.TRUNK,
        elbow = -1f,
        knee = 1f,
        bounds = Rect(-0.47f, -0.03f, 0.51f, 0.56f),
        load = Load.HEAD,
    ),
    ;

    /** The lifter [t] of the way from [start] (0) to [end] (1). */
    fun figure(t: Float): Figure {
        val pose = Pose(
            lerp(start.pelvis, end.pelvis, t),
            lerp(start.lean, end.lean, t),
            lerp(start.hand, end.hand, t),
            lerp(start.foot, end.foot, t),
            lerp(start.reach, end.reach, t),
            lerp(start.thigh, end.thigh, t),
            lerp(start.sole, end.sole, t),
            lerp(start.curl, end.curl, t),
            lerp(start.shrug, end.shrug, t),
        )
        val body = if (view == View.SIDE) sideBody(pose, hands, feet, elbow, knee) else frontBody(pose, hands, elbow, knee)
        val at = when (load) {
            Load.HAND -> body.hands.first()
            Load.FOOT -> body.ankles.first()
            Load.KNEE -> body.knees.first()
            Load.HEAD -> body.head
        }
        return Figure(view, body.bones, body.limbs, props(this, pose, body), at)
    }

    /** The way the weight goes over the rep, start to end. */
    fun travel(steps: Int = 10): List<Offset> = (0..steps).map { figure(it / steps.toFloat()).load }
}

/** The lifter standing at ease as [view] sees them, with nothing in their hands: the pose an anatomy plate is drawn in. */
internal fun standing(view: View): Figure {
    val body = if (view == View.SIDE) {
        sideBody(Pose(Offset(0f, 0.535f), lean = 0f, hand = Offset(12f, 0.312f), foot = Offset(0f, 0.045f)), Hands.SWING, Feet.ROOM, elbowBend = -1f, kneeBend = 1f)
    } else {
        frontBody(Pose(Offset(0f, 0.548f), lean = 0f, hand = Offset(16f, 0.312f), foot = Offset(0.07f, 0.045f)), Hands.SWING, elbowBend = 1f, kneeBend = 1f)
    }
    return Figure(view, body.bones, body.limbs, emptyList(), body.hands.first())
}

/** The room a [standing] figure takes up, y up. */
internal val STANDING_BOUNDS = Rect(-0.22f, -0.02f, 0.22f, 1.02f)

/** A rep, in seconds: up, a beat at the top, down slower, a beat at the bottom. */
internal const val REP_SECONDS = 3.2f

/** How far through a rep the figure is at [seconds], 0 at the start pose and 1 at the end, eased the way a lift is. */
internal fun repProgress(seconds: Float): Float {
    val s = ((seconds % REP_SECONDS) + REP_SECONDS) % REP_SECONDS
    return when {
        s < 0.3f -> 0f
        s < 1.3f -> ease((s - 0.3f) / 1.0f)
        s < 1.6f -> 1f
        s < 3.0f -> 1f - ease((s - 1.6f) / 1.4f)
        else -> 0f
    }
}

private fun ease(x: Float): Float = x * x * x * (x * (x * 6f - 15f) + 10f)

internal const val TRUNK_LENGTH = 0.27f
private const val CHEST_TOP = 0.232f
private const val HEAD_AT = 0.388f
private const val HEAD_R = 0.06f
private const val UPPER_ARM = 0.165f
private const val FOREARM = 0.15f
private const val THIGH = 0.245f
private const val SHIN = 0.245f

private fun Offset.turned(): Offset = Offset(-y, x)

private fun Offset.unit(): Offset = getDistance().let { if (it < 1e-6f) Offset(0f, 1f) else this / it }

private fun cross(a: Offset, b: Offset): Float = a.x * b.y - a.y * b.x

/** The middle joint of a two-bone limb from [root] to [target], folding to the [bend] side, and where its end really reaches. */
private fun fold(root: Offset, target: Offset, first: Float, second: Float, bend: Float): Pair<Offset, Offset> {
    val line = target - root
    val along = line.unit()
    val distance = line.getDistance().coerceIn(abs(first - second) + 1e-3f, first + second - 1e-3f)
    val a = (first * first - second * second + distance * distance) / (2 * distance)
    val h = sqrt((first * first - a * a).coerceAtLeast(0f))
    return (root + along * a + along.turned() * (h * bend)) to (root + along * distance)
}

/** The elbow of an arm pushing [target] up from under it, [forearm] below the hand: drawn in toward [root] where the upper arm couldn't reach it. */
private fun under(root: Offset, target: Offset, forearm: Float): Offset {
    val reach = target - Offset(0f, forearm) - root
    return root + if (reach.getDistance() > UPPER_ARM) reach.unit() * UPPER_ARM else reach
}

/** A body solved from a pose: its bones, each side's frames, and where its hands, knees, ankles and head are. */
private class Body(
    val bones: List<Bone>,
    val limbs: List<Limbs>,
    val hands: List<Offset>,
    val knees: List<Offset>,
    val ankles: List<Offset>,
    val head: Offset,
    val pelvis: Offset,
    val up: Offset,
    val front: Offset,
)

private fun radians(degrees: Float): Float = degrees * (PI / 180.0).toFloat()

/** Which way a limb points that is turned [angle] from hanging straight down: toward the front seen from the side, out from the midline on [side] seen from front or back. */
private fun hanging(angle: Float, side: Float = 1f): Offset = Offset(sin(angle) * side, -cos(angle))

/** The elbow and the hand of the arm at [shoulder], as [hands] reads the pose. [up] and [front] are the chest's. */
private fun reach(pose: Pose, hands: Hands, shoulder: Offset, up: Offset, front: Offset, bend: Float, side: Float): Pair<Offset, Offset> {
    val upper = UPPER_ARM * pose.reach
    val fore = FOREARM * pose.reach
    return when (hands) {
        Hands.ROOM -> fold(shoulder, Offset(pose.hand.x * side, pose.hand.y), upper, fore, bend)

        Hands.PRESS -> Offset(pose.hand.x * side, pose.hand.y).let { under(shoulder, it, fore) to it }

        Hands.SPREAD -> {
            val target = Offset(pose.hand.x * side, pose.hand.y)
            val seen = ((target - shoulder).getDistance() / (UPPER_ARM + FOREARM) * 1.03f).coerceIn(0.25f, 1f)
            fold(shoulder, target, UPPER_ARM * seen, FOREARM * seen, bend)
        }

        Hands.TRUNK -> fold(shoulder, shoulder + front * pose.hand.x + up * pose.hand.y, upper, fore, bend)

        Hands.SWING -> fold(shoulder, shoulder + hanging(radians(pose.hand.x), side) * pose.hand.y, upper, fore, bend)

        Hands.JOINTS -> {
            val elbow = shoulder + hanging(radians(pose.hand.x), side) * upper
            elbow to elbow + hanging(radians(pose.hand.x + pose.hand.y), side) * fore
        }
    }
}

private fun MutableList<Bone>.arm(shoulder: Offset, elbow: Offset, hand: Offset) {
    add(Bone(shoulder, elbow, 0.036f, 0.029f, Group.ARM, BoneKind.UPPER_ARM))
    add(Bone(elbow, hand, 0.028f, 0.021f, Group.ARM, BoneKind.FOREARM))
    add(Bone(hand, hand, 0.025f, 0.025f, Group.ARM, BoneKind.HAND))
}

private fun MutableList<Bone>.leg(hip: Offset, knee: Offset, ankle: Offset, toe: Offset, group: Group) {
    add(Bone(hip, knee, 0.058f, 0.043f, group, BoneKind.THIGH))
    add(Bone(knee, ankle, 0.041f, 0.027f, group, BoneKind.SHIN))
    add(Bone(ankle, toe, 0.027f, 0.019f, group, BoneKind.FOOT))
}

/** A limb's two frames, their far sides all turned the same way: [turn] 1 or −1 times a quarter turn from the bone's line. */
private fun limbFrames(root: Offset, joint: Offset, end: Offset, turn: Float): List<Frame> =
    listOf(Frame(root, joint - root, (joint - root).unit().turned() * turn), Frame(joint, end - joint, (end - joint).unit().turned() * turn))

private fun Offset.spun(angle: Float): Offset = Offset(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))

private fun joint(origin: Offset, before: Offset, across: Offset, after: Offset, length: Float, full: Float = length, wrap: Float = 0f, overhead: Float = 0f, stub: Float = 0.2f): Joint {
    val a = before.unit()
    val b = after.unit()
    // A part that points out of the page shows as a stub, and which way a stub lies says little about the joint: it turns what wraps it less,
    // and not at all once no more than [stub] of its length shows.
    val seen = ((length / full - stub) / 0.5f).coerceIn(0f, 1f)
    val angle = atan2(cross(a, b), a.x * b.x + a.y * b.y) * seen * seen * (3f - 2f * seen)
    // Past a right angle and a bit, a raised arm draws what is on it out long and flat.
    val stretched = ((abs(angle) - 1.75f) / 1.15f).coerceIn(0f, 1f)
    return Joint(origin, a, across, angle, length, full, wrap * sin(angle / 2) * sin(angle / 2), overhead * stretched * stretched * (3f - 2f * stretched))
}

/** One side's frames and joints, in [Seg]'s order, from the trunk's frame and the arm's and leg's. */
private fun limbs(trunk: Frame, arm: List<Frame>, leg: List<Frame>): Limbs = Limbs(
    listOf(trunk) + arm + leg,
    listOf(
        joint(arm[0].origin, trunk.axisAt(1f) * -1f, trunk.acrossAt(1f), arm[0].axis, arm[0].axis.getDistance(), full = UPPER_ARM, overhead = 0.42f),
        joint(arm[1].origin, arm[0].axis, arm[0].across, arm[1].axis, FOREARM),
        joint(leg[0].origin, trunk.axis * -1f, trunk.across, leg[0].axis, leg[0].axis.getDistance(), full = THIGH, wrap = 0.14f, stub = 0.4f),
        joint(leg[1].origin, leg[0].axis, leg[0].across, leg[1].axis, SHIN),
    ),
)

/** How far apart the two legs are drawn, seen from the side, so that the far one shows behind the near one: as a step, and as a turn of the thigh. */
private const val FAR_STEP = 0.04f
private const val FAR_TURN = 3f

private fun sideBody(pose: Pose, hands: Hands, feet: Feet, elbowBend: Float, kneeBend: Float): Body {
    val lean = radians(pose.lean)
    val up = Offset(sin(lean), cos(lean))
    val front = Offset(up.y, -up.x)
    // Forward is a turn clockwise, as the lifter faces right.
    val trunk = Torso(pose.pelvis, up * TRUNK_LENGTH, front, -radians(pose.curl), 0f)
    val shoulder = trunk.at(1f, 0f)
    val head = trunk.at(HEAD_AT / TRUNK_LENGTH, 0.012f)
    val (elbow, hand) = reach(pose, hands, shoulder, trunk.axisAt(1f).unit(), trunk.acrossAt(1f), elbowBend, 1f)

    fun leg(far: Boolean): Pair<Offset, Offset> = if (feet == Feet.JOINTS) {
        val thigh = radians(pose.foot.x + if (far) FAR_TURN else 0f)
        val knee = pose.pelvis + hanging(thigh) * (THIGH * pose.thigh)
        knee to knee + hanging(thigh - radians(pose.foot.y)) * SHIN
    } else {
        fold(pose.pelvis, pose.foot + Offset(if (far) FAR_STEP else 0f, 0f), THIGH * pose.thigh, SHIN, kneeBend)
    }
    val (knee, ankle) = leg(far = false)
    val (farKnee, farAnkle) = leg(far = true)

    fun toe(knee: Offset, ankle: Offset): Offset {
        val shin = if (feet == Feet.JOINTS) (ankle - knee).let { atan2(it.x, -it.y) } else 0f
        return ankle + Offset(0.085f, -0.022f).spun(shin + radians(pose.sole))
    }

    val bones = buildList {
        leg(pose.pelvis, farKnee, farAnkle, toe(farKnee, farAnkle), Group.FAR_LEG)
        add(Bone(pose.pelvis, trunk.at(CHEST_TOP / TRUNK_LENGTH, 0f), 0.066f, 0.072f, Group.BODY, BoneKind.TRUNK))
        add(Bone(trunk.at(1f + 0.02f / TRUNK_LENGTH, 0f), head, 0.03f, 0.028f, Group.BODY, BoneKind.NECK))
        add(Bone(head, head, HEAD_R, HEAD_R, Group.BODY, BoneKind.HEAD))
        leg(pose.pelvis, knee, ankle, toe(knee, ankle), Group.LEG)
        arm(shoulder, elbow, hand)
    }
    // A limb's front is a quarter turn from its line, as it is on a limb hanging straight down.
    // An arm folded the other way is one turned over to hold a bar behind the neck: its front is on its other side.
    val frames = limbs(trunk, limbFrames(shoulder, elbow, hand, -elbowBend), limbFrames(pose.pelvis, knee, ankle, 1f))
    return Body(bones, listOf(frames), listOf(hand), listOf(knee), listOf(ankle), head, pose.pelvis, up, front)
}

private fun frontBody(pose: Pose, hands: Hands, elbowBend: Float, kneeBend: Float): Body {
    val up = Offset(0f, 1f)
    val chest = pose.pelvis + up * TRUNK_LENGTH
    val head = pose.pelvis + up * (HEAD_AT + 0.005f)
    // Shoulders that lift take the whole girdle most of the way with them.
    val lift = up * pose.shrug
    val bones = ArrayList<Bone>()
    val limbs = ArrayList<Limbs>()
    val handsAt = ArrayList<Offset>()
    val knees = ArrayList<Offset>()
    val ankles = ArrayList<Offset>()
    bones += Bone(pose.pelvis + Offset(-0.045f, 0f), pose.pelvis + Offset(0.045f, 0f), 0.062f, 0.062f, Group.BODY, BoneKind.HIPS)
    bones += Bone(pose.pelvis + up * 0.04f, pose.pelvis + up * 0.2f, 0.08f, 0.094f, Group.BODY, BoneKind.TRUNK)
    bones += Bone(chest + Offset(-0.098f, -0.004f) + lift * 0.8f, chest + Offset(0.098f, -0.004f) + lift * 0.8f, 0.043f, 0.043f, Group.BODY, BoneKind.GIRDLE)
    bones += Bone(chest + up * 0.02f, head, 0.03f, 0.028f, Group.BODY, BoneKind.NECK)
    bones += Bone(head, head, HEAD_R - 0.004f, HEAD_R - 0.004f, Group.BODY, BoneKind.HEAD)
    for (side in listOf(1f, -1f)) {
        val shoulder = chest + Offset(0.118f * side, -0.004f) + lift
        val (elbow, hand) = reach(pose, hands, shoulder, up, Offset(side, 0f), elbowBend * side, side)
        val hip = pose.pelvis + Offset(0.055f * side, -0.01f)
        val (knee, ankle) = fold(hip, Offset(pose.foot.x * side, pose.foot.y), THIGH * pose.thigh, SHIN, kneeBend * side)
        // Seen from the front or back a leg lies over nothing of the trunk: it is painted as one part with it.
        // A foot is seen end on: toes pointed down show as more of it below the ankle.
        bones.leg(hip, knee, ankle, ankle + Offset(0.018f * side, -0.022f + 0.085f * sin(radians(pose.sole))), Group.BODY)
        bones.arm(shoulder, elbow, hand)
        handsAt += hand
        knees += knee
        ankles += ankle
        // Out from the midline is a quarter turn from a hanging limb's line, one way on each side.
        limbs += limbs(Torso(pose.pelvis, up * TRUNK_LENGTH, Offset(side, 0f), 0f, pose.shrug), limbFrames(shoulder, elbow, hand, side), limbFrames(hip, knee, ankle, side))
    }
    return Body(bones, limbs, handsAt, knees, ankles, head, pose.pelvis, up, Offset(1f, 0f))
}

/** The bench, machine or iron a movement is done on, with the parts that move where the pose puts them. */
private fun props(movement: Movement, pose: Pose, body: Body): List<Prop> = buildList {
    fun frame(ax: Float, ay: Float, bx: Float, by: Float, r: Float = 0.012f) = add(Prop(PropKind.FRAME, Offset(ax, ay), Offset(bx, by), r))
    fun strut(a: Offset, b: Offset, r: Float = 0.012f) = add(Prop(PropKind.FRAME, a, b, r))
    fun pad(a: Offset, b: Offset, r: Float = 0.024f) = add(Prop(PropKind.PAD, a, b, r))
    fun cable(a: Offset, b: Offset) = add(Prop(PropKind.CABLE, a, b, 0.003f))
    val hands = body.hands
    val pelvis = body.pelvis

    /** A bar seen end on, with its plate. */
    fun barbell(at: Offset, plate: Float) {
        add(Prop(PropKind.WEIGHT, at, plate))
        add(Prop(PropKind.GRIP, at, 0.013f))
    }

    /** A dumbbell in each hand: seen end on, or with [along] seen from its side, its handle lying that way. */
    fun dumbbells(along: Offset? = null) = hands.forEach { hand ->
        if (along == null) {
            add(Prop(PropKind.WEIGHT, hand, hand, 0.038f, front = true))
        } else {
            add(Prop(PropKind.FRAME, hand - along * 0.05f, hand + along * 0.05f, 0.008f, front = true))
            for (end in listOf(-1f, 1f)) {
                val at = hand + along * (0.052f * end)
                add(Prop(PropKind.WEIGHT, at - along.turned() * 0.032f, at + along.turned() * 0.032f, 0.015f, front = true))
            }
        }
        add(Prop(PropKind.GRIP, hand, 0.011f))
    }

    /** A machine's seat seen from the front or from behind, on its post; with [back], the pad the lifter sits against. */
    fun seat(back: Boolean = false) {
        frame(-0.2f, 0.012f, 0.2f, 0.012f)
        frame(0f, 0.012f, 0f, pelvis.y - 0.1f, r = 0.02f)
        if (back) pad(pelvis + Offset(0f, 0.02f), pelvis + Offset(0f, 0.43f), 0.07f)
        pad(pelvis + Offset(-0.1f, -0.082f), pelvis + Offset(0.1f, -0.082f), 0.022f)
    }

    /** A cable tower either side of the lifter, a pulley at [height] on each; each hand holds the cable of the tower on its own side, or with [crossed] the other one. */
    fun towers(height: Float, crossed: Boolean) {
        for ((index, side) in listOf(1f, -1f).withIndex()) {
            val pulley = Offset(0.5f * side, height)
            frame(0.5f * side, 0.012f, 0.5f * side, height + 0.07f, r = 0.016f)
            frame(0.42f * side, 0.012f, 0.54f * side, 0.012f)
            add(Prop(PropKind.FRAME, pulley, 0.02f))
            cable(pulley, hands[if (crossed) 1 - index else index])
        }
        hands.forEach { add(Prop(PropKind.GRIP, it, 0.012f)) }
    }

    /** The pad of a machine that the lower leg pushes on, [toward] its front or its back, on an arm from the knee's hinge. */
    fun roller(toward: Float) {
        val knee = body.knees.first()
        val ankle = body.ankles.first()
        val shin = (ankle - knee).unit()
        val at = ankle + shin.turned() * (0.058f * toward) - shin * 0.015f
        strut(knee, at, r = 0.01f)
        add(Prop(PropKind.PAD, at, at, 0.03f, front = true))
        add(Prop(PropKind.FRAME, at, at, 0.03f, front = true))
    }

    when (movement) {
        Movement.INCLINE_PRESS -> {
            // The back pad lies under the trunk at its angle, clear of the buttock; the seat is under the hips, tipped up a little at the knee.
            val under = body.pelvis - body.front * 0.094f
            frame(-0.36f, 0.012f, 0.2f, 0.012f)
            frame(-0.25f, 0.012f, -0.25f, 0.36f)
            frame(0.06f, 0.012f, 0.06f, 0.18f)
            frame(-0.25f, 0.18f, 0.06f, 0.18f)
            add(Prop(PropKind.PAD, under + body.up * 0.075f, under + body.up * 0.43f, 0.024f))
            add(Prop(PropKind.PAD, Offset(-0.005f, 0.193f), Offset(0.15f, 0.206f), 0.024f))
            add(Prop(PropKind.WEIGHT, hands.first(), 0.088f))
            add(Prop(PropKind.GRIP, hands.first(), 0.013f))
        }

        Movement.LAT_PULLDOWN -> {
            // Seen from behind: the tower is beyond the lifter, the bar comes down in front of them.
            val bar = pose.hand.y
            frame(-0.2f, 0.012f, 0.2f, 0.012f)
            frame(0f, 0.012f, 0f, 1.05f, r = 0.02f)
            frame(-0.16f, 1.05f, 0.16f, 1.05f, r = 0.014f)
            add(Prop(PropKind.PAD, Offset(-0.1f, 0.25f), Offset(0.1f, 0.25f), 0.022f))
            add(Prop(PropKind.CABLE, Offset(0f, 1.03f), Offset(0f, bar), 0.003f))
            add(Prop(PropKind.FRAME, Offset(0f, 1.03f), 0.026f))
            add(Prop(PropKind.FRAME, Offset(-0.3f, bar - 0.02f), Offset(-0.24f, bar), 0.008f))
            add(Prop(PropKind.FRAME, Offset(-0.24f, bar), Offset(0.24f, bar), 0.008f))
            add(Prop(PropKind.FRAME, Offset(0.24f, bar), Offset(0.3f, bar - 0.02f), 0.008f))
        }

        Movement.SQUAT -> barbell(hands.first(), 0.1f)

        Movement.SQUAT_FRONT -> {
            // The bar across the shoulders, seen along its length, a plate edge on at each end.
            val bar = body.pelvis.y + TRUNK_LENGTH + 0.02f
            frame(-0.52f, bar, 0.52f, bar, r = 0.008f)
            for (side in listOf(-1f, 1f)) add(Prop(PropKind.WEIGHT, Offset(0.46f * side, bar - 0.1f), Offset(0.46f * side, bar + 0.1f), 0.016f))
        }

        Movement.LATERAL_RAISE, Movement.FRONT_RAISE, Movement.SHRUG, Movement.BICEPS_CURL -> dumbbells()

        Movement.BENCH_PRESS -> {
            // A flat bench under the back and the head, on two legs.
            val top = pelvis.y - 0.09f
            frame(pelvis.x - 0.46f, 0.012f, pelvis.x + 0.04f, 0.012f)
            frame(pelvis.x - 0.38f, 0.012f, pelvis.x - 0.38f, top - 0.03f)
            frame(pelvis.x - 0.04f, 0.012f, pelvis.x - 0.04f, top - 0.03f)
            pad(Offset(pelvis.x - 0.44f, top), Offset(pelvis.x + 0.04f, top))
            barbell(hands.first(), 0.11f)
        }

        Movement.OVERHEAD_PRESS -> {
            seat(back = true)
            // The bar is in front of the lifter all the way up, a plate edge on at each end.
            val bar = hands.first().y
            add(Prop(PropKind.FRAME, Offset(-0.5f, bar), Offset(0.5f, bar), 0.008f, front = true))
            for (side in listOf(-1f, 1f)) add(Prop(PropKind.WEIGHT, Offset(0.44f * side, bar - 0.1f), Offset(0.44f * side, bar + 0.1f), 0.016f, front = true))
        }

        Movement.CHEST_FLY -> {
            seat(back = true)
            towers(pelvis.y + 0.27f, crossed = false)
        }

        Movement.REAR_DELT_FLY -> {
            seat()
            towers(pelvis.y + 0.27f, crossed = true)
        }

        Movement.DIP -> {
            // The near bar, seen along its side, on its two posts; the legs hang between it and the far one.
            val rail = hands.first().y - 0.03f
            frame(-0.2f, 0.012f, 0.26f, 0.012f)
            frame(-0.12f, 0.012f, -0.12f, rail)
            frame(0.18f, 0.012f, 0.18f, rail)
            frame(-0.16f, rail, 0.22f, rail, r = 0.016f)
        }

        Movement.PULL_UP -> {
            val bar = hands.first().y
            for (side in listOf(-1f, 1f)) {
                frame(0.4f * side, 0.012f, 0.4f * side, bar, r = 0.016f)
                frame(0.32f * side, 0.012f, 0.48f * side, 0.012f)
            }
            frame(-0.4f, bar, 0.4f, bar)
        }

        Movement.SEATED_ROW -> {
            // A low seat, a plate for the feet, and the cable running level from a tower in front to the hands.
            val pulley = Offset(0.47f, 0.425f)
            frame(-0.26f, 0.012f, 0.53f, 0.012f)
            frame(pelvis.x, 0.012f, pelvis.x, pelvis.y - 0.11f)
            pad(pelvis + Offset(-0.08f, -0.09f), pelvis + Offset(0.13f, -0.09f))
            frame(0.325f, 0.11f, 0.405f, 0.22f, r = 0.016f)
            frame(0.365f, 0.012f, 0.365f, 0.16f)
            frame(0.5f, 0.012f, 0.5f, 0.62f, r = 0.016f)
            add(Prop(PropKind.WEIGHT, Offset(0.5f, 0.07f), Offset(0.5f, 0.3f), 0.03f))
            add(Prop(PropKind.FRAME, pulley, 0.02f))
            cable(pulley, hands.first())
            add(Prop(PropKind.GRIP, hands.first(), 0.013f))
        }

        Movement.TRICEPS_PUSHDOWN -> {
            // The tower in front, its pulley over the hands.
            val pulley = Offset(0.2f, 1.01f)
            frame(0.2f, 0.012f, 0.44f, 0.012f)
            frame(0.36f, 0.012f, 0.36f, 1.04f, r = 0.016f)
            frame(0.36f, 1.04f, 0.2f, 1.04f)
            add(Prop(PropKind.WEIGHT, Offset(0.36f, 0.08f), Offset(0.36f, 0.36f), 0.03f))
            add(Prop(PropKind.FRAME, pulley, 0.02f))
            cable(pulley, hands.first())
            add(Prop(PropKind.GRIP, hands.first(), 0.013f))
        }

        Movement.LEG_PRESS -> {
            // The sled rides its rails up and away from the seat; the feet are flat on its plate, the weights behind that.
            val under = pelvis - body.front * 0.094f
            val ankle = body.ankles.first()
            val push = (ankle - pelvis).unit()
            val plate = ankle + push * 0.045f + push.turned() * 0.03f
            val rail = pelvis - push.turned() * 0.1f
            frame(-0.42f, 0.012f, 0.5f, 0.012f)
            strut(rail - push * 0.06f, rail + push * 0.74f)
            strut(rail + push * 0.68f, Offset((rail + push * 0.68f).x, 0.012f))
            strut(rail - push * 0.06f, Offset((rail - push * 0.06f).x, 0.012f))
            pad(under + body.up * 0.05f, under + body.up * 0.42f)
            pad(under - body.up * 0.03f, under - body.up * 0.03f + Offset(0.1f, -0.03f))
            add(Prop(PropKind.WEIGHT, plate + push * 0.085f, 0.07f))
            strut(plate + push * 0.012f, plate + push * 0.085f)
            strut(plate - push.turned() * 0.11f, plate + push.turned() * 0.13f, r = 0.022f)
        }

        Movement.LEG_EXTENSION -> {
            // A high seat tipped back, the pad on the front of the ankles.
            val under = pelvis - body.front * 0.094f
            frame(pelvis.x - 0.12f, 0.012f, pelvis.x + 0.3f, 0.012f)
            frame(pelvis.x + 0.09f, 0.012f, pelvis.x + 0.09f, pelvis.y - 0.11f, r = 0.02f)
            pad(under + body.up * 0.05f, under + body.up * 0.42f)
            pad(pelvis + Offset(-0.03f, -0.086f), pelvis + Offset(0.2f, -0.086f))
            roller(toward = 1f)
        }

        Movement.LEG_CURL -> {
            // A bench to lie along, the pad on the back of the ankles.
            val top = pelvis.y - 0.094f
            frame(pelvis.x - 0.22f, 0.012f, pelvis.x + 0.36f, 0.012f)
            frame(pelvis.x - 0.12f, 0.012f, pelvis.x - 0.12f, top - 0.03f)
            frame(pelvis.x + 0.26f, 0.012f, pelvis.x + 0.26f, top - 0.03f)
            pad(Offset(pelvis.x - 0.2f, top), Offset(pelvis.x + 0.34f, top))
            strut(Offset(pelvis.x + 0.26f, top - 0.06f), hands.first(), r = 0.008f)
            add(Prop(PropKind.GRIP, hands.first(), 0.012f))
            roller(toward = -1f)
        }

        Movement.CALF_RAISE -> {
            // The step the balls of the feet are on, its top edge picked out.
            pad(Offset(0.085f, 0.025f), Offset(0.275f, 0.025f), 0.025f)
            frame(0.062f, 0.05f, 0.298f, 0.05f, r = 0.006f)
            frame(0.062f, 0.05f, 0.062f, 0.006f, r = 0.006f)
            frame(0.298f, 0.05f, 0.298f, 0.006f, r = 0.006f)
            dumbbells(along = Offset(1f, 0f))
        }

        Movement.HIP_HINGE -> barbell(hands.first(), 0.1f)

        Movement.HIP_ABDUCTION, Movement.HIP_ADDUCTION -> {
            // The machine's pads against the knees: outside them to push apart, inside them to squeeze together.
            val out = if (movement == Movement.HIP_ABDUCTION) 0.062f else -0.058f
            seat(back = movement == Movement.HIP_ADDUCTION)
            body.knees.forEachIndexed { index, knee ->
                val side = if (index == 0) 1f else -1f
                val at = knee + Offset(out * side, 0f)
                strut(Offset(at.x, pelvis.y - 0.1f), at, r = 0.01f)
                add(Prop(PropKind.PAD, at + Offset(0f, -0.045f), at + Offset(0f, 0.045f), 0.014f, front = true))
                add(Prop(PropKind.FRAME, at + Offset(0.014f * side, -0.045f), at + Offset(0.014f * side, 0.045f), 0.004f, front = true))
            }
        }

        Movement.CRUNCH -> {
            pad(Offset(-0.44f, 0.01f), Offset(0.47f, 0.01f), 0.01f)
            frame(-0.44f, 0.02f, 0.47f, 0.02f, r = 0.004f)
        }
    }
}
