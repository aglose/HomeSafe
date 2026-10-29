package com.meticulouscreations.homesafe.di

import androidx.tracing.Tracer
import com.meticulouscreations.homesafe.PlatformContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/**
 * [AppGraph] on Android. Its factory takes the Tracing 2.0 [Tracer] because Metro's runtime
 * tracing (`-Phomesafe.tracing=true`, see app/build.gradle.kts) writes every binding the graph
 * creates through it, starting with the graph's own construction; without that flag the tracer
 * is unused by generated code.
 */
@DependencyGraph(AppScope::class)
interface AndroidAppGraph : AppGraph {
    /** The tracer the graph was made with, for code that wants its own sections in the same trace. */
    val tracer: Tracer

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platformContext: PlatformContext, @Provides tracer: Tracer): AndroidAppGraph
    }
}

/** With the process's global tracer, which HomeSafeApplication sets to its driver's before any graph is made. */
actual fun createAppGraph(platformContext: PlatformContext): AppGraph =
    createGraphFactory<AndroidAppGraph.Factory>().create(platformContext, Tracer.global)
