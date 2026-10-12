package com.meticulouscreations.homesafe.fitness.domain

/**
 * The way a body moves in an exercise, whatever it is done on: what the figure on an exercise's
 * card performs. There are far fewer of these than there are exercises (a hack squat, a goblet
 * squat and a lunge are all a squat to look at), and every exercise is one of them.
 */
enum class MovementKind {
    SQUAT,
    LEG_PRESS,
    LEG_EXTENSION,
    LEG_CURL,
    CALF_RAISE,
    HIP_HINGE,
    HIP_ABDUCTION,
    HIP_ADDUCTION,
    BENCH_PRESS,
    INCLINE_PRESS,
    CHEST_FLY,
    DIP,
    OVERHEAD_PRESS,
    LATERAL_RAISE,
    FRONT_RAISE,
    REAR_DELT_FLY,
    SHRUG,
    LAT_PULLDOWN,
    PULL_UP,
    SEATED_ROW,
    BICEPS_CURL,
    TRICEPS_PUSHDOWN,
    CRUNCH,
}

/**
 * Reads which movement an exercise is from its name, its shelf and the muscle it is for, the
 * way [ExerciseClassifier] reads the rest. Every exercise comes out as something: a name that
 * says nothing more than its shelf is that shelf's plainest movement.
 */
object MovementClassifier {
    fun movement(exercise: Exercise): MovementKind = movement(exercise.name, exercise.bodyPart, exercise.primary)

    fun movement(name: String, bodyPart: BodyPart, primary: Muscle): MovementKind {
        val n = " " + name.lowercase().replace('-', ' ') + " "
        return when (bodyPart) {
            BodyPart.CORE -> MovementKind.CRUNCH

            BodyPart.LEGS -> when {
                n.has("calf") || primary == Muscle.CALVES -> MovementKind.CALF_RAISE
                n.has("leg curl", "hamstring curl") || (primary == Muscle.HAMSTRINGS && n.has("curl")) -> MovementKind.LEG_CURL
                n.has("extension") && !n.has("back extension", "hip extension") -> MovementKind.LEG_EXTENSION
                n.has("abductor", "abduction") -> MovementKind.HIP_ABDUCTION
                n.has("adductor", "adduction") || primary == Muscle.ADDUCTORS -> MovementKind.HIP_ADDUCTION
                n.has("deadlift", " rdl ", "hip thrust", "good morning", "back extension", "hip extension", "bridge", "kickback") -> MovementKind.HIP_HINGE
                n.has("leg press") -> MovementKind.LEG_PRESS
                primary == Muscle.HAMSTRINGS -> MovementKind.HIP_HINGE
                else -> MovementKind.SQUAT
            }

            BodyPart.CHEST -> when {
                n.has(" fly", "cross", "pec deck") -> MovementKind.CHEST_FLY
                n.has(" dip") -> MovementKind.DIP
                n.has("incline") -> MovementKind.INCLINE_PRESS
                else -> MovementKind.BENCH_PRESS
            }

            BodyPart.BACK -> when {
                n.has("shrug") || primary == Muscle.TRAPS -> MovementKind.SHRUG
                n.has("rear delt", "face pull", "reverse fly") || primary == Muscle.REAR_DELTS -> MovementKind.REAR_DELT_FLY
                n.has("pull up", "pullup", " chin") -> MovementKind.PULL_UP
                n.has(" row") -> MovementKind.SEATED_ROW
                n.has("deadlift", "back extension", "good morning") -> MovementKind.HIP_HINGE
                else -> MovementKind.LAT_PULLDOWN
            }

            BodyPart.SHOULDERS -> when {
                n.has("shrug") -> MovementKind.SHRUG
                n.has("rear delt", "face pull", "rotator", "reverse") || primary == Muscle.REAR_DELTS -> MovementKind.REAR_DELT_FLY
                n.has("press", "military") -> MovementKind.OVERHEAD_PRESS
                primary == Muscle.FRONT_DELTS -> MovementKind.FRONT_RAISE
                else -> MovementKind.LATERAL_RAISE
            }

            BodyPart.BICEPS -> MovementKind.BICEPS_CURL

            BodyPart.TRICEPS -> if (n.has(" dip")) MovementKind.DIP else MovementKind.TRICEPS_PUSHDOWN
        }
    }

    private fun String.has(vararg words: String) = words.any { contains(it) }
}
