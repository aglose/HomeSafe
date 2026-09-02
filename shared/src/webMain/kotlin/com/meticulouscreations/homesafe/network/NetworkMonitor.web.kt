package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Browsers don't get a monitor here; the route chosen at sign-in sticks (see the desktop fallback). */
private object NoOpNetworkMonitor : NetworkMonitor {
    override val changes: Flow<Unit> = emptyFlow()
}

actual fun createNetworkMonitor(context: PlatformContext): NetworkMonitor = NoOpNetworkMonitor
