package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import java.io.File

private val appDatabase: AppDatabase by lazy {
    Room.databaseBuilder<AppDatabase>(name = File(DesktopStorage.dataDirectory, "homesafe.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}

actual fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao = appDatabase.connectionHistoryDao()

actual fun createCameraDao(context: PlatformContext): CameraDao = appDatabase.cameraDao()

actual fun createCameraListDao(context: PlatformContext): CameraListDao = appDatabase.cameraListDao()

actual fun createSettingsDao(context: PlatformContext): SettingsDao = appDatabase.settingsDao()

actual fun createMomentsDao(context: PlatformContext): MomentsDao = appDatabase.momentsDao()

actual fun createWeatherDao(context: PlatformContext): WeatherDao = appDatabase.weatherDao()

actual fun createFitnessDao(context: PlatformContext): FitnessDao = appDatabase.fitnessDao()

actual fun createFinanceSavedDao(context: PlatformContext): FinanceSavedDao = appDatabase.financeSavedDao()

actual fun createAtlasDao(context: PlatformContext): AtlasDao = appDatabase.atlasDao()
