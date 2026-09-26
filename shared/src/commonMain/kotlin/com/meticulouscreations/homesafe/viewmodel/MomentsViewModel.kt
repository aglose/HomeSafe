package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentPresentation
import com.meticulouscreations.homesafe.domain.model.VisitKind
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.domain.model.downloadFileName
import com.meticulouscreations.homesafe.domain.model.endOfDayEpochSeconds
import com.meticulouscreations.homesafe.domain.model.groupIntoVisits
import com.meticulouscreations.homesafe.domain.model.isGenericCar
import com.meticulouscreations.homesafe.domain.model.present
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
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.VideoSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A feed entry plus everything the card needs to draw it. [thumbnailUrl] is null only while disconnected.
 *
 * For one detection that is all there is. For several folded into a visit or a household car's
 * routine stretch ([kind]), [event] is the one a tap plays and whose thumbnail stands for the
 * lot, [presentation] reads for the whole entry, and [clips] lists every detection in it, oldest
 * first, for the card to open onto.
 */
@Immutable
data class MomentItem(
    val event: MomentEvent,
    val presentation: MomentPresentation,
    val thumbnailUrl: String?,
    /** What the list keys the entry on: stays put while a visit grows newer clips. */
    val key: String = event.id,
    val kind: VisitKind = VisitKind.SINGLE,
    val clips: List<MomentClip> = emptyList(),
    /** [event] is a car the classifier left unnamed, and nothing else in the entry was named: it can be tagged as a known car. */
    val canTagCar: Boolean = false,
)

/** One detection inside a folded entry, as its row in the opened list reads. */
@Immutable
data class MomentClip(
    val event: MomentEvent,
    /** "6:55 PM" */
    val timeLabel: String,
    /** "Person on the front lawn" */
    val title: String,
    /** "0:12", or null while still in progress. */
    val durationLabel: String?,
    /** A car the classifier left unnamed, which can be tagged as a known car. */
    val canTagCar: Boolean = false,
)

/** A date header and the cards beneath it, in feed order. */
@Immutable
data class MomentGroup(val dateGroup: String, val dateSubLabel: String, val items: List<MomentItem>)

/** A camera the feed can be narrowed to: Frigate's key for it, and what the UI calls it. */
@Immutable
data class MomentCameraOption(val name: String, val displayName: String)

@Immutable
data class MomentsUiState(
    val groups: List<MomentGroup> = emptyList(),
    val selectedCategory: MomentCategory = MomentCategory.ALL,
    /**
     * Hides what Frigate put a name to — a recognised face, one of the household's cars — so
     * what is left is the people and vehicles nobody here knows. Combines with [selectedCategory].
     */
    val unfamiliarOnly: Boolean = false,
    /** Every camera on the server, in the server's order, for the camera filter. */
    val cameras: List<MomentCameraOption> = emptyList(),
    /** The camera the feed is narrowed to; null shows every camera. */
    val selectedCamera: MomentCameraOption? = null,
    val error: String? = null,
    /**
     * The day the feed was opened at, when it was: it shows that day and earlier, newest first.
     * Null is the live feed.
     */
    val historyDay: LocalDate? = null,
    /** The server has older detections below the last loaded; the feed can ask for the next page. */
    val hasOlder: Boolean = false,
    /** The next page down is on its way. */
    val loadingOlder: Boolean = false,
    /** The card currently opened to play its clip, if any. */
    val expandedEventId: String? = null,
    /** The clip player request for [expandedEventId]; null while it's being resolved. */
    val clipRequest: PlayerRequest? = null,
    /** A frame from the recording at the event's start: on screen the instant the card opens, before the clip URL is even resolved. */
    val clipPosterUrl: String? = null,
    val clipError: String? = null,
    /** True from the moment a clip is requested until the player reports it is no longer buffering. */
    val clipBuffering: Boolean = false,
)

/** The download state of, at most, one card at a time — a card's icon reads this by matching [eventId]. */
@Immutable
data class DownloadUiState(
    val downloadingEventId: String? = null,
    /** Set once [downloadingEventId]'s attempt finishes; null error means success. Cleared again shortly after. */
    val resultEventId: String? = null,
    val resultError: String? = null,
)

