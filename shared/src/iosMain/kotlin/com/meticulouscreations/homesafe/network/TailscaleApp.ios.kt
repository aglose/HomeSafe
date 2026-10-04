package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext

/** iOS opens another app only by a URL scheme it publishes, and Tailscale documents none for this, so the button isn't offered. */
actual fun createTailscaleApp(context: PlatformContext): TailscaleApp = NoTailscaleApp
