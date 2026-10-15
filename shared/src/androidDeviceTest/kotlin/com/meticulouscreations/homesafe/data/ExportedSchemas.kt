package com.meticulouscreations.homesafe.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The schema Room exported for [version] of [AppDatabase], as the migration tests rebuild an old
 * database from it. shared/schemas is packed into the test APK as Java resources (see
 * shared/build.gradle.kts), so it is read from there rather than from the source tree.
 */
internal fun exportedSchema(version: Int): String {
    val path = "com.meticulouscreations.homesafe.data.AppDatabase/$version.json"
    val stream = AppDatabase::class.java.classLoader?.getResourceAsStream(path)
        ?: error("$path is not in the test APK: is shared/schemas still a resource directory of androidDeviceTest?")
    return stream.bufferedReader().use { it.readText() }
}

/** The app under test's context, which Room's Android builder needs. */
internal fun testContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext
