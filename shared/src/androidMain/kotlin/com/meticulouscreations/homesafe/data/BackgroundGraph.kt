package com.meticulouscreations.homesafe.data

import android.content.Context
import com.meticulouscreations.homesafe.di.BackgroundServices

/**
 * The app graph for code the OS wakes with no Activity in sight — the geofence and boot
 * receivers, the messaging service's token rotation, the weather worker. Built once per process
 * on the application context, so a burst of broadcasts doesn't build a graph each. It has no
 * UI-bound pieces (see the headless fallbacks in AlertNotifier.android.kt and
 * BiometricCredentialStore.android.kt) and nobody starts its pollers; it exists to reach the relay.
 *
 * The graph is built in :app, which this module can't see, so the application hands over how to
 * build it at start-up ([install], from HomeSafeApplication), before any receiver can run.
 */
object BackgroundGraph {
    @Volatile private var factory: ((Context) -> BackgroundServices)? = null

    @Volatile private var instance: BackgroundServices? = null

    fun install(factory: (Context) -> BackgroundServices) {
        this.factory = factory
    }

    fun get(context: Context): BackgroundServices =
        instance ?: synchronized(this) {
            instance ?: checkNotNull(factory) { "BackgroundGraph.install wasn't called: is HomeSafeApplication the application class?" }
                .invoke(context.applicationContext)
                .also { instance = it }
        }
}
