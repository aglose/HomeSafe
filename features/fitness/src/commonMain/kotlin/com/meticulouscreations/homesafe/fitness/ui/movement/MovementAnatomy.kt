package com.meticulouscreations.homesafe.fitness.ui.movement

import androidx.compose.ui.geometry.Offset
import com.meticulouscreations.homesafe.fitness.domain.Muscle

// The superficial muscles, head by head, as they lie on the rig. Each head is a band between two
// borders that run from its origin to its insertion; every point of a border is fastened to a
// body part (its Frame), so a muscle that crosses a joint stretches and folds as the joint moves.
// Its fibres are the lines in between, which is what makes a pectoral fan and a biceps run straight.
// Deep muscles (vastus intermedius, supraspinatus, the rhomboids) are under these and not drawn.

/** A head of a muscle, or a muscle that has only one. [muscle] is what its work is counted under. */
internal enum class HeadId(val muscle: Muscle?) {
    STERNOCLEIDOMASTOID(null),
    TRAPEZIUS_UPPER(Muscle.TRAPS),
    TRAPEZIUS_MIDDLE(Muscle.TRAPS),
    TRAPEZIUS_LOWER(Muscle.TRAPS),
    DELTOID_ANTERIOR(Muscle.FRONT_DELTS),
    DELTOID_LATERAL(Muscle.SIDE_DELTS),
    DELTOID_POSTERIOR(Muscle.REAR_DELTS),
    PECTORALIS_CLAVICULAR(Muscle.CHEST),
    PECTORALIS_STERNAL(Muscle.CHEST),
    SERRATUS_ANTERIOR(null),
    RECTUS_ABDOMINIS(Muscle.ABS),
    EXTERNAL_OBLIQUE(Muscle.ABS),
    LATISSIMUS_DORSI(Muscle.BACK),
    TERES_MAJOR(Muscle.BACK),
    TERES_MINOR(Muscle.REAR_DELTS),
    INFRASPINATUS(Muscle.REAR_DELTS),
    ERECTOR_SPINAE(Muscle.BACK),
    BICEPS_LONG(Muscle.BICEPS),
    BICEPS_SHORT(Muscle.BICEPS),
    BRACHIALIS(Muscle.BICEPS),
    TRICEPS_LONG(Muscle.TRICEPS),
    TRICEPS_LATERAL(Muscle.TRICEPS),
    TRICEPS_MEDIAL(Muscle.TRICEPS),
    BRACHIORADIALIS(Muscle.FOREARMS),
    WRIST_FLEXORS(Muscle.FOREARMS),
    FLEXOR_CARPI_ULNARIS(Muscle.FOREARMS),
    EXTENSOR_CARPI_RADIALIS(Muscle.FOREARMS),
    EXTENSOR_DIGITORUM(Muscle.FOREARMS),
    EXTENSOR_CARPI_ULNARIS(Muscle.FOREARMS),
    GLUTEUS_MEDIUS(Muscle.GLUTES),
    GLUTEUS_MAXIMUS(Muscle.GLUTES),
    TENSOR_FASCIAE_LATAE(Muscle.GLUTES),
    SARTORIUS(null),
    RECTUS_FEMORIS(Muscle.QUADS),
    VASTUS_LATERALIS(Muscle.QUADS),
    VASTUS_MEDIALIS(Muscle.QUADS),
    ADDUCTOR_LONGUS(Muscle.ADDUCTORS),
    ADDUCTOR_MAGNUS(Muscle.ADDUCTORS),
    GRACILIS(Muscle.ADDUCTORS),
    BICEPS_FEMORIS_LONG(Muscle.HAMSTRINGS),
    BICEPS_FEMORIS_SHORT(Muscle.HAMSTRINGS),
    SEMITENDINOSUS(Muscle.HAMSTRINGS),
    SEMIMEMBRANOSUS(Muscle.HAMSTRINGS),
    TIBIALIS_ANTERIOR(null),
    EXTENSOR_DIGITORUM_LONGUS(null),
    PERONEUS_LONGUS(null),
    GASTROCNEMIUS_LATERAL(Muscle.CALVES),
    GASTROCNEMIUS_MEDIAL(Muscle.CALVES),
    SOLEUS(Muscle.CALVES),
}

/** A point on a body part: [along] it and [out] to its side, as [Frame.at] reads them; on a joint, [turn] is how much of its bend the point goes with ([Joint.at]). */
internal class Anchor(val seg: Seg, val along: Float, val out: Float, val turn: Float = 0.5f)

/**
 * A band of muscle or tendon. [a] and [b] are its two borders, each from origin to insertion.
 * [tendonIn] and [tendonOut] are how much of its length is tendon at either end; [cuts] are
 * tendinous lines across it (the abdominals'). [id] null is tendon or bone alone. It is drawn
 * with [group], before that group's bones when [under]. [dome] 0–1 is how much it rounds along
 * its length as well as across: little for a strap, a lot for a mass like the buttock.
 */
internal class Head(
    val id: HeadId?,
    val group: Group,
    val a: List<Anchor>,
    val b: List<Anchor>,
    val tendonIn: Float = 0.08f,
    val tendonOut: Float = 0.12f,
    val cuts: List<Float> = emptyList(),
    val under: Boolean = false,
    val dome: Float = 0.3f,
)

/** A bony point that shows between the muscles: the kneecap. */
internal class Landmark(val seg: Seg, val along: Float, val out: Float, val r: Float, val group: Group)

/** How much a movement asks of a head: the roles kinesiology gives them. */
internal const val PRIME_MOVER = 1f
internal const val SYNERGIST = 0.6f
internal const val STABILIZER = 0.3f

/**
 * Which heads a movement works, and how hard, by their usual roles (a prime mover does the lift,
 * a synergist helps it, a stabilizer holds a joint still for it). These are roles, not
 * measurements: no number here comes from an EMG study.
 */
