package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.CameraRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.MediaUrlRepository
import com.meticulouscreations.homesafe.domain.repository.PropertyLayoutRepository
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveWebRtcSignalingUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCameraPlacementsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.PlaceCameraUseCase
import com.meticulouscreations.homesafe.domain.usecase.RemoveCameraPlacementUseCase
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
import kotlin.test.fail

/**
 * The property map's own decisions: pairing each camera with its placement (or with nothing, so
 * it waits in the arrange tray), and keeping a dropped marker on the plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PropertyMapViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeCameras(cameras: List<Camera>) : CameraRepository {
        val flow = MutableStateFlow(cameras)
        override fun observeCameras(): Flow<List<Camera>> = flow
    }

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
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    private class FakePropertyLayout(placements: List<CameraPlacement> = emptyList()) : PropertyLayoutRepository {
        val placements = MutableStateFlow(placements)
        val homeLayout = MutableStateFlow(HomeLayout.MAP)
        override fun observePlacements(): Flow<List<CameraPlacement>> = this.placements
        override suspend fun place(placement: CameraPlacement) {
            this.placements.value = this.placements.value.filterNot { it.cameraName == placement.cameraName } + placement
        }
        override suspend fun removePlacement(cameraName: String) {
            placements.value = placements.value.filterNot { it.cameraName == cameraName }
        }
        override fun observeHomeLayout(): Flow<HomeLayout> = homeLayout
        override suspend fun setHomeLayout(layout: HomeLayout) {
            homeLayout.value = layout
        }
    }

    private class Harness(
        cameras: List<Camera> = emptyList(),
        placements: List<CameraPlacement> = emptyList(),
        serverUrl: String? = "http://frigate.test:8971",
    ) {
        val layoutRepo = FakePropertyLayout(placements)
        val viewModel = PropertyMapViewModel(
            observeCamerasUseCase = ObserveCamerasUseCase(FakeCameras(cameras)),
            observeCurrentServerUrlUseCase = ObserveCurrentServerUrlUseCase(FakeConnection(serverUrl)),
            observeCameraPlacementsUseCase = ObserveCameraPlacementsUseCase(layoutRepo),
            getLiveStreamUrlUseCase = GetLiveStreamUrlUseCase(FakeMediaUrls),
            getLiveWebRtcSignalingUrlUseCase = GetLiveWebRtcSignalingUrlUseCase(FakeMediaUrls),
            getCameraSnapshotUrlUseCase = GetCameraSnapshotUrlUseCase(FakeMediaUrls),
            placeCameraUseCase = PlaceCameraUseCase(layoutRepo),
            removeCameraPlacementUseCase = RemoveCameraPlacementUseCase(layoutRepo),
            setHomeLayoutUseCase = SetHomeLayoutUseCase(layoutRepo),
        )
    }

    private fun TestScope.mapped(viewModel: PropertyMapViewModel): List<MappedCamera> {
        backgroundScope.launch { viewModel.cameras.collect {} }
        advanceUntilIdle()
        return assertNotNull(viewModel.cameras.value, "cameras should have loaded once collected")
    }

    @Test
    fun camerasAreNullUntilTheFirstReadLands() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "front_door", enabled = true)))
        assertNull(h.viewModel.cameras.value, "null keeps the plan in its loading state rather than claiming there are no cameras")
    }

    @Test
    fun aPlacedCameraCarriesItsPositionAndAPlayableStream() = runTest {
        val h = Harness(
            cameras = listOf(Camera(name = "front_door", enabled = true, gridStreamName = "front_door_sub")),
            placements = listOf(CameraPlacement("front_door", x = 0.3f, y = 0.6f)),
        )
        val cameras = mapped(h.viewModel)

        assertEquals(CameraPlacement("front_door", 0.3f, 0.6f), cameras.single().placement)
        assertEquals("http://frigate.test:8971/live/front_door_sub", cameras.single().tile.streamUrl, "markers play the grid stream, not full quality")
    }

    @Test
    fun anUnplacedCameraIsStillListedSoItCanWaitInTheTray() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "garage", enabled = true)))
        val cameras = mapped(h.viewModel)

        assertNull(cameras.single().placement, "nothing has said where the garage camera is, so it is not put on the plan")
    }

    @Test
    fun placingACameraPutsItOnThePlan() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "garage", enabled = true)))
        val before = mapped(h.viewModel)
        assertNull(before.single().placement)

        h.viewModel.place("garage", 0.8f, 0.25f)
        advanceUntilIdle()

        assertEquals(CameraPlacement("garage", 0.8f, 0.25f), assertNotNull(h.viewModel.cameras.value).single().placement)
    }

    @Test
    fun placingOutsideThePlanIsPulledBackToItsEdge() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "garage", enabled = true)))
        mapped(h.viewModel)

        h.viewModel.place("garage", x = 1.4f, y = -0.2f)
        advanceUntilIdle()

        assertEquals(
            CameraPlacement("garage", 1f, 0f),
            assertNotNull(h.viewModel.cameras.value).single().placement,
            "a drag that ran off the panel leaves the marker on the edge, never off the plan",
        )
    }

    @Test
    fun removingAPlacementSendsTheCameraBackToTheTray() = runTest {
        val h = Harness(
            cameras = listOf(Camera(name = "front_door", enabled = true)),
            placements = listOf(CameraPlacement("front_door", 0.5f, 0.5f)),
        )
        mapped(h.viewModel)

        h.viewModel.removePlacement("front_door")
        advanceUntilIdle()

        assertNull(assertNotNull(h.viewModel.cameras.value).single().placement)
    }

    @Test
    fun aDisabledCameraCanStillBePlacedButHasNothingToPlay() = runTest {
        val h = Harness(
            cameras = listOf(Camera(name = "side_gate", enabled = false)),
            placements = listOf(CameraPlacement("side_gate", 0.2f, 0.2f)),
        )
        val cameras = mapped(h.viewModel)

        assertNotNull(cameras.single().placement, "where a camera is doesn't stop being true when Frigate turns it off")
        assertNull(cameras.single().tile.streamUrl)
    }

    @Test
    fun leavingTheMapWritesTheLayoutChoiceThrough() = runTest {
        val h = Harness()

        h.viewModel.showListLayout()
        advanceUntilIdle()

        assertEquals(HomeLayout.LIST, h.layoutRepo.homeLayout.value)
    }
}