@OptIn(ExperimentalTime::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MomentsViewModel(
    observeMomentsUseCase: ObserveMomentsUseCase,
    observeMomentsErrorUseCase: ObserveMomentsErrorUseCase,
    observeMomentsPagingUseCase: ObserveMomentsPagingUseCase,
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    observeCamerasUseCase: ObserveCamerasUseCase,
    private val loadOlderMomentsUseCase: LoadOlderMomentsUseCase,
    private val showMomentsBeforeUseCase: ShowMomentsBeforeUseCase,
    private val showMomentsFromCameraUseCase: ShowMomentsFromCameraUseCase,
    private val getMomentClipStreamUseCase: GetMomentClipStreamUseCase,
    private val downloadMomentClipUseCase: DownloadMomentClipUseCase,
    private val getEventThumbnailUrlUseCase: GetEventThumbnailUrlUseCase,
    private val getRecordingSnapshotUrlUseCase: GetRecordingSnapshotUrlUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val serverUrl: StateFlow<String?> = observeCurrentServerUrlUseCase()

    private val _selectedCategory = MutableStateFlow(MomentCategory.ALL)
    private val _unfamiliarOnly = MutableStateFlow(false)
    private val _selectedCameraName = MutableStateFlow<String?>(null)
    private val _historyDay = MutableStateFlow<LocalDate?>(null)
    private val _clip = MutableStateFlow(ClipState())
    private var clipJob: Job? = null

    private val _downloadState = MutableStateFlow(DownloadUiState())
    val downloadState: StateFlow<DownloadUiState> = _downloadState.asStateFlow()
    private var downloadJob: Job? = null

    /**
     * What the feed is narrowed to. A picked camera the server no longer lists (a switch to
     * another server, a camera removed from the config) reads as every camera, rather than a
     * filter nobody can see the option for; while the list is still loading it is kept.
     */
    private val filters: StateFlow<Filters> = combine(
        _selectedCategory,
        _unfamiliarOnly,
        _selectedCameraName,
        observeCamerasUseCase(),
    ) { category, unfamiliarOnly, cameraName, cameras ->
        val options = cameras.map { MomentCameraOption(it.name, it.displayName) }
        val camera = cameraName?.let { name ->
            options.firstOrNull { it.name == name } ?: MomentCameraOption(name, cameraDisplayName(name)).takeIf { options.isEmpty() }
        }
        Filters(category, unfamiliarOnly, options, camera)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Filters(MomentCategory.ALL, false, emptyList(), null))

    init {
        // The server does the narrowing (a quiet camera's moments would otherwise be pages deep
        // beneath a busy one's), so the repository follows the camera in force for as long as
        // this view model lives — including when a vanished camera falls back to every camera,
        // and on creation, where it undoes a narrowing a previous feed left behind.
        viewModelScope.launch {
            filters.map { it.camera?.name }.distinctUntilChanged().collect { showMomentsFromCameraUseCase(it) }
        }
        // A new camera is a new feed: the open clip's card is about to vanish with the old one.
        viewModelScope.launch {
            filters.map { it.camera?.name }.distinctUntilChanged().drop(1).collect { collapse() }
        }
    }

    /**
     * The cards: the feed filtered, folded into visits, presented and grouped by day. Everything
     * that isn't about the open clip.
     */
    private val feed: Flow<MomentsUiState> = combine(
        observeMomentsUseCase(),
        filters,
        observeMomentsErrorUseCase(),
        observeMomentsPagingUseCase(),
        serverUrl,
    ) { events, filters, error, paging, serverUrl ->
        val day = today()
        val category = filters.category
        val cameraName = filters.camera?.name
        val items = events
            .filter { category == MomentCategory.ALL || it.category == category }
            // The server already narrowed the feed; this only hides the old camera's cards in the
            // moment between a pick and the repository starting over.
            .filter { cameraName == null || it.cameraName == cameraName }
            // Folded after the filters, so a visit's gaps are judged between the detections on
            // screen, and before "unfamiliar only", which judges the visit as a whole.
            .groupIntoVisits()
            .filter { !filters.unfamiliarOnly || !it.isFamiliar }
            .map { visit ->
                val lead = visit.lead
                // Built here, not in the card, so a LAN/Tailscale route flip re-points every thumbnail at once.
                // Deliberately NOT gated on hasSnapshot: that flag is about the full-frame snapshot.jpg, which
                // needs `snapshots: enabled` in Frigate's config. The object thumbnail is served for every
                // tracked object regardless (verified: has_snapshot=false yet thumbnail.jpg -> 200), so
                // gating on it would hide thumbnails for every real detection on a server without snapshots.
                val thumb = serverUrl?.let { getEventThumbnailUrlUseCase(it, lead.id) }
                val clips = if (visit.kind == VisitKind.SINGLE) {
                    emptyList()
                } else {
                    visit.events.map { event ->
                        val p = event.present(day)
                        MomentClip(event, p.timeLabel, p.title, p.durationLabel, canTagCar = event.isGenericCar)
                    }
                }
                // A household car's routine is named by definition; a visit with a name in it reads as that name.
                val canTagCar = visit.kind != VisitKind.ROUTINE && !visit.isFamiliar && lead.isGenericCar
                MomentItem(lead, visit.present(day), thumb, key = visit.key, kind = visit.kind, clips = clips, canTagCar = canTagCar)
            }
        // groupBy preserves encounter order, and the feed arrives newest-first, so "Today" leads.
        val groups = items
            .groupBy { it.presentation.dateGroup to it.presentation.dateSubLabel }
            .map { (key, group) -> MomentGroup(key.first, key.second, group) }
        MomentsUiState(
            groups = groups,
            selectedCategory = category,
            unfamiliarOnly = filters.unfamiliarOnly,
            cameras = filters.cameras,
            selectedCamera = filters.camera,
            error = error,
            hasOlder = paging.hasOlder,
            loadingOlder = paging.loadingOlder,
        )
    }

    val uiState: StateFlow<MomentsUiState> = combine(feed, _historyDay, _clip) { feed, historyDay, clip ->
        feed.copy(
            historyDay = historyDay,
            expandedEventId = clip.eventId,
            clipRequest = clip.request,
            clipPosterUrl = clip.posterUrl,
            clipError = clip.error,
            clipBuffering = clip.buffering,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MomentsUiState())

    fun selectCategory(category: MomentCategory) {
        _selectedCategory.value = category
    }

    /** Shows only what Frigate couldn't put a name to (see [MomentsUiState.unfamiliarOnly]), or everything again. */
    fun setUnfamiliarOnly(unfamiliarOnly: Boolean) {
        _unfamiliarOnly.value = unfamiliarOnly
    }

    /** Narrows the feed to one camera, by its Frigate name (the server is asked for just its moments); null shows every camera again. */
    fun selectCamera(cameraName: String?) {
        _selectedCameraName.value = cameraName
    }

    /**
     * Opens the feed at the end of [day] — that day and earlier, newest first — or, with null,
     * back at now. The open clip closes with the feed it was in: the card it belonged to is
     * about to vanish, and a player under a list that just changed underneath it is a confusing thing.
     */
    fun showDay(day: LocalDate?) {
        if (_historyDay.value == day) return
        collapse()
        _historyDay.value = day
        showMomentsBeforeUseCase(day?.endOfDayEpochSeconds(TimeZone.currentSystemDefault()))
    }

    /** Asks for the next page down. Safe to call freely: the repository ignores it while one is in flight or nothing is left. */
    fun loadOlder() {
        viewModelScope.launch { loadOlderMomentsUseCase() }
    }

    /** Tapping the open card closes it; tapping another swaps to it. Only events with a clip open. */
    fun toggleExpanded(event: MomentEvent) {
        if (_clip.value.eventId == event.id || !event.hasClip) {
            collapse()
            return
        }
        clipJob?.cancel()
        // The poster is known from the event alone, so it goes up before the clip URL is resolved
        // and stays as the player's own poster until the clip's first frame paints over it.
        val posterUrl = serverUrl.value?.let {
            getRecordingSnapshotUrlUseCase(it, event.cameraName, event.startEpochSeconds, height = CLIP_POSTER_HEIGHT)
        }
        _clip.value = ClipState(eventId = event.id, posterUrl = posterUrl)
        clipJob = viewModelScope.launch {
            runCatching { getMomentClipStreamUseCase(event.id) }
                .onSuccess { stream ->
                    _clip.update {
                        if (it.eventId != event.id) {
                            it
                        } // buffering=true up front: the player shows only its poster until the first
                        // frame, so the UI must assume loading until it hears otherwise.
                        else {
                            it.copy(
                                request = PlayerRequest(VideoSource.Recording(stream.url, stream.headers, startPositionMs = 0, posterUrl = posterUrl)),
                                buffering = true,
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _clip.update { if (it.eventId != event.id) it else it.copy(error = e.message ?: "Couldn't load clip") }
                }
        }
    }

    fun collapse() {
        clipJob?.cancel()
        _clip.value = ClipState()
    }

    fun onClipBuffering(isBuffering: Boolean) {
        _clip.update { if (it.request != null) it.copy(buffering = isBuffering) else it }
    }

    /** The player gave up on the clip (bad status, expired session, purged event): say so instead of a blank box. */
    fun onClipPlaybackError() {
        _clip.update { if (it.eventId != null) it.copy(error = "Couldn't play this clip", buffering = false) else it }
    }

    /** Downloads one event's clip at a time; a tap on another card's download button cancels an in-flight one. */
    fun downloadClip(event: MomentEvent) {
        if (!event.hasClip || _downloadState.value.downloadingEventId == event.id) return
        downloadJob?.cancel()
        _downloadState.value = DownloadUiState(downloadingEventId = event.id)
        downloadJob = viewModelScope.launch {
            val result = downloadMomentClipUseCase(event.id, event.downloadFileName())
            _downloadState.value = DownloadUiState(resultEventId = event.id, resultError = result.exceptionOrNull()?.message)
            delay(RESULT_FLASH_MS)
            // Only clear if nothing newer has started/finished in the meantime.
            _downloadState.update { if (it.resultEventId == event.id) DownloadUiState() else it }
        }
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private data class Filters(val category: MomentCategory, val unfamiliarOnly: Boolean, val cameras: List<MomentCameraOption>, val camera: MomentCameraOption?)

    private data class ClipState(
        val eventId: String? = null,
        val request: PlayerRequest? = null,
        val posterUrl: String? = null,
        val error: String? = null,
        val buffering: Boolean = false,
    )

    private companion object {
        const val RESULT_FLASH_MS = 2_500L

        /** The clip box is full-width 16:9; a 480-tall frame is sharp enough and ~30 KB. */
        const val CLIP_POSTER_HEIGHT = 480
    }
}
