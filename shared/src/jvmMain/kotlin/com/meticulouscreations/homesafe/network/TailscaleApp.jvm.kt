package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext

/** No way to open another app by name here, so the button isn't offered. */
actual fun createTailscaleApp(context: PlatformContext): TailscaleApp = NoTailscaleApp
