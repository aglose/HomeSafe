package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A lift, machine or movement the fitness app tracks. Added in schema 19, with the rest of this
 * file. [id] is made from its shelf and name (see `Exercise.idFor`), so the same notes imported
 * twice land on the same row. The enums are stored by name, so a value this build doesn't know
 * degrades to a default instead of failing to read; [secondaryMuscles] is their names, comma
 * separated.
 */
@Entity
data class FitnessExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val bodyPart: String,
    val equipment: String,
    val loadKind: String,
    val primaryMuscle: String,
    val secondaryMuscles: String,
    val repLow: Int,
    val repHigh: Int,
    val increment: Double,
    val restSeconds: Int,
    val note: String,
    val archived: Boolean,
)

/**
 * One set. [epochSeconds] is 0 for a benchmark brought over from the notes with no date on it;
 * [workoutId] is null for those and for anything logged outside a workout. [id] is given by the
 * repository, rising, so sets logged in the same second keep their order.
 */
@Entity(indices = [Index(value = ["exerciseId"])])
data class FitnessSetEntity(
    @PrimaryKey val id: Long,
    val exerciseId: String,
    val workoutId: Long?,
    val epochSeconds: Long,
    val weight: Double,
    val reps: Int,
    val bodyweight: Double?,
    val note: String,
    val imported: Boolean,
)

/** A session; open while [finishedAtEpochSeconds] is null. */
@Entity
data class FitnessWorkoutEntity(
    @PrimaryKey val id: Long,
    val focus: String,
    val startedAtEpochSeconds: Long,
    val finishedAtEpochSeconds: Long?,
)

/** The start of a bulk, a cut or a stretch of maintenance; it runs until the next row's start. */
@Entity
data class FitnessPhaseEntity(
    @PrimaryKey val id: Long,
    val kind: String,
    val startedAtEpochSeconds: Long,
)

/** A weigh-in, one a day ([epochDay] in local time). */
@Entity
data class FitnessBodyweightEntity(
    @PrimaryKey val epochDay: Long,
    val pounds: Double,
)

/**
 * A singleton row (always [id] = 0) holding the heart-rate settings: what the zones are worked
 * out from, and the sensor that was chosen. Added in schema 20, with [FitnessHeartSummaryEntity].
 * A missing row, like a null column, means "not set".
 */
@Entity
data class FitnessHeartSettingsEntity(
    @PrimaryKey val id: Int = 0,
    val maxBpm: Int?,
    val age: Int?,
    val restingBpm: Int?,
    val sensorAddress: String?,
    val sensorName: String?,
)

/**
 * A workout's heart, added up while it was open: the milliseconds spent under zone 1 and in
 * each zone as the zones were set that day, the readings weighted by how long each stood (for
 * the average), and the highest. One row a workout, and only for workouts a sensor was on for.
 * The readings themselves are not kept.
 */
@Entity
data class FitnessHeartSummaryEntity(
    @PrimaryKey val workoutId: Long,
    val belowMillis: Long,
    val zone1Millis: Long,
    val zone2Millis: Long,
    val zone3Millis: Long,
    val zone4Millis: Long,
    val zone5Millis: Long,
    val bpmMillis: Long,
    val peakBpm: Int,
)

@Dao
interface FitnessDao {
    @Query("SELECT * FROM FitnessExerciseEntity ORDER BY name")
    fun observeExercises(): Flow<List<FitnessExerciseEntity>>

    @Upsert
    suspend fun upsertExercises(exercises: List<FitnessExerciseEntity>)

    @Query("DELETE FROM FitnessExerciseEntity WHERE id = :id")
    suspend fun deleteExercise(id: String)

    @Query("SELECT * FROM FitnessSetEntity ORDER BY epochSeconds, id")
    fun observeSets(): Flow<List<FitnessSetEntity>>

    @Query("SELECT * FROM FitnessSetEntity")
    suspend fun sets(): List<FitnessSetEntity>

    @Query("SELECT MAX(id) FROM FitnessSetEntity")
    suspend fun maxSetId(): Long?

    @Query("SELECT COUNT(*) FROM FitnessSetEntity WHERE workoutId = :workoutId")
    suspend fun countSetsOfWorkout(workoutId: Long): Int

    @Upsert
    suspend fun upsertSets(sets: List<FitnessSetEntity>)

