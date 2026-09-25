package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * `~/.homesafe`, unless the `homesafe.dataDir` system property names somewhere else — which the
 * JVM test task does, so the integration tests never touch a developer's real sign-in history.
 */
private fun appDataDir(): File =
    System.getProperty("homesafe.dataDir")?.let(::File) ?: File(System.getProperty("user.home"), ".homesafe")

private fun buildAppDatabase(): AppDatabase {
    val appDataDir = appDataDir().apply { mkdirs() }
    val dbFile = File(appDataDir, "homesafe.db")
    return Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}

private val appDatabase: AppDatabase by lazy { buildAppDatabase() }

actual fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao =
    appDatabase.connectionHistoryDao()

actual fun createCameraDao(context: PlatformContext): CameraDao =
    appDatabase.cameraDao()

actual fun createSettingsDao(context: PlatformContext): SettingsDao =
    appDatabase.settingsDao()

actual fun createMomentsDao(context: PlatformContext): MomentsDao =
    appDatabase.momentsDao()
