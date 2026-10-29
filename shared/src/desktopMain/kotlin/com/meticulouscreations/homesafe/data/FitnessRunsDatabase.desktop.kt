package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import java.io.File

private val fitnessRunsDatabase: FitnessRunsDatabase by lazy {
    Room.databaseBuilder<FitnessRunsDatabase>(name = File(DesktopStorage.dataDirectory, "fitness_runs.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

actual fun createFitnessRunDao(context: PlatformContext): FitnessRunDao = fitnessRunsDatabase.fitnessRunDao()
