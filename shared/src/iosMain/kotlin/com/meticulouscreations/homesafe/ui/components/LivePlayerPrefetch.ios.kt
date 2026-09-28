package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts the iOS pool's players ahead of their cards, like Android's. The WebRTC engine lives on
 * the Swift side, which makes its peers only when asked, so there are no offers to prepare here.
 */
actual fun createLivePlayerPrefetch(context: PlatformContext): LivePlayerPrefetch = IosLivePlayerPrefetch

/** Hops to the main thread, where the pool and its players live. */
private object IosLivePlayerPrefetch : LivePlayerPrefetch {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun prepare(count: Int) = Unit

    override fun prefetch(streams: List<LivePrefetch>) {
        main.launch { LivePlayerPool.prefetch(streams) }
    }

    override fun cancel() {
        main.launch { LivePlayerPool.cancelPrefetch() }
    }
}
