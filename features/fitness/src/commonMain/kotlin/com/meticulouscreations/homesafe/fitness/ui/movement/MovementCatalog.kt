package com.meticulouscreations.homesafe.fitness.ui.movement

import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import com.meticulouscreations.homesafe.fitness.domain.MovementKind

/**
 * Where a movement's figure comes from, for each way of drawing it: which of the jointed rig's
 * movements performs a [MovementKind] (the stone figure and the forged study), and where its
 * rendered frames are kept (the true-3D one).
 */
internal object MovementCatalog {

    /**
     * The rig's movement for [kind]. A rig movement named as the kind is, is the kind's own: add
     * `BENCH_PRESS` to [Movement] and the bench press is drawn with it, with nothing to change
     * here. Until the rig has one, [standIn] says which of its movements is performed instead.
     */
    fun rig(kind: MovementKind): Movement = own(kind) ?: standIn(kind)

    /** The rig's own movement for [kind], if it has one yet. */
    fun own(kind: MovementKind): Movement? = Movement.entries.firstOrNull { it.name == kind.name }

    /**
     * STAND-INS. The rig movement drawn for a kind the rig has no movement of its own for: the
     * nearest of the five it began with, by the muscles lit first and the shape of the lift
     * second. Every line below the first four is a figure doing the wrong exercise, and is only
     * reached while [Movement] lacks an entry under the kind's name (see [rig]); once the rig
     * has them all, none is. It stays a `when` over every kind so that a kind added later
     * can't be left with no figure at all.
     */
    fun standIn(kind: MovementKind): Movement = when (kind) {
        // The rig's own.
        MovementKind.SQUAT -> Movement.SQUAT

        MovementKind.INCLINE_PRESS -> Movement.INCLINE_PRESS

        MovementKind.LATERAL_RAISE -> Movement.LATERAL_RAISE

        MovementKind.LAT_PULLDOWN -> Movement.LAT_PULLDOWN

        // Stand-ins: legs. Hips and knees bending under a load, from the side; from the front where it is the thigh alone.
        MovementKind.LEG_PRESS,
        MovementKind.HIP_HINGE,
        MovementKind.LEG_CURL,
        MovementKind.CALF_RAISE,
        -> Movement.SQUAT

        MovementKind.LEG_EXTENSION,
        MovementKind.HIP_ABDUCTION,
        MovementKind.HIP_ADDUCTION,
        -> Movement.SQUAT_FRONT

        // Stand-ins: pressing. Chest, front of the shoulder and triceps pushing a weight away.
        MovementKind.BENCH_PRESS,
        MovementKind.CHEST_FLY,
        MovementKind.DIP,
        MovementKind.OVERHEAD_PRESS,
        MovementKind.TRICEPS_PUSHDOWN,
        -> Movement.INCLINE_PRESS

        // Stand-ins: the shoulder girdle, seen from the front with a weight in each hand.
        MovementKind.FRONT_RAISE,
        MovementKind.REAR_DELT_FLY,
        MovementKind.SHRUG,
        -> Movement.LATERAL_RAISE

        // Stand-ins: pulling. The back and the elbow flexors drawing a weight in.
        MovementKind.PULL_UP,
        MovementKind.SEATED_ROW,
        MovementKind.BICEPS_CURL,
        -> Movement.LAT_PULLDOWN

        // Stand-in: the trunk. Nothing the rig does is for the abdominals; in a squat they at least hold.
        MovementKind.CRUNCH -> Movement.SQUAT
    }

    /** How the rig paints a figure of [style]. The true-3D figure isn't the rig's: where it has no frames, the forged study stands in. */
    fun rigStyle(style: FigureStyle): MovementStyle = when (style) {
        FigureStyle.STONE -> MovementStyle.MARBLE
        FigureStyle.FORGED, FigureStyle.TRUE_3D -> MovementStyle.FORGE
    }

    /** The heads the rig calls out by name for [movement]: the ones that do the lift and the ones that help it. */
    fun named(movement: Movement): Set<HeadId> = movement.work().filterValues { it >= SYNERGIST }.keys

    /** The folder a kind's rendered frames are packed into, under the app's resources: `files/movement/squat`. */
    fun folder(kind: MovementKind): String = "files/movement/${kind.name.lowercase()}"
}
