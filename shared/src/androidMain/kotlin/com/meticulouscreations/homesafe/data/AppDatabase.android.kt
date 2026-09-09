package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers

private fun buildAppDatabase(context: PlatformContext): AppDatabase {
    val appContext = context.context.applicationContext
    val dbFile = appContext.getDatabasePath("homesafe.db")
    return Room.databaseBuilder<AppDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}

private lateinit var appDatabaseInstance: AppDatabase

private fun appDatabase(context: PlatformContext): AppDatabase {
    if (!::appDatabaseInstance.isInitialized) {
        appDatabaseInstance = buildAppDatabase(context)
    }
    return appDatabaseInstance
}

actual fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao =
    appDatabase(context).connectionHistoryDao()

actual fun createCameraDao(context: PlatformContext): CameraDao =
    appDatabase(context).cameraDao()

actual fun createSettingsDao(context: PlatformContext): SettingsDao =
    appDatabase(context).settingsDao()

actual fun createPropertyLayoutDao(context: PlatformContext): PropertyLayoutDao =
    appDatabase(context).propertyLayoutDao()
