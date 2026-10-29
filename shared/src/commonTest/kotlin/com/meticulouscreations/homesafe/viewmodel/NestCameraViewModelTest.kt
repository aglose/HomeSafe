package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.data.CameraLayoutRepositoryImpl
import com.meticulouscreations.homesafe.data.FrigateMediaUrlRepository
import com.meticulouscreations.homesafe.data.GoogleHomeApp
import com.meticulouscreations.homesafe.data.InMemoryCameraListDao
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.data.PlaybackPreferencesRepositoryImpl
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.PlaybackPreferences
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.VideoFit
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveWebRtcSignalingUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCameraLayoutsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObservePlaybackPreferencesUseCase
import com.meticulouscreations.homesafe.domain.usecase.UpdatePlaybackPreferencesUseCase
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class NestCameraViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private object NoConnection : ConnectionRepository {
        override val activeConnection = MutableStateFlow<ActiveConnection?>(null)
        override val currentServerUrl = MutableStateFlow<String?>(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = Res.string.biometric_name_generic
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    private fun viewModel(preferences: PlaybackPreferencesRepositoryImpl, googleHomeApp: GoogleHomeApp = GoogleHomeApp { true }): NestCameraViewModel {
        val media = FrigateMediaUrlRepository()
        return NestCameraViewModel(
            observeCurrentServerUrlUseCase = ObserveCurrentServerUrlUseCase(NoConnection),
            getLiveStreamUrlUseCase = GetLiveStreamUrlUseCase(media),
            getLiveWebRtcSignalingUrlUseCase = GetLiveWebRtcSignalingUrlUseCase(media),
            observePlaybackPreferencesUseCase = ObservePlaybackPreferencesUseCase(preferences),
            updatePlaybackPreferencesUseCase = UpdatePlaybackPreferencesUseCase(preferences),
            observeCameraLayoutsUseCase = ObserveCameraLayoutsUseCase(CameraLayoutRepositoryImpl(InMemoryCameraListDao(), NoConnection)),
            googleHomeApp = googleHomeApp,
        )
    }

    @Test
    fun theSpeakerIsSavedAndTheNextVisitOpensWithIt() = runTest {
        val preferences = PlaybackPreferencesRepositoryImpl(InMemorySettingsDao())
        preferences.updatePlaybackPreferences(PlaybackPreferences(nestFit = VideoFit.Fill))

        val first = viewModel(preferences)
        backgroundScope.launch { first.soundOn.collect {} }
        runCurrent()
        assertFalse(first.soundOn.value, "off until it has been turned on")

        first.toggleSound()
        advanceUntilIdle()
        assertTrue(first.soundOn.value)
        assertEquals(PlaybackPreferences(soundOn = true, nestFit = VideoFit.Fill), preferences.observePlaybackPreferences().first(), "the fit is left as it was")

        val next = viewModel(preferences)
        backgroundScope.launch { next.soundOn.collect {} }
        runCurrent()
        assertTrue(next.soundOn.value, "a later visit opens with sound on")
    }

    @Test
    fun talkingOpensGoogleHome() {
        var opened = 0
        val googleHome = GoogleHomeApp {
            opened++
            true
        }
        val viewModel = viewModel(PlaybackPreferencesRepositoryImpl(InMemorySettingsDao()), googleHomeApp = googleHome)

        viewModel.talkInGoogleHome()

        assertEquals(1, opened)
    }
}
