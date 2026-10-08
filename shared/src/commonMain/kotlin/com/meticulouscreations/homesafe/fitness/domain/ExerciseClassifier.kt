package com.meticulouscreations.homesafe.fitness.domain

/** What an exercise's name says about it: where it belongs, what it is done on and what it works. */
data class Classification(
    val bodyPart: BodyPart,
    val equipment: Equipment,
    val loadKind: LoadKind,
    val primary: Muscle,
    val secondary: List<Muscle>,
    val repBand: IntRange,
    val increment: Double,
    val restSeconds: Int,
)

/**
 * Reads an exercise's name the way a lifter would ("Rear delt cable perp", "Incline press
 * machine") and fills in the rest, so that neither importing a note nor adding an exercise by
 * hand means a form. Every guess is only a starting point: the exercise's own page changes any
 * of it.
 *
 * Which muscles get credit follows the usual convention for counting volume: the muscle a lift
 * is for counts a full set, the ones that help count half (Pelland et al., whose "fractional"
 * counting predicted growth best).
 */
object ExerciseClassifier {
    fun classify(name: String, section: BodyPart? = null): Classification {
        val n = " " + name.lowercase().replace('-', ' ') + " "
        val part = section ?: bodyPart(n)
        val equipment = equipment(n)
        val (primary, secondary) = muscles(n, part)
        val isolation = secondary.isEmpty() || primary == Muscle.BICEPS || primary == Muscle.FOREARMS
        val small = primary in SMALL
        val band = when {
            equipment == Equipment.BODYWEIGHT -> 8..15
            small -> 15..20
            isolation -> 10..15
            equipment == Equipment.BARBELL -> 6..10
            else -> 8..12
        }
        val increment = when (equipment) {
            Equipment.BARBELL -> if (part == BodyPart.LEGS) 10.0 else 5.0
            Equipment.DUMBBELL -> 5.0
            Equipment.CABLE -> if (isolation) 2.5 else 5.0
            Equipment.MACHINE, Equipment.SMITH, Equipment.PLATE_LOADED -> 10.0
            Equipment.BODYWEIGHT, Equipment.OTHER -> 5.0
        }
        // Long enough before a peak set to do it justice; little is gained past a couple of minutes on the small stuff (Singer et al. 2024).
        val rest = when {
            primary == Muscle.CALVES || primary == Muscle.ABS -> 60
            isolation || small -> 90
            equipment == Equipment.BARBELL -> 180
            else -> 150
        }
        val loadKind = if (equipment == Equipment.BODYWEIGHT) LoadKind.BODYWEIGHT else LoadKind.WEIGHT
        return Classification(part, equipment, loadKind, primary, secondary, band, increment, rest)
    }

    private fun String.has(vararg words: String) = words.any { contains(it) }

    /** The shelf a name belongs on when no heading said. The order settles the overlaps: a leg curl is not a curl, a rear delt row is not a row. */
    private fun bodyPart(n: String): BodyPart = when {
        n.has(" ab ", " abs ", "crunch", "plank", "hip flexion") -> BodyPart.CORE
        n.has("leg ", "squat", "calf", "hamstring", "quad", "glute", "abductor", "adductor", "lunge", "hip ", "deadlift") -> BodyPart.LEGS
        n.has("delt", "shoulder", "military", "lateral", "rotator", "overhead press") -> BodyPart.SHOULDERS
        n.has("tricep", "push down", "pushdown", "skull", " dip") -> BodyPart.TRICEPS
        n.has("curl", "bicep", "hammer", "forearm") -> BodyPart.BICEPS
        n.has("lat ", "pulldown", "pull down", "pull up", "pullup", "row", "trap", "shrug", "chin") -> BodyPart.BACK
        n.has("chest", "bench", "pec", "incline", "push up", "pushup", " fly") -> BodyPart.CHEST
        else -> BodyPart.CORE
    }

