package com.meticulouscreations.homesafe.data

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Upsert
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.flow.Flow

/**
 * What the Weather app's Ski tab keeps, in a database of its own (`ski.db`) beside [AppDatabase],
 * which has as many tables as Room can check in one generated method. One table of JSON by key:
 * the household's plan, and the last answer from each service, so the tab opens on what it had.
 * A new field in any of them is a default in its class, not a migration.
 */
@Database(entities = [SkiKeptEntity::class], version = 1)
@ConstructedBy(SkiDatabaseConstructor::class)
abstract class SkiDatabase : RoomDatabase() {
    abstract fun skiDao(): SkiDao
}

// The Room compiler generates the `actual` implementations for each target.
@Suppress("KotlinNoActualForExpect")
expect object SkiDatabaseConstructor : RoomDatabaseConstructor<SkiDatabase> {
    override fun initialize(): SkiDatabase
}

/** The [SkiDao] of the platform's one [SkiDatabase]. */
expect fun createSkiDao(context: PlatformContext): SkiDao

/** One thing kept for the Ski tab: [json] under [key], as it stood at [savedAtEpochSeconds]. */
@Entity
data class SkiKeptEntity(
    @PrimaryKey val key: String,
    val json: String,
    val savedAtEpochSeconds: Long,
)

@Dao
interface SkiDao {
    @Query("SELECT * FROM SkiKeptEntity WHERE `key` = :key")
    suspend fun kept(key: String): SkiKeptEntity?

    @Query("SELECT * FROM SkiKeptEntity WHERE `key` = :key")
    fun observe(key: String): Flow<SkiKeptEntity?>

    @Upsert
    suspend fun keep(entity: SkiKeptEntity)
}
