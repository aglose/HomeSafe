package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/** Starts the desktop pool's engines ahead of their cards. There is no WebRTC here, so no offers to prepare. */
actual fun createLivePlayerPrefetch(context: PlatformContext): LivePlayerPrefetch = DesktopLivePlayerPrefetch

/** Hops to the UI thread, where the pool and its engines live. */
private object DesktopLivePlayerPrefetch : LivePlayerPrefetch {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    override fun prepare(count: Int) = Unit

    override fun prefetch(streams: List<LivePrefetch>) {
        ui.launch { DesktopVideoPool.prefetch(streams) }
    }

    override fun cancel() {
        ui.launch { DesktopVideoPool.cancelPrefetch() }
    }
}
