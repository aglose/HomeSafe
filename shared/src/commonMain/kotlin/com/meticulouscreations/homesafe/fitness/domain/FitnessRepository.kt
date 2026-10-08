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

    suspend fun addSets(drafts: List<SetDraft>): List<LoggedSet>

    suspend fun updateSet(set: LoggedSet)

    suspend fun deleteSet(id: Long)

    suspend fun startWorkout(focus: WorkoutFocus, atEpochSeconds: Long): Workout

    suspend fun finishWorkout(id: Long, atEpochSeconds: Long)

    /** Removes the workout and the sets logged in it: a session opened by mistake. */
    suspend fun discardWorkout(id: Long)

    suspend fun startPhase(kind: PhaseKind, atEpochSeconds: Long)

    suspend fun saveBodyweight(entry: BodyweightEntry)

    suspend fun deleteBodyweight(epochDay: Long)
}
