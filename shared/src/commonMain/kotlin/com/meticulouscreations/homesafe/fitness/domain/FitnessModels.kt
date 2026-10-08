package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_equipment_barbell
import homesafe.shared.generated.resources.fitness_equipment_bodyweight
import homesafe.shared.generated.resources.fitness_equipment_cable
import homesafe.shared.generated.resources.fitness_equipment_dumbbell
import homesafe.shared.generated.resources.fitness_equipment_machine
import homesafe.shared.generated.resources.fitness_equipment_other
import homesafe.shared.generated.resources.fitness_equipment_plate_loaded
import homesafe.shared.generated.resources.fitness_equipment_smith
import homesafe.shared.generated.resources.fitness_focus_arms
import homesafe.shared.generated.resources.fitness_focus_back
import homesafe.shared.generated.resources.fitness_focus_chest
import homesafe.shared.generated.resources.fitness_focus_legs
import homesafe.shared.generated.resources.fitness_focus_shoulders
import homesafe.shared.generated.resources.fitness_load_bodyweight
import homesafe.shared.generated.resources.fitness_load_level
import homesafe.shared.generated.resources.fitness_load_per_hand
import homesafe.shared.generated.resources.fitness_load_plates
import homesafe.shared.generated.resources.fitness_load_weight
import homesafe.shared.generated.resources.fitness_muscle_abs
import homesafe.shared.generated.resources.fitness_muscle_adductors
import homesafe.shared.generated.resources.fitness_muscle_back
import homesafe.shared.generated.resources.fitness_muscle_biceps
import homesafe.shared.generated.resources.fitness_muscle_calves
import homesafe.shared.generated.resources.fitness_muscle_chest
import homesafe.shared.generated.resources.fitness_muscle_forearms
import homesafe.shared.generated.resources.fitness_muscle_front_delts
import homesafe.shared.generated.resources.fitness_muscle_glutes
import homesafe.shared.generated.resources.fitness_muscle_hamstrings
import homesafe.shared.generated.resources.fitness_muscle_quads
import homesafe.shared.generated.resources.fitness_muscle_rear_delts
import homesafe.shared.generated.resources.fitness_muscle_side_delts
import homesafe.shared.generated.resources.fitness_muscle_traps
import homesafe.shared.generated.resources.fitness_muscle_triceps
import homesafe.shared.generated.resources.fitness_part_back
import homesafe.shared.generated.resources.fitness_part_biceps
import homesafe.shared.generated.resources.fitness_part_chest
import homesafe.shared.generated.resources.fitness_part_core
import homesafe.shared.generated.resources.fitness_part_legs
import homesafe.shared.generated.resources.fitness_part_shoulders
import homesafe.shared.generated.resources.fitness_part_triceps
import homesafe.shared.generated.resources.fitness_phase_bulk
import homesafe.shared.generated.resources.fitness_phase_cut
import homesafe.shared.generated.resources.fitness_phase_maintain
import org.jetbrains.compose.resources.StringResource

/** The shelf an exercise sits on: the way the notes this app grew out of were divided. */
enum class BodyPart(val label: StringResource) {
    CHEST(Res.string.fitness_part_chest),
    BACK(Res.string.fitness_part_back),
    LEGS(Res.string.fitness_part_legs),
    SHOULDERS(Res.string.fitness_part_shoulders),
    BICEPS(Res.string.fitness_part_biceps),
    TRICEPS(Res.string.fitness_part_triceps),
    CORE(Res.string.fitness_part_core),
}

/**
 * What a workout is for. [parts] are the shelves it opens on; [extras] are the ones that usually
 * ride along with it (pressing already works the shoulders and triceps, pulling the biceps), offered
 * a tap away.
 */
enum class WorkoutFocus(val label: StringResource, val parts: List<BodyPart>, val extras: List<BodyPart>) {
    CHEST(Res.string.fitness_focus_chest, listOf(BodyPart.CHEST), listOf(BodyPart.SHOULDERS, BodyPart.TRICEPS)),
    BACK(Res.string.fitness_focus_back, listOf(BodyPart.BACK), listOf(BodyPart.BICEPS, BodyPart.SHOULDERS)),
    LEGS(Res.string.fitness_focus_legs, listOf(BodyPart.LEGS), listOf(BodyPart.CORE)),
    SHOULDERS(Res.string.fitness_focus_shoulders, listOf(BodyPart.SHOULDERS), listOf(BodyPart.TRICEPS, BodyPart.BICEPS)),
    ARMS(Res.string.fitness_focus_arms, listOf(BodyPart.BICEPS, BodyPart.TRICEPS), listOf(BodyPart.SHOULDERS)),
    ;

    companion object {
        /** The three days the week is built from; the other two are there for when one is wanted. */
        val MAIN = listOf(CHEST, BACK, LEGS)
    }
}

