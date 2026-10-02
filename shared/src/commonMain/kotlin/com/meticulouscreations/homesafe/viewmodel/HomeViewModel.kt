package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.HomeStatus
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.StationaryObjectPresentation
import com.meticulouscreations.homesafe.domain.model.homeStatus
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetEventThumbnailUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveWebRtcSignalingUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveLatestMomentUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveStationaryObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ReconnectToServerUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshStationaryObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetAwayUseCase
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
 */
@Immutable
data class CameraTile(
    val camera: Camera,
    val streamUrl: String?,
    val posterUrl: String?,
    /** Where the card's player can negotiate WebRTC for the same stream; null wherever [streamUrl] is. */
    val webRtcSignalingUrl: String? = null,
)

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
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    private val getLiveStreamUrlUseCase: GetLiveStreamUrlUseCase,
    private val getLiveWebRtcSignalingUrlUseCase: GetLiveWebRtcSignalingUrlUseCase,
    private val getCameraSnapshotUrlUseCase: GetCameraSnapshotUrlUseCase,
    private val getEventThumbnailUrlUseCase: GetEventThumbnailUrlUseCase,
    observeHouseholdPresenceUseCase: ObserveHouseholdPresenceUseCase,
    observeStationaryObjectsUseCase: ObserveStationaryObjectsUseCase,
    observeLatestMomentUseCase: ObserveLatestMomentUseCase,
    private val setAwayUseCase: SetAwayUseCase,
    private val reconnectToServerUseCase: ReconnectToServerUseCase,
    private val refreshHouseholdPresenceUseCase: RefreshHouseholdPresenceUseCase,
    private val refreshStationaryObjectsUseCase: RefreshStationaryObjectsUseCase,
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
     * The page's pull to refresh. Every camera without moving video is told to reconnect now
     * rather than when its back-off says ([reconnectRequests]), and everything the page reads is
     * asked for again: the route, the session and the camera list ([ReconnectToServerUseCase]),
     * who is home, and the cars in view — whose poll also brings the summary's latest moment.
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

    /**
     * Null until the first read of the local camera cache lands (a few milliseconds), so the
     * screen can tell "not loaded yet" from "this server has no cameras" and never flashes the
     * empty-state message on the way in. URLs are built here, not in the card, so a LAN/Tailscale
     * route flip re-points every player at once.
     */
    val cameras: StateFlow<List<CameraTile>?> = combine(
        observeCamerasUseCase(),
        observeCurrentServerUrlUseCase(),
    ) { cameras, serverUrl ->
        cameras.map { camera -> tile(camera, serverUrl) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The two lines at the top of the page — what last happened, how many cameras are on, who is
     * home — see [homeStatus]. Each part is left out until what it is built from has loaded, and
     * the whole is recomputed every [STATUS_TICK_MS] as well as on every change, so "3 min ago"
     * keeps counting and a detection settles into "All quiet since …" on its own.
     */
    val status: StateFlow<HomeStatus> = combine(
        // Null until the first answer, then a list of zero or one: "not loaded yet" and "nothing
        // has ever happened" read differently at the top of the page.
        observeLatestMomentUseCase().map { listOfNotNull(it) }.onStart<List<MomentEvent>?> { emit(null) },
        cameras,
        observeHouseholdPresenceUseCase(),
        statusTicks(),
    ) { latest, tiles, presence, _ ->
        val now = clock.now()
        homeStatus(
            latestMoment = latest?.firstOrNull(),
            momentsLoaded = latest != null,
            cameras = tiles?.map { it.camera },
            presence = presence,
            nowEpochSeconds = now.toEpochMilliseconds() / 1000.0,
            today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStatus(headline = null, details = null))

    private fun statusTicks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(STATUS_TICK_MS)
        }
    }

    private fun tile(camera: Camera, serverUrl: String?): CameraTile {
        if (serverUrl == null || !camera.enabled) return CameraTile(camera, streamUrl = null, posterUrl = null)
        // The grid uses the camera's (possibly lower-quality) grid stream — full quality is
        // reserved for the single-camera detail view.
        return CameraTile(
            camera = camera,
            streamUrl = getLiveStreamUrlUseCase(serverUrl, camera.gridStreamName),
            posterUrl = getCameraSnapshotUrlUseCase(serverUrl, camera.name, height = GRID_POSTER_HEIGHT),
            webRtcSignalingUrl = getLiveWebRtcSignalingUrlUseCase(serverUrl, camera.gridStreamName),
        )
    }

    private companion object {
        /** Server-side downscale for grid posters: plenty for a card, ~30 KB per refresh instead of a full detect frame. */
        const val GRID_POSTER_HEIGHT = 480

        /** How often the summary's relative times are recomputed: "3 min ago" never has to be more precise than this. */
        const val STATUS_TICK_MS = 30_000L

        /** The shortest a pull to refresh holds the band open: one sweep of its scan, as on the Moments tab. */
        const val MIN_REFRESH_MS = 900L

        /** The longest: about one request's timeout, after which the band closes on whatever has landed. */
        const val MAX_REFRESH_MS = 8_000L
    }
}