internal fun Movement.work(): Map<HeadId, Float> = when (this) {
    Movement.INCLINE_PRESS -> mapOf(
        HeadId.PECTORALIS_CLAVICULAR to PRIME_MOVER,
        HeadId.DELTOID_ANTERIOR to PRIME_MOVER,
        HeadId.PECTORALIS_STERNAL to SYNERGIST,
        HeadId.TRICEPS_LATERAL to SYNERGIST,
        HeadId.TRICEPS_MEDIAL to SYNERGIST,
        HeadId.TRICEPS_LONG to SYNERGIST,
        HeadId.SERRATUS_ANTERIOR to STABILIZER,
        HeadId.DELTOID_LATERAL to STABILIZER,
    )

    Movement.LAT_PULLDOWN -> mapOf(
        HeadId.LATISSIMUS_DORSI to PRIME_MOVER,
        HeadId.TERES_MAJOR to PRIME_MOVER,
        HeadId.DELTOID_POSTERIOR to SYNERGIST,
        HeadId.TRAPEZIUS_LOWER to SYNERGIST,
        HeadId.TRAPEZIUS_MIDDLE to SYNERGIST,
        HeadId.BRACHIORADIALIS to SYNERGIST,
        HeadId.BICEPS_LONG to SYNERGIST,
        HeadId.BICEPS_SHORT to SYNERGIST,
        HeadId.BRACHIALIS to SYNERGIST,
        HeadId.TRICEPS_LONG to STABILIZER,
        HeadId.INFRASPINATUS to STABILIZER,
        HeadId.TERES_MINOR to STABILIZER,
    )

    Movement.SQUAT, Movement.SQUAT_FRONT -> mapOf(
        HeadId.VASTUS_LATERALIS to PRIME_MOVER,
        HeadId.VASTUS_MEDIALIS to PRIME_MOVER,
        HeadId.GLUTEUS_MAXIMUS to PRIME_MOVER,
        HeadId.RECTUS_FEMORIS to SYNERGIST,
        HeadId.ADDUCTOR_MAGNUS to SYNERGIST,
        HeadId.SOLEUS to SYNERGIST,
        HeadId.BICEPS_FEMORIS_LONG to STABILIZER,
        HeadId.SEMITENDINOSUS to STABILIZER,
        HeadId.GASTROCNEMIUS_LATERAL to STABILIZER,
        HeadId.GLUTEUS_MEDIUS to STABILIZER,
        HeadId.ERECTOR_SPINAE to STABILIZER,
        HeadId.RECTUS_ABDOMINIS to STABILIZER,
        HeadId.EXTERNAL_OBLIQUE to STABILIZER,
    )

    Movement.LATERAL_RAISE -> mapOf(
        HeadId.DELTOID_LATERAL to PRIME_MOVER,
        HeadId.DELTOID_ANTERIOR to SYNERGIST,
        HeadId.TRAPEZIUS_UPPER to SYNERGIST,
        HeadId.SERRATUS_ANTERIOR to SYNERGIST,
        HeadId.BRACHIORADIALIS to STABILIZER,
    )

    Movement.BENCH_PRESS -> mapOf(
        HeadId.PECTORALIS_STERNAL to PRIME_MOVER,
        HeadId.PECTORALIS_CLAVICULAR to SYNERGIST,
        HeadId.DELTOID_ANTERIOR to SYNERGIST,
        HeadId.TRICEPS_LATERAL to SYNERGIST,
        HeadId.TRICEPS_MEDIAL to SYNERGIST,
        HeadId.TRICEPS_LONG to SYNERGIST,
        HeadId.SERRATUS_ANTERIOR to STABILIZER,
    )

    Movement.OVERHEAD_PRESS -> mapOf(
        HeadId.DELTOID_ANTERIOR to PRIME_MOVER,
        HeadId.DELTOID_LATERAL to PRIME_MOVER,
        HeadId.TRICEPS_LATERAL to SYNERGIST,
        HeadId.TRICEPS_MEDIAL to SYNERGIST,
        HeadId.TRICEPS_LONG to SYNERGIST,
        HeadId.TRAPEZIUS_UPPER to SYNERGIST,
        HeadId.SERRATUS_ANTERIOR to SYNERGIST,
        HeadId.PECTORALIS_CLAVICULAR to SYNERGIST,
        HeadId.RECTUS_ABDOMINIS to STABILIZER,
        HeadId.EXTERNAL_OBLIQUE to STABILIZER,
    )

    Movement.CHEST_FLY -> mapOf(
        HeadId.PECTORALIS_STERNAL to PRIME_MOVER,
        HeadId.PECTORALIS_CLAVICULAR to PRIME_MOVER,
        HeadId.DELTOID_ANTERIOR to SYNERGIST,
        HeadId.BICEPS_SHORT to STABILIZER,
        HeadId.SERRATUS_ANTERIOR to STABILIZER,
    )

    Movement.REAR_DELT_FLY -> mapOf(
        HeadId.DELTOID_POSTERIOR to PRIME_MOVER,
        HeadId.TRAPEZIUS_MIDDLE to SYNERGIST,
        HeadId.INFRASPINATUS to SYNERGIST,
        HeadId.TERES_MINOR to SYNERGIST,
        HeadId.TRAPEZIUS_LOWER to SYNERGIST,
        HeadId.TRICEPS_LONG to STABILIZER,
    )

    Movement.FRONT_RAISE -> mapOf(
        HeadId.DELTOID_ANTERIOR to PRIME_MOVER,
        HeadId.PECTORALIS_CLAVICULAR to SYNERGIST,
        HeadId.DELTOID_LATERAL to SYNERGIST,
        HeadId.TRAPEZIUS_UPPER to SYNERGIST,
        HeadId.SERRATUS_ANTERIOR to SYNERGIST,
    )

    Movement.SHRUG -> mapOf(
        HeadId.TRAPEZIUS_UPPER to PRIME_MOVER,
        HeadId.TRAPEZIUS_MIDDLE to SYNERGIST,
        HeadId.WRIST_FLEXORS to STABILIZER,
        HeadId.FLEXOR_CARPI_ULNARIS to STABILIZER,
        HeadId.ERECTOR_SPINAE to STABILIZER,
    )

    Movement.BICEPS_CURL -> mapOf(
        HeadId.BICEPS_LONG to PRIME_MOVER,
        HeadId.BICEPS_SHORT to PRIME_MOVER,
        HeadId.BRACHIALIS to SYNERGIST,
        HeadId.BRACHIORADIALIS to SYNERGIST,
        HeadId.DELTOID_ANTERIOR to STABILIZER,
        HeadId.WRIST_FLEXORS to STABILIZER,
    )

    Movement.TRICEPS_PUSHDOWN -> mapOf(
        HeadId.TRICEPS_LATERAL to PRIME_MOVER,
        HeadId.TRICEPS_MEDIAL to PRIME_MOVER,
        HeadId.TRICEPS_LONG to SYNERGIST,
        HeadId.LATISSIMUS_DORSI to STABILIZER,
        HeadId.RECTUS_ABDOMINIS to STABILIZER,
        HeadId.EXTENSOR_CARPI_ULNARIS to STABILIZER,
    )

    Movement.DIP -> mapOf(
        HeadId.TRICEPS_LATERAL to PRIME_MOVER,
        HeadId.TRICEPS_MEDIAL to PRIME_MOVER,
        HeadId.TRICEPS_LONG to PRIME_MOVER,
        HeadId.PECTORALIS_STERNAL to PRIME_MOVER,
        HeadId.DELTOID_ANTERIOR to SYNERGIST,
        HeadId.LATISSIMUS_DORSI to STABILIZER,
        HeadId.TRAPEZIUS_LOWER to STABILIZER,
    )

    Movement.PULL_UP -> mapOf(
        HeadId.LATISSIMUS_DORSI to PRIME_MOVER,
        HeadId.TERES_MAJOR to PRIME_MOVER,
        HeadId.DELTOID_POSTERIOR to SYNERGIST,
        HeadId.TRAPEZIUS_LOWER to SYNERGIST,
        HeadId.TRAPEZIUS_MIDDLE to SYNERGIST,
        HeadId.BRACHIORADIALIS to SYNERGIST,
        HeadId.BICEPS_LONG to SYNERGIST,
        HeadId.BICEPS_SHORT to SYNERGIST,
        HeadId.BRACHIALIS to SYNERGIST,
        HeadId.RECTUS_ABDOMINIS to STABILIZER,
        HeadId.INFRASPINATUS to STABILIZER,
        HeadId.TERES_MINOR to STABILIZER,
    )

    Movement.SEATED_ROW -> mapOf(
        HeadId.LATISSIMUS_DORSI to PRIME_MOVER,
        HeadId.TRAPEZIUS_MIDDLE to PRIME_MOVER,
        HeadId.TERES_MAJOR to SYNERGIST,
        HeadId.DELTOID_POSTERIOR to SYNERGIST,
        HeadId.TRAPEZIUS_LOWER to SYNERGIST,
        HeadId.BICEPS_LONG to SYNERGIST,
        HeadId.BICEPS_SHORT to SYNERGIST,
        HeadId.BRACHIALIS to SYNERGIST,
        HeadId.BRACHIORADIALIS to SYNERGIST,
        HeadId.ERECTOR_SPINAE to STABILIZER,
    )

    Movement.LEG_PRESS -> mapOf(
        HeadId.VASTUS_LATERALIS to PRIME_MOVER,
        HeadId.VASTUS_MEDIALIS to PRIME_MOVER,
        HeadId.GLUTEUS_MAXIMUS to PRIME_MOVER,
        HeadId.RECTUS_FEMORIS to SYNERGIST,
        HeadId.ADDUCTOR_MAGNUS to SYNERGIST,
        HeadId.SOLEUS to SYNERGIST,
        HeadId.BICEPS_FEMORIS_LONG to STABILIZER,
        HeadId.SEMITENDINOSUS to STABILIZER,
    )

    Movement.LEG_EXTENSION -> mapOf(
        HeadId.RECTUS_FEMORIS to PRIME_MOVER,
        HeadId.VASTUS_LATERALIS to PRIME_MOVER,
        HeadId.VASTUS_MEDIALIS to PRIME_MOVER,
        HeadId.TIBIALIS_ANTERIOR to STABILIZER,
    )

    Movement.LEG_CURL -> mapOf(
        HeadId.BICEPS_FEMORIS_LONG to PRIME_MOVER,
        HeadId.SEMITENDINOSUS to PRIME_MOVER,
        HeadId.SEMIMEMBRANOSUS to PRIME_MOVER,
        HeadId.BICEPS_FEMORIS_SHORT to PRIME_MOVER,
        HeadId.GASTROCNEMIUS_LATERAL to SYNERGIST,
        HeadId.GASTROCNEMIUS_MEDIAL to SYNERGIST,
        HeadId.GLUTEUS_MAXIMUS to STABILIZER,
    )

    Movement.CALF_RAISE -> mapOf(
        HeadId.GASTROCNEMIUS_LATERAL to PRIME_MOVER,
        HeadId.GASTROCNEMIUS_MEDIAL to PRIME_MOVER,
        HeadId.SOLEUS to PRIME_MOVER,
        HeadId.PERONEUS_LONGUS to SYNERGIST,
        HeadId.TIBIALIS_ANTERIOR to STABILIZER,
    )

    Movement.HIP_HINGE -> mapOf(
        HeadId.GLUTEUS_MAXIMUS to PRIME_MOVER,
        HeadId.BICEPS_FEMORIS_LONG to PRIME_MOVER,
        HeadId.SEMITENDINOSUS to PRIME_MOVER,
        HeadId.SEMIMEMBRANOSUS to PRIME_MOVER,
        HeadId.ERECTOR_SPINAE to SYNERGIST,
        HeadId.ADDUCTOR_MAGNUS to SYNERGIST,
        HeadId.LATISSIMUS_DORSI to STABILIZER,
        HeadId.TRAPEZIUS_UPPER to STABILIZER,
        HeadId.WRIST_FLEXORS to STABILIZER,
    )

    Movement.HIP_ABDUCTION -> mapOf(
        HeadId.GLUTEUS_MEDIUS to PRIME_MOVER,
        HeadId.TENSOR_FASCIAE_LATAE to SYNERGIST,
        HeadId.GLUTEUS_MAXIMUS to SYNERGIST,
    )

    Movement.HIP_ADDUCTION -> mapOf(
        HeadId.ADDUCTOR_LONGUS to PRIME_MOVER,
        HeadId.ADDUCTOR_MAGNUS to PRIME_MOVER,
        HeadId.GRACILIS to PRIME_MOVER,
    )

    Movement.CRUNCH -> mapOf(
        HeadId.RECTUS_ABDOMINIS to PRIME_MOVER,
        HeadId.EXTERNAL_OBLIQUE to SYNERGIST,
        HeadId.RECTUS_FEMORIS to STABILIZER,
        HeadId.TENSOR_FASCIAE_LATAE to STABILIZER,
    )
}