/** A muscle a set is counted toward, for the week's volume. */
enum class Muscle(val label: StringResource) {
    CHEST(Res.string.fitness_muscle_chest),
    BACK(Res.string.fitness_muscle_back),
    TRAPS(Res.string.fitness_muscle_traps),
    FRONT_DELTS(Res.string.fitness_muscle_front_delts),
    SIDE_DELTS(Res.string.fitness_muscle_side_delts),
    REAR_DELTS(Res.string.fitness_muscle_rear_delts),
    BICEPS(Res.string.fitness_muscle_biceps),
    TRICEPS(Res.string.fitness_muscle_triceps),
    FOREARMS(Res.string.fitness_muscle_forearms),
    ABS(Res.string.fitness_muscle_abs),
    QUADS(Res.string.fitness_muscle_quads),
    HAMSTRINGS(Res.string.fitness_muscle_hamstrings),
    GLUTES(Res.string.fitness_muscle_glutes),
    ADDUCTORS(Res.string.fitness_muscle_adductors),
    CALVES(Res.string.fitness_muscle_calves),
}

enum class Equipment(val label: StringResource) {
    BARBELL(Res.string.fitness_equipment_barbell),
    DUMBBELL(Res.string.fitness_equipment_dumbbell),
    MACHINE(Res.string.fitness_equipment_machine),
    CABLE(Res.string.fitness_equipment_cable),
    PLATE_LOADED(Res.string.fitness_equipment_plate_loaded),
    SMITH(Res.string.fitness_equipment_smith),
    BODYWEIGHT(Res.string.fitness_equipment_bodyweight),
    OTHER(Res.string.fitness_equipment_other),
}

/** What the number written beside the reps means for an exercise, which is also how it is shown and stepped. */
enum class LoadKind(val label: StringResource) {
    /** Pounds on the bar, the stack or the cable. */
    WEIGHT(Res.string.fitness_load_weight),

    /** Pounds in each hand: a pair of dumbbells, written "44 lb × 2" in the notes. */
    PER_HAND(Res.string.fitness_load_per_hand),

    /** Pounds, kept as the total, but loaded and spoken of as 45 lb plates a side: "4 plates + 25". */
    PLATES(Res.string.fitness_load_plates),

    /** A machine's pin number, which isn't pounds at all. */
    LEVEL(Res.string.fitness_load_level),

    /** The body itself, with anything hung from it as the weight (0 when there is nothing). */
    BODYWEIGHT(Res.string.fitness_load_bodyweight),
}

/** The stretch of training a set belongs to: gaining, leaning out, or holding. */
enum class PhaseKind(val label: StringResource) {
    BULK(Res.string.fitness_phase_bulk),
    CUT(Res.string.fitness_phase_cut),
    MAINTAIN(Res.string.fitness_phase_maintain),
}

/**
 * One lift, machine or movement the lifter tracks. [repLow]–[repHigh] is the band its peak set
 * is worked in before weight goes on, [increment] the usual jump in its own unit (see
 * [LoadKind]), [restSeconds] what the timer counts after a set. [note] is the lifter's own line
 * about it: a seat height, a cue.
 */
@Immutable
data class Exercise(
    val id: String,
    val name: String,
    val bodyPart: BodyPart,
    val equipment: Equipment = Equipment.OTHER,
    val loadKind: LoadKind = LoadKind.WEIGHT,
    val primary: Muscle,
    val secondary: List<Muscle> = emptyList(),
    val repLow: Int = 8,
    val repHigh: Int = 12,
    val increment: Double = 5.0,
    val restSeconds: Int = 120,
    val note: String = "",
    val archived: Boolean = false,
) {
    companion object {
        /** The id an exercise called [name] on the [bodyPart] shelf has: stable, so importing the same notes twice lands on the same exercise. */
        fun idFor(bodyPart: BodyPart, name: String): String =
            bodyPart.name.lowercase() + "/" + name.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().split(Regex("\\s+")).joinToString("-")
    }
}

/**
 * One set as it was done. [weight] is in the exercise's own unit ([LoadKind]); for a bodyweight
 * movement it is what was added. [epochSeconds] is 0 for a benchmark brought over from the notes
 * with no date on it: something that was once done, at a time nobody wrote down. [bodyweight] is
 * what the lifter weighed when it matters to the lift and was noted.
 */
@Immutable
data class LoggedSet(
    val id: Long,
    val exerciseId: String,
    val weight: Double,
    val reps: Int,
    val epochSeconds: Long,
    val workoutId: Long? = null,
    val bodyweight: Double? = null,
    val note: String = "",
    val imported: Boolean = false,
) {
    /** From the notes, undated: it counts toward the all-time bests and the ladder but belongs to no phase and no week. */
    val isBenchmark: Boolean get() = epochSeconds == 0L
}

/** A session at the gym: open while [finishedAtEpochSeconds] is null. */
@Immutable
data class Workout(
    val id: Long,
    val focus: WorkoutFocus,
    val startedAtEpochSeconds: Long,
    val finishedAtEpochSeconds: Long? = null,
)

/** A phase begins at [startedAtEpochSeconds] and runs until the next one does. */
@Immutable
data class Phase(val id: Long, val kind: PhaseKind, val startedAtEpochSeconds: Long)

/** A morning's weigh-in, by the day it was taken ([epochDay], days since 1970 in local time). */
@Immutable
data class BodyweightEntry(val epochDay: Long, val pounds: Double)

internal const val SECONDS_PER_DAY = 86_400L
