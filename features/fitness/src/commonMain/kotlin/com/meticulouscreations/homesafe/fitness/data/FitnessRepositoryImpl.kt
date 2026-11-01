package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessBodyweightEntity
import com.meticulouscreations.homesafe.data.FitnessDao
import com.meticulouscreations.homesafe.data.FitnessExerciseEntity
import com.meticulouscreations.homesafe.data.FitnessHeartSettingsEntity
import com.meticulouscreations.homesafe.data.FitnessHeartSummaryEntity
import com.meticulouscreations.homesafe.data.FitnessLogGone
import com.meticulouscreations.homesafe.data.FitnessLogRows
import com.meticulouscreations.homesafe.data.FitnessPhaseEntity
import com.meticulouscreations.homesafe.data.FitnessSetEntity
import com.meticulouscreations.homesafe.data.FitnessWorkoutEntity
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.Equipment
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.FitnessIdFloor
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
import kotlin.time.Clock

/** The training log in the app's database ([FitnessDao]). */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FitnessRepositoryImpl(
    private val dao: FitnessDao,
    /** Where a new row's id begins. A test that counts ids hands in its own. */
    private val idFloor: FitnessIdFloor = FitnessIdFloor.FromClock,
) : FitnessRepository {
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
        var next = newId(dao.maxSetId())
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
        val here = held()
        val merge = LogCopies.merge(copy, here, floorOver(here))
        if (!merge.isEmpty) {
            val rows = merge.toRows()
            dao.writeMerge(
                exercises = rows.exercises,
                sets = rows.sets,
                workouts = rows.workouts,
                phases = rows.phases,
                bodyweights = rows.bodyweights,
                heartSettings = merge.heart?.toEntity(),
                heartSummaries = rows.heartSummaries,
            )
        }
        merge
    }

    /** A new row's id: past the [highest] the log holds, and past [idFloor]. */
    private fun newId(highest: Long?): Long = maxOf((highest ?: 0L) + 1, idFloor.next(highest ?: 0L))

    /** Where the new ids of a merge into [log] begin. */
    private fun floorOver(log: LogCopy): Long =
        idFloor.next(maxOf(log.workouts.maxOfOrNull { it.id } ?: 0L, log.sets.maxOfOrNull { it.id } ?: 0L, log.phases.maxOfOrNull { it.id } ?: 0L))

    /** Runs [block] with no write to the log able to land in the middle of it: what the server is told, and what it says, are worked out under this. */
    internal suspend fun <T> holdingLog(block: suspend () -> T): T = writes.withLock { block() }

    /** The log's rows as they stand. Under [holdingLog]. */
    internal suspend fun rows() = FitnessLogRows(
        exercises = dao.observeExercises().first(),
        sets = dao.sets(),
        workouts = dao.observeWorkouts().first(),
        phases = dao.observePhases().first(),
        bodyweights = dao.observeBodyweights().first(),
        heartSummaries = dao.observeHeartSummaries().first(),
    )

    /** What the zones are worked out from, which is what goes to the server of the heart-rate settings. Under [holdingLog]. */
    internal suspend fun heartProfile(): HeartProfile = dao.heartSettings()?.let { HeartProfile(it.maxBpm, it.age, it.restingBpm) } ?: HeartProfile()

    /** Writes what another device changed. A null [profile] leaves the zones as they are; the sensor chosen here stays, whatever they did there. Under [holdingLog]. */
    internal suspend fun writeFromServer(written: FitnessLogRows, profile: HeartProfile?, gone: FitnessLogGone) {
        dao.writeFromServer(written, profile?.let { (dao.heartSettings() ?: EMPTY_HEART_SETTINGS).withProfile(it) }, gone)
    }

    /**
     * Brings this log together with the server's for the first time, which is [server] as it stands:
     * what the server has is taken as it is, and what only this device had is kept, under new ids
     * ([LogCopies.merge], as for a copy of a log brought in). The new ones are in the log afterwards
     * and not on the server, so the next comparison sends them. One transaction, so that being stopped
     * part-way loses nothing, and if it runs again it finds nothing more to add.
     *
     * Not while a workout in progress here would change its id (returns false), because the screen is
     * holding on to it: anything but the server's same workout does, since another install's workout
     * can have the id without being the one. A later try will find it closed.
     */
    internal suspend fun adopt(server: FitnessLogRows, profile: HeartProfile, nowEpochSeconds: Long = Clock.System.now().epochSeconds): Boolean = writes.withLock {
        val here = held()
        val theirs = LogCopy(
            exercises = server.exercises.map { it.toDomain() },
            sets = server.sets.map { it.toDomain() },
            workouts = server.workouts.map { it.toDomain() },
            phases = server.phases.map { Phase(it.id, enumOr(it.kind, PhaseKind.MAINTAIN), it.startedAtEpochSeconds) },
            bodyweights = server.bodyweights.map { BodyweightEntry(it.epochDay, it.pounds) },
            heart = HeartSettings(profile),
            heartSummaries = server.heartSummaries.associate { it.workoutId to it.toDomain() },
        )
        val keepsItsId = theirs.workouts.mapTo(HashSet()) { Triple(it.id, it.focus, it.startedAtEpochSeconds) }
        if (here.workouts.any { it.isInProgress(nowEpochSeconds) && Triple(it.id, it.focus, it.startedAtEpochSeconds) !in keepsItsId }) return@withLock false
        val merge = LogCopies.merge(here, theirs, floorOver(theirs))
        val added = merge.toRows()
        // The exercises the merge rewrites are the ones it says differ, so they go in over the server's own.
        val rewritten = added.exercises.mapTo(HashSet()) { it.id }
        dao.replaceLog(
            FitnessLogRows(
                exercises = server.exercises.filter { it.id !in rewritten } + added.exercises,
                sets = server.sets + added.sets,
                workouts = server.workouts + added.workouts,
                phases = server.phases + added.phases,
                bodyweights = server.bodyweights + added.bodyweights,
                heartSummaries = server.heartSummaries + added.heartSummaries,
            ),
            (merge.heart ?: theirs.heart).toEntity(),
        )
        true
    }

    /** Takes everything out of the log but the sensor chosen here. For a log that has all gone to the server and belongs to someone else. Under [holdingLog]. */
    internal suspend fun emptyLog() = dao.replaceLog(FitnessLogRows(), dao.heartSettings()?.withProfile(HeartProfile()))

    /** Under [writes], which every write takes: the log as it stands at one moment, with no edit half in it. */
    private suspend fun held() = LogCopy(exercises.first(), sets.first(), workouts.first(), phases.first(), bodyweights.first(), heartSettings.first(), heartSummaries.first())

    override suspend fun updateSet(set: LoggedSet) = writes.withLock {
        dao.upsertSets(listOf(set.toEntity()))
    }

    override suspend fun deleteSet(id: Long) = writes.withLock { dao.deleteSet(id) }

    override suspend fun deleteSets(ids: List<Long>) {
        if (ids.isNotEmpty()) writes.withLock { dao.deleteSets(ids) }
    }

    override suspend fun startWorkout(focus: WorkoutFocus, atEpochSeconds: Long): Workout = writes.withLock {
        dao.openWorkout()?.toDomain()?.takeIf { it.isInProgress(atEpochSeconds) }?.let { return@withLock it }
        val row = FitnessWorkoutEntity(newId(dao.maxWorkoutId()), focus.name, atEpochSeconds, null)
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

    override suspend fun deleteWorkout(id: Long) = writes.withLock { dao.removeWorkout(id) }

    override suspend fun startPhase(kind: PhaseKind, atEpochSeconds: Long) = writes.withLock {
        dao.upsertPhase(FitnessPhaseEntity(newId(dao.maxPhaseId()), kind.name, atEpochSeconds))
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
        dao.upsertHeartSettings(row.withProfile(profile))
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

private fun FitnessHeartSettingsEntity.withProfile(profile: HeartProfile) = copy(maxBpm = profile.maxBpm, age = profile.age, restingBpm = profile.restingBpm)

private fun HeartSettings.toEntity() =
    FitnessHeartSettingsEntity(maxBpm = profile.maxBpm, age = profile.age, restingBpm = profile.restingBpm, sensorAddress = sensor?.address, sensorName = sensor?.name)

private fun LogMerge.toRows() = FitnessLogRows(
    exercises = exercises.map { it.toEntity() },
    sets = sets.map { it.toEntity() },
    workouts = workouts.map { FitnessWorkoutEntity(it.id, it.focus.name, it.startedAtEpochSeconds, it.finishedAtEpochSeconds) },
    phases = phases.map { FitnessPhaseEntity(it.id, it.kind.name, it.startedAtEpochSeconds) },
    bodyweights = bodyweights.map { FitnessBodyweightEntity(it.epochDay, it.pounds) },
    heartSummaries = heartSummaries.map { (workoutId, summary) -> summary.toEntity(workoutId) },
)

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
