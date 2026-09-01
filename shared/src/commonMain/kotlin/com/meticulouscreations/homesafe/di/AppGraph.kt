package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.Greeting
import com.meticulouscreations.homesafe.Platform
import com.meticulouscreations.homesafe.getPlatform
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraph

@DependencyGraph(AppScope::class)
interface AppGraph {
    val greeting: Greeting

    @Provides
    fun providePlatform(): Platform = getPlatform()
}

val appGraph: AppGraph by lazy { createGraph() }
