package com.meticulouscreations.homesafe.ui.components

import android.util.Log
import com.meticulouscreations.homesafe.PlatformContext

/**
 * libwebrtc's native initialisation, the shared EGL context and the signaling HTTP client, all
 * on a background thread while the sign-in screen is up. Measured against the first join it
 * saves that join a few hundred milliseconds of main-thread work.
 */
actual fun warmUpLivePlayback(context: PlatformContext) {
    val appContext = context.context.applicationContext
    Thread({
        runCatching { LivePlayerPool.warmUp(appContext) }
            .onFailure { Log.w("HomeSafeLive", "live playback warm-up failed; the first join will initialise instead", it) }
    }, "homesafe-live-warmup").apply {
        isDaemon = true
        start()
    }
}
