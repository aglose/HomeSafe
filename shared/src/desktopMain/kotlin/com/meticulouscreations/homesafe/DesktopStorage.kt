package com.meticulouscreations.homesafe

import java.io.File
import java.util.prefs.Preferences

/**
 * Where the desktop app keeps what it keeps: its databases and files in the folder each OS has for
 * an app's own data (`~/Library/Application Support/HomeSafe` on a Mac), its few preferences in
 * the JVM's [Preferences] (a plist in `~/Library/Preferences` on a Mac). The Android app's
 * equivalents are its data directory and SharedPreferences; the iOS app's its Documents folder
 * and the standard user defaults.
 */
object DesktopStorage {
    /**
     * The folder the databases live in, made the first time it is asked for. The tests point it
     * (and [preferences]) somewhere of their own with `-Dhomesafe.dataDir`, so a run never touches
     * the app's real data on the machine it runs on.
     */
    val dataDirectory: File by lazy {
        System.getProperty("homesafe.dataDir")?.let { return@lazy File(it).apply { mkdirs() } }
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val dir = when {
            "mac" in os -> File(home, "Library/Application Support/HomeSafe")
            "win" in os -> File(System.getenv("APPDATA") ?: home, "HomeSafe")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "homesafe")
        }
        dir.apply { mkdirs() }
    }

    /** A node of the app's preferences: one per part of the app, as Android has a SharedPreferences file each. */
    fun preferences(name: String): Preferences = Preferences.userRoot().node("${System.getProperty("homesafe.preferencesNode") ?: "com/meticulouscreations/homesafe"}/$name")
}

/** Whether this is a Mac, for the few things only macOS has (its Keychain, `caffeinate`, `open -a`). */
val isMacOs: Boolean = System.getProperty("os.name").lowercase().contains("mac")
