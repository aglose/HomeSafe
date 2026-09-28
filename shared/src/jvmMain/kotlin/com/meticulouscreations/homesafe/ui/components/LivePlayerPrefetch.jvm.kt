package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext

/** Not wired yet: the desktop pool starts its players when a card binds. */
actual fun createLivePlayerPrefetch(context: PlatformContext): LivePlayerPrefetch = NoLivePlayerPrefetch
