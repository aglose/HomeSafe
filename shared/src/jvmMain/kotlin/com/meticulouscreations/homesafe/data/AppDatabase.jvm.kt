package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers
import java.io.File

actual fun createConnectionHistoryDao(context: PlatformContext): ConnectionHistoryDao {
    val appDataDir = File(System.getProperty("user.home"), ".homesafe").apply { mkdirs() }
    val dbFile = File(appDataDir, "homesafe.db")
    val database = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    return database.connectionHistoryDao()
}
