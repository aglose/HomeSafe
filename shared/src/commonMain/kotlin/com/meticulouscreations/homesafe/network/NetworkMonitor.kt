package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.flow.Flow

/**
 * Reports when the device's network path changes: Wi-Fi ↔ cellular, joining or leaving a
 * Wi-Fi network, a VPN coming up or down. Each emission is a cue to re-check which of the
 * server's addresses is reachable.
 *
 * The emission deliberately carries no detail. Reachability is *probed*, not inferred from
 * the network type or SSID — reading the SSID needs location permission on both mobile
 * platforms, and it still wouldn't say whether the router lets this client reach the server.
 */
interface NetworkMonitor {
    val changes: Flow<Unit>
}

/**
 * Builds the platform's [NetworkMonitor]. Android and iOS watch the OS network path; desktop
 * and web have no comparable API wired up and never emit, so they keep whichever route was
 * chosen at sign-in.
 */
expect fun createNetworkMonitor(context: PlatformContext): NetworkMonitor
