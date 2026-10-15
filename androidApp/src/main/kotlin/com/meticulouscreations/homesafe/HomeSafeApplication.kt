package com.meticulouscreations.homesafe

import android.app.Application
import com.meticulouscreations.homesafe.data.BackgroundGraph
import com.meticulouscreations.homesafe.di.createAppGraph

/**
 * Tells :shared how to build the app graph for code the OS wakes with no screen (receivers,
 * workers, a push). The graph is in :app, which :shared can't see; see BackgroundGraph. Here
 * because the application is created before any of that code can run.
 */
class HomeSafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BackgroundGraph.install { context -> createAppGraph(PlatformContext(context)) }
    }
}
