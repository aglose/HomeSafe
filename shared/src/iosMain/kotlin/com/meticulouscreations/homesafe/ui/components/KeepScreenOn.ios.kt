package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/** Through the app's idle timer, which is what dims and locks the screen when nobody touches it. */
@Composable
actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        KeepScreenOnHolds.acquire()
        onDispose { KeepScreenOnHolds.release() }
    }
}

/** How many players are holding the screen on. Main thread only, like the effects that call it. */
private object KeepScreenOnHolds {
    private var count = 0

    fun acquire() {
        if (count++ == 0) UIApplication.sharedApplication.idleTimerDisabled = true
    }

    fun release() {
        if (count == 0) return
        if (--count == 0) UIApplication.sharedApplication.idleTimerDisabled = false
    }
}
