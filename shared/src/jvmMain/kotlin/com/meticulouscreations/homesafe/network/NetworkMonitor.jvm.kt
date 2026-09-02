package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Desktop has no cheap, cross-OS network-change notification wired up here, so the route
 * chosen at sign-in simply sticks — matching the precedent of the other "unavailable on
 * desktop" platform fallbacks in this module.
 */
private object NoOpNetworkMonitor : NetworkMonitor {
    override val changes: Flow<Unit> = emptyFlow()
}

actual fun createNetworkMonitor(context: PlatformContext): NetworkMonitor = NoOpNetworkMonitor
