package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraLayout
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.CameraSource
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.StationaryObjectPresentation
import com.meticulouscreations.homesafe.domain.model.VideoFit
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetEventThumbnailUrlUseCase
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
import com.meticulouscreations.homesafe.domain.usecase.SetCameraOrientationUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A camera plus what its grid card needs to play it. [streamUrl] and [posterUrl] are null while
 * disconnected or while the camera is disabled on the server — the card shows a placeholder then.
 * A Nest camera never has a [posterUrl]: there is no Frigate snapshot of it, and a still from
 * go2rtc would mean dialling the camera through Google just for a poster.
 */
@Immutable
data class CameraTile(
    val camera: Camera,
    val streamUrl: String?,
    val posterUrl: String?,
    /** Where the card's player can negotiate WebRTC for the same stream; null wherever [streamUrl] is. */
    val webRtcSignalingUrl: String? = null,
    /** How the video sits in the card when it isn't 16:9 (see `ShapedVideo`): null stretches it, as every Frigate camera does. */
    val videoFit: VideoFit? = null,
    /** Which way up to draw the picture: upright for a camera mounted level. */
    val orientation: CameraOrientation = CameraOrientation.UPRIGHT,
)

/** Whether these camera names, in this order, are the positions [layouts] has saved for them. */
private fun List<String>.isSavedIn(layouts: Map<String, CameraLayout>): Boolean =
    withIndex().all { (position, name) -> layouts[name]?.position == position }

/**
 * [cameras] (one list's) in the order the Home page shows them: Arrange mode's [draft] while one
 * is under way, otherwise each camera's saved position, and those never arranged after the rest,
 * as they came.
 */
internal fun arrangedCameras(cameras: List<Camera>, layouts: Map<String, CameraLayout>, draft: List<String>?): List<Camera> {
    val rank: (Camera) -> Int? = if (draft != null) {
        { camera -> draft.indexOf(camera.name).takeIf { it >= 0 } }
    } else {
        { camera -> layouts[camera.name]?.position }
    }
    return cameras.sortedWith(compareBy(nullsLast()) { camera: Camera -> rank(camera) })
}

/**
 * One parked vehicle on the "In view now" strip: what it is, how its card reads, and the crop of
 * its most recent sighting. [thumbnailUrl] is null only while disconnected.
 */
@Immutable
data class InViewItem(
    val subject: StationaryObject,
    val presentation: StationaryObjectPresentation,
    val thumbnailUrl: String?,
)