    @Query("DELETE FROM FitnessSetEntity WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("DELETE FROM FitnessSetEntity WHERE exerciseId = :exerciseId")
    suspend fun deleteSetsOfExercise(exerciseId: String)

    @Query("SELECT * FROM FitnessWorkoutEntity ORDER BY startedAtEpochSeconds, id")
    fun observeWorkouts(): Flow<List<FitnessWorkoutEntity>>

    @Query("SELECT * FROM FitnessWorkoutEntity WHERE id = :id")
    suspend fun workout(id: Long): FitnessWorkoutEntity?

    @Query("SELECT * FROM FitnessWorkoutEntity WHERE finishedAtEpochSeconds IS NULL ORDER BY startedAtEpochSeconds DESC, id DESC LIMIT 1")
    suspend fun openWorkout(): FitnessWorkoutEntity?

    @Query("SELECT MAX(id) FROM FitnessWorkoutEntity")
    suspend fun maxWorkoutId(): Long?

    @Upsert
    suspend fun upsertWorkout(workout: FitnessWorkoutEntity)

    @Query("DELETE FROM FitnessWorkoutEntity WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Query("SELECT * FROM FitnessPhaseEntity ORDER BY startedAtEpochSeconds, id")
    fun observePhases(): Flow<List<FitnessPhaseEntity>>

    @Query("SELECT MAX(id) FROM FitnessPhaseEntity")
    suspend fun maxPhaseId(): Long?

    @Upsert
    suspend fun upsertPhase(phase: FitnessPhaseEntity)

    @Query("SELECT * FROM FitnessBodyweightEntity ORDER BY epochDay")
    fun observeBodyweights(): Flow<List<FitnessBodyweightEntity>>

    @Upsert
    suspend fun upsertBodyweight(entry: FitnessBodyweightEntity)

    @Query("DELETE FROM FitnessBodyweightEntity WHERE epochDay = :epochDay")
    suspend fun deleteBodyweight(epochDay: Long)

    @Query("SELECT * FROM FitnessHeartSettingsEntity WHERE id = 0")
    fun observeHeartSettings(): Flow<FitnessHeartSettingsEntity?>

    @Query("SELECT * FROM FitnessHeartSettingsEntity WHERE id = 0")
    suspend fun heartSettings(): FitnessHeartSettingsEntity?

    @Upsert
    suspend fun upsertHeartSettings(settings: FitnessHeartSettingsEntity)

    @Query("SELECT * FROM FitnessHeartSummaryEntity ORDER BY workoutId")
    fun observeHeartSummaries(): Flow<List<FitnessHeartSummaryEntity>>

    @Upsert
    suspend fun upsertHeartSummary(summary: FitnessHeartSummaryEntity)

    @Query("DELETE FROM FitnessHeartSummaryEntity WHERE workoutId = :workoutId")
    suspend fun deleteHeartSummary(workoutId: Long)

    /**
     * Writes everything a copy of a log brings, in one transaction: all of it lands or none of it
     * does, so a set is never left pointing at a workout that didn't make it in. Sets go before
     * the workouts they were done in, for the one implementation that has no transactions
     * ([InMemoryFitnessDao]): whoever follows the log there never sees one of those workouts empty.
     */
    @Transaction
    suspend fun writeMerge(
        exercises: List<FitnessExerciseEntity>,
        sets: List<FitnessSetEntity>,
        workouts: List<FitnessWorkoutEntity>,
        phases: List<FitnessPhaseEntity>,
        bodyweights: List<FitnessBodyweightEntity>,
        heartSettings: FitnessHeartSettingsEntity?,
        heartSummaries: List<FitnessHeartSummaryEntity>,
    ) {
        if (exercises.isNotEmpty()) upsertExercises(exercises)
        if (sets.isNotEmpty()) upsertSets(sets)
        workouts.forEach { upsertWorkout(it) }
        phases.forEach { upsertPhase(it) }
        bodyweights.forEach { upsertBodyweight(it) }
        if (heartSettings != null) upsertHeartSettings(heartSettings)
        heartSummaries.forEach { upsertHeartSummary(it) }
    }
}

/** [FitnessDao] in memory: for the web target, which has no database, and for tests. */
class InMemoryFitnessDao : FitnessDao {
    private val exercises = MutableStateFlow<List<FitnessExerciseEntity>>(emptyList())
    private val sets = MutableStateFlow<List<FitnessSetEntity>>(emptyList())
    private val workouts = MutableStateFlow<List<FitnessWorkoutEntity>>(emptyList())
    private val phases = MutableStateFlow<List<FitnessPhaseEntity>>(emptyList())
    private val bodyweights = MutableStateFlow<List<FitnessBodyweightEntity>>(emptyList())
    private val heartSettings = MutableStateFlow<FitnessHeartSettingsEntity?>(null)
    private val heartSummaries = MutableStateFlow<List<FitnessHeartSummaryEntity>>(emptyList())

    override fun observeExercises(): Flow<List<FitnessExerciseEntity>> = exercises

    override suspend fun upsertExercises(exercises: List<FitnessExerciseEntity>) {
        val replaced = exercises.associateBy { it.id }
        this.exercises.update { current -> (current.filter { it.id !in replaced } + replaced.values).sortedBy { it.name } }
    }

    override suspend fun deleteExercise(id: String) {
        exercises.update { current -> current.filter { it.id != id } }
    }

    override fun observeSets(): Flow<List<FitnessSetEntity>> = sets

    override suspend fun sets(): List<FitnessSetEntity> = sets.value

    override suspend fun maxSetId(): Long? = sets.value.maxOfOrNull { it.id }

    override suspend fun countSetsOfWorkout(workoutId: Long): Int = sets.value.count { it.workoutId == workoutId }

    override suspend fun upsertSets(sets: List<FitnessSetEntity>) {
        val replaced = sets.associateBy { it.id }
        this.sets.update { current ->
            (current.filter { it.id !in replaced } + replaced.values).sortedWith(compareBy({ it.epochSeconds }, { it.id }))
        }
    }

    override suspend fun deleteSet(id: Long) {
        sets.update { current -> current.filter { it.id != id } }
    }

    override suspend fun deleteSetsOfExercise(exerciseId: String) {
        sets.update { current -> current.filter { it.exerciseId != exerciseId } }
    }

    override fun observeWorkouts(): Flow<List<FitnessWorkoutEntity>> = workouts

    override suspend fun workout(id: Long): FitnessWorkoutEntity? = workouts.value.firstOrNull { it.id == id }

    override suspend fun openWorkout(): FitnessWorkoutEntity? =
        workouts.value.filter { it.finishedAtEpochSeconds == null }.maxWithOrNull(compareBy({ it.startedAtEpochSeconds }, { it.id }))

    override suspend fun maxWorkoutId(): Long? = workouts.value.maxOfOrNull { it.id }

    override suspend fun upsertWorkout(workout: FitnessWorkoutEntity) {
        workouts.update { current ->
            (current.filter { it.id != workout.id } + workout).sortedWith(compareBy({ it.startedAtEpochSeconds }, { it.id }))
        }
    }

    override suspend fun deleteWorkout(id: Long) {
        workouts.update { current -> current.filter { it.id != id } }
    }

    override fun observePhases(): Flow<List<FitnessPhaseEntity>> = phases

    override suspend fun maxPhaseId(): Long? = phases.value.maxOfOrNull { it.id }

    override suspend fun upsertPhase(phase: FitnessPhaseEntity) {
        phases.update { current ->
            (current.filter { it.id != phase.id } + phase).sortedWith(compareBy({ it.startedAtEpochSeconds }, { it.id }))
        }
    }

    override fun observeBodyweights(): Flow<List<FitnessBodyweightEntity>> = bodyweights

    override suspend fun upsertBodyweight(entry: FitnessBodyweightEntity) {
        bodyweights.update { current -> (current.filter { it.epochDay != entry.epochDay } + entry).sortedBy { it.epochDay } }
    }

    override suspend fun deleteBodyweight(epochDay: Long) {
        bodyweights.update { current -> current.filter { it.epochDay != epochDay } }
    }

    override fun observeHeartSettings(): Flow<FitnessHeartSettingsEntity?> = heartSettings

    override suspend fun heartSettings(): FitnessHeartSettingsEntity? = heartSettings.value

    override suspend fun upsertHeartSettings(settings: FitnessHeartSettingsEntity) {
        heartSettings.value = settings
    }

    override fun observeHeartSummaries(): Flow<List<FitnessHeartSummaryEntity>> = heartSummaries

    override suspend fun upsertHeartSummary(summary: FitnessHeartSummaryEntity) {
        heartSummaries.update { current -> (current.filter { it.workoutId != summary.workoutId } + summary).sortedBy { it.workoutId } }
    }

    override suspend fun deleteHeartSummary(workoutId: Long) {
        heartSummaries.update { current -> current.filter { it.workoutId != workoutId } }
    }
}
