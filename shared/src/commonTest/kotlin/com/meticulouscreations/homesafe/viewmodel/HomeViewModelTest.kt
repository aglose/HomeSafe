package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.data.CameraLayoutRepositoryImpl
import com.meticulouscreations.homesafe.data.InMemoryCameraListDao
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.data.PlaybackPreferencesRepositoryImpl
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraCrop
import com.meticulouscreations.homesafe.domain.model.CameraLayout
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.CameraSource
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.PlaybackPreferences
import com.meticulouscreations.homesafe.domain.model.PresenceSource
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.VideoFit
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import com.meticulouscreations.homesafe.domain.repository.CameraLayoutRepository
import com.meticulouscreations.homesafe.domain.repository.CameraRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.MediaUrlRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import com.meticulouscreations.homesafe.domain.repository.NestCameraRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetEventThumbnailUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStillUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveWebRtcSignalingUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCameraLayoutsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveDoorsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveNestCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObservePlaybackPreferencesUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveStationaryObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ReconnectToServerUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshDoorsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshNestCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshStationaryObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveCameraOrderUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetAwayUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraHiddenUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraOrientationUseCase
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.moments_in_view_since
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Covers what the home grid actually decides: which cameras get playable URLs and which get a
 * placeholder. `tile()` is where a disconnected server or a camera disabled on the server turns
 * into "show the placeholder", and it had no coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
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

    private class FakeNestCameras(cameras: List<Camera>?) : NestCameraRepository {
        val flow = MutableStateFlow(cameras)
        var refreshes = 0
        override fun observeNestCameras(): Flow<List<Camera>?> = flow
        override suspend fun refresh() {
            refreshes++
        }
        override suspend fun refresh(connection: ActiveConnection) = fail("unused")
    }

    /** URLs are built by string shape only — the real repository's format is its own test's job. */
    private object FakeMediaUrls : MediaUrlRepository {
        override fun liveStreamUrl(serverUrl: String, streamName: String, audioCodecs: List<String>) =
            "$serverUrl/live/$streamName"
        override fun liveWebRtcSignalingUrl(serverUrl: String, streamName: String) = "$serverUrl/webrtc/$streamName"
        override fun liveStillUrl(serverUrl: String, streamName: String) = "$serverUrl/still/$streamName"
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
        var refreshes = 0
        override suspend fun refresh(): Result<Unit> {
            refreshes++
            return Result.success(Unit)
        }
        override suspend fun setDecidesPresence(decides: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun setHome(home: HomeLocation?): Result<Unit> = Result.success(Unit)
        override suspend fun removeDevice(deviceId: String): Result<Unit> = Result.success(Unit)
        override suspend fun setThisDeviceAway(away: Boolean, source: PresenceSource, dwellSeconds: Int): Result<Unit> {
            setAwayCalls += away
            presence.value = HouseholdPresence(emptyList(), everyoneAway = away)
            return Result.success(Unit)
        }
    }

    private class FakeConnection(url: String?) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(url?.let { ActiveConnection(it, localUrl = null, route = ConnectionRoute.TAILSCALE) })
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = Res.string.biometric_name_generic
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit

        var reconnects = 0

        /** When set, a reconnect waits for it: a server slow to answer, or not answering at all. */
        var reconnectGate: CompletableDeferred<Unit>? = null
        override suspend fun reconnect() {
            reconnects++
            reconnectGate?.await()
        }
    }

    private object FakePushToken : PushTokenProvider {
        override val isSupported = true
        override suspend fun token(): String? = "test-token"
    }

    /** Only the in-view strip is read here; the feed's own methods belong to its view model's test. */
    private class FakeMoments(private val inView: List<StationaryObject>) : MomentsRepository {
        var stationaryRefreshes = 0
        override fun observeStationaryObjects(): Flow<List<StationaryObject>> = MutableStateFlow(inView)
        override fun refreshStationaryObjects() {
            stationaryRefreshes++
        }
        override fun nameCar(eventId: String, subLabel: String) = Unit
        override fun observeMoments(): Flow<List<MomentEvent>> = fail("unused")
        override fun observeError(): Flow<UiText?> = fail("unused")
        override fun observePaging(): Flow<MomentsPaging> = fail("unused")
        override suspend fun loadOlder() = fail("unused")
        override fun showBefore(epochSeconds: Double?) = fail("unused")
        override fun showCamera(cameraName: String?) = fail("unused")
        override fun observeRecentMoments(cameraName: String, limit: Int, lookbackSeconds: Double): Flow<List<MomentEvent>> = fail("unused")
        override fun observeMomentsBetween(cameraName: String, afterEpochSeconds: Double, beforeEpochSeconds: Double): Flow<List<MomentEvent>> = fail("unused")
        override suspend fun refresh() = fail("unused")
        override suspend fun getClipStream(eventId: String): RecordingStream = fail("unused")
        override suspend fun getClipDownloadUrl(eventId: String): RecordingStream = fail("unused")
    }

    private class Harness(
        cameras: List<Camera> = emptyList(),
        indoorCameras: List<Camera>? = emptyList(),
        serverUrl: String? = "http://frigate.test:8971",
        everyoneAway: Boolean = false,
        inView: List<StationaryObject> = emptyList(),
        layoutRepository: CameraLayoutRepository? = null,
    ) {
        val cameraRepo = FakeCameras(cameras)
        val playbackPreferences = PlaybackPreferencesRepositoryImpl(InMemorySettingsDao())
        val connection = FakeConnection(serverUrl)
        val layouts = layoutRepository ?: CameraLayoutRepositoryImpl(InMemoryCameraListDao(), connection)
        val indoorRepo = FakeNestCameras(indoorCameras)
        val presenceRepo = FakePresence(everyoneAway)
        val moments = FakeMoments(inView)
        val doors = FakeDoorRepository()
        val viewModel = HomeViewModel(
            observeCamerasUseCase = ObserveCamerasUseCase(cameraRepo),
            observeNestCamerasUseCase = ObserveNestCamerasUseCase(indoorRepo),
            observeCurrentServerUrlUseCase = ObserveCurrentServerUrlUseCase(connection),
            observePlaybackPreferencesUseCase = ObservePlaybackPreferencesUseCase(playbackPreferences),
            getLiveStreamUrlUseCase = GetLiveStreamUrlUseCase(FakeMediaUrls),
            getLiveWebRtcSignalingUrlUseCase = GetLiveWebRtcSignalingUrlUseCase(FakeMediaUrls),
            getCameraSnapshotUrlUseCase = GetCameraSnapshotUrlUseCase(FakeMediaUrls),
            getLiveStillUrlUseCase = GetLiveStillUrlUseCase(FakeMediaUrls),
            getEventThumbnailUrlUseCase = GetEventThumbnailUrlUseCase(FakeMediaUrls),
            observeHouseholdPresenceUseCase = ObserveHouseholdPresenceUseCase(presenceRepo),
            observeDoorsUseCase = ObserveDoorsUseCase(doors),
            refreshDoorsUseCase = RefreshDoorsUseCase(doors),
            observeStationaryObjectsUseCase = ObserveStationaryObjectsUseCase(moments),
            setAwayUseCase = SetAwayUseCase(presenceRepo),
            reconnectToServerUseCase = ReconnectToServerUseCase(connection),
            refreshHouseholdPresenceUseCase = RefreshHouseholdPresenceUseCase(presenceRepo),
            refreshStationaryObjectsUseCase = RefreshStationaryObjectsUseCase(moments),
            refreshNestCamerasUseCase = RefreshNestCamerasUseCase(indoorRepo),
            observeCameraLayoutsUseCase = ObserveCameraLayoutsUseCase(layouts),
            saveCameraOrderUseCase = SaveCameraOrderUseCase(layouts),
            setCameraOrientationUseCase = SetCameraOrientationUseCase(layouts),
            setCameraHiddenUseCase = SetCameraHiddenUseCase(layouts),
            clock = object : Clock {
                // 18:00 UTC, a couple of hours after [tesla] arrived and the same day wherever the
                // test runs, so the card's arrival never reads "yesterday".
                override fun now(): Instant = Instant.fromEpochSeconds(1_789_408_800)
            },
        )
    }

    /**
     * `cameras` and `everyoneAway` are `WhileSubscribed` StateFlows: with nothing collecting they
     * sit at their initial value forever, so a test that just reads `.value` would assert on the
     * placeholder rather than on anything the view model computed.
     */
    private fun TestScope.activate(flow: StateFlow<*>) {
        backgroundScope.launch { flow.collect {} }
        // advanceUntilIdle() treats background work as idle, so a collector launched after the
        // first one would otherwise never start.
        runCurrent()
    }

    private fun TestScope.tiles(viewModel: HomeViewModel): List<CameraTile> {
        activate(viewModel.cameras)
        advanceUntilIdle()
        return assertNotNull(viewModel.cameras.value, "cameras should have loaded once collected")
    }

    /** Sarah's Tesla, parked in the driveway a couple of hours ago and seen there a moment ago. */
    private fun tesla() = StationaryObject(
        thumbnailEventId = "latest-sighting",
        cameraName = "hikvision_1",
        label = "car",
        subLabel = "sarahs_tesla",
        zones = listOf("driveway"),
        firstSeenEpochSeconds = 1_789_400_000.0,
        lastSeenEpochSeconds = 1_789_408_700.0,
        seenRecently = true,
        sightings = 5,
        sinceIsKnown = true,
    )

    private fun TestScope.indoorTiles(viewModel: HomeViewModel): List<CameraTile>? {
        activate(viewModel.indoorCameras)
        advanceUntilIdle()
        return viewModel.indoorCameras.value
    }

    private fun nest(name: String, placement: CameraPlacement = CameraPlacement.Indoor) =
        Camera(name = name, enabled = true, placement = placement, source = CameraSource.Nest)

    @Test
    fun thePageOpensOnTheOutdoorCameras() = runTest {
        val h = Harness(indoorCameras = listOf(nest("living_room")))
        assertEquals(CameraPlacement.Outdoor, h.viewModel.placement.value)

        h.viewModel.showPlacement(CameraPlacement.Indoor)
        assertEquals(CameraPlacement.Indoor, h.viewModel.placement.value)
    }

    @Test
    fun anIndoorCameraPlaysItsStreamOverItsLastSeenStill() = runTest {
        val h = Harness(indoorCameras = listOf(nest("living_room")))
        val tile = assertNotNull(indoorTiles(h.viewModel)).single()

        assertEquals("http://frigate.test:8971/live/living_room", tile.streamUrl)
        assertEquals("http://frigate.test:8971/webrtc/living_room", tile.webRtcSignalingUrl)
        assertEquals("http://frigate.test:8971/still/living_room", tile.posterUrl, "a still of its own stream, not a Frigate snapshot")
        assertTrue(tile.posterIsLastSeen, "shown only from what the device kept: asking go2rtc for one would wait on Google")
        assertEquals(CameraPlacement.Indoor, tile.camera.placement)
    }

    @Test
    fun aNestCameraWithAGridStreamPlaysItOnItsCard() = runTest {
        val h = Harness(indoorCameras = listOf(nest("living_room").copy(gridStreamName = "living_room_sub")))
        val tile = assertNotNull(indoorTiles(h.viewModel)).single()

        assertEquals("http://frigate.test:8971/live/living_room_sub", tile.streamUrl, "the smaller stream, as a Frigate card plays its sub stream")
        assertEquals("http://frigate.test:8971/webrtc/living_room_sub", tile.webRtcSignalingUrl)
        assertEquals("http://frigate.test:8971/still/living_room_sub", tile.posterUrl, "and its still is of what the card plays")
    }

    @Test
    fun indoorCamerasAreNullUntilTheServerAnswers() = runTest {
        val h = Harness(indoorCameras = null)
        assertNull(indoorTiles(h.viewModel), "null keeps the switch hidden rather than showing an empty indoor list")
    }

    @Test
    fun aFrigateCameraReadingANestStreamIsThatNestCameraWithFrigatesPoster() = runTest {
        val h = Harness(
            cameras = listOf(
                Camera(name = "front_door", enabled = true),
                Camera(name = "living_room", enabled = true, liveStreamName = "living_room", gridStreamName = "living_room_sub"),
            ),
            indoorCameras = listOf(nest("living_room"), nest("nursery")),
        )
        assertEquals(listOf("front_door"), tiles(h.viewModel).map { it.camera.name }, "it leaves the outdoor list")
        val indoor = assertNotNull(indoorTiles(h.viewModel))
        assertEquals(listOf("living_room", "nursery"), indoor.map { it.camera.name }, "Frigate's, then the Nest cameras Frigate doesn't read")

        val living = indoor.first()
        assertEquals(CameraSource.Frigate, living.camera.source, "so it opens Frigate's page")
        assertTrue(living.camera.fromNest)
        assertEquals("http://frigate.test:8971/live/living_room_sub", living.streamUrl)
        assertEquals("http://frigate.test:8971/snapshot/living_room?h=480", living.posterUrl, "Frigate's live snapshot, kept fresh")
        assertFalse(living.posterIsLastSeen)
        assertEquals(VideoFit.Fit, living.videoFit, "shown at the Nest picture's own shape, not stretched")
    }

    @Test
    fun aFrigateCameraReadingAnOutdoorNestStreamStaysOutdoors() = runTest {
        val h = Harness(
            cameras = listOf(Camera(name = "outdoor_doorbell", enabled = true, gridStreamName = "outdoor_doorbell_sub")),
            indoorCameras = listOf(nest("outdoor_doorbell", CameraPlacement.Outdoor)),
        )
        assertEquals(listOf("outdoor_doorbell"), tiles(h.viewModel).map { it.camera.name }, "once, as Frigate's")
        assertEquals(CameraSource.Frigate, tiles(h.viewModel).single().camera.source)
        assertEquals(emptyList(), indoorTiles(h.viewModel))
    }

    @Test
    fun anOutdoorNestCameraJoinsTheFrigateOnes() = runTest {
        val h = Harness(
            cameras = listOf(Camera(name = "front_door", enabled = true)),
            indoorCameras = listOf(nest("outdoor_doorbell", CameraPlacement.Outdoor), nest("kitchen")),
        )
        val outdoor = tiles(h.viewModel)
        assertEquals(listOf("front_door", "outdoor_doorbell"), outdoor.map { it.camera.name }, "Frigate's first, then the Nest camera outside")
        assertEquals("http://frigate.test:8971/snapshot/front_door?h=480", outdoor[0].posterUrl)
        assertEquals("http://frigate.test:8971/still/outdoor_doorbell", outdoor[1].posterUrl, "a Nest camera's poster is its last-seen still, wherever it is")
        assertTrue(outdoor[1].posterIsLastSeen)
        assertFalse(outdoor[0].posterIsLastSeen)
        assertEquals("http://frigate.test:8971/live/outdoor_doorbell", outdoor[1].streamUrl)
        assertEquals(listOf("kitchen"), indoorTiles(h.viewModel)?.map { it.camera.name })
    }

    @Test
    fun nestCardsShowTheWholePictureUntilToldToFillAndFrigatesStretch() = runTest {
        val h = Harness(
            cameras = listOf(Camera(name = "front_door", enabled = true)),
            indoorCameras = listOf(nest("outdoor_doorbell", CameraPlacement.Outdoor), nest("kitchen")),
        )
        assertEquals(listOf(null, VideoFit.Fit), tiles(h.viewModel).map { it.videoFit }, "Frigate's cameras keep stretching to their card")
        assertEquals(listOf(VideoFit.Fit), indoorTiles(h.viewModel)?.map { it.videoFit })

        h.playbackPreferences.updatePlaybackPreferences(PlaybackPreferences(nestFit = VideoFit.Fill))
        advanceUntilIdle()
        assertEquals(listOf(null, VideoFit.Fill), h.viewModel.cameras.value?.map { it.videoFit })
        assertEquals(listOf(VideoFit.Fill), h.viewModel.indoorCameras.value?.map { it.videoFit })
    }

    @Test
    fun theOutdoorListWaitsForTheKeptNestCamerasSoNoneShowsInTheWrongList() = runTest {
        val h = Harness(cameras = listOf(Camera(name = "living_room", enabled = true)), indoorCameras = null)
        activate(h.viewModel.cameras)
        advanceUntilIdle()
        assertNull(h.viewModel.cameras.value, "the skeleton, for the moment the device's record takes to read")

        h.indoorRepo.flow.value = listOf(nest("living_room"))
        advanceUntilIdle()
        assertEquals(emptyList(), h.viewModel.cameras.value)
        assertEquals(listOf("living_room"), indoorTiles(h.viewModel)?.map { it.camera.name })
    }

    /**
     * Saves land at once, but are read back only when [deliver] says: the gap between a write
     * returning and the observation that carries it, which on a device is a database hop.
     */
    private class SlowLayouts : CameraLayoutRepository {
        private val saved = mutableMapOf<String, CameraLayout>()
        private val observed = MutableStateFlow<Map<String, CameraLayout>>(emptyMap())
        override fun observeLayouts(): Flow<Map<String, CameraLayout>> = observed
        override suspend fun saveOrder(cameraNames: List<String>) {
            cameraNames.forEachIndexed { position, name -> saved[name] = (saved[name] ?: CameraLayout()).copy(position = position) }
        }
        override suspend fun setOrientation(cameraName: String, orientation: CameraOrientation) {
            saved[cameraName] = (saved[cameraName] ?: CameraLayout()).copy(orientation = orientation)
        }
        override suspend fun setFraming(cameraName: String, orientation: CameraOrientation, crop: CameraCrop) {
            saved[cameraName] = (saved[cameraName] ?: CameraLayout()).copy(orientation = orientation, crop = crop)
        }
        override suspend fun setHidden(cameraName: String, hidden: Boolean) {
            saved[cameraName] = (saved[cameraName] ?: CameraLayout()).copy(hidden = hidden)
        }
        fun deliver() {
            observed.value = saved.toMap()
        }
    }

    @Test
    fun aDroppedCardStaysPutWhileItsSaveIsReadBack() = runTest {
        val slow = SlowLayouts()
        val h = Harness(cameras = three, layoutRepository = slow)
        tiles(h.viewModel)
        h.viewModel.startArranging()

        h.viewModel.moveCamera(CameraPlacement.Outdoor, from = "driveway", to = "front_door")
        h.viewModel.saveOrder(CameraPlacement.Outdoor)
        advanceUntilIdle()
        assertEquals(listOf("driveway", "front_door", "backyard"), h.viewModel.cameras.value?.map { it.camera.name }, "saved but not yet read back: no jump home")

        slow.deliver()
        advanceUntilIdle()
        assertEquals(listOf("driveway", "front_door", "backyard"), h.viewModel.cameras.value?.map { it.camera.name })
    }

    @Test
    fun aSecondTurnStartsFromTheFirstEvenBeforeItIsReadBack() = runTest {
        val slow = SlowLayouts()
        val h = Harness(cameras = three, layoutRepository = slow)
        tiles(h.viewModel)

        h.viewModel.turnCamera("backyard", clockwise = true)
        advanceUntilIdle()
        h.viewModel.turnCamera("backyard", clockwise = true)
        advanceUntilIdle()
        assertEquals(CameraOrientation(quarterTurns = 2), h.viewModel.cameras.value?.first { it.camera.name == "backyard" }?.orientation)

        slow.deliver()
        advanceUntilIdle()
        assertEquals(CameraOrientation(quarterTurns = 2), h.viewModel.cameras.value?.first { it.camera.name == "backyard" }?.orientation, "and stays turned once read back")
    }

    private val three = listOf("front_door", "backyard", "driveway").map { Camera(name = it, enabled = true) }

    @Test
    fun theSavedOrderArrangesTheListAndUnarrangedCamerasFollow() = runTest {
        val h = Harness(cameras = three)
        h.layouts.saveOrder(listOf("driveway", "front_door"))
        assertEquals(listOf("driveway", "front_door", "backyard"), tiles(h.viewModel).map { it.camera.name })
    }

    @Test
    fun aDragMovesTheCardAtOnceAndTheDropSavesTheOrder() = runTest {
        val h = Harness(cameras = three)
        tiles(h.viewModel)
        h.viewModel.startArranging()

        h.viewModel.moveCamera(CameraPlacement.Outdoor, from = "driveway", to = "front_door")
        runCurrent()
        assertEquals(listOf("driveway", "front_door", "backyard"), h.viewModel.cameras.value?.map { it.camera.name }, "the card moves under the finger")

        h.viewModel.saveOrder(CameraPlacement.Outdoor)
        advanceUntilIdle()
        assertEquals(mapOf("driveway" to 0, "front_door" to 1, "backyard" to 2), h.layouts.observeLayouts().first().mapValues { it.value.position })
        assertEquals(listOf("driveway", "front_door", "backyard"), h.viewModel.cameras.value?.map { it.camera.name }, "and stays there once read back")
    }

    @Test
    fun aQuarterTurnIsShownAndSaved() = runTest {
        val h = Harness(cameras = three)
        tiles(h.viewModel)

        h.viewModel.turnCamera("backyard", clockwise = false)
        advanceUntilIdle()
        assertEquals(CameraOrientation(quarterTurns = 3), h.viewModel.cameras.value?.first { it.camera.name == "backyard" }?.orientation)
        assertEquals(CameraOrientation(quarterTurns = 3), h.layouts.observeLayouts().first()["backyard"]?.orientation)
    }

    @Test
    fun aQuarterTurnKeepsTheCropAndTheTiltTheEditorSaved() = runTest {
        val h = Harness(cameras = three)
        val crop = CameraCrop(zoom = 2f, centerX = 0.3f, centerY = 0.6f)
        h.layouts.setFraming("backyard", CameraOrientation(0, 2.5f), crop)
        tiles(h.viewModel)
        advanceUntilIdle()
        assertEquals(crop, h.viewModel.cameras.value?.first { it.camera.name == "backyard" }?.crop, "the card shows the crop")

        h.viewModel.turnCamera("backyard", clockwise = true)
        advanceUntilIdle()
        val saved = h.layouts.observeLayouts().first()["backyard"]
        assertEquals(CameraOrientation(1, 2.5f), saved?.orientation)
        assertEquals(crop, saved?.crop)
    }

    @Test
    fun aHiddenCameraIsMarkedAtOnceSavedAndLeftOffTheHomePageOutsideArrangeMode() = runTest {
        val slow = SlowLayouts()
        val h = Harness(cameras = three, layoutRepository = slow)
        tiles(h.viewModel)

        h.viewModel.setCameraHidden("backyard", hidden = true)
        runCurrent()
        val listed = h.viewModel.cameras.value.orEmpty()
        assertEquals(listOf(false, true, false), listed.map { it.hidden }, "marked before the save is read back")
        assertEquals(listOf("front_door", "driveway"), listed.onHome(arranging = false).map { it.camera.name }, "off the page")
        assertEquals(listOf("front_door", "backyard", "driveway"), listed.onHome(arranging = true).map { it.camera.name }, "but there to bring back while arranging")

        slow.deliver()
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), h.viewModel.cameras.value?.map { it.hidden }, "and still hidden once read back")
    }

    @Test
    fun aHiddenCameraKeepsItsPlaceAndFramingAndComesBack() = runTest {
        val h = Harness(cameras = three)
        val crop = CameraCrop(zoom = 2f, centerX = 0.3f)
        h.layouts.saveOrder(listOf("driveway", "backyard", "front_door"))
        h.layouts.setFraming("backyard", CameraOrientation(quarterTurns = 1), crop)
        tiles(h.viewModel)

        h.viewModel.setCameraHidden("backyard", hidden = true)
        advanceUntilIdle()
        assertEquals(CameraLayout(position = 1, orientation = CameraOrientation(quarterTurns = 1), crop = crop, hidden = true), h.layouts.observeLayouts().first()["backyard"])

        h.viewModel.setCameraHidden("backyard", hidden = false)
        advanceUntilIdle()
        assertEquals(listOf("driveway", "backyard", "front_door"), h.viewModel.cameras.value?.onHome(arranging = false)?.map { it.camera.name }, "back where it was")
    }

    @Test
    fun pullingToRefreshAsksForTheIndoorCamerasAgain() = runTest {
        val h = Harness(indoorCameras = listOf(nest("living_room")))
        h.viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, h.indoorRepo.refreshes)
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
    fun aParkedCarBecomesACardWithItsOwnThumbnail() = runTest {
        val h = Harness(inView = listOf(tesla()))
        activate(h.viewModel.inView)
        advanceUntilIdle()

        val item = h.viewModel.inView.value.single()
        assertEquals("Sarah's Tesla".asUiText(), item.presentation.title)
        assertEquals("Driveway", item.presentation.placeLabel)
        // The wording of the time is the domain model's own test (which can pin a time zone); here it
        // only matters that the arrival survived the trip through the view model.
        assertEquals(Res.string.moments_in_view_since, (item.presentation.sinceLabel as UiText.Resource).res, "the card says when the car got there")
        assertEquals("http://frigate.test:8971/thumb/latest-sighting", item.thumbnailUrl, "the crop of the sighting, not a camera snapshot")
    }

    @Test
    fun nothingParkedMeansNoStripAtAll() = runTest {
        val h = Harness()
        activate(h.viewModel.inView)
        advanceUntilIdle()

        assertEquals(emptyList(), h.viewModel.inView.value, "an empty list is what tells the home page to draw no section")
    }

    @Test
    fun aParkedCarHasNoThumbnailWhileDisconnected() = runTest {
        val h = Harness(serverUrl = null, inView = listOf(tesla()))
        activate(h.viewModel.inView)
        advanceUntilIdle()

        assertNull(h.viewModel.inView.value.single().thumbnailUrl, "no server to fetch the crop from")
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

    @Test
    fun aPullAsksAgainForEverythingThePageReads() = runTest {
        val h = Harness()

        h.viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, h.connection.reconnects, "route, session and the camera list")
        assertEquals(1, h.presenceRepo.refreshes, "who is home")
        assertEquals(1, h.moments.stationaryRefreshes, "the cars in view")
        assertEquals(1, h.viewModel.reconnectRequests.value, "and every card's player is asked to reconnect")
    }

    @Test
    fun theBandStaysUpForOneSweepEvenWhenTheAnswerIsInstant() = runTest {
        val h = Harness()

        h.viewModel.refresh()
        runCurrent()
        assertTrue(h.viewModel.refreshing.value)

        advanceTimeBy(800)
        assertTrue(h.viewModel.refreshing.value, "a fast answer still reads as a refresh")

        advanceUntilIdle()
        assertFalse(h.viewModel.refreshing.value)
    }

    @Test
    fun aServerThatDoesNotAnswerClosesTheBandWithoutCancellingTheReconnect() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.connection.reconnectGate = gate

        h.viewModel.refresh()
        advanceTimeBy(8_001)
        runCurrent()

        assertFalse(h.viewModel.refreshing.value, "the band shouldn't wait out every timeout on the way")
        assertEquals(0, h.moments.stationaryRefreshes, "the reconnect is still running behind it")

        h.viewModel.refresh()
        runCurrent()
        assertEquals(1, h.connection.reconnects, "a pull while the last one's work still runs starts nothing new")
        assertFalse(h.viewModel.refreshing.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, h.moments.stationaryRefreshes, "and finishes what it started")

        h.viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, h.connection.reconnects, "once it has, the next pull runs")
    }

    @Test
    fun presenceAndTheCarsAreAskedOnlyOnceTheRouteIsSettled() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.connection.reconnectGate = gate

        h.viewModel.refresh()
        runCurrent()
        assertEquals(0, h.presenceRepo.refreshes, "not on the address the reconnect may be about to move off")
        assertEquals(0, h.moments.stationaryRefreshes)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, h.presenceRepo.refreshes)
        assertEquals(1, h.moments.stationaryRefreshes)
    }

    @Test
    fun aPullWhileOneIsRunningIsIgnored() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.connection.reconnectGate = gate

        h.viewModel.refresh()
        runCurrent()
        h.viewModel.refresh()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, h.connection.reconnects)
        assertEquals(1, h.viewModel.reconnectRequests.value)
    }
}
