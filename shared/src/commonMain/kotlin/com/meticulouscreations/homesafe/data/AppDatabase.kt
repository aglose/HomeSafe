package com.meticulouscreations.homesafe.data

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.meticulouscreations.homesafe.PlatformContext

@Database(entities = [ConnectionHistoryEntity::class], version = 1)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionHistoryDao(): ConnectionHistoryDao
}

// The Room compiler generates the `actual` implementations for each target.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/**
 * Builds the platform's [ConnectionHistoryDao]. On Android, JVM (desktop), and iOS this is
 * backed by a real Room/SQLite database (via [androidx.sqlite:sqlite-bundled]). That artifact
 * doesn't yet publish a JS/Wasm driver, so the web target falls back to [InMemoryConnectionHistoryDao].
 */
expect fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao
