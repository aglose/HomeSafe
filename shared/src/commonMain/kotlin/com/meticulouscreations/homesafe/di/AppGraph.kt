package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.Greeting
import com.meticulouscreations.homesafe.Platform
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.data.BiometricCredentialStore
import com.meticulouscreations.homesafe.data.CameraDao
import com.meticulouscreations.homesafe.data.CameraRepositoryImpl
import com.meticulouscreations.homesafe.data.ConnectionHistoryDao
import com.meticulouscreations.homesafe.data.ConnectionRepositoryImpl
import com.meticulouscreations.homesafe.data.MomentsRepositoryImpl
import com.meticulouscreations.homesafe.data.SettingsDao
import com.meticulouscreations.homesafe.data.SettingsRepositoryImpl
import com.meticulouscreations.homesafe.data.createBiometricCredentialStore
import com.meticulouscreations.homesafe.data.createCameraDao
import com.meticulouscreations.homesafe.data.createConnectionHistoryDao
import com.meticulouscreations.homesafe.data.createSettingsDao
import com.meticulouscreations.homesafe.domain.repository.CameraRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.domain.usecase.ConnectToServerUseCase
import com.meticulouscreations.homesafe.domain.usecase.ForgetBiometricCredentialsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMomentsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMostRecentConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveBiometricCredentialsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SignInWithBiometricsUseCase
import com.meticulouscreations.homesafe.domain.usecase.UpdateSettingsUseCase
import com.meticulouscreations.homesafe.getPlatform
import com.meticulouscreations.homesafe.network.FrigateApiClient
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
    val biometricCredentialStore: BiometricCredentialStore

    val connectionRepository: ConnectionRepository
    val cameraRepository: CameraRepository
    val momentsRepository: MomentsRepository
    val settingsRepository: SettingsRepository

    val connectToServerUseCase: ConnectToServerUseCase
    val signInWithBiometricsUseCase: SignInWithBiometricsUseCase
    val saveBiometricCredentialsUseCase: SaveBiometricCredentialsUseCase
    val forgetBiometricCredentialsUseCase: ForgetBiometricCredentialsUseCase
    val observeMostRecentConnectionUseCase: ObserveMostRecentConnectionUseCase
    val observeCamerasUseCase: ObserveCamerasUseCase
    val observeMomentsUseCase: ObserveMomentsUseCase
    val observeSettingsUseCase: ObserveSettingsUseCase
    val updateSettingsUseCase: UpdateSettingsUseCase

    @Provides
    fun providePlatform(): Platform = getPlatform()

    @SingleIn(AppScope::class)
    @Provides
    fun provideConnectionHistoryDao(platformContext: PlatformContext): ConnectionHistoryDao =
        createConnectionHistoryDao(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideCameraDao(platformContext: PlatformContext): CameraDao =
        createCameraDao(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideSettingsDao(platformContext: PlatformContext): SettingsDao =
        createSettingsDao(platformContext)

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

    @Provides
    fun bindConnectionRepository(impl: ConnectionRepositoryImpl): ConnectionRepository = impl

    @Provides
    fun bindCameraRepository(impl: CameraRepositoryImpl): CameraRepository = impl

    @Provides
    fun bindMomentsRepository(impl: MomentsRepositoryImpl): MomentsRepository = impl

    @Provides
    fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository = impl

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platformContext: PlatformContext): AppGraph
    }
}

fun createAppGraph(platformContext: PlatformContext): AppGraph =
    createGraphFactory<AppGraph.Factory>().create(platformContext)
