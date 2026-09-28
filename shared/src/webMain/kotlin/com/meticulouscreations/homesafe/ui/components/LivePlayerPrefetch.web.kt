package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext

/** No live player on the web yet, so nothing to start early. */
actual fun createLivePlayerPrefetch(context: PlatformContext): LivePlayerPrefetch = NoLivePlayerPrefetch
