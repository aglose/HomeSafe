package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext

/** Not wired yet: the iOS pool could take the same head start, but for now its cards start their own players. */
actual fun createLivePlayerPrefetch(context: PlatformContext): LivePlayerPrefetch = NoLivePlayerPrefetch