    private fun equipment(n: String): Equipment = when {
        n.has("smith") -> Equipment.SMITH

        n.has("dumbbell", " db ", "hammer curl", "incline curl", "raises") -> Equipment.DUMBBELL

        n.has("plate") -> Equipment.PLATE_LOADED

        n.has("barbell", "olympic", " ez ", "military") -> Equipment.BARBELL

        n.has("cable", "push down", "pushdown", "rope", "strap", "triangle bar", "straight bar", "dbar", "d bar") -> Equipment.CABLE

        n.has("machine", "hack squat", "leg press", "extension", "abductor", "adductor", "pulldown", "pull down", "leg curl", " fly", "high row", " press ") && !n.has("bench") -> Equipment.MACHINE

        n.has("pull up", "pullup", " dip", "pistol", "push up", "pushup", "chin up", "ring ") -> Equipment.BODYWEIGHT

        // Named for the lift alone, it is the barbell one.
        n.has("bench", "squat", "deadlift") -> Equipment.BARBELL

        else -> Equipment.OTHER
    }

    private fun muscles(n: String, part: BodyPart): Pair<Muscle, List<Muscle>> = when (part) {
        BodyPart.CHEST -> when {
            n.has(" fly", "cross", "pec deck") -> Muscle.CHEST to emptyList()
            else -> Muscle.CHEST to listOf(Muscle.FRONT_DELTS, Muscle.TRICEPS)
        }

        BodyPart.BACK -> when {
            n.has("rear delt") -> Muscle.REAR_DELTS to listOf(Muscle.TRAPS)
            n.has("shrug") -> Muscle.TRAPS to emptyList()
            n.has("trap") -> Muscle.TRAPS to listOf(Muscle.BACK, Muscle.REAR_DELTS)
            n.has("straight arm", "pullover") -> Muscle.BACK to emptyList()
            n.has("row") -> Muscle.BACK to listOf(Muscle.BICEPS, Muscle.REAR_DELTS)
            else -> Muscle.BACK to listOf(Muscle.BICEPS)
        }

        BodyPart.LEGS -> when {
            n.has("calf") -> Muscle.CALVES to emptyList()
            n.has("leg curl", "hamstring") -> Muscle.HAMSTRINGS to emptyList()
            n.has("extension") -> Muscle.QUADS to emptyList()
            n.has("abductor") -> Muscle.GLUTES to emptyList()
            n.has("adductor") -> Muscle.ADDUCTORS to emptyList()
            n.has("deadlift", "hip thrust") -> Muscle.GLUTES to listOf(Muscle.HAMSTRINGS)
            n.has("glute") -> Muscle.GLUTES to listOf(Muscle.QUADS)
            else -> Muscle.QUADS to listOf(Muscle.GLUTES)
        }

        BodyPart.SHOULDERS -> when {
            n.has("rear delt") && n.has("row") -> Muscle.REAR_DELTS to listOf(Muscle.BACK)

            n.has("rear delt", "rotator", "face pull") -> Muscle.REAR_DELTS to emptyList()

            n.has("middle delt", "side delt", "lateral") -> Muscle.SIDE_DELTS to emptyList()

            // "Antieror" is how the notes spell it.
            n.has("anterior", "antieror", "front") -> Muscle.FRONT_DELTS to emptyList()

            n.has("press", "military") -> Muscle.FRONT_DELTS to listOf(Muscle.SIDE_DELTS, Muscle.TRICEPS)

            else -> Muscle.SIDE_DELTS to emptyList()
        }

        BodyPart.BICEPS -> when {
            n.has("forearm", "wrist") -> Muscle.FOREARMS to emptyList()
            n.has("hammer") -> Muscle.BICEPS to listOf(Muscle.FOREARMS)
            else -> Muscle.BICEPS to emptyList()
        }

        BodyPart.TRICEPS -> when {
            n.has(" dip") -> Muscle.TRICEPS to listOf(Muscle.CHEST, Muscle.FRONT_DELTS)
            else -> Muscle.TRICEPS to emptyList()
        }

        BodyPart.CORE -> Muscle.ABS to emptyList()
    }

    /** Muscles that are trained light and long. */
    private val SMALL = setOf(Muscle.SIDE_DELTS, Muscle.REAR_DELTS, Muscle.CALVES, Muscle.ABS, Muscle.FOREARMS)
}