/** The heads [view] shows, in the order they are painted. */
internal fun heads(view: View): List<Head> = when (view) {
    View.SIDE -> SIDE_HEADS
    View.FRONT -> FRONT_HEADS
    View.BACK -> BACK_HEADS
}

internal fun landmarks(view: View): List<Landmark> = when (view) {
    View.SIDE -> listOf(Landmark(Seg.KNEE, 0f, 0.036f, 0.014f, Group.LEG))
    View.FRONT -> listOf(Landmark(Seg.KNEE, 0f, 0f, 0.018f, Group.LEG))
    View.BACK -> listOf(Landmark(Seg.UPPER_ARM, 1f, 0f, 0.009f, Group.ARM))
}

/** A head's two borders as they stand on one side of [figure], each sampled at [BAND_STEPS] points from origin to insertion. */
internal class Band(val head: Head, val limb: Int, val a: Array<Offset>, val b: Array<Offset>) {
    /** The middle of the belly. */
    val center: Offset get() = (a[BAND_STEPS / 2] + b[BAND_STEPS / 2]) / 2f
}

internal const val BAND_STEPS = 21

/** Every head [figure] shows, on each side it has, in painting order. */
internal fun bands(figure: Figure): List<Band> = heads(figure.view).flatMap { head ->
    figure.limbs.mapIndexed { index, limbs ->
        Band(head, index, curve(head.a.map { limbs.at(it.seg, it.along, it.out, it.turn) }), curve(head.b.map { limbs.at(it.seg, it.along, it.out, it.turn) }))
    }
}

/**
 * A smooth line through [points] (a Catmull-Rom spline), as [BAND_STEPS] points, the same number
 * to each stretch between two of them. Where it passes a point it leans along the line between
 * that point's neighbours, by this stretch's share of the way between them: points drawn close
 * together by a bent joint would otherwise be overshot, and the border would bulge or loop there.
 */
private fun curve(points: List<Offset>): Array<Offset> {
    val spans = points.size - 1
    return Array(BAND_STEPS) { step ->
        val at = step / (BAND_STEPS - 1f) * spans
        val i = at.toInt().coerceAtMost(spans - 1)
        val u = at - i
        val p1 = points[i]
        val p2 = points[i + 1]
        val reach = (p2 - p1).getDistance()
        val m1 = if (i == 0) (p2 - p1) / 2f else lean(points[i - 1], p2, (p1 - points[i - 1]).getDistance(), reach)
        val m2 = if (i == spans - 1) (p2 - p1) / 2f else lean(p1, points[i + 2], (points[i + 2] - p2).getDistance(), reach)
        val uu = u * u
        val uuu = uu * u
        p1 * (2f * uuu - 3f * uu + 1f) + m1 * (uuu - 2f * uu + u) + p2 * (3f * uu - 2f * uuu) + m2 * (uuu - uu)
    }
}

/** How a spline leans at a point between [before] and [after], over a stretch [reach] long whose neighbour on the point's other side is [other] long. */
private fun lean(before: Offset, after: Offset, other: Float, reach: Float): Offset = (after - before) * (reach / (other + reach).coerceAtLeast(1e-6f))

private fun t(along: Float, out: Float) = Anchor(Seg.TRUNK, along, out)

private fun a(along: Float, out: Float) = Anchor(Seg.UPPER_ARM, along, out)

private fun f(along: Float, out: Float) = Anchor(Seg.FOREARM, along, out)

private fun h(along: Float, out: Float) = Anchor(Seg.THIGH, along, out)

private fun s(along: Float, out: Float) = Anchor(Seg.SHIN, along, out)

