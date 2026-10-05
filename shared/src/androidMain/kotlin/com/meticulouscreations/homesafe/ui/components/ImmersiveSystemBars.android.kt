package com.meticulouscreations.homesafe.ui.components

import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Through the window's own [WindowInsetsController] (API 30; minSdk is 33), so no extra
 * dependency. Swiping in from an edge shows the bars transiently over the content, and they hide
 * again on their own, which is what a video editor wants.
 *
 * Counted per window, because two screens can ask at once: the clip editor opens over a camera
 * that is already full screen, and the camera leaves the composition a moment after the editor
 * has landed. Whichever goes first must not bring the bars back under the one still up.
 */
@Composable
actual fun ImmersiveSystemBars() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        ImmersiveHolds.acquire(window)
        onDispose { ImmersiveHolds.release(window) }
    }
}

/** Who has the system bars hidden, per window. Main thread only, like the effects that call it. */
private object ImmersiveHolds {
    private class Hold(val previousBehavior: Int?, var count: Int = 0)

    private val holds = WeakHashMap<Window, Hold>()

    fun acquire(window: Window) {
        val controller = window.insetsController
        val hold = holds.getOrPut(window) { Hold(previousBehavior = controller?.systemBarsBehavior) }
        if (hold.count++ == 0) {
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsets.Type.systemBars())
        }
    }

    fun release(window: Window) {
        val hold = holds[window] ?: return
        if (--hold.count > 0) return
        holds.remove(window)
        val controller = window.insetsController
        controller?.show(WindowInsets.Type.systemBars())
        hold.previousBehavior?.let { controller?.systemBarsBehavior = it }
    }
}
