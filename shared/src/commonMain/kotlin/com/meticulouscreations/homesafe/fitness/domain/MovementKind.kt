package com.meticulouscreations.homesafe.fitness.domain

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_movement_bench_press
import homesafe.shared.generated.resources.fitness_movement_biceps_curl
import homesafe.shared.generated.resources.fitness_movement_calf_raise
import homesafe.shared.generated.resources.fitness_movement_chest_fly
import homesafe.shared.generated.resources.fitness_movement_crunch
import homesafe.shared.generated.resources.fitness_movement_dip
import homesafe.shared.generated.resources.fitness_movement_front_raise
import homesafe.shared.generated.resources.fitness_movement_hip_abduction
import homesafe.shared.generated.resources.fitness_movement_hip_adduction
import homesafe.shared.generated.resources.fitness_movement_hip_hinge
import homesafe.shared.generated.resources.fitness_movement_incline_press
import homesafe.shared.generated.resources.fitness_movement_lat_pulldown
import homesafe.shared.generated.resources.fitness_movement_lateral_raise
import homesafe.shared.generated.resources.fitness_movement_leg_curl
import homesafe.shared.generated.resources.fitness_movement_leg_extension
import homesafe.shared.generated.resources.fitness_movement_leg_press
import homesafe.shared.generated.resources.fitness_movement_overhead_press
import homesafe.shared.generated.resources.fitness_movement_pull_up
import homesafe.shared.generated.resources.fitness_movement_rear_delt_fly
import homesafe.shared.generated.resources.fitness_movement_seated_row
import homesafe.shared.generated.resources.fitness_movement_shrug
import homesafe.shared.generated.resources.fitness_movement_squat
import homesafe.shared.generated.resources.fitness_movement_triceps_pushdown
import org.jetbrains.compose.resources.StringResource

/**
 * The way a body moves in an exercise, whatever it is done on: what the figure on an exercise's
 * card performs. There are far fewer of these than there are exercises (a hack squat, a goblet
 * squat and a lunge are all a squat to look at), and every exercise is one of them. [label] is
 * its name, for where a figure has to be said to be only the nearest there is.
 */
enum class MovementKind(val label: StringResource) {
    SQUAT(Res.string.fitness_movement_squat),
    LEG_PRESS(Res.string.fitness_movement_leg_press),
    LEG_EXTENSION(Res.string.fitness_movement_leg_extension),
    LEG_CURL(Res.string.fitness_movement_leg_curl),
    CALF_RAISE(Res.string.fitness_movement_calf_raise),
    HIP_HINGE(Res.string.fitness_movement_hip_hinge),
    HIP_ABDUCTION(Res.string.fitness_movement_hip_abduction),
    HIP_ADDUCTION(Res.string.fitness_movement_hip_adduction),
    BENCH_PRESS(Res.string.fitness_movement_bench_press),
    INCLINE_PRESS(Res.string.fitness_movement_incline_press),
    CHEST_FLY(Res.string.fitness_movement_chest_fly),
    DIP(Res.string.fitness_movement_dip),
    OVERHEAD_PRESS(Res.string.fitness_movement_overhead_press),
    LATERAL_RAISE(Res.string.fitness_movement_lateral_raise),
    FRONT_RAISE(Res.string.fitness_movement_front_raise),
    REAR_DELT_FLY(Res.string.fitness_movement_rear_delt_fly),
    SHRUG(Res.string.fitness_movement_shrug),
    LAT_PULLDOWN(Res.string.fitness_movement_lat_pulldown),
    PULL_UP(Res.string.fitness_movement_pull_up),
    SEATED_ROW(Res.string.fitness_movement_seated_row),
    BICEPS_CURL(Res.string.fitness_movement_biceps_curl),
    TRICEPS_PUSHDOWN(Res.string.fitness_movement_triceps_pushdown),
    CRUNCH(Res.string.fitness_movement_crunch),
}

/**
 * The movement an exercise was read as, and whether its name said so. Where it didn't
 * ([named] false), the movement is only the likeliest for the shelf it is on or the muscle it
 * is for: its figure may well be doing a different lift, and the screens say so under it.
 */
data class MovementMatch(val kind: MovementKind, val named: Boolean)

/**
 * Reads which movement an exercise is from its name, its shelf and the muscle it is for, the
 * way [ExerciseClassifier] reads the rest. Every exercise comes out as something: a name that
 * says nothing more than its shelf is that shelf's plainest movement.
 */