private fun sh(along: Float, out: Float, turn: Float) = Anchor(Seg.SHOULDER, along, out, turn)

private fun hp(along: Float, out: Float, turn: Float = 0.5f) = Anchor(Seg.HIP, along, out, turn)

/** How far the shoulder blade follows the arm: a third of the way, near enough. */
private const val BLADE = 0.35f

// The cap of the shoulder is fastened like the buttock: its origin goes with the blade, its
// insertion with the arm. A border the raised arm closes on has points between that turn by a
// share growing from one to the other, so it draws up like a fan and never folds back over its
// own origin. A border the arm opens away from has none, so it is pulled straight between the two.

/** The same seen from the side, where an arm raised forward tips the blade about a line across the picture: its point hardly moves. */
private const val BLADE_SIDE = 0.12f

/** How far the collarbone's middle follows an arm raised to the side: hardly at all, where the blade's spine behind it swings down. */
private const val COLLAR = 0.12f

/** Round the cap at its fullest, on the side the arm closes on as it is raised. */
private const val CAP = 0.72f

/** Where the cap has all but joined the arm. */
private const val CAP_END = 0.93f

/** Between the lateral head and the anterior, and between it and the posterior, as a side view sees them, short of where each ends on the arm. */
private val CAP_SEAM_AHEAD = listOf(sh(-0.23f, 0.018f, BLADE_SIDE), sh(0.3f, 0.014f, 0.95f))
private val CAP_SEAM_BEHIND = listOf(sh(-0.23f, -0.018f, BLADE_SIDE), sh(0.3f, -0.01f, 0.95f))

/** Between the lateral head and the head in front of it or behind it, as a front or back view sees it: down the face of the shoulder. */
private val CAP_SEAM_FACING = listOf(sh(-0.236f, -0.006f, BLADE), sh(0.08f, 0.016f, 0.7f), sh(0.28f, 0.02f, CAP_END), sh(0.5f, 0.016f, 1f))

private val BODY = Group.BODY
private val LEG = Group.LEG
private val ARM = Group.ARM

/** What a leg's heads are painted with from the front or from behind: there nothing of the leg lies over the trunk, and they are one part. */
private val TRUNK_LEG = Group.BODY

// What both a front and a back view show: the cap of the shoulder.
private val DELTOID_LATERAL_FACING = Head(
    HeadId.DELTOID_LATERAL,
    ARM,
    a = CAP_SEAM_FACING,
    b = listOf(sh(-0.255f, 0.01f, BLADE), sh(0.1f, 0.062f, CAP), sh(0.24f, 0.064f, 0.8f), sh(0.38f, 0.05f, 0.92f), sh(0.54f, 0.02f, 1f)),
    tendonIn = 0.04f,
    tendonOut = 0.06f,
    dome = 0.8f,
)

/** The tendinous lines across the rectus, as shares of its length: at the navel, and at a third and two thirds of the way from there to the ribs. */
private val RECTUS_CUTS = listOf(0.41f, 0.66f, 0.89f)

