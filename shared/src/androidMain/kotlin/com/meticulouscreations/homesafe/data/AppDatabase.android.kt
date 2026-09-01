package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers

actual fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao {
    val appContext = context.context.applicationContext
    val dbFile = appContext.getDatabasePath("homesafe.db")
    val database = Room.databaseBuilder<AppDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    return database.connectionHistoryDao()
}
