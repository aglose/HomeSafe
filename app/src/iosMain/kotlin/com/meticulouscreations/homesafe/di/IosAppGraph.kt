package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.PlatformContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/** [AppGraph] on iOS: the same graph as Android's, without the tracer (no Tracing 2.0 there). */
@DependencyGraph(AppScope::class)
interface IosAppGraph : AppGraph {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platformContext: PlatformContext): IosAppGraph
    }
}

actual fun createAppGraph(platformContext: PlatformContext): AppGraph =
    createGraphFactory<IosAppGraph.Factory>().create(platformContext)
