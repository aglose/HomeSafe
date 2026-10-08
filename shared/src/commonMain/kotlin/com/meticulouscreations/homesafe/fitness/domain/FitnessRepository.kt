package com.meticulouscreations.homesafe.fitness.domain

import kotlinx.coroutines.flow.Flow

/**
 * The training log, kept on the device: the exercises, every set, the workouts they were done
 * in, the phases and the weigh-ins. Each is small enough to be handed over whole whenever it
 * changes, which is what the screens are worked out from.
 */
interface FitnessRepository {
    val exercises: Flow<List<Exercise>>
    val sets: Flow<List<LoggedSet>>
    val workouts: Flow<List<Workout>>
    val phases: Flow<List<Phase>>
    val bodyweights: Flow<List<BodyweightEntry>>

    suspend fun saveExercises(exercises: List<Exercise>)

    /** Removes the exercise and every set of it. */
    suspend fun deleteExercise(id: String)

    /** Saves [drafts]. One that names a workout that is no longer there is kept, with no workout. */
    suspend fun addSets(drafts: List<SetDraft>): List<LoggedSet>

    /**
     * Brings an import in as one step: the [exercises] that are new, and of [drafts] only the
     * sets the log hasn't already got (same exercise, weight, reps and date), so that the same
     * plan confirmed twice adds its sets once. Returns how many sets were added.
     */
    suspend fun importNotes(exercises: List<Exercise>, drafts: List<SetDraft>): Int

    suspend fun updateSet(set: LoggedSet)

    suspend fun deleteSet(id: Long)

    /** Opens a workout, or hands back the one already in progress: there is only ever one. */
    suspend fun startWorkout(focus: WorkoutFocus, atEpochSeconds: Long): Workout

    /** Closes the workout at [atEpochSeconds]. One with no sets in it was opened by mistake, and is removed instead. */
    suspend fun finishWorkout(id: Long, atEpochSeconds: Long)

    suspend fun startPhase(kind: PhaseKind, atEpochSeconds: Long)

    suspend fun saveBodyweight(entry: BodyweightEntry)

    suspend fun deleteBodyweight(epochDay: Long)
}