@OptIn(ExperimentalTime::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HomeViewModel(
    observeCamerasUseCase: ObserveCamerasUseCase,
    observeNestCamerasUseCase: ObserveNestCamerasUseCase,
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    observePlaybackPreferencesUseCase: ObservePlaybackPreferencesUseCase,
    private val getLiveStreamUrlUseCase: GetLiveStreamUrlUseCase,
    private val getLiveWebRtcSignalingUrlUseCase: GetLiveWebRtcSignalingUrlUseCase,
    private val getCameraSnapshotUrlUseCase: GetCameraSnapshotUrlUseCase,
    private val getEventThumbnailUrlUseCase: GetEventThumbnailUrlUseCase,
    observeHouseholdPresenceUseCase: ObserveHouseholdPresenceUseCase,
    observeDoorsUseCase: ObserveDoorsUseCase,
    private val refreshDoorsUseCase: RefreshDoorsUseCase,
    observeStationaryObjectsUseCase: ObserveStationaryObjectsUseCase,
    private val setAwayUseCase: SetAwayUseCase,
    private val reconnectToServerUseCase: ReconnectToServerUseCase,
    private val refreshHouseholdPresenceUseCase: RefreshHouseholdPresenceUseCase,
    private val refreshStationaryObjectsUseCase: RefreshStationaryObjectsUseCase,
    private val refreshNestCamerasUseCase: RefreshNestCamerasUseCase,
    observeCameraLayoutsUseCase: ObserveCameraLayoutsUseCase,
    private val saveCameraOrderUseCase: SaveCameraOrderUseCase,
    private val setCameraOrientationUseCase: SetCameraOrientationUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)

    /** The pull's work, which can outlast the band (see [refresh]); what stops two running at once. */
    private var refreshWork: Job? = null

    /** True while a pull to refresh is running — what keeps the band open over the list. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _reconnectRequests = MutableStateFlow(0)

    /**
     * Goes up by one on every pull to refresh; each camera card hands it to its player, which
     * takes the change as a request to reconnect if its picture isn't moving (see
     * `CameraStreamPlayer`'s `reconnectRequests`). A count rather than an event so a card
     * composed later can tell an old request from a new one.
     */
    val reconnectRequests: StateFlow<Int> = _reconnectRequests.asStateFlow()

    /**
     * The household's cars standing in view of a camera right now — "Sarah's Tesla · Driveway ·
     * since 8:12 AM" — so opening the app answers whether a car is home without reading the feed
     * for it. Opens on what the device last knew and is corrected by the first poll, so it reads
     * the same whether the server has answered yet or not. Empty while signed out, and empty when
     * none of them is parked anywhere: the strip is then not drawn at all rather than announcing
     * that the driveway is empty.
     *
     * Collecting it is what starts the poll behind it, so it runs only while the Home tab is up.
     */
    val inView: StateFlow<List<InViewItem>> = combine(
        observeStationaryObjectsUseCase(),
        observeCurrentServerUrlUseCase(),
    ) { subjects, serverUrl ->
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        subjects.map { subject ->
            // Built here, not in the card, so a LAN/Tailscale route flip re-points every thumbnail at once.
            InViewItem(
                subject = subject,
                presentation = subject.present(today),
                thumbnailUrl = serverUrl?.let { getEventThumbnailUrlUseCase(it, subject.thumbnailEventId) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Away mode banner: true while the relay says nobody is home. Collecting it keeps presence polled. */
    val everyoneAway: StateFlow<Boolean> = observeHouseholdPresenceUseCase()
        .map { it.everyoneAway }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The door sensors, for the strip under the away banner: each door and whether it's open.
     * Empty with no sensors (or a relay from before them), and the strip is then not drawn.
     * Collecting it keeps the doors polled while the Home tab is up.
     */
    val doors: StateFlow<List<Door>> = observeDoorsUseCase()
        .map { it.doors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The page's pull to refresh. Every camera without moving video is told to reconnect now
     * rather than when its back-off says ([reconnectRequests]), and everything the page reads is
     * asked for again: the route, the session and the camera list ([ReconnectToServerUseCase]),
     * the indoor cameras, who is home, and the cars in view.
     *
     * The band stays up while that runs, for at least [MIN_REFRESH_MS] (one sweep of its scan, so
     * a fast answer still reads as one) and at most [MAX_REFRESH_MS]: a server that isn't
     * answering shouldn't hold it open through every timeout on the way, so past that it closes
     * and whatever is still running carries on behind it. Until that work has finished, another
     * pull is ignored — the band closing is not the refresh finishing.
     */
    fun refresh() {
        if (_refreshing.value || refreshWork?.isActive == true) return
        _refreshing.value = true
        _reconnectRequests.update { it + 1 }
        val work = viewModelScope.launch {
            // The route first: presence and the strip's poll both read the address in use when
            // they start, and after leaving the house that is a dead LAN address until this moves it.
            reconnectToServerUseCase()
            coroutineScope {
                launch { refreshHouseholdPresenceUseCase() }
                launch { refreshNestCamerasUseCase() }
                launch { refreshDoorsUseCase() }
                refreshStationaryObjectsUseCase()
            }
        }
        refreshWork = work
        viewModelScope.launch {
            try {
                coroutineScope {
                    launch { delay(MIN_REFRESH_MS) }
                    withTimeoutOrNull(MAX_REFRESH_MS) { work.join() }
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** "I'm back": marks this phone home, which ends away mode for the household. Failures leave the banner up. */
    fun markBack() {
        viewModelScope.launch { setAwayUseCase(false) }
    }

    /** Arrange mode's moves not yet saved, by list: the order being dragged into. */
    private val orderDrafts = MutableStateFlow<Map<CameraPlacement, List<String>>>(emptyMap())

    /** Turns and tilts under way and not yet saved, by camera, so the slider moves the picture under the finger. */
    private val orientationDrafts = MutableStateFlow<Map<String, CameraOrientation>>(emptyMap())

    /**
     * The saved layouts as they are read back. A draft (above) is dropped only once these show it
     * saved, never merely when its write returns: the write landing and the read coming back are
     * not the same moment, and dropping it in between would flick the card or picture back to how
     * it was. A draft that has moved on since (a newer drag or tilt) is kept.
     */
    private val savedLayouts: StateFlow<Map<String, CameraLayout>> = observeCameraLayoutsUseCase()
        .onEach { layouts ->
            orderDrafts.update { drafts -> drafts.filterValues { order -> !order.isSavedIn(layouts) } }
            orientationDrafts.update { drafts -> drafts.filter { (name, orientation) -> layouts[name]?.orientation != orientation } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The saved layouts with Arrange mode's unsaved changes over them. Opens empty rather than holding the cameras back. */
    private val arrangement: StateFlow<Arrangement> = combine(
        savedLayouts,
        orderDrafts,
        orientationDrafts,
        ::Arrangement,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Arrangement(emptyMap(), emptyMap(), emptyMap()))

    /** How the Nest cameras' pictures sit in their cards: the saved choice, whole until it is read. */
    private val nestFit: Flow<VideoFit> = observePlaybackPreferencesUseCase().map { it.nestFit }.onStart { emit(VideoFit.Fit) }.distinctUntilChanged()

    /**
     * The Nest cameras, null until the server has said which there are (see
     * [ObserveNestCamerasUseCase]). A Nest stream that a Frigate camera already plays is that
     * camera, and is left out here. Shared, so the two lists built from it ask the server once.
     */
    private val nestCameras: StateFlow<List<Camera>?> = combine(
        observeNestCamerasUseCase(),
        observeCamerasUseCase(),
    ) { nest, frigate ->
        val frigateStreams = frigate.flatMapTo(HashSet()) { listOf(it.name, it.liveStreamName, it.gridStreamName) }
        nest?.filterNot { it.name in frigateStreams }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The outdoor cameras: Frigate's, then any Nest camera outside (a doorbell). Null until the
     * first read of the local camera cache lands (a few milliseconds), so the screen can tell
     * "not loaded yet" from "this server has no cameras" and never flashes the empty-state
     * message on the way in. It doesn't wait for the Nest cameras: they join when the server
     * answers. URLs are built here, not in the card, so a LAN/Tailscale route flip re-points
     * every player at once.
     */
    val cameras: StateFlow<List<CameraTile>?> = combine(
        observeCamerasUseCase(),
        nestCameras,
        observeCurrentServerUrlUseCase(),
        nestFit,
        arrangement,
    ) { frigate, nest, serverUrl, fit, arrangement ->
        val outdoor = frigate + nest.orEmpty().filter { it.placement == CameraPlacement.Outdoor }
        arrangement.arranged(outdoor, CameraPlacement.Outdoor).map { camera -> tile(camera, serverUrl, fit, arrangement) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _placement = MutableStateFlow(CameraPlacement.Outdoor)

    /**
     * Which cameras the page is showing: the outdoor ones until the viewer switches.
     * Kept here rather than in the screen so it lasts while the page is away (a camera opened
     * from it, another tab) but not past the app's process: every launch opens outdoors.
     */
    val placement: StateFlow<CameraPlacement> = _placement.asStateFlow()

    fun showPlacement(placement: CameraPlacement) {
        _placement.value = placement
    }

    /** The indoor (Nest) cameras' cards, null until the server has said which there are. */
    val indoorCameras: StateFlow<List<CameraTile>?> = combine(
        nestCameras,
        observeCurrentServerUrlUseCase(),
        nestFit,
        arrangement,
    ) { nest, serverUrl, fit, arrangement ->
        nest?.filter { it.placement == CameraPlacement.Indoor }
            ?.let { arrangement.arranged(it, CameraPlacement.Indoor) }
            ?.map { camera -> tile(camera, serverUrl, fit, arrangement) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _arranging = MutableStateFlow(false)

    /**
     * Arrange mode: the cards can be dragged into a new order and turned the right way up. Saved
     * as it goes (each drop, each turn, each tilt let go), so leaving it any way loses nothing.
     */
    val arranging: StateFlow<Boolean> = _arranging.asStateFlow()

    fun startArranging() {
        _arranging.value = true
    }

    fun finishArranging() {
        _arranging.value = false
        orderDrafts.value.keys.forEach(::saveOrder)
    }

    /** Arrange mode's drag: [from] takes [to]'s place in [placement]'s list, the cards between shifting up or down one. */
    fun moveCamera(placement: CameraPlacement, from: String, to: String) {
        val current = orderDrafts.value[placement] ?: shownNames(placement) ?: return
        val fromIndex = current.indexOf(from)
        val toIndex = current.indexOf(to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        val moved = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        orderDrafts.update { it + (placement to moved) }
    }

    /** A drag let go: [placement]'s list is saved in the order it now has. */
    fun saveOrder(placement: CameraPlacement) {
        val order = orderDrafts.value[placement] ?: return
        // The draft stays until the saved order is read back (see savedLayouts).
        viewModelScope.launch { saveCameraOrderUseCase(order) }
    }

    /** A quarter turn of [cameraName]'s picture, saved at once. */
    fun turnCamera(cameraName: String, clockwise: Boolean) {
        saveOrientation(cameraName, arrangement.value.orientation(cameraName).turned(clockwise))
    }

    /** The tilt slider moving: shown at once, saved when it is let go ([saveTilt]). */
    fun tiltCamera(cameraName: String, degrees: Float) {
        orientationDrafts.update { it + (cameraName to arrangement.value.orientation(cameraName).tilted(degrees)) }
    }

    fun saveTilt(cameraName: String) {
        orientationDrafts.value[cameraName]?.let { saveOrientation(cameraName, it) }
    }

    private fun saveOrientation(cameraName: String, orientation: CameraOrientation) {
        orientationDrafts.update { it + (cameraName to orientation) }
        // The draft stays until the saved turn is read back (see savedLayouts).
        viewModelScope.launch { setCameraOrientationUseCase(cameraName, orientation) }
    }

    private fun shownNames(placement: CameraPlacement): List<String>? =
        (if (placement == CameraPlacement.Indoor) indoorCameras.value else cameras.value)?.map { it.camera.name }

    /** The saved layouts, and Arrange mode's changes not saved yet over them. */
    private data class Arrangement(
        val layouts: Map<String, CameraLayout>,
        val orders: Map<CameraPlacement, List<String>>,
        val orientations: Map<String, CameraOrientation>,
    ) {
        fun arranged(cameras: List<Camera>, placement: CameraPlacement): List<Camera> = arrangedCameras(cameras, layouts, orders[placement])

        fun orientation(cameraName: String): CameraOrientation =
            orientations[cameraName] ?: layouts[cameraName]?.orientation ?: CameraOrientation.UPRIGHT
    }

    private fun tile(camera: Camera, serverUrl: String?, nestFit: VideoFit, arrangement: Arrangement): CameraTile {
        val videoFit = nestFit.takeIf { camera.source == CameraSource.Nest }
        val orientation = arrangement.orientation(camera.name)
        if (serverUrl == null || !camera.enabled) {
            return CameraTile(camera, streamUrl = null, posterUrl = null, videoFit = videoFit, orientation = orientation)
        }
        // The grid uses the camera's (possibly lower-quality) grid stream — full quality is
        // reserved for the single-camera detail view.
        return CameraTile(
            camera = camera,
            streamUrl = getLiveStreamUrlUseCase(serverUrl, camera.gridStreamName),
            posterUrl = getCameraSnapshotUrlUseCase(serverUrl, camera.name, height = GRID_POSTER_HEIGHT)
                .takeIf { camera.source == CameraSource.Frigate },
            webRtcSignalingUrl = getLiveWebRtcSignalingUrlUseCase(serverUrl, camera.gridStreamName),
            videoFit = videoFit,
            orientation = orientation,
        )
    }

    private companion object {
        /** Server-side downscale for grid posters: plenty for a card, ~30 KB per refresh instead of a full detect frame. */
        const val GRID_POSTER_HEIGHT = 480

        /** The shortest a pull to refresh holds the band open: one sweep of its scan, as on the Moments tab. */
        const val MIN_REFRESH_MS = 900L

        /** The longest: about one request's timeout, after which the band closes on whatever has landed. */
        const val MAX_REFRESH_MS = 8_000L
    }
}
