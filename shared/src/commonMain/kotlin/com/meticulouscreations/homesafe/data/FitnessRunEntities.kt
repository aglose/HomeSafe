package com.meticulouscreations.homesafe.data

import androidx.room3.AutoMigration
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
 *
 * Beside them, what this install last agreed with the server about the training log and the runs
 * ([FitnessSyncRowEntity], [FitnessSyncStateEntity]; version 2): kept apart from the log itself
 * for the same reason, and so that [AppDatabase] being thrown away can be told from the log having
 * been deleted row by row.
 */
@Database(
    entities = [FitnessRecordedRunEntity::class, FitnessRunEventEntity::class, FitnessSyncRowEntity::class, FitnessSyncStateEntity::class],
    version = 2,
    autoMigrations = [
        // 1 -> 2: what was last agreed with the server, in new tables.
        AutoMigration(from = 1, to = 2),
    ],
)
@ConstructedBy(FitnessRunsDatabaseConstructor::class)
abstract class FitnessRunsDatabase : RoomDatabase() {
    abstract fun fitnessRunDao(): FitnessRunDao

    abstract fun fitnessSyncDao(): FitnessSyncDao
}

// The Room compiler generates the `actual` implementations for each target.
@Suppress("KotlinNoActualForExpect")
expect object FitnessRunsDatabaseConstructor : RoomDatabaseConstructor<FitnessRunsDatabase> {
    override fun initialize(): FitnessRunsDatabase
}

/** The [FitnessRunDao] of the platform's one [FitnessRunsDatabase]. */
expect fun createFitnessRunDao(context: PlatformContext): FitnessRunDao

/** The [FitnessSyncDao] of the platform's one [FitnessRunsDatabase]. */
expect fun createFitnessSyncDao(context: PlatformContext): FitnessSyncDao

/**
 * A row of the log or a recorded run as the server last had it, by `kind/id`. A row here that the
 * log hasn't got was deleted here since; one the log has differently was changed; one the log has
 * and this hasn't is new. So what to send is worked out by comparing, and no way of changing the
 * log can forget to say so, or lose it by being killed at the wrong moment.
 */
@Entity
data class FitnessSyncRowEntity(
    @PrimaryKey val key: String,
    val data: String,
)

/**
 * A singleton row (always [id] = 0): whose log this install's is and how far it has heard. [server]
 * is the address that identifies the server, whichever route is in use, and [owner] whose log it is
 * there. A missing row means this install's log has never been brought together with the server's.
 */
@Entity
data class FitnessSyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val server: String,
    val owner: String,
    val cursor: Long,
    /** Set just before this device's log is emptied for someone else's, so that being stopped part-way isn't taken for deletions. */
    val handingOver: Boolean = false,
)

@Dao
interface FitnessSyncDao {
    @Query("SELECT * FROM FitnessSyncRowEntity")
    suspend fun rows(): List<FitnessSyncRowEntity>

    @Query("SELECT * FROM FitnessSyncStateEntity WHERE id = 0")
    suspend fun state(): FitnessSyncStateEntity?

    @Upsert
    suspend fun upsertRows(rows: List<FitnessSyncRowEntity>)

    @Query("DELETE FROM FitnessSyncRowEntity WHERE `key` = :key")
    suspend fun deleteRow(key: String)

    @Upsert
    suspend fun upsertState(state: FitnessSyncStateEntity)

    @Query("DELETE FROM FitnessSyncRowEntity")
    suspend fun deleteRows()

    @Query("DELETE FROM FitnessSyncStateEntity")
    suspend fun deleteState()

    /** What the server now has, and how far this install has heard: all of it or none of it. */
    @Transaction
    suspend fun record(rows: List<FitnessSyncRowEntity>, gone: List<String>, state: FitnessSyncStateEntity) {
        if (rows.isNotEmpty()) upsertRows(rows)
        gone.forEach { deleteRow(it) }
        upsertState(state)
    }

    /** What the server now has is [rows], in place of whatever was thought before. */
    @Transaction
    suspend fun startOver(rows: List<FitnessSyncRowEntity>, state: FitnessSyncStateEntity) {
        deleteRows()
        if (rows.isNotEmpty()) upsertRows(rows)
        upsertState(state)
    }

    /** Forgets the server altogether, so the next sync brings the two together as though for the first time. */
    @Transaction
    suspend fun forget() {
        deleteRows()
        deleteState()
    }
}

/** [FitnessSyncDao] in memory, for tests. */
class InMemoryFitnessSyncDao : FitnessSyncDao {
    private val rows = HashMap<String, FitnessSyncRowEntity>()
    private var state: FitnessSyncStateEntity? = null

    override suspend fun rows(): List<FitnessSyncRowEntity> = rows.values.toList()

    override suspend fun state(): FitnessSyncStateEntity? = state

    override suspend fun upsertRows(rows: List<FitnessSyncRowEntity>) {
        rows.forEach { this.rows[it.key] = it }
    }

    override suspend fun deleteRow(key: String) {
        rows.remove(key)
    }

    override suspend fun upsertState(state: FitnessSyncStateEntity) {
        this.state = state
    }

    override suspend fun deleteRows() = rows.clear()

    override suspend fun deleteState() {
        state = null
    }

    override suspend fun record(rows: List<FitnessSyncRowEntity>, gone: List<String>, state: FitnessSyncStateEntity) {
        upsertRows(rows)
        gone.forEach { deleteRow(it) }
        upsertState(state)
    }

    override suspend fun startOver(rows: List<FitnessSyncRowEntity>, state: FitnessSyncStateEntity) {
        deleteRows()
        upsertRows(rows)
        upsertState(state)
    }

    override suspend fun forget() {
        deleteRows()
        deleteState()
    }
}

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

    /** Saves a run recorded elsewhere, as it was finished there: it is never seen with only some of its positions. */
    @Transaction
    suspend fun writeRun(run: FitnessRecordedRunEntity, events: List<FitnessRunEventEntity>) {
        deleteEvents(run.id)
        if (events.isNotEmpty()) upsertEvents(events)
        upsertRun(run)
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

    override suspend fun writeRun(run: FitnessRecordedRunEntity, events: List<FitnessRunEventEntity>) {
        deleteEvents(run.id)
        upsertEvents(events)
        upsertRun(run)
    }
}
