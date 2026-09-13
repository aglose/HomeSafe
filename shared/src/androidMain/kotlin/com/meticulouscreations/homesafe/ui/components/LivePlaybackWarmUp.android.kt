package com.meticulouscreations.homesafe.ui.components

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meticulouscreations.homesafe.PlatformContext

/**
 * libwebrtc's native initialisation, the shared EGL context and the signaling HTTP client,
 * built at launch behind the sign-in screen rather than inside the first camera's join.
 *
 * Posted to the main thread rather than run on a worker: every EGL context and decoder the
 * player stack makes shares with the root context created here, and doing that on the same
 * thread the renderers and peers are made on keeps the set-up identical to the lazy
 * initialisation it replaces — only earlier. It lands one frame after the root composition,
 * so the sign-in form is on screen before the ~100–300 ms this takes.
 */
actual fun warmUpLivePlayback(context: PlatformContext) {
    val appContext = context.context.applicationContext
    Handler(Looper.getMainLooper()).post {
        runCatching { LivePlayerPool.warmUp(appContext) }
            .onFailure { Log.w("HomeSafeLive", "live playback warm-up failed; the first join will initialise instead", it) }
    }
}
