package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessBodyweightEntity
import com.meticulouscreations.homesafe.data.FitnessDao
import com.meticulouscreations.homesafe.data.FitnessExerciseEntity
import com.meticulouscreations.homesafe.data.FitnessHeartSettingsEntity
import com.meticulouscreations.homesafe.data.FitnessHeartSummaryEntity
import com.meticulouscreations.homesafe.data.FitnessPhaseEntity
import com.meticulouscreations.homesafe.data.FitnessSetEntity
import com.meticulouscreations.homesafe.data.FitnessWorkoutEntity
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.Equipment
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.FitnessRepository
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSettings
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LogCopies
import com.meticulouscreations.homesafe.fitness.domain.LogCopy
import com.meticulouscreations.homesafe.fitness.domain.LogMerge
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The training log in the app's database ([FitnessDao]). */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FitnessRepositoryImpl(private val dao: FitnessDao) : FitnessRepository {
    // One writer at a time, every write included. Ids are handed out here, rising, so two never get the same one; a decision
    // made on what the log holds (is this workout empty? is one already open? has this set been imported?) is made and acted
    // on in one piece; and the log read under it ([held]) is the log at one moment, with no edit half in it.
    private val writes = Mutex()

    override val exercises: Flow<List<Exercise>> = dao.observeExercises().map { rows -> rows.map { it.toDomain() } }
    override val sets: Flow<List<LoggedSet>> = dao.observeSets().map { rows -> rows.map { it.toDomain() } }
    override val workouts: Flow<List<Workout>> = dao.observeWorkouts().map { rows -> rows.map { it.toDomain() } }
    override val phases: Flow<List<Phase>> = dao.observePhases().map { rows ->
        rows.map { Phase(it.id, enumOr(it.kind, PhaseKind.MAINTAIN), it.startedAtEpochSeconds) }
    }
    override val bodyweights: Flow<List<BodyweightEntry>> = dao.observeBodyweights().map { rows -> rows.map { BodyweightEntry(it.epochDay, it.pounds) } }

    override suspend fun saveExercises(exercises: List<Exercise>) {
        if (exercises.isNotEmpty()) writes.withLock { dao.upsertExercises(exercises.map { it.toEntity() }) }
    }

    override suspend fun deleteExercise(id: String) = writes.withLock {
        dao.deleteSetsOfExercise(id)
        dao.deleteExercise(id)
    }

    override suspend fun addSets(drafts: List<SetDraft>): List<LoggedSet> {
        if (drafts.isEmpty()) return emptyList()
        return writes.withLock { insert(drafts) }
    }

    override suspend fun importNotes(exercises: List<Exercise>, drafts: List<SetDraft>): Int = writes.withLock {
        if (exercises.isNotEmpty()) dao.upsertExercises(exercises.map { it.toEntity() })
        val have = dao.sets().mapTo(HashSet()) { SetKey(it.exerciseId, it.weight, it.reps, it.epochSeconds) }
        insert(drafts.filter { have.add(SetKey(it.exerciseId, it.weight, it.reps, it.epochSeconds)) }).size
    }

    /** Under [writes]. A set for a workout that has gone (cancelled a moment before the set landed) is kept, on its own. */
    private suspend fun insert(drafts: List<SetDraft>): List<LoggedSet> {
        if (drafts.isEmpty()) return emptyList()
        val workouts = drafts.mapNotNull { it.workoutId }.distinct().associateWith { dao.workout(it) != null }
        var next = (dao.maxSetId() ?: 0L) + 1
        val rows = drafts.map { draft ->
            val workoutId = draft.workoutId?.takeIf { workouts[it] == true }
            FitnessSetEntity(next++, draft.exerciseId, workoutId, draft.epochSeconds, draft.weight, draft.reps, draft.bodyweight, draft.note, draft.imported)
        }
        dao.upsertSets(rows)
        return rows.map { it.toDomain() }
    }

    override suspend fun logCopy(): LogCopy = writes.withLock { held() }

    override suspend fun bringIn(copy: LogCopy): LogMerge = writes.withLock {
        // Worked out and written under the one lock every writer takes, so nothing moves between the two; and written as
        // one transaction, so a copy that is cut off part-way (the app killed, the caller cancelled) leaves nothing behind.
        val merge = LogCopies.merge(copy, held())
        if (!merge.isEmpty) {
            dao.writeMerge(
                exercises = merge.exercises.map { it.toEntity() },
                sets = merge.sets.map { it.toEntity() },
                workouts = merge.workouts.map { FitnessWorkoutEntity(it.id, it.focus.name, it.startedAtEpochSeconds, it.finishedAtEpochSeconds) },
                phases = merge.phases.map { FitnessPhaseEntity(it.id, it.kind.name, it.startedAtEpochSeconds) },
                bodyweights = merge.bodyweights.map { FitnessBodyweightEntity(it.epochDay, it.pounds) },
                heartSettings = merge.heart?.let { heart ->
                    val profile = heart.profile
                    FitnessHeartSettingsEntity(maxBpm = profile.maxBpm, age = profile.age, restingBpm = profile.restingBpm, sensorAddress = heart.sensor?.address, sensorName = heart.sensor?.name)
                },
                heartSummaries = merge.heartSummaries.map { (workoutId, summary) -> summary.toEntity(workoutId) },
            )
        }
        merge
    }

    /** Under [writes], which every write takes: the log as it stands at one moment, with no edit half in it. */
    private suspend fun held() = LogCopy(exercises.first(), sets.first(), workouts.first(), phases.first(), bodyweights.first(), heartSettings.first(), heartSummaries.first())

    override suspend fun updateSet(set: LoggedSet) = writes.withLock {
        dao.upsertSets(listOf(set.toEntity()))
    }

    override suspend fun deleteSet(id: Long) = writes.withLock { dao.deleteSet(id) }

    override suspend fun startWorkout(focus: WorkoutFocus, atEpochSeconds: Long): Workout = writes.withLock {
        dao.openWorkout()?.toDomain()?.takeIf { it.isInProgress(atEpochSeconds) }?.let { return@withLock it }
        val row = FitnessWorkoutEntity((dao.maxWorkoutId() ?: 0L) + 1, focus.name, atEpochSeconds, null)
        dao.upsertWorkout(row)
        row.toDomain()
    }

    override suspend fun finishWorkout(id: Long, atEpochSeconds: Long) = writes.withLock {
        val row = dao.workout(id) ?: return@withLock
        if (dao.countSetsOfWorkout(id) == 0) {
            dao.deleteWorkout(id)
            dao.deleteHeartSummary(id)
        } else {
            dao.upsertWorkout(row.copy(finishedAtEpochSeconds = atEpochSeconds))
        }
    }

    override suspend fun startPhase(kind: PhaseKind, atEpochSeconds: Long) = writes.withLock {
        dao.upsertPhase(FitnessPhaseEntity((dao.maxPhaseId() ?: 0L) + 1, kind.name, atEpochSeconds))
    }

    override suspend fun saveBodyweight(entry: BodyweightEntry) = writes.withLock { dao.upsertBodyweight(FitnessBodyweightEntity(entry.epochDay, entry.pounds)) }

    override suspend fun deleteBodyweight(epochDay: Long) = writes.withLock { dao.deleteBodyweight(epochDay) }

    override val heartSettings: Flow<HeartSettings> = dao.observeHeartSettings().map { row ->
        if (row == null) HeartSettings() else HeartSettings(HeartProfile(row.maxBpm, row.age, row.restingBpm), row.sensorAddress?.let { HeartSensor(it, row.sensorName.orEmpty()) })
    }

    override val heartSummaries: Flow<Map<Long, HeartSummary>> = dao.observeHeartSummaries().map { rows -> rows.associate { it.workoutId to it.toDomain() } }

    // The profile and the sensor share a row and are saved apart, so each is written over what the row holds at that moment.
    override suspend fun saveHeartProfile(profile: HeartProfile) = writes.withLock {
        val row = dao.heartSettings() ?: EMPTY_HEART_SETTINGS
        dao.upsertHeartSettings(row.copy(maxBpm = profile.maxBpm, age = profile.age, restingBpm = profile.restingBpm))
    }

    override suspend fun saveHeartSensor(sensor: HeartSensor?) = writes.withLock {
        val row = dao.heartSettings() ?: EMPTY_HEART_SETTINGS
        dao.upsertHeartSettings(row.copy(sensorAddress = sensor?.address, sensorName = sensor?.name))
    }

    override suspend fun saveHeartSummary(workoutId: Long, summary: HeartSummary) = writes.withLock {
        // A workout cancelled a moment before its heart was saved leaves nothing behind.
        if (dao.workout(workoutId) == null) return@withLock
        dao.upsertHeartSummary(summary.toEntity(workoutId))
    }
}