/** From the front, out from the midline: the lifter's left is on the right of the picture, and each side is the other's mirror. */
private val FRONT_HEADS: List<Head> = listOf(
    Head(HeadId.TRAPEZIUS_UPPER, BODY, listOf(t(1.3f, 0.03f), t(1.2f, 0.045f), t(1.14f, 0.085f), t(1.125f, 0.125f)), listOf(t(1.22f, 0.032f), t(1.13f, 0.05f), t(1.105f, 0.085f), t(1.1f, 0.12f))),
    Head(HeadId.STERNOCLEIDOMASTOID, BODY, listOf(t(1.36f, 0.045f), t(1.2f, 0.03f), t(1.075f, 0.008f)), listOf(t(1.33f, 0.058f), t(1.18f, 0.045f), t(1.085f, 0.03f))),
    // The collarbone.
    Head(null, BODY, listOf(t(1.09f, 0.012f), t(1.118f, 0.118f)), listOf(t(1.072f, 0.012f), t(1.1f, 0.118f))),
    Head(HeadId.LATISSIMUS_DORSI, BODY, listOf(t(0.5f, 0.094f), t(0.72f, 0.1f), a(0.26f, -0.03f)), listOf(t(0.5f, 0.102f), t(0.74f, 0.118f), a(0.3f, -0.024f))),
    Head(HeadId.EXTERNAL_OBLIQUE, BODY, listOf(t(0.7f, 0.052f), t(0.45f, 0.052f), t(0.12f, 0.04f)), listOf(t(0.68f, 0.092f), t(0.45f, 0.088f), t(0.3f, 0.084f), t(0.13f, 0.09f)), tendonOut = 0.22f),
    Head(HeadId.RECTUS_ABDOMINIS, BODY, listOf(t(-0.16f, 0.004f), t(0.13f, 0.005f), t(0.42f, 0.005f), t(0.72f, 0.006f)), listOf(t(-0.16f, 0.02f), t(0.13f, 0.042f), t(0.42f, 0.05f), t(0.72f, 0.045f)), tendonIn = 0.07f, tendonOut = 0.03f, cuts = RECTUS_CUTS),
    Head(HeadId.SERRATUS_ANTERIOR, BODY, listOf(t(0.69f, 0.1f), t(0.71f, 0.066f)), listOf(t(0.65f, 0.097f), t(0.678f, 0.07f)), tendonIn = 0f),
    Head(HeadId.SERRATUS_ANTERIOR, BODY, listOf(t(0.635f, 0.098f), t(0.655f, 0.066f)), listOf(t(0.595f, 0.095f), t(0.623f, 0.07f)), tendonIn = 0f),
    Head(HeadId.SERRATUS_ANTERIOR, BODY, listOf(t(0.58f, 0.096f), t(0.6f, 0.066f)), listOf(t(0.54f, 0.093f), t(0.568f, 0.07f)), tendonIn = 0f),
    Head(HeadId.PECTORALIS_STERNAL, BODY, listOf(t(1.06f, 0.012f), t(0.96f, 0.05f), t(0.9f, 0.09f), a(0.33f, -0.028f)), listOf(t(0.74f, 0.012f), t(0.72f, 0.06f), t(0.78f, 0.105f), a(0.36f, -0.03f)), tendonIn = 0.04f),
    Head(HeadId.PECTORALIS_CLAVICULAR, BODY, listOf(t(1.085f, 0.065f), t(1f, 0.088f), a(0.2f, -0.03f), a(0.32f, -0.02f)), listOf(t(1.06f, 0.012f), t(0.96f, 0.05f), t(0.9f, 0.09f), a(0.33f, -0.028f)), tendonIn = 0.04f),

    Head(HeadId.TENSOR_FASCIAE_LATAE, TRUNK_LEG, listOf(t(0.14f, 0.092f), h(0.1f, 0.052f), h(0.26f, 0.054f)), listOf(t(0.1f, 0.075f), h(0.1f, 0.034f), h(0.26f, 0.044f)), tendonOut = 0.3f),
    Head(HeadId.GRACILIS, TRUNK_LEG, listOf(t(-0.21f, 0.004f), h(0.3f, -0.05f), h(0.7f, -0.052f), h(1f, -0.042f)), listOf(t(-0.22f, 0f), h(0.3f, -0.058f), h(0.7f, -0.058f), h(1f, -0.048f)), tendonOut = 0.25f),
    Head(HeadId.ADDUCTOR_LONGUS, TRUNK_LEG, listOf(t(-0.08f, 0.03f), h(0.2f, -0.016f), h(0.48f, -0.032f)), listOf(t(-0.2f, 0.008f), h(0.2f, -0.046f), h(0.48f, -0.042f))),
    Head(HeadId.VASTUS_LATERALIS, TRUNK_LEG, listOf(h(0.16f, 0.054f), h(0.45f, 0.06f), h(0.75f, 0.05f), h(0.92f, 0.03f)), listOf(h(0.2f, 0.03f), h(0.45f, 0.028f), h(0.8f, 0.016f), h(0.93f, 0.012f))),
    Head(HeadId.VASTUS_MEDIALIS, TRUNK_LEG, listOf(h(0.42f, -0.014f), h(0.75f, -0.01f), h(0.94f, -0.008f)), listOf(h(0.4f, -0.03f), h(0.7f, -0.052f), h(0.88f, -0.05f), h(0.98f, -0.026f)), tendonOut = 0.06f),
    Head(HeadId.RECTUS_FEMORIS, TRUNK_LEG, listOf(h(0.06f, 0.03f), h(0.45f, 0.026f), h(0.8f, 0.014f), h(0.95f, 0.008f)), listOf(h(0.1f, 0.008f), h(0.45f, -0.012f), h(0.8f, -0.008f), h(0.95f, -0.006f)), tendonOut = 0.2f),
    Head(HeadId.SARTORIUS, TRUNK_LEG, listOf(t(0.12f, 0.085f), h(0.25f, 0.004f), h(0.6f, -0.028f), h(0.95f, -0.038f), s(0.08f, -0.02f)), listOf(t(0.09f, 0.072f), h(0.25f, -0.012f), h(0.6f, -0.04f), h(0.95f, -0.046f), s(0.08f, -0.03f))),
    Head(HeadId.SOLEUS, TRUNK_LEG, listOf(s(0.45f, -0.03f), s(0.7f, -0.03f), s(0.9f, -0.02f)), listOf(s(0.45f, -0.016f), s(0.7f, -0.014f), s(0.9f, -0.012f)), tendonOut = 0.25f),
    Head(HeadId.GASTROCNEMIUS_MEDIAL, TRUNK_LEG, listOf(s(0.05f, -0.03f), s(0.3f, -0.046f), s(0.52f, -0.032f)), listOf(s(0.08f, -0.016f), s(0.3f, -0.022f), s(0.52f, -0.02f))),
    Head(HeadId.PERONEUS_LONGUS, TRUNK_LEG, listOf(s(0.06f, 0.038f), s(0.4f, 0.038f), s(0.9f, 0.022f)), listOf(s(0.08f, 0.024f), s(0.4f, 0.026f), s(0.9f, 0.014f)), tendonOut = 0.35f),
    Head(HeadId.TIBIALIS_ANTERIOR, TRUNK_LEG, listOf(s(0.08f, 0.022f), s(0.4f, 0.024f), s(0.8f, 0.006f), s(1f, -0.006f)), listOf(s(0.1f, 0.006f), s(0.4f, 0.004f), s(0.8f, -0.004f), s(1f, -0.014f)), tendonOut = 0.35f),

    Head(HeadId.TRICEPS_LONG, ARM, listOf(a(0.2f, -0.026f), a(0.6f, -0.03f), a(0.95f, -0.02f)), listOf(a(0.2f, -0.034f), a(0.6f, -0.038f), a(0.95f, -0.026f))),
    Head(HeadId.BRACHIALIS, ARM, listOf(a(0.5f, 0.024f), a(0.75f, 0.026f), a(1f, 0.014f)), listOf(a(0.52f, 0.034f), a(0.75f, 0.033f), a(1f, 0.024f))),
    Head(HeadId.BICEPS_SHORT, ARM, listOf(a(0.14f, -0.006f), a(0.5f, -0.004f), a(0.84f, -0.004f), f(0.1f, -0.002f)), listOf(a(0.12f, -0.024f), a(0.5f, -0.03f), a(0.84f, -0.018f), f(0.1f, -0.008f)), tendonOut = 0.2f),
    Head(HeadId.BICEPS_LONG, ARM, listOf(a(0.14f, 0.006f), a(0.5f, 0.022f), a(0.84f, 0.012f), f(0.1f, 0.004f)), listOf(a(0.14f, -0.006f), a(0.5f, -0.004f), a(0.84f, -0.004f), f(0.1f, -0.002f)), tendonOut = 0.2f),
    Head(HeadId.FLEXOR_CARPI_ULNARIS, ARM, listOf(a(0.97f, -0.026f), f(0.25f, -0.01f), f(0.6f, -0.008f), f(0.95f, -0.006f)), listOf(a(1f, -0.03f), f(0.2f, -0.028f), f(0.6f, -0.021f), f(0.95f, -0.015f)), tendonOut = 0.35f),
    Head(HeadId.WRIST_FLEXORS, ARM, listOf(a(0.94f, -0.016f), f(0.25f, 0.008f), f(0.6f, 0.005f), f(0.95f, 0.003f)), listOf(a(0.97f, -0.026f), f(0.25f, -0.01f), f(0.6f, -0.008f), f(0.95f, -0.006f)), tendonOut = 0.4f),
    Head(HeadId.BRACHIORADIALIS, ARM, listOf(a(0.78f, 0.03f), f(0.1f, 0.032f), f(0.5f, 0.024f), f(0.88f, 0.014f)), listOf(a(0.84f, 0.016f), f(0.15f, 0.012f), f(0.5f, 0.008f), f(0.88f, 0.008f)), tendonOut = 0.4f),
    Head(HeadId.DELTOID_ANTERIOR, ARM, listOf(sh(-0.18f, -0.048f, COLLAR), sh(0.3f, -0.028f, 1f), sh(0.48f, 0.006f, 1f)), CAP_SEAM_FACING, tendonIn = 0.04f, tendonOut = 0.06f, dome = 0.7f),
    DELTOID_LATERAL_FACING,
)

