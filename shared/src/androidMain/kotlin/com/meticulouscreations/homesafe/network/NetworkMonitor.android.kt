package com.meticulouscreations.homesafe.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Backed by [ConnectivityManager]'s default-network callback (needs `ACCESS_NETWORK_STATE`).
 * A new default network — a different Wi-Fi network, or cellular after Wi-Fi drops — arrives
 * as [ConnectivityManager.NetworkCallback.onAvailable]; losing it entirely as `onLost`.
 * Capability changes are ignored: they fire constantly (signal strength, metered flags) and
 * never move the phone between the server's LAN and the outside world on their own.
 */
private class AndroidNetworkMonitor(private val connectivityManager: ConnectivityManager) : NetworkMonitor {
    override val changes: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
}

actual fun createNetworkMonitor(context: PlatformContext): NetworkMonitor {
    val connectivityManager =
        context.context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return AndroidNetworkMonitor(connectivityManager)
}
