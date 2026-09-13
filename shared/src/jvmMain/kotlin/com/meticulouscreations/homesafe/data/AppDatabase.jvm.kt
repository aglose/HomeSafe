package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers
import java.io.File

private fun buildAppDatabase(): AppDatabase {
    val appDataDir = File(System.getProperty("user.home"), ".homesafe").apply { mkdirs() }
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

actual fun createPropertyLayoutDao(context: PlatformContext): PropertyLayoutDao =
    appDatabase.propertyLayoutDao()
