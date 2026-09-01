package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.Greeting
import com.meticulouscreations.homesafe.Platform
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.data.ConnectionHistoryDao
import com.meticulouscreations.homesafe.data.createConnectionHistoryDao
import com.meticulouscreations.homesafe.getPlatform
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory

@DependencyGraph(AppScope::class)
interface AppGraph {
    val greeting: Greeting
    val connectionHistoryDao: ConnectionHistoryDao

    @Provides
    fun providePlatform(): Platform = getPlatform()

    @SingleIn(AppScope::class)
    @Provides
    fun provideConnectionHistoryDao(platformContext: PlatformContext): ConnectionHistoryDao =
        createConnectionHistoryDao(platformContext)

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platformContext: PlatformContext): AppGraph
    }
}

fun createAppGraph(platformContext: PlatformContext): AppGraph =
    createGraphFactory<AppGraph.Factory>().create(platformContext)
