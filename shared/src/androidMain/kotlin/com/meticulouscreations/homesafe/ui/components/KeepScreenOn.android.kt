package com.meticulouscreations.homesafe.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * Through the compose view's own [View.setKeepScreenOn], which holds the screen on while that
 * view is attached to a visible window, with no permission and no wake lock to leak.
 */
@Composable
actual fun KeepScreenOn() {
    if (LocalInspectionMode.current) return
    val view = LocalView.current
    DisposableEffect(view) {
        KeepScreenOnHolds.acquire(view)
        onDispose { KeepScreenOnHolds.release(view) }
    }
}

/** Who is holding the screen on, per view. Main thread only, like the effects that call it. */
private object KeepScreenOnHolds {
    private val holds = WeakHashMap<View, Int>()

    fun acquire(view: View) {
        val count = holds[view] ?: 0
        holds[view] = count + 1
        if (count == 0) view.keepScreenOn = true
    }

    fun release(view: View) {
        val count = holds[view] ?: return
        if (count > 1) {
            holds[view] = count - 1
            return
        }
        holds.remove(view)
        view.keepScreenOn = false
    }
}
