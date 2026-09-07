package com.meticulouscreations.homesafe.data

import android.content.Context
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.di.createAppGraph

/**
 * The app graph for code the OS wakes with no Activity in sight — the geofence and boot
 * receivers, the messaging service's token rotation. Built once per process on the application
 * context, so a burst of broadcasts doesn't build a graph each. It has no UI-bound pieces (see
 * the headless fallbacks in AlertNotifier.android.kt and BiometricCredentialStore.android.kt)
 * and nobody starts its pollers; it exists to reach the relay.
 */
object BackgroundGraph {
    @Volatile private var instance: AppGraph? = null

    fun get(context: Context): AppGraph =
        instance ?: synchronized(this) {
            instance ?: createAppGraph(PlatformContext(context.applicationContext)).also { instance = it }
        }
}