object MovementClassifier {
    fun movement(exercise: Exercise): MovementKind = match(exercise).kind

    fun movement(name: String, bodyPart: BodyPart, primary: Muscle): MovementKind = match(name, bodyPart, primary).kind

    fun match(exercise: Exercise): MovementMatch = match(exercise.name, exercise.bodyPart, exercise.primary)

    fun match(name: String, bodyPart: BodyPart, primary: Muscle): MovementMatch {
        val n = " " + name.lowercase().replace('-', ' ') + " "

        // The name says it is this movement; or it doesn't, and this is only where its shelf or its muscle points.
        fun said(kind: MovementKind) = MovementMatch(kind, named = true)
        fun nearest(kind: MovementKind, vararg words: String) = MovementMatch(kind, named = n.has(*words))

        return when (bodyPart) {
            BodyPart.CORE -> nearest(MovementKind.CRUNCH, "crunch", "sit up", "situp", "curl up")

            BodyPart.LEGS -> when {
                n.has("calf") || primary == Muscle.CALVES -> nearest(MovementKind.CALF_RAISE, "calf")
                n.has("leg curl", "hamstring curl") || (primary == Muscle.HAMSTRINGS && n.has("curl")) -> said(MovementKind.LEG_CURL)
                n.has("extension") && !n.has("back extension", "hip extension") -> said(MovementKind.LEG_EXTENSION)
                n.has("abductor", "abduction") -> said(MovementKind.HIP_ABDUCTION)
                n.has("adductor", "adduction") || primary == Muscle.ADDUCTORS -> nearest(MovementKind.HIP_ADDUCTION, "adductor", "adduction")
                n.has("deadlift", " rdl ", "hip thrust", "good morning", "back extension", "hip extension", "bridge", "kickback") -> said(MovementKind.HIP_HINGE)
                n.has("leg press") -> said(MovementKind.LEG_PRESS)
                primary == Muscle.HAMSTRINGS -> nearest(MovementKind.HIP_HINGE)
                else -> nearest(MovementKind.SQUAT, "squat", "lunge", "step up")
            }

            BodyPart.CHEST -> when {
                n.has(" fly", "cross", "pec deck") -> said(MovementKind.CHEST_FLY)
                n.has(" dip") -> said(MovementKind.DIP)
                n.has("incline") -> said(MovementKind.INCLINE_PRESS)
                else -> nearest(MovementKind.BENCH_PRESS, "bench", "press", "push up", "pushup")
            }

            BodyPart.BACK -> when {
                n.has("shrug") || primary == Muscle.TRAPS -> nearest(MovementKind.SHRUG, "shrug")
                n.has("rear delt", "face pull", "reverse fly") || primary == Muscle.REAR_DELTS -> nearest(MovementKind.REAR_DELT_FLY, "rear delt", "face pull", "reverse fly")
                n.has("pull up", "pullup", " chin") -> said(MovementKind.PULL_UP)
                n.has(" row") -> said(MovementKind.SEATED_ROW)
                n.has("deadlift", "back extension", "good morning") -> said(MovementKind.HIP_HINGE)
                else -> nearest(MovementKind.LAT_PULLDOWN, "pulldown", "pull down")
            }

            BodyPart.SHOULDERS -> when {
                n.has("shrug") -> said(MovementKind.SHRUG)
                n.has("rear delt", "face pull", "rotator", "reverse") || primary == Muscle.REAR_DELTS -> nearest(MovementKind.REAR_DELT_FLY, "rear delt", "face pull", "reverse")
                n.has("press", "military") -> said(MovementKind.OVERHEAD_PRESS)
                primary == Muscle.FRONT_DELTS -> nearest(MovementKind.FRONT_RAISE, "front", "anterior")
                else -> nearest(MovementKind.LATERAL_RAISE, "lateral", "side raise", "side delt", "middle delt")
            }

            BodyPart.BICEPS -> nearest(MovementKind.BICEPS_CURL, "curl")

            BodyPart.TRICEPS -> if (n.has(" dip")) said(MovementKind.DIP) else nearest(MovementKind.TRICEPS_PUSHDOWN, "pushdown", "push down", "pressdown", "press down", "cable", "rope")
        }
    }

    private fun String.has(vararg words: String) = words.any { contains(it) }
}
