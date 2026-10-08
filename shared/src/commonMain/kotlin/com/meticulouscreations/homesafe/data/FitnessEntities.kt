package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
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
}

/** [FitnessDao] in memory: for the web target, which has no database, and for tests. */
class InMemoryFitnessDao : FitnessDao {
    private val exercises = MutableStateFlow<List<FitnessExerciseEntity>>(emptyList())
    private val sets = MutableStateFlow<List<FitnessSetEntity>>(emptyList())
    private val workouts = MutableStateFlow<List<FitnessWorkoutEntity>>(emptyList())
    private val phases = MutableStateFlow<List<FitnessPhaseEntity>>(emptyList())
    private val bodyweights = MutableStateFlow<List<FitnessBodyweightEntity>>(emptyList())

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
}
