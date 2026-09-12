package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceSource
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import com.meticulouscreations.homesafe.domain.repository.CameraRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.MediaUrlRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.repository.PropertyLayoutRepository
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveWebRtcSignalingUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveHomeLayoutUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetAwayUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetHomeLayoutUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers what the home grid actually decides: which cameras get playable URLs and which get a
 * placeholder. `tile()` is where a disconnected server or a camera disabled on the server turns
 * into "show the placeholder", and it had no coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which has no implementation on the JVM test
    // target (jvmMain has no coroutines-swing), so every test here has to install one.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeCameras(cameras: List<Camera>) : CameraRepository {
        val flow = MutableStateFlow(cameras)
        override fun observeCameras(): Flow<List<Camera>> = flow
    }

    /** URLs are built by string shape only — the real repository's format is its own test's job. */
    private object FakeMediaUrls : MediaUrlRepository {
        override fun liveStreamUrl(serverUrl: String, streamName: String, audioCodecs: List<String>) =
            "$serverUrl/live/$streamName"
        override fun liveWebRtcSignalingUrl(serverUrl: String, streamName: String) = "$serverUrl/webrtc/$streamName"
        override fun cameraSnapshotUrl(serverUrl: String, cameraName: String, height: Int?, cacheBuster: Long?) =
            "$serverUrl/snapshot/$cameraName?h=$height"
        override fun eventThumbnailUrl(serverUrl: String, eventId: String) = "$serverUrl/thumb/$eventId"
        override fun recordingSnapshotUrl(serverUrl: String, cameraName: String, epochSeconds: Double, height: Int?) =
            "$serverUrl/rec/$cameraName/$epochSeconds"
    }

    private class FakePresence(everyoneAway: Boolean = false) : PresenceRepository {
        override val presence = MutableStateFlow(
            HouseholdPresence(devices = emptyList(), everyoneAway = everyoneAway),
        )
        var setAwayCalls = mutableListOf<Boolean>()
        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
        override suspend fun setHome(home: HomeLocation?): Result<Unit> = Result.success(Unit)
        override suspend fun setThisDeviceAway(away: Boolean, source: PresenceSource, dwellSeconds: Int): Result<Unit> {
            setAwayCalls += away
            presence.value = HouseholdPresence(emptyList(), everyoneAway = away)
            return Result.success(Unit)
        }
    }

    private class FakeConnection(url: String?) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
    }

    private class FakePropertyLayout : PropertyLayoutRepository {
        val placements = MutableStateFlow<List<CameraPlacement>>(emptyList())
        val homeLayout = MutableStateFlow(HomeLayout.DEFAULT)
        override fun observePlacements(): Flow<List<CameraPlacement>> = placements
        override suspend fun place(placement: CameraPlacement) {
            placements.value = placements.value.filterNot { it.cameraName == placement.cameraName } + placement
        }
        override suspend fun removePlacement(cameraName: String) {
            placements.value = placements.value.filterNot { it.cameraName == cameraName }
        }
        override fun observeHomeLayout(): Flow<HomeLayout> = homeLayout
        override suspend fun setHomeLayout(layout: HomeLayout) {
            homeLayout.value = layout
        }
    }

    private object FakePushToken : PushTokenProvider {
        override val isSupported = true
        override suspend fun token(): String? = "test-token"
    }

    private class Harness(
        cameras: List<Camera> = emptyList(),
        serverUrl: String? = "http://frigate.test:8971",
        everyoneAway: Boolean = false,
    ) {
        val cameraRepo = FakeCameras(cameras)
        val presenceRepo = FakePresence(everyoneAway)
        val connection = FakeConnection(serverUrl)
        val propertyLayout = FakePropertyLayout()
        val viewModel = HomeViewModel(
            observeCamerasUseCase = ObserveCamerasUseCase(cameraRepo),
            observeCurrentServerUrlUseCase = ObserveCurrentServerUrlUseCase(connection),
            getLiveStreamUrlUseCase = GetLiveStreamUrlUseCase(FakeMediaUrls),
            getLiveWebRtcSignalingUrlUseCase = GetLiveWebRtcSignalingUrlUseCase(FakeMediaUrls),
            getCameraSnapshotUrlUseCase = GetCameraSnapshotUrlUseCase(FakeMediaUrls),
            observeHouseholdPresenceUseCase = ObserveHouseholdPresenceUseCase(presenceRepo),
            observeHomeLayoutUseCase = ObserveHomeLayoutUseCase(propertyLayout),
            setAwayUseCase = SetAwayUseCase(presenceRepo),
            setHomeLayoutUseCase = SetHomeLayoutUseCase(propertyLayout),
        )
    }

    /**
     * `cameras` and `everyoneAway` are `WhileSubscribed` StateFlows: with nothing collecting they
     * sit at their initial value forever, so a test that just reads `.value` would assert on the
     * placeholder rather than on anything the view model computed.
     */
    private fun TestScope.activate(flow: StateFlow<*>) {
        backgroundScope.launch { flow.collect {} }
    }

    private fun TestScope.tiles(viewModel: HomeViewModel): List<CameraTile> {
        activate(viewModel.cameras)
        advanceUntilIdle()
        return assertNotNull(viewModel.cameras.value, "cameras should have loaded once collected")
    }

    @Test
    fun camerasAreNullUntilTheFirstReadLands() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "front_door", enabled = true)))
        assertNull(h.viewModel.cameras.value, "null is what tells the grid to show a skeleton rather than 'no cameras'")
    }

    @Test
    fun anEnabledCameraGetsPlayableUrls() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "front_door", enabled = true, gridStreamName = "front_door_sub")))
        val collected = tiles(h.viewModel)

        assertEquals(1, collected.size)
        assertEquals("http://frigate.test:8971/live/front_door_sub", collected[0].streamUrl, "the grid plays the grid stream, not the full-quality one")
        assertEquals("http://frigate.test:8971/webrtc/front_door_sub", collected[0].webRtcSignalingUrl, "and can negotiate WebRTC for that same stream")
        assertEquals("http://frigate.test:8971/snapshot/front_door?h=480", collected[0].posterUrl)
    }

    @Test
    fun aCameraDisabledOnTheServerGetsNoUrls() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "garage", enabled = false)))
        val collected = tiles(h.viewModel)

        assertNull(collected[0].streamUrl, "a disabled camera has nothing to play")
        assertNull(collected[0].webRtcSignalingUrl)
        assertNull(collected[0].posterUrl)
    }

    @Test
    fun noServerMeansNoUrlsEvenForAnEnabledCamera() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "front_door", enabled = true)), serverUrl = null)
        val collected = tiles(h.viewModel)

        assertNull(collected[0].streamUrl, "disconnected: the card shows a placeholder rather than a URL that cannot load")
        assertNull(collected[0].webRtcSignalingUrl)
        assertNull(collected[0].posterUrl)
    }

    @Test
    fun everyoneAwayFollowsPresence() = runTest {
        val h = Harness(everyoneAway = true)
        activate(h.viewModel.everyoneAway)
        advanceUntilIdle()

        assertTrue(h.viewModel.everyoneAway.value)
    }

    @Test
    fun theLayoutStartsUnknownAndThenFollowsWhatWasStored() = runTest {
        val h = Harness()
        assertNull(h.viewModel.layout.value, "null keeps the skeleton up rather than flashing the wrong layout")

        activate(h.viewModel.layout)
        advanceUntilIdle()
        assertEquals(HomeLayout.LIST, h.viewModel.layout.value)
    }

    @Test
    fun switchingLayoutIsRemembered() = runTest {
        val h = Harness()
        activate(h.viewModel.layout)
        advanceUntilIdle()

        h.viewModel.setLayout(HomeLayout.MAP)
        advanceUntilIdle()

        assertEquals(HomeLayout.MAP, h.viewModel.layout.value)
        assertEquals(HomeLayout.MAP, h.propertyLayout.homeLayout.value, "the choice is written through, not just held in the view model")
    }

    @Test
    fun markBackClearsAwayMode() = runTest {
        val h = Harness(everyoneAway = true)
        activate(h.viewModel.everyoneAway)
        advanceUntilIdle()

        h.viewModel.markBack()
        advanceUntilIdle()

        assertEquals(listOf(false), h.presenceRepo.setAwayCalls, "'I'm back' marks this device home")
    }
}
