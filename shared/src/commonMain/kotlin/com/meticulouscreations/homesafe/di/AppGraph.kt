package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.Greeting
import com.meticulouscreations.homesafe.Platform
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.data.BiometricCredentialStore
import com.meticulouscreations.homesafe.data.ConnectionHistoryDao
import com.meticulouscreations.homesafe.data.createBiometricCredentialStore
import com.meticulouscreations.homesafe.data.createConnectionHistoryDao
import com.meticulouscreations.homesafe.getPlatform
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateSessionRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

@DependencyGraph(AppScope::class)
interface AppGraph {
    val greeting: Greeting
    val connectionHistoryDao: ConnectionHistoryDao
    val frigateApiClient: FrigateApiClient
    val frigateSessionRepository: FrigateSessionRepository
    val biometricCredentialStore: BiometricCredentialStore

    @Provides
    fun providePlatform(): Platform = getPlatform()

    @SingleIn(AppScope::class)
    @Provides
    fun provideConnectionHistoryDao(platformContext: PlatformContext): ConnectionHistoryDao =
        createConnectionHistoryDao(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideBiometricCredentialStore(platformContext: PlatformContext): BiometricCredentialStore =
        createBiometricCredentialStore(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideHttpClient(): HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpCookies)
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
    }

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platformContext: PlatformContext): AppGraph
    }
}

fun createAppGraph(platformContext: PlatformContext): AppGraph =
    createGraphFactory<AppGraph.Factory>().create(platformContext)
