package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext

/**
 * Gets the live-video engine's one-off start-up costs out of the way before any camera asks
 * for a picture — called once, at app start, while the sign-in screen is up. On Android that
 * is libwebrtc's native initialisation and the shared EGL context (a few hundred milliseconds
 * on the main thread if left to the first join), done here on a background thread. Platforms
 * with nothing to warm do nothing; the desktop's FFmpeg warm-up is started by its own `main`.
 * Idempotent and never throws: a warm-up that fails leaves the first join to pay as before.
 */
expect fun warmUpLivePlayback(context: PlatformContext)
