package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.darwin.dispatch_get_main_queue

/**
 * Backed by `NWPathMonitor`. Its update handler fires once with the current path as soon as
 * monitoring starts and again on every path change (interface, gateway, VPN), which is exactly
 * the "re-check reachability now" cue [NetworkMonitor] promises.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosNetworkMonitor : NetworkMonitor {
    override val changes: Flow<Unit> = callbackFlow {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { _ -> trySend(Unit) }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
        awaitClose { nw_path_monitor_cancel(monitor) }
    }
}

actual fun createNetworkMonitor(context: PlatformContext): NetworkMonitor = IosNetworkMonitor()
