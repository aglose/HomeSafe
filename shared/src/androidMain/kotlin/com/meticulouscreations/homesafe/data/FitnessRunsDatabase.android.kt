package com.meticulouscreations.homesafe.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers

private var fitnessRunsDatabase: FitnessRunsDatabase? = null

private fun fitnessRunsDatabase(context: Context): FitnessRunsDatabase = fitnessRunsDatabase ?: synchronized(FitnessRunsDatabase::class) {
    fitnessRunsDatabase ?: Room.databaseBuilder<FitnessRunsDatabase>(
        context = context,
        name = context.getDatabasePath("fitness_runs.db").absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
        .also { fitnessRunsDatabase = it }
}

actual fun createFitnessRunDao(context: PlatformContext): FitnessRunDao =
    fitnessRunsDatabase(context.context.applicationContext).fitnessRunDao()
