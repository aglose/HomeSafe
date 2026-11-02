package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.runProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The Mac's own WebKit, in a small program of its own the app carries in its resources
 * (`desktopApp/macos/SkiBrowser.swift`), one run a page. It can't start, or a page takes it down,
 * and all that happens is a null here: the resort's other prices are shown instead. Cancelling the
 * call ends the program, and the engine's processes go with it.
 */
internal class MacSkiBrowser(private val helper: File) : SkiBrowser {
    override val isSupported = true

    private val oneAtATime = Mutex()

    override suspend fun read(url: String, script: String, timeoutMillis: Long): String? = oneAtATime.withLock {
        runInterruptible(Dispatchers.IO) {
            // It keeps its own time; this is for one that has stopped answering altogether.
            val allowed = timeoutMillis / MILLIS_PER_SECOND + HELPER_GRACE_SECONDS
            val outcome = runProcess(listOf(helper.path, url, timeoutMillis.toString()), script.encodeToByteArray(), allowed)
            outcome?.takeIf { it.exitCode == 0 }?.output?.decodeToString()
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val HELPER_GRACE_SECONDS = 5L
    }
}

/** Anywhere the program isn't (another OS, or a build without it): a resort that sells only through a browser shows the link to its shop. */
private object NoSkiBrowser : SkiBrowser {
    override val isSupported = false

    override suspend fun read(url: String, script: String, timeoutMillis: Long): String? = null
}

/** The program in [resourcesDir] if it can be run. */
internal fun skiBrowserIn(resourcesDir: String?): SkiBrowser =
    resourcesDir?.let { File(it, "HomeSafeBrowser") }?.takeIf { it.canExecute() }?.let(::MacSkiBrowser) ?: NoSkiBrowser

actual fun createSkiBrowser(platformContext: PlatformContext): SkiBrowser = skiBrowserIn(System.getProperty("compose.application.resources.dir"))
