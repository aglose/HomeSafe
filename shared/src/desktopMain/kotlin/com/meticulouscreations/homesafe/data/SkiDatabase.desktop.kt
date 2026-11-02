package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import java.io.File

private val skiDatabase: SkiDatabase by lazy {
    Room.databaseBuilder<SkiDatabase>(name = File(DesktopStorage.dataDirectory, "ski.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

actual fun createSkiDao(context: PlatformContext): SkiDao = skiDatabase.skiDao()
