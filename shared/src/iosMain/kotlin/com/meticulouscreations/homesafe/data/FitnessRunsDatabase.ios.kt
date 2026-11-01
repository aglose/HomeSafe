package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
private val fitnessRunsDatabase: FitnessRunsDatabase by lazy {
    val documents = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(NSDocumentDirectory, NSUserDomainMask, appropriateForURL = null, create = false, error = null)?.path,
    )
    Room.databaseBuilder<FitnessRunsDatabase>(name = "$documents/fitness_runs.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
}

actual fun createFitnessRunDao(context: PlatformContext): FitnessRunDao = fitnessRunsDatabase.fitnessRunDao()

actual fun createFitnessSyncDao(context: PlatformContext): FitnessSyncDao = fitnessRunsDatabase.fitnessSyncDao()
