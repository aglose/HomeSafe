package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

private fun buildAppDatabase(): AppDatabase {
    val dbFilePath = documentDirectory() + "/homesafe.db"
    return Room.databaseBuilder<AppDatabase>(name = dbFilePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
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

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}
