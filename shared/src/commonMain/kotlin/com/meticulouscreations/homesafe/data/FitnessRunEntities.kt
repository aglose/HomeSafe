package com.meticulouscreations.homesafe.data

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The runs recorded in the fitness app with the phone's GPS, in a database of their own
 * (`fitness_runs.db`) beside [AppDatabase]. Kept apart because [AppDatabase] has as many tables
 * as Room can check in one generated method (two more and its `onValidateSchema` is past the
 * JVM's 64 KB a method), and because a run is the owner's own record: unlike that database's
 * caches it is never thrown away to get past a migration. A change to these tables needs a
 * migration written for it.
 */
@Database(entities = [FitnessRecordedRunEntity::class, FitnessRunEventEntity::class], version = 1)
@ConstructedBy(FitnessRunsDatabaseConstructor::class)
abstract class FitnessRunsDatabase : RoomDatabase() {
    abstract fun fitnessRunDao(): FitnessRunDao
}

// The Room compiler generates the `actual` implementations for each target.
@Suppress("KotlinNoActualForExpect")
expect object FitnessRunsDatabaseConstructor : RoomDatabaseConstructor<FitnessRunsDatabase> {
    override fun initialize(): FitnessRunsDatabase
}

/** The [FitnessRunDao] of the platform's one [FitnessRunsDatabase]. */
expect fun createFitnessRunDao(context: PlatformContext): FitnessRunDao

/**
 * A run recorded in the fitness app with the phone's GPS, in [FitnessRunsDatabase] with
 * [FitnessRunEventEntity]. [endEpochMillis] is null while it is being recorded, and for one the
 * app was stopped in the middle of until it is next opened; [meters] and [movingMillis] are
 * what it came to, written when it is finished.
 */
@Entity
data class FitnessRecordedRunEntity(
    @PrimaryKey val id: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val utcOffsetSeconds: Int,
    val meters: Double,
    val movingMillis: Long,
)

/**
 * One thing that happened while a run was recorded, [seq] counting from 0: a position from the
 * GPS, or the runner pausing or going on (`RunEventKind`, by name). The position is zero for a
 * pause. Everything about the run is worked out again from these.
 */
@Entity(primaryKeys = ["runId", "seq"])
data class FitnessRunEventEntity(
    val runId: String,
    val seq: Int,
    val kind: String,
    val epochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

@Dao
interface FitnessRunDao {
    @Query("SELECT * FROM FitnessRecordedRunEntity WHERE endEpochMillis IS NOT NULL ORDER BY startEpochMillis DESC")
    fun observeFinished(): Flow<List<FitnessRecordedRunEntity>>

    @Query("SELECT * FROM FitnessRecordedRunEntity WHERE endEpochMillis IS NULL ORDER BY startEpochMillis")
    suspend fun unfinished(): List<FitnessRecordedRunEntity>

    @Query("SELECT * FROM FitnessRecordedRunEntity WHERE id = :id")
    suspend fun run(id: String): FitnessRecordedRunEntity?

    @Upsert
    suspend fun upsertRun(run: FitnessRecordedRunEntity)

    @Query("SELECT * FROM FitnessRunEventEntity WHERE runId = :runId ORDER BY seq")
    suspend fun events(runId: String): List<FitnessRunEventEntity>

    @Query("SELECT MAX(seq) FROM FitnessRunEventEntity WHERE runId = :runId")
    suspend fun lastSeq(runId: String): Int?

    @Upsert
    suspend fun upsertEvents(events: List<FitnessRunEventEntity>)

    @Query("DELETE FROM FitnessRunEventEntity WHERE runId = :runId")
    suspend fun deleteEvents(runId: String)

    @Query("DELETE FROM FitnessRecordedRunEntity WHERE id = :id")
    suspend fun deleteRun(id: String)

    /** Removes a run and everything recorded in it, all or none. */
    @Transaction
    suspend fun removeRun(id: String) {
        deleteEvents(id)
        deleteRun(id)
    }
}

/** [FitnessRunDao] in memory, for tests. */
class InMemoryFitnessRunDao : FitnessRunDao {
    private val runs = MutableStateFlow<List<FitnessRecordedRunEntity>>(emptyList())
    private val events = MutableStateFlow<List<FitnessRunEventEntity>>(emptyList())

    override fun observeFinished(): Flow<List<FitnessRecordedRunEntity>> =
        runs.map { all -> all.filter { it.endEpochMillis != null }.sortedByDescending { it.startEpochMillis } }

    override suspend fun unfinished(): List<FitnessRecordedRunEntity> = runs.value.filter { it.endEpochMillis == null }.sortedBy { it.startEpochMillis }

    override suspend fun run(id: String): FitnessRecordedRunEntity? = runs.value.firstOrNull { it.id == id }

    override suspend fun upsertRun(run: FitnessRecordedRunEntity) {
        runs.update { current -> current.filter { it.id != run.id } + run }
    }

    override suspend fun events(runId: String): List<FitnessRunEventEntity> = events.value.filter { it.runId == runId }.sortedBy { it.seq }

    override suspend fun lastSeq(runId: String): Int? = events.value.filter { it.runId == runId }.maxOfOrNull { it.seq }

    override suspend fun upsertEvents(events: List<FitnessRunEventEntity>) {
        val keys = events.map { it.runId to it.seq }.toSet()
        this.events.update { current -> current.filter { (it.runId to it.seq) !in keys } + events }
    }

    override suspend fun deleteEvents(runId: String) {
        events.update { current -> current.filter { it.runId != runId } }
    }

    override suspend fun deleteRun(id: String) {
        runs.update { current -> current.filter { it.id != id } }
    }

    override suspend fun removeRun(id: String) {
        deleteEvents(id)
        deleteRun(id)
    }
}
