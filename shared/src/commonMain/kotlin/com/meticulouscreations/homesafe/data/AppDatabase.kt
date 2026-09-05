package com.meticulouscreations.homesafe.data

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.DeleteColumn
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.AutoMigrationSpec
import com.meticulouscreations.homesafe.PlatformContext

/**
 * 4 -> 5: the Settings tab stopped storing toggles that never did anything (they were placeholders
 * for server features) and gained per-category notification switches. The dropped columns must be
 * named here; the added ones carry defaults on the entity.
 */
@DeleteColumn(tableName = "SettingsEntity", columnName = "autoPurgeOldMedia")
@DeleteColumn(tableName = "SettingsEntity", columnName = "globalMotionDetection")
@DeleteColumn(tableName = "SettingsEntity", columnName = "coralEdgeInference")
@DeleteColumn(tableName = "SettingsEntity", columnName = "faceRecognition")
class SettingsPlaceholdersDropped : AutoMigrationSpec

/**
 * 5 -> 6: the three app-wide category switches became per-zone rules in their own table
 * ([AlertZoneRuleEntity]). Existing choices aren't carried over — the table starts empty, so
 * every place alerts with the defaults until the user picks otherwise.
 */
@DeleteColumn(tableName = "SettingsEntity", columnName = "notifyPeople")
@DeleteColumn(tableName = "SettingsEntity", columnName = "notifyVehicles")
@DeleteColumn(tableName = "SettingsEntity", columnName = "notifyAnimals")
class CategorySwitchesMovedToZones : AutoMigrationSpec

@Database(
    entities = [ConnectionHistoryEntity::class, CameraEntity::class, SettingsEntity::class, AlertZoneRuleEntity::class],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = SettingsPlaceholdersDropped::class),
        AutoMigration(from = 5, to = 6, spec = CategorySwitchesMovedToZones::class),
    ],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionHistoryDao(): ConnectionHistoryDao
    abstract fun cameraDao(): CameraDao
    abstract fun settingsDao(): SettingsDao
}

// The Room compiler generates the `actual` implementations for each target.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/**
 * Builds the platform's DAOs. On Android, JVM (desktop), and iOS these are backed by a single
 * shared real Room/SQLite database instance (via [androidx.sqlite:sqlite-bundled]) — each
 * `createXDao` call returns a DAO from that same instance, never opening a second connection.
 * That artifact doesn't yet publish a JS/Wasm driver, so the web target falls back to
 * non-persistent in-memory DAOs ([InMemoryConnectionHistoryDao], [InMemoryCameraDao],
 * [InMemorySettingsDao]).
 */
expect fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao
expect fun createCameraDao(context: PlatformContext): CameraDao
expect fun createSettingsDao(context: PlatformContext): SettingsDao
