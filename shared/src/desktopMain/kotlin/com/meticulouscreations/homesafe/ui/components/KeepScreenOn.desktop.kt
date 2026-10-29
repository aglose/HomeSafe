package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.meticulouscreations.homesafe.isMacOs

/**
 * Through `caffeinate`, the Mac's own way to keep the display awake while something is being
 * watched: held while any player wants it, and tied to this process (`-w`), so a crash can't
 * leave the Mac unable to sleep. Elsewhere there is nothing to hold.
 */
@Composable
actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        KeepScreenOnHolds.acquire()
        onDispose { KeepScreenOnHolds.release() }
    }
}

/** How many players are holding the display on. Main thread only, like the effects that call it. */
internal object KeepScreenOnHolds {
    private var count = 0
    private var caffeinate: Process? = null

    fun acquire() {
        if (count++ == 0 && isMacOs) {
            caffeinate = runCatching { ProcessBuilder("caffeinate", "-d", "-w", ProcessHandle.current().pid().toString()).start() }.getOrNull()
        }
    }

    fun release() {
        if (count == 0) return
        if (--count == 0) {
            caffeinate?.destroy()
            caffeinate = null
        }
    }
}
