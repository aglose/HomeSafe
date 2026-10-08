package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessBodyweightEntity
import com.meticulouscreations.homesafe.data.FitnessDao
import com.meticulouscreations.homesafe.data.FitnessExerciseEntity
import com.meticulouscreations.homesafe.data.FitnessPhaseEntity
import com.meticulouscreations.homesafe.data.FitnessSetEntity
import com.meticulouscreations.homesafe.data.FitnessWorkoutEntity
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.Equipment
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.FitnessRepository
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.Muscle
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The training log in the app's database ([FitnessDao]). */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FitnessRepositoryImpl(private val dao: FitnessDao) : FitnessRepository {
    // Ids are handed out here, rising: one writer at a time, so two never get the same one.
    private val ids = Mutex()

    override val exercises: Flow<List<Exercise>> = dao.observeExercises().map { rows -> rows.map { it.toDomain() } }
    override val sets: Flow<List<LoggedSet>> = dao.observeSets().map { rows -> rows.map { it.toDomain() } }
    override val workouts: Flow<List<Workout>> = dao.observeWorkouts().map { rows -> rows.map { it.toDomain() } }
    override val phases: Flow<List<Phase>> = dao.observePhases().map { rows ->
        rows.map { Phase(it.id, enumOr(it.kind, PhaseKind.MAINTAIN), it.startedAtEpochSeconds) }
    }
    override val bodyweights: Flow<List<BodyweightEntry>> = dao.observeBodyweights().map { rows -> rows.map { BodyweightEntry(it.epochDay, it.pounds) } }

    override suspend fun saveExercises(exercises: List<Exercise>) {
        if (exercises.isNotEmpty()) dao.upsertExercises(exercises.map { it.toEntity() })
    }

    override suspend fun deleteExercise(id: String) {
        dao.deleteSetsOfExercise(id)
        dao.deleteExercise(id)
    }

    override suspend fun addSets(drafts: List<SetDraft>): List<LoggedSet> {
        if (drafts.isEmpty()) return emptyList()
        return ids.withLock {
            var next = (dao.maxSetId() ?: 0L) + 1
            val rows = drafts.map { draft ->
                FitnessSetEntity(next++, draft.exerciseId, draft.workoutId, draft.epochSeconds, draft.weight, draft.reps, draft.bodyweight, draft.note, draft.imported)
            }
            dao.upsertSets(rows)
            rows.map { it.toDomain() }
        }
    }

    override suspend fun updateSet(set: LoggedSet) {
        dao.upsertSets(listOf(FitnessSetEntity(set.id, set.exerciseId, set.workoutId, set.epochSeconds, set.weight, set.reps, set.bodyweight, set.note, set.imported)))
    }

    override suspend fun deleteSet(id: Long) = dao.deleteSet(id)

    override suspend fun startWorkout(focus: WorkoutFocus, atEpochSeconds: Long): Workout = ids.withLock {
        val row = FitnessWorkoutEntity((dao.maxWorkoutId() ?: 0L) + 1, focus.name, atEpochSeconds, null)
        dao.upsertWorkout(row)
        row.toDomain()
    }

    override suspend fun finishWorkout(id: Long, atEpochSeconds: Long) {
        val row = dao.workout(id) ?: return
        dao.upsertWorkout(row.copy(finishedAtEpochSeconds = atEpochSeconds))
    }

    override suspend fun discardWorkout(id: Long) {
        dao.deleteSetsOfWorkout(id)
        dao.deleteWorkout(id)
    }

    override suspend fun startPhase(kind: PhaseKind, atEpochSeconds: Long) = ids.withLock {
        dao.upsertPhase(FitnessPhaseEntity((dao.maxPhaseId() ?: 0L) + 1, kind.name, atEpochSeconds))
    }

    override suspend fun saveBodyweight(entry: BodyweightEntry) = dao.upsertBodyweight(FitnessBodyweightEntity(entry.epochDay, entry.pounds))

    override suspend fun deleteBodyweight(epochDay: Long) = dao.deleteBodyweight(epochDay)
}

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback

private fun FitnessExerciseEntity.toDomain() = Exercise(
    id = id,
    name = name,
    bodyPart = enumOr(bodyPart, BodyPart.CORE),
    equipment = enumOr(equipment, Equipment.OTHER),
    loadKind = enumOr(loadKind, LoadKind.WEIGHT),
    primary = enumOr(primaryMuscle, Muscle.ABS),
    secondary = secondaryMuscles.split(',').mapNotNull { name -> Muscle.entries.firstOrNull { it.name == name } },
    repLow = repLow,
    repHigh = repHigh,
    increment = increment,
    restSeconds = restSeconds,
    note = note,
    archived = archived,
)

private fun Exercise.toEntity() = FitnessExerciseEntity(
    id = id,
    name = name,
    bodyPart = bodyPart.name,
    equipment = equipment.name,
    loadKind = loadKind.name,
    primaryMuscle = primary.name,
    secondaryMuscles = secondary.joinToString(",") { it.name },
    repLow = repLow,
    repHigh = repHigh,
    increment = increment,
    restSeconds = restSeconds,
    note = note,
    archived = archived,
)

private fun FitnessSetEntity.toDomain() = LoggedSet(id, exerciseId, weight, reps, epochSeconds, workoutId, bodyweight, note, imported)

private fun FitnessWorkoutEntity.toDomain() = Workout(id, enumOr(focus, WorkoutFocus.CHEST), startedAtEpochSeconds, finishedAtEpochSeconds)
