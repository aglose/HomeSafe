package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.domain.repository.CameraRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.MediaUrlRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import com.meticulouscreations.homesafe.domain.usecase.DownloadMomentClipUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetEventThumbnailUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetMomentClipStreamUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.LoadOlderMomentsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMomentsErrorUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMomentsPagingUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMomentsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ShowMomentsBeforeUseCase
import com.meticulouscreations.homesafe.domain.usecase.ShowMomentsFromCameraUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The feed's filters: which cards survive a camera and a type picked together, that the camera
 * is asked of the server, and what the camera filter makes of a camera the server stops listing.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MomentsViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Narrows on [showCamera] the way the server does, and remembers every camera it was told. */
    private class FakeMoments(private val events: List<MomentEvent>) : MomentsRepository {
        val camera = MutableStateFlow<String?>("left over from an earlier feed")
        val cameraCalls = mutableListOf<String?>()
        override fun observeMoments(): Flow<List<MomentEvent>> = camera.map { c -> events.filter { c == null || it.cameraName == c } }
        override fun observeError(): Flow<String?> = flowOf(null)
        override fun observePaging(): Flow<MomentsPaging> = flowOf(MomentsPaging())
        override suspend fun loadOlder() = Unit
        override fun showBefore(epochSeconds: Double?) = Unit
        override fun showCamera(cameraName: String?) {
            cameraCalls += cameraName
            camera.value = cameraName
        }
        override fun observeRecentMoments(cameraName: String, limit: Int): Flow<List<MomentEvent>> = fail("unused")
        override fun observeLatestMoment(): Flow<MomentEvent?> = fail("unused")
        override fun observeStationaryObjects(): Flow<List<StationaryObject>> = fail("unused")
        override suspend fun refresh() = Unit
        override suspend fun getClipStream(eventId: String): RecordingStream = fail("unused")
        override suspend fun getClipDownloadUrl(eventId: String): RecordingStream = fail("unused")
    }

    private class FakeCameras(cameras: List<Camera>) : CameraRepository {
        val flow = MutableStateFlow(cameras)
        override fun observeCameras(): Flow<List<Camera>> = flow
    }

    private object FakeMediaUrls : MediaUrlRepository {
        override fun liveStreamUrl(serverUrl: String, streamName: String, audioCodecs: List<String>) = fail("unused")
        override fun liveWebRtcSignalingUrl(serverUrl: String, streamName: String) = fail("unused")
        override fun cameraSnapshotUrl(serverUrl: String, cameraName: String, height: Int?, cacheBuster: Long?) = fail("unused")
        override fun eventThumbnailUrl(serverUrl: String, eventId: String) = "$serverUrl/thumb/$eventId"
        override fun recordingSnapshotUrl(serverUrl: String, cameraName: String, epochSeconds: Double, height: Int?) = fail("unused")
    }

    private object FakeConnection : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow<String?>("http://frigate.test:8971")
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

    private object NoDownloads : ClipDownloader {
        override suspend fun download(url: String, headers: Map<String, String>, fileName: String): Result<Unit> = fail("unused")
    }

    private fun event(id: String, camera: String, label: String) = MomentEvent(
        id = id,
        cameraName = camera,
        label = label,
        subLabel = null,
        startEpochSeconds = 1_789_400_000.0 - id.hashCode().mod(1000),
        endEpochSeconds = 1_789_400_010.0,
        topScore = 0.9,
        hasClip = true,
        hasSnapshot = false,
    )

    private val feed = listOf(
        event("a", "front_door", "person"),
        event("b", "front_door", "car"),
        event("c", "backyard", "person"),
        event("d", "backyard", "dog"),
    )

    private inner class Harness(cameras: List<Camera> = listOf(Camera("front_door", true), Camera("backyard", true))) {
        val moments = FakeMoments(feed)
        val cameraRepo = FakeCameras(cameras)
        val viewModel = MomentsViewModel(
            observeMomentsUseCase = ObserveMomentsUseCase(moments),
            observeMomentsErrorUseCase = ObserveMomentsErrorUseCase(moments),
            observeMomentsPagingUseCase = ObserveMomentsPagingUseCase(moments),
            observeCurrentServerUrlUseCase = ObserveCurrentServerUrlUseCase(FakeConnection),
            observeCamerasUseCase = ObserveCamerasUseCase(cameraRepo),
            loadOlderMomentsUseCase = LoadOlderMomentsUseCase(moments),
            showMomentsBeforeUseCase = ShowMomentsBeforeUseCase(moments),
            showMomentsFromCameraUseCase = ShowMomentsFromCameraUseCase(moments),
            getMomentClipStreamUseCase = GetMomentClipStreamUseCase(moments),
            downloadMomentClipUseCase = DownloadMomentClipUseCase(moments, NoDownloads),
            getEventThumbnailUrlUseCase = GetEventThumbnailUrlUseCase(FakeMediaUrls),
            getRecordingSnapshotUrlUseCase = GetRecordingSnapshotUrlUseCase(FakeMediaUrls),
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(1_789_400_000)
            },
        )
    }

    /** uiState is `WhileSubscribed`: with nothing collecting it would sit at its initial value. */
    private fun TestScope.state(viewModel: MomentsViewModel): MomentsUiState {
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        return viewModel.uiState.value
    }

    private val MomentsUiState.ids get() = groups.flatMap { g -> g.items.map { it.event.id } }.sorted()

    @Test
    fun theCameraListIsTheServersInItsOrderAndNothingIsNarrowedAtFirst() = runTest(dispatcher) {
        val state = state(Harness().viewModel)
        assertEquals(listOf(MomentCameraOption("front_door", "Front Door"), MomentCameraOption("backyard", "Backyard")), state.cameras)
        assertNull(state.selectedCamera)
        assertEquals(listOf("a", "b", "c", "d"), state.ids)
    }

    @Test
    fun aNewFeedUndoesANarrowingAnEarlierOneLeftOnTheRepository() = runTest(dispatcher) {
        val harness = Harness()
        state(harness.viewModel)
        assertEquals(listOf<String?>(null), harness.moments.cameraCalls)
        assertEquals(listOf("a", "b", "c", "d"), harness.viewModel.uiState.value.ids)
    }

    @Test
    fun pickingACameraAsksTheServerForOnlyItsMoments() = runTest(dispatcher) {
        val harness = Harness()
        state(harness.viewModel)
        harness.viewModel.selectCamera("backyard")
        advanceUntilIdle()
        val state = harness.viewModel.uiState.value
        assertEquals(MomentCameraOption("backyard", "Backyard"), state.selectedCamera)
        assertEquals(listOf("c", "d"), state.ids)
        assertEquals(listOf(null, "backyard"), harness.moments.cameraCalls)
    }

    @Test
    fun aCameraAndATypeNarrowTogether() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.selectCamera("front_door")
        harness.viewModel.selectCategory(MomentCategory.PEOPLE)
        assertEquals(listOf("a"), state(harness.viewModel).ids)
    }

    @Test
    fun clearingTheCameraShowsEveryCameraAgain() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.selectCamera("front_door")
        state(harness.viewModel)
        harness.viewModel.selectCamera(null)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c", "d"), harness.viewModel.uiState.value.ids)
    }

    @Test
    fun aCameraTheServerStopsListingFallsBackToEveryCamera() = runTest(dispatcher) {
        val harness = Harness()
        harness.viewModel.selectCamera("backyard")
        state(harness.viewModel)
        harness.cameraRepo.flow.value = listOf(Camera("front_door", true))
        advanceUntilIdle()
        val state = harness.viewModel.uiState.value
        assertNull(state.selectedCamera)
        assertEquals(listOf("a", "b", "c", "d"), state.ids)
        assertNull(harness.moments.cameraCalls.last(), "the server is asked for every camera again, too")
    }

    @Test
    fun aPickedCameraHoldsWhileTheCameraListIsStillEmpty() = runTest(dispatcher) {
        val harness = Harness(cameras = emptyList())
        harness.viewModel.selectCamera("backyard")
        val state = state(harness.viewModel)
        assertEquals(MomentCameraOption("backyard", "Backyard"), state.selectedCamera)
        assertEquals(listOf("c", "d"), state.ids)
    }
}