private val EMPTY_HEART_SETTINGS = FitnessHeartSettingsEntity(maxBpm = null, age = null, restingBpm = null, sensorAddress = null, sensorName = null)

private fun FitnessHeartSummaryEntity.toDomain() =
    HeartSummary(belowMillis, listOf(zone1Millis, zone2Millis, zone3Millis, zone4Millis, zone5Millis), bpmMillis, peakBpm)

private fun HeartSummary.toEntity(workoutId: Long) = FitnessHeartSummaryEntity(
    workoutId = workoutId,
    belowMillis = belowMillis,
    zone1Millis = zoneMillis[0],
    zone2Millis = zoneMillis[1],
    zone3Millis = zoneMillis[2],
    zone4Millis = zoneMillis[3],
    zone5Millis = zoneMillis[4],
    bpmMillis = bpmMillis,
    peakBpm = peakBpm,
)

private data class SetKey(val exerciseId: String, val weight: Double, val reps: Int, val epochSeconds: Long)

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

private fun LoggedSet.toEntity() = FitnessSetEntity(id, exerciseId, workoutId, epochSeconds, weight, reps, bodyweight, note, imported)

private fun FitnessSetEntity.toDomain() = LoggedSet(id, exerciseId, weight, reps, epochSeconds, workoutId, bodyweight, note, imported)

private fun FitnessWorkoutEntity.toDomain() = Workout(id, enumOr(focus, WorkoutFocus.CHEST), startedAtEpochSeconds, finishedAtEpochSeconds)
