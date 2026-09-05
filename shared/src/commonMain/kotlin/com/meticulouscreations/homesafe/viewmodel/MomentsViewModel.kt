package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentPresentation
import com.meticulouscreations.homesafe.domain.model.downloadFileName
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import com.meticulouscreations.homesafe.domain.usecase.DownloadMomentClipUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetMomentClipStreamUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMomentsUseCase
import com.meticulouscreations.homesafe.network.frigateEventThumbnailUrl
import com.meticulouscreations.homesafe.network.frigateRecordingSnapshotUrl
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.VideoSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** A detection plus everything the card needs to draw it. [thumbnailUrl] is null only while disconnected. */
@Immutable
data class MomentItem(val event: MomentEvent, val presentation: MomentPresentation, val thumbnailUrl: String?)

/** A date header and the cards beneath it, in feed order. */
@Immutable
data class MomentGroup(val dateGroup: String, val dateSubLabel: String, val items: List<MomentItem>)

@Immutable
data class MomentsUiState(
    val groups: List<MomentGroup> = emptyList(),
    val selectedCategory: MomentCategory = MomentCategory.ALL,
    val error: String? = null,
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

class MomentsViewModel(
    observeMomentsUseCase: ObserveMomentsUseCase,
    private val getMomentClipStreamUseCase: GetMomentClipStreamUseCase,
    private val downloadMomentClipUseCase: DownloadMomentClipUseCase,
    momentsRepository: MomentsRepository,
    private val connectionRepository: ConnectionRepository,
    private val today: () -> LocalDate = ::localToday,
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow(MomentCategory.ALL)
    private val _clip = MutableStateFlow(ClipState())
    private var clipJob: Job? = null

    private val _downloadState = MutableStateFlow(DownloadUiState())
    val downloadState: StateFlow<DownloadUiState> = _downloadState.asStateFlow()
    private var downloadJob: Job? = null

    val uiState: StateFlow<MomentsUiState> = combine(
        observeMomentsUseCase(),
        _selectedCategory,
        momentsRepository.observeError(),
        _clip,
        connectionRepository.currentServerUrl,
    ) { events, category, error, clip, serverUrl ->
        val day = today()
        val items = events
            .filter { category == MomentCategory.ALL || it.category == category }
            .map { event ->
                // Built here, not in the card, so a LAN/Tailscale route flip re-points every thumbnail at once.
                // Deliberately NOT gated on hasSnapshot: that flag is about the full-frame snapshot.jpg, which
                // needs `snapshots: enabled` in Frigate's config. The object thumbnail is served for every
                // tracked object regardless (verified: has_snapshot=false yet thumbnail.jpg -> 200), so
                // gating on it would hide thumbnails for every real detection on a server without snapshots.
                val thumb = serverUrl?.let { frigateEventThumbnailUrl(it, event.id) }
                MomentItem(event, event.present(day), thumb)
            }
        // groupBy preserves encounter order, and the feed arrives newest-first, so "Today" leads.
        val groups = items
            .groupBy { it.presentation.dateGroup to it.presentation.dateSubLabel }
            .map { (key, group) -> MomentGroup(key.first, key.second, group) }
        MomentsUiState(
            groups = groups,
            selectedCategory = category,
            error = error,
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

    /** Tapping the open card closes it; tapping another swaps to it. Only events with a clip open. */
    fun toggleExpanded(event: MomentEvent) {
        if (_clip.value.eventId == event.id || !event.hasClip) {
            collapse()
            return
        }
        clipJob?.cancel()
        // The poster is known from the event alone, so it goes up before the clip URL is resolved
        // and stays as the player's own poster until the clip's first frame paints over it.
        val posterUrl = connectionRepository.currentServerUrl.value?.let {
            frigateRecordingSnapshotUrl(it, event.cameraName, event.startEpochSeconds, height = CLIP_POSTER_HEIGHT)
        }
        _clip.value = ClipState(eventId = event.id, posterUrl = posterUrl)
        clipJob = viewModelScope.launch {
            runCatching { getMomentClipStreamUseCase(event.id) }
                .onSuccess { stream ->
                    _clip.update {
                        if (it.eventId != event.id) it
                        // buffering=true up front: the player shows only its poster until the first
                        // frame, so the UI must assume loading until it hears otherwise.
                        else it.copy(
                            request = PlayerRequest(VideoSource.Recording(stream.url, stream.headers, startPositionMs = 0, posterUrl = posterUrl)),
                            buffering = true,
                        )
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

@OptIn(ExperimentalTime::class)
private fun localToday(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
