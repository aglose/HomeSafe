package com.meticulouscreations.homesafe.ui.components

import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Through the window's own [WindowInsetsController] (API 30; minSdk is 33), so no extra
 * dependency. Swiping in from an edge shows the bars transiently over the content, and they hide
 * again on their own, which is what a video editor wants.
 */
@Composable
actual fun ImmersiveSystemBars() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val controller = window.insetsController
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsets.Type.systemBars())
        onDispose {
            controller?.show(WindowInsets.Type.systemBars())
            if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
        }
    }
}
