package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The JVM has no network-path callback like Android's ConnectivityManager or iOS's NWPathMonitor,
 * so this looks at the interfaces and their addresses every few seconds and emits when they
 * change: a laptop leaving the house's Wi-Fi, joining another, or Tailscale coming up or down
 * each moves an address. The first look is the starting point, not a change.
 */
private class DesktopNetworkMonitor : NetworkMonitor {
    override val changes: Flow<Unit> = flow {
        while (true) {
            emit(upIpv4Addresses())
            delay(POLL_INTERVAL_MS)
        }
    }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .drop(1)
        .map { }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
    }
}

actual fun createNetworkMonitor(context: PlatformContext): NetworkMonitor = DesktopNetworkMonitor()