/** From behind, out from the midline: each side is the other's mirror. */
private val BACK_HEADS: List<Head> = listOf(
    Head(HeadId.EXTERNAL_OBLIQUE, BODY, listOf(t(0.44f, 0.098f), t(0.28f, 0.088f), t(0.14f, 0.09f)), listOf(t(0.32f, 0.082f), t(0.2f, 0.062f), t(0.13f, 0.05f))),
    Head(HeadId.INFRASPINATUS, BODY, listOf(t(1f, 0.03f), t(1.01f, 0.075f), a(0.04f, -0.016f)), listOf(t(0.86f, 0.038f), t(0.9f, 0.08f), a(0.1f, -0.024f))),
    Head(HeadId.TERES_MINOR, BODY, listOf(t(0.855f, 0.045f), t(0.895f, 0.085f), a(0.12f, -0.024f)), listOf(t(0.83f, 0.047f), t(0.87f, 0.088f), a(0.15f, -0.026f))),
    Head(HeadId.TERES_MAJOR, BODY, listOf(t(0.825f, 0.05f), t(0.865f, 0.092f), a(0.2f, -0.03f)), listOf(t(0.76f, 0.056f), t(0.8f, 0.1f), a(0.27f, -0.032f))),
    Head(HeadId.LATISSIMUS_DORSI, BODY, listOf(t(0.76f, 0.004f), t(0.77f, 0.05f), t(0.8f, 0.1f), a(0.24f, -0.03f)), listOf(t(0.1f, 0.03f), t(0.3f, 0.086f), t(0.6f, 0.112f), t(0.76f, 0.12f), a(0.3f, -0.024f)), tendonIn = 0.13f),
    Head(HeadId.ERECTOR_SPINAE, BODY, listOf(t(0.02f, 0.006f), t(0.3f, 0.007f), t(0.52f, 0.006f)), listOf(t(0.02f, 0.034f), t(0.3f, 0.03f), t(0.52f, 0.02f)), tendonIn = 0.2f),
    Head(HeadId.TRAPEZIUS_LOWER, BODY, listOf(t(0.93f, 0.004f), t(0.95f, 0.04f), t(0.99f, 0.075f)), listOf(t(0.5f, 0.003f), t(0.7f, 0.022f), t(0.86f, 0.045f), t(0.97f, 0.068f)), tendonIn = 0.04f),
    Head(HeadId.TRAPEZIUS_MIDDLE, BODY, listOf(t(1.07f, 0.004f), t(1.06f, 0.04f), t(1.07f, 0.09f), t(1.1f, 0.125f)), listOf(t(0.93f, 0.004f), t(0.95f, 0.04f), t(1f, 0.08f), t(1.06f, 0.118f)), tendonIn = 0.1f),
    Head(HeadId.TRAPEZIUS_UPPER, BODY, listOf(t(1.36f, 0.012f), t(1.22f, 0.04f), t(1.14f, 0.09f), t(1.125f, 0.128f)), listOf(t(1.07f, 0.004f), t(1.06f, 0.04f), t(1.07f, 0.09f), t(1.1f, 0.125f)), tendonIn = 0.04f),

    Head(HeadId.ADDUCTOR_MAGNUS, TRUNK_LEG, listOf(t(-0.2f, 0.004f), h(0.3f, -0.04f), h(0.6f, -0.044f)), listOf(h(0.05f, -0.056f), h(0.3f, -0.058f), h(0.6f, -0.054f))),
    Head(HeadId.VASTUS_LATERALIS, TRUNK_LEG, listOf(h(0.28f, 0.056f), h(0.5f, 0.059f), h(0.85f, 0.045f)), listOf(h(0.3f, 0.04f), h(0.5f, 0.04f), h(0.85f, 0.038f))),
    Head(HeadId.SEMIMEMBRANOSUS, TRUNK_LEG, listOf(h(0.45f, -0.022f), h(0.75f, -0.026f), h(0.98f, -0.03f)), listOf(h(0.45f, -0.04f), h(0.75f, -0.044f), h(0.98f, -0.038f))),
    Head(HeadId.SEMITENDINOSUS, TRUNK_LEG, listOf(h(0.14f, -0.004f), h(0.5f, 0.006f), h(0.8f, -0.008f), s(0.08f, -0.024f)), listOf(h(0.14f, -0.022f), h(0.5f, -0.02f), h(0.8f, -0.024f), s(0.08f, -0.032f)), tendonOut = 0.3f),
    Head(HeadId.BICEPS_FEMORIS_LONG, TRUNK_LEG, listOf(h(0.14f, 0.012f), h(0.5f, 0.036f), h(0.85f, 0.036f), s(0.06f, 0.034f)), listOf(h(0.14f, -0.004f), h(0.5f, 0.008f), h(0.85f, 0.016f), s(0.06f, 0.024f)), tendonOut = 0.2f),
    Head(HeadId.GLUTEUS_MEDIUS, TRUNK_LEG, listOf(t(0.36f, 0.05f), t(0.31f, 0.092f), hp(-0.14f, 0.052f), hp(0f, 0.052f)), listOf(t(0.3f, 0.03f), t(0.29f, 0.045f), hp(-0.17f, 0.03f), hp(0.02f, 0.048f)), tendonOut = 0.16f, dome = 0.5f),
    Head(HeadId.GLUTEUS_MAXIMUS, TRUNK_LEG, listOf(t(0.26f, 0.01f), t(0.29f, 0.045f), hp(-0.17f, 0.03f), hp(0.04f, 0.05f)), listOf(t(-0.2f, 0.006f), hp(0.27f, -0.035f), hp(0.31f, 0f), hp(0.28f, 0.04f)), tendonIn = 0.05f, tendonOut = 0.07f, dome = 0.95f),
    Head(HeadId.SOLEUS, TRUNK_LEG, listOf(s(0.3f, 0.04f), s(0.6f, 0.034f), s(0.82f, 0.016f), s(1.02f, 0.008f)), listOf(s(0.3f, -0.04f), s(0.6f, -0.036f), s(0.82f, -0.016f), s(1.02f, -0.008f)), tendonOut = 0.32f),
    Head(HeadId.GASTROCNEMIUS_LATERAL, TRUNK_LEG, listOf(h(0.95f, 0.022f), s(0.12f, 0.04f), s(0.35f, 0.04f), s(0.5f, 0.024f)), listOf(h(0.95f, 0.004f), s(0.15f, 0.002f), s(0.35f, 0.002f), s(0.5f, 0.004f))),
    Head(HeadId.GASTROCNEMIUS_MEDIAL, TRUNK_LEG, listOf(h(0.95f, -0.002f), s(0.15f, -0.002f), s(0.4f, -0.002f), s(0.56f, -0.006f)), listOf(h(0.95f, -0.024f), s(0.12f, -0.042f), s(0.38f, -0.047f), s(0.56f, -0.028f))),

    Head(HeadId.EXTENSOR_CARPI_ULNARIS, ARM, listOf(f(0.03f, 0f), f(0.25f, -0.007f), f(0.6f, -0.006f), f(0.95f, -0.004f)), listOf(f(0.02f, -0.024f), f(0.3f, -0.027f), f(0.6f, -0.02f), f(0.9f, -0.015f)), tendonOut = 0.35f),
    Head(HeadId.EXTENSOR_DIGITORUM, ARM, listOf(a(0.97f, 0.016f), f(0.25f, 0.01f), f(0.6f, 0.006f), f(0.95f, 0.004f)), listOf(f(0.03f, 0f), f(0.25f, -0.007f), f(0.6f, -0.006f), f(0.95f, -0.004f)), tendonOut = 0.4f),
    Head(HeadId.BRACHIORADIALIS, ARM, listOf(a(0.76f, 0.03f), f(0.1f, 0.03f), f(0.5f, 0.022f), f(0.82f, 0.014f)), listOf(a(0.82f, 0.018f), f(0.12f, 0.012f), f(0.5f, 0.01f), f(0.82f, 0.008f)), tendonOut = 0.35f),
    // The triceps' common tendon, flat down the back of the arm to the point of the elbow.
    Head(null, ARM, listOf(a(0.58f, 0.006f), a(0.8f, 0.014f), a(1f, 0.012f)), listOf(a(0.58f, -0.004f), a(0.8f, -0.012f), a(1f, -0.012f))),
    Head(HeadId.TRICEPS_MEDIAL, ARM, listOf(a(0.66f, -0.03f), a(0.82f, -0.03f), a(0.96f, -0.02f)), listOf(a(0.66f, -0.014f), a(0.82f, -0.014f), a(0.96f, -0.012f)), tendonIn = 0f),
    Head(HeadId.TRICEPS_LONG, ARM, listOf(a(0.1f, -0.012f), a(0.4f, -0.004f), a(0.62f, -0.004f)), listOf(a(0.12f, -0.03f), a(0.4f, -0.038f), a(0.62f, -0.03f), a(0.72f, -0.018f)), tendonOut = 0.1f),
    Head(HeadId.TRICEPS_LATERAL, ARM, listOf(a(0.2f, 0.03f), a(0.45f, 0.038f), a(0.66f, 0.03f), a(0.74f, 0.02f)), listOf(a(0.22f, 0.008f), a(0.45f, 0.004f), a(0.66f, 0.006f)), tendonOut = 0.1f),
    Head(HeadId.DELTOID_POSTERIOR, ARM, listOf(sh(-0.09f, -0.048f, BLADE), sh(0.3f, -0.03f, 1f), sh(0.48f, 0.004f, 1f)), CAP_SEAM_FACING, tendonIn = 0.08f, tendonOut = 0.06f, dome = 0.7f),
    DELTOID_LATERAL_FACING,
)

