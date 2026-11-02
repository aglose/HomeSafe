package com.meticulouscreations.homesafe.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers

private var skiDatabase: SkiDatabase? = null

private fun skiDatabase(context: Context): SkiDatabase = skiDatabase ?: synchronized(SkiDatabase::class) {
    skiDatabase ?: Room.databaseBuilder<SkiDatabase>(
        context = context,
        name = context.getDatabasePath("ski.db").absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
        .also { skiDatabase = it }
}

actual fun createSkiDao(context: PlatformContext): SkiDao =
    skiDatabase(context.context.applicationContext).skiDao()
