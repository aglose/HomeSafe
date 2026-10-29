package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.PlatformContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/** [AppGraph] on the desktop: the same graph as iOS's, without the tracer. */
@DependencyGraph(AppScope::class)
interface DesktopAppGraph : AppGraph {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platformContext: PlatformContext): DesktopAppGraph
    }
}

actual fun createAppGraph(platformContext: PlatformContext): AppGraph =
    createGraphFactory<DesktopAppGraph.Factory>().create(platformContext)