/** From the lifter's side, out toward their front. */
private val SIDE_HEADS: List<Head> = listOf(
    Head(HeadId.ERECTOR_SPINAE, BODY, listOf(t(0.5f, -0.07f), t(0.25f, -0.064f), t(0.05f, -0.06f)), listOf(t(0.5f, -0.05f), t(0.25f, -0.044f), t(0.05f, -0.04f))),
    Head(HeadId.EXTERNAL_OBLIQUE, BODY, listOf(t(0.7f, 0.052f), t(0.45f, 0.05f), t(0.16f, 0.046f)), listOf(t(0.62f, -0.02f), t(0.4f, -0.034f), t(0.18f, -0.03f)), tendonOut = 0.1f),
    Head(HeadId.RECTUS_ABDOMINIS, BODY, listOf(t(-0.14f, 0.045f), t(0.13f, 0.067f), t(0.42f, 0.072f), t(0.72f, 0.074f)), listOf(t(-0.14f, 0.03f), t(0.13f, 0.047f), t(0.42f, 0.05f), t(0.72f, 0.054f)), tendonIn = 0.07f, tendonOut = 0.03f, cuts = RECTUS_CUTS),
    Head(HeadId.SERRATUS_ANTERIOR, BODY, listOf(t(0.74f, 0f), t(0.71f, 0.045f)), listOf(t(0.695f, -0.002f), t(0.68f, 0.04f)), tendonIn = 0f),
    Head(HeadId.SERRATUS_ANTERIOR, BODY, listOf(t(0.68f, 0f), t(0.65f, 0.045f)), listOf(t(0.635f, -0.002f), t(0.62f, 0.04f)), tendonIn = 0f),
    Head(HeadId.SERRATUS_ANTERIOR, BODY, listOf(t(0.62f, 0f), t(0.59f, 0.045f)), listOf(t(0.575f, -0.002f), t(0.56f, 0.04f)), tendonIn = 0f),
    Head(HeadId.LATISSIMUS_DORSI, BODY, listOf(t(0.22f, -0.05f), t(0.5f, -0.028f), t(0.74f, -0.006f), a(0.22f, -0.012f)), listOf(t(0.2f, -0.066f), t(0.55f, -0.074f), t(0.8f, -0.064f), a(0.24f, -0.03f)), tendonIn = 0.06f),
    Head(HeadId.TRAPEZIUS_LOWER, BODY, listOf(t(1.1f, -0.056f), t(1f, -0.072f), t(0.8f, -0.076f), t(0.55f, -0.07f)), listOf(t(1.09f, -0.042f), t(0.98f, -0.054f), t(0.8f, -0.058f), t(0.58f, -0.062f))),
    Head(HeadId.TERES_MAJOR, BODY, listOf(t(0.88f, -0.058f), t(0.89f, -0.04f), a(0.16f, -0.028f)), listOf(t(0.8f, -0.058f), t(0.82f, -0.036f), a(0.24f, -0.026f))),
    Head(HeadId.INFRASPINATUS, BODY, listOf(t(1.02f, -0.052f), t(1.01f, -0.035f), a(0.04f, -0.02f)), listOf(t(0.9f, -0.058f), t(0.91f, -0.04f), a(0.12f, -0.03f))),
    Head(HeadId.STERNOCLEIDOMASTOID, BODY, listOf(t(1.3f, 0f), t(1.2f, 0.016f), t(1.08f, 0.04f)), listOf(t(1.29f, -0.014f), t(1.19f, 0f), t(1.07f, 0.026f))),
    Head(HeadId.TRAPEZIUS_UPPER, BODY, listOf(t(1.32f, -0.03f), t(1.2f, -0.038f), t(1.12f, -0.05f), t(1.04f, -0.07f)), listOf(t(1.3f, -0.016f), t(1.2f, -0.012f), t(1.14f, -0.004f), t(1.13f, 0.012f))),
    Head(HeadId.PECTORALIS_STERNAL, BODY, listOf(t(0.93f, 0.092f), t(0.9f, 0.058f), a(0.3f, 0.028f)), listOf(t(0.72f, 0.082f), t(0.76f, 0.054f), a(0.34f, 0.022f)), tendonIn = 0.03f),
    Head(HeadId.PECTORALIS_CLAVICULAR, BODY, listOf(t(1.07f, 0.04f), t(1.03f, 0.03f), a(0.24f, 0.03f)), listOf(t(0.93f, 0.092f), t(0.9f, 0.058f), a(0.3f, 0.028f)), tendonIn = 0.03f),

    Head(HeadId.SEMITENDINOSUS, LEG, listOf(t(-0.12f, -0.05f), h(0.3f, -0.054f), h(0.5f, -0.05f), h(0.85f, -0.036f), s(0.05f, -0.026f)), listOf(t(-0.13f, -0.058f), h(0.3f, -0.062f), h(0.5f, -0.058f), h(0.85f, -0.044f), s(0.04f, -0.034f)), tendonOut = 0.25f),
    Head(HeadId.BICEPS_FEMORIS_LONG, LEG, listOf(t(-0.1f, -0.03f), h(0.3f, -0.024f), h(0.5f, -0.02f), h(0.85f, -0.016f), s(0.05f, -0.014f)), listOf(t(-0.12f, -0.05f), h(0.3f, -0.054f), h(0.5f, -0.05f), h(0.85f, -0.036f), s(0.05f, -0.026f)), tendonOut = 0.2f),
    Head(HeadId.BICEPS_FEMORIS_SHORT, LEG, listOf(h(0.55f, -0.014f), h(0.8f, -0.01f), h(0.97f, -0.008f)), listOf(h(0.55f, -0.02f), h(0.8f, -0.016f), h(0.97f, -0.016f))),
    Head(HeadId.VASTUS_LATERALIS, LEG, listOf(h(0.14f, 0.034f), h(0.5f, 0.036f), h(0.85f, 0.03f), h(0.95f, 0.028f)), listOf(h(0.16f, -0.008f), h(0.5f, -0.014f), h(0.8f, -0.01f), h(0.93f, 0.008f))),
    Head(HeadId.RECTUS_FEMORIS, LEG, listOf(h(-0.06f, 0.052f), h(0.15f, 0.056f), h(0.5f, 0.062f), h(0.85f, 0.046f), h(0.97f, 0.04f)), listOf(h(0.1f, 0.036f), h(0.5f, 0.036f), h(0.85f, 0.03f), h(0.97f, 0.03f)), tendonOut = 0.16f),
    // The iliotibial band, down the outside of the thigh.
    Head(null, LEG, listOf(h(0.2f, 0.016f), h(0.6f, 0.01f), h(1f, 0.01f)), listOf(h(0.22f, 0.008f), h(0.6f, 0.003f), h(1f, 0.003f))),
    Head(HeadId.TENSOR_FASCIAE_LATAE, LEG, listOf(t(0.25f, 0.036f), t(0.15f, 0.048f), hp(0.2f, 0.032f, 0.75f)), listOf(t(0.26f, 0.02f), h(0.22f, 0.014f)), tendonOut = 0.2f),
    Head(HeadId.GLUTEUS_MEDIUS, LEG, listOf(t(0.34f, -0.03f), t(0.34f, 0.02f), hp(-0.02f, 0.022f)), listOf(t(0.32f, -0.04f), hp(-0.14f, -0.004f), hp(0.04f, 0.004f)), tendonOut = 0.16f, dome = 0.5f),
    Head(HeadId.GLUTEUS_MAXIMUS, LEG, listOf(t(0.32f, -0.04f), hp(-0.14f, -0.004f), h(0.1f, 0.012f), h(0.26f, 0.012f), h(0.4f, 0f)), listOf(hp(-0.25f, -0.06f, 0f), hp(-0.12f, -0.077f, 0.2f), hp(0f, -0.083f, 0.4f), hp(0.12f, -0.082f, 0.6f), hp(0.22f, -0.071f, 0.8f), hp(0.3f, -0.055f, 1f), h(0.37f, -0.038f), h(0.44f, -0.012f)), tendonIn = 0.04f, tendonOut = 0.14f, dome = 0.95f),
    Head(HeadId.SOLEUS, LEG, listOf(s(0.2f, -0.008f), s(0.5f, -0.01f), s(0.8f, -0.012f), s(1.02f, -0.018f)), listOf(s(0.3f, -0.03f), s(0.55f, -0.032f), s(0.8f, -0.024f), s(1.02f, -0.026f)), tendonOut = 0.3f),
    Head(HeadId.GASTROCNEMIUS_LATERAL, LEG, listOf(h(0.94f, -0.012f), s(0.1f, -0.012f), s(0.35f, -0.014f), s(0.5f, -0.018f)), listOf(h(0.94f, -0.03f), s(0.12f, -0.044f), s(0.35f, -0.046f), s(0.52f, -0.028f))),
    Head(HeadId.PERONEUS_LONGUS, LEG, listOf(s(0.08f, 0.008f), s(0.4f, 0.008f), s(0.8f, 0.006f), s(0.98f, 0.004f)), listOf(s(0.06f, -0.004f), s(0.4f, -0.006f), s(0.8f, -0.004f), s(1f, -0.01f)), tendonOut = 0.35f),
    Head(HeadId.EXTENSOR_DIGITORUM_LONGUS, LEG, listOf(s(0.08f, 0.018f), s(0.35f, 0.02f), s(0.7f, 0.014f), s(0.98f, 0.01f)), listOf(s(0.08f, 0.008f), s(0.4f, 0.008f), s(0.8f, 0.006f), s(0.98f, 0.004f)), tendonOut = 0.35f),
    Head(HeadId.TIBIALIS_ANTERIOR, LEG, listOf(s(0.06f, 0.034f), s(0.35f, 0.036f), s(0.7f, 0.024f), s(0.98f, 0.018f)), listOf(s(0.08f, 0.018f), s(0.35f, 0.02f), s(0.7f, 0.014f), s(0.98f, 0.01f)), tendonOut = 0.35f),

    Head(HeadId.EXTENSOR_CARPI_ULNARIS, ARM, listOf(f(0.02f, -0.016f), f(0.3f, -0.014f), f(0.6f, -0.01f), f(0.95f, -0.008f)), listOf(f(0.03f, -0.026f), f(0.3f, -0.026f), f(0.6f, -0.02f), f(0.95f, -0.016f)), tendonOut = 0.35f),
    Head(HeadId.EXTENSOR_DIGITORUM, ARM, listOf(a(0.9f, -0.01f), f(0.15f, -0.002f), f(0.5f, 0f), f(0.92f, 0f)), listOf(f(0.02f, -0.016f), f(0.3f, -0.014f), f(0.6f, -0.01f), f(0.95f, -0.008f)), tendonOut = 0.4f),
    Head(HeadId.EXTENSOR_CARPI_RADIALIS, ARM, listOf(a(0.76f, 0f), f(0.12f, 0.012f), f(0.45f, 0.012f), f(0.9f, 0.008f)), listOf(a(0.9f, -0.01f), f(0.15f, -0.002f), f(0.5f, 0f), f(0.92f, 0f)), tendonOut = 0.4f),
    Head(HeadId.BRACHIORADIALIS, ARM, listOf(a(0.72f, 0.014f), f(0.1f, 0.03f), f(0.45f, 0.026f), f(0.9f, 0.014f)), listOf(a(0.76f, 0f), f(0.12f, 0.012f), f(0.45f, 0.012f), f(0.9f, 0.008f)), tendonOut = 0.4f),
    Head(HeadId.TRICEPS_MEDIAL, ARM, listOf(a(0.7f, -0.016f), a(0.85f, -0.014f), a(0.97f, -0.012f)), listOf(a(0.7f, -0.03f), a(0.85f, -0.03f), a(0.97f, -0.024f)), tendonIn = 0f, tendonOut = 0.2f),
    Head(HeadId.TRICEPS_LONG, ARM, listOf(a(0.14f, -0.016f), a(0.4f, -0.022f), a(0.62f, -0.02f), a(0.7f, -0.018f)), listOf(a(0.12f, -0.03f), a(0.4f, -0.042f), a(0.62f, -0.036f), a(0.72f, -0.026f)), tendonOut = 0.1f),
    Head(HeadId.TRICEPS_LATERAL, ARM, listOf(a(0.26f, 0f), a(0.45f, -0.002f), a(0.62f, -0.006f), a(0.7f, -0.012f)), listOf(a(0.24f, -0.014f), a(0.45f, -0.02f), a(0.66f, -0.018f)), tendonOut = 0.1f),
    Head(HeadId.BRACHIALIS, ARM, listOf(a(0.5f, 0.012f), a(0.75f, 0.012f), a(0.98f, 0.008f)), listOf(a(0.52f, -0.003f), a(0.75f, -0.004f), a(0.98f, -0.004f))),
    Head(HeadId.BICEPS_LONG, ARM, listOf(a(0.16f, 0.022f), a(0.45f, 0.036f), a(0.75f, 0.03f), f(0.08f, 0.012f)), listOf(a(0.18f, 0.006f), a(0.45f, 0.012f), a(0.75f, 0.012f), f(0.08f, 0.004f)), tendonOut = 0.2f),
    Head(HeadId.DELTOID_POSTERIOR, ARM, CAP_SEAM_BEHIND + sh(0.48f, 0f, 1f), listOf(sh(-0.065f, -0.05f, BLADE_SIDE), sh(0.28f, -0.04f, 0.9f), sh(0.46f, -0.008f, 1f)), tendonIn = 0.08f, tendonOut = 0.06f, dome = 0.7f),
    Head(HeadId.DELTOID_ANTERIOR, ARM, listOf(sh(-0.13f, 0.04f, BLADE_SIDE), sh(0.12f, 0.052f, CAP), sh(0.3f, 0.04f, CAP_END), sh(0.46f, 0.012f, 1f)), CAP_SEAM_AHEAD + sh(0.48f, 0.01f, 1f), tendonIn = 0.04f, tendonOut = 0.06f, dome = 0.7f),
    Head(HeadId.DELTOID_LATERAL, ARM, CAP_SEAM_AHEAD + sh(0.54f, 0.008f, 1f), CAP_SEAM_BEHIND + sh(0.54f, 0f, 1f), tendonIn = 0.04f, tendonOut = 0.06f, dome = 0.8f),
)
