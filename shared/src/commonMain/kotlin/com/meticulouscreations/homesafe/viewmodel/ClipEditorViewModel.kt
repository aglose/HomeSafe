package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ClipRange
import com.meticulouscreations.homesafe.domain.model.ClipWindow
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.RecordingPlaylist
import com.meticulouscreations.homesafe.domain.model.clipRangeForMoment
import com.meticulouscreations.homesafe.domain.model.initialClipRange
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.model.withLength
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingHistoryUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingStreamUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCameraMomentsBetweenUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveRecordingClipUseCase
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.SeekCommand
import com.meticulouscreations.homesafe.ui.components.VideoSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The two ends of the selection a finger can hold. */
enum class TrimHandle { START, END }

/** Where saving the selected clip has got to. */
@Immutable
sealed interface ClipSaveState {
    data object Idle : ClipSaveState
    data object Saving : ClipSaveState
    data object Saved : ClipSaveState
    data class Failed(val message: String) : ClipSaveState
}

/**
 * What was actually recorded inside the editor's reach, as wall-clock spans, so the filmstrip can
 * shade the gaps (a camera that was offline, or a server that was restarting) rather than show a
 * frame that doesn't exist.
 */
@Immutable
data class RecordedSpans(val spans: List<ClosedFloatingPointRange<Double>> = emptyList())

/**
 * A detection inside the editor's reach: a mark along the filmstrip and a chip under it that
 * snaps the selection to exactly that stretch. [endEpochSeconds] is where it ended, or the end of
 * what can be clipped while it's still going on.
 */
@Immutable
data class ClipMoment(
    val id: String,
    val startEpochSeconds: Double,
    val endEpochSeconds: Double,
    val category: MomentCategory,
    /** "Person in the porch", as the Moments feed would title it. */
    val title: String,
    /** "8:42 AM". */
    val timeLabel: String,
)

@Immutable
data class ClipEditorUiState(
    /** The first history read and playlist are still on their way. */
    val isLoading: Boolean = true,
    /** Why there is nothing to clip (no connection, nothing recorded here); null while fine. */
    val loadError: String? = null,
    /** The clippable history: nothing before [earliestEpochSeconds] or after [latestEpochSeconds] can be selected. */
    val earliestEpochSeconds: Double = 0.0,
    val latestEpochSeconds: Double = 0.0,
    val recorded: RecordedSpans = RecordedSpans(),
    /** What the filmstrip spans right now; null until loaded. */
    val window: ClipWindow? = null,
    /** The selection; null until loaded. */
    val range: ClipRange? = null,
    /** Wall-clock time of the frame on screen (or under the finger while scrubbing). */
    val playheadEpochSeconds: Double? = null,
    /** The handle a finger is holding, if any: playback pauses and shows that handle's frame. */
    val activeHandle: TrimHandle? = null,
    val isScrubbing: Boolean = false,
    /**
     * A recording snapshot to hold over the player: the frame under a held handle or scrubbing
     * finger, or a seek target the player hasn't reached yet. Null once playback has caught up.
     */
    val previewEpochSeconds: Double? = null,
    val isPlaying: Boolean = true,
    val isBuffering: Boolean = false,
    val playerRequest: PlayerRequest? = null,
    val save: ClipSaveState = ClipSaveState.Idle,
    /** What was detected inside the reach, oldest first; empty until the camera's moments arrive. */
    val moments: List<ClipMoment> = emptyList(),
) {
    /** Whether the editor has something to trim. */
    val isReady: Boolean get() = range != null && window != null
}

/**
 * The clip editor behind the camera screen's scissors: a stretch of continuous recording around
 * [anchorEpochSeconds] (wherever the camera screen was playing, or just behind live), a selection
 * the viewer trims with two handles while the selection plays on a loop, and a save that has
 * Frigate cut exactly that stretch into an MP4. The detections inside the reach are offered as
 * one-tap selections, and the length chips as another.
 *
 * The whole reach — [LOOKAROUND_SECONDS] either side of the anchor — is one Frigate VOD playlist,
 * loaded once, so every trim, scrub, loop and filmstrip pan afterwards is an in-player seek: the
 * preview never reloads while the viewer works.
 */
@OptIn(ExperimentalTime::class)
@AssistedInject
class ClipEditorViewModel(
    @Assisted private val cameraName: String,
    @Assisted private val anchorEpochSeconds: Double,
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    private val getRecordingHistoryUseCase: GetRecordingHistoryUseCase,
    private val getRecordingStreamUseCase: GetRecordingStreamUseCase,
    private val getRecordingSnapshotUrlUseCase: GetRecordingSnapshotUrlUseCase,
    private val saveRecordingClipUseCase: SaveRecordingClipUseCase,
    private val observeCameraMomentsBetweenUseCase: ObserveCameraMomentsBetweenUseCase,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(cameraName: String, anchorEpochSeconds: Double): ClipEditorViewModel
    }

    private val serverUrl: StateFlow<String?> = observeCurrentServerUrlUseCase()

    private val _uiState = MutableStateFlow(ClipEditorUiState())
    val uiState: StateFlow<ClipEditorUiState> = _uiState.asStateFlow()

    private var playlist: RecordingPlaylist? = null
    private var seekSequence = 0L

    /** Whether playback should resume once the finger holding a handle or the playhead lets go. */
    private var resumeAfterGesture = true
    private var saveJob: Job? = null
    private var momentsJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private fun now(): Double = clock.now().toEpochMilliseconds() / 1000.0

    private suspend fun load() {
        val server = serverUrl.filterNotNull().first()
        // Frigate needs a few seconds to file a finished segment, so the last moments before
        // "now" aren't clippable yet; an anchor at the live edge slides back to what exists.
        val latestPossible = now() - LIVE_EDGE_SECONDS
        val anchor = anchorEpochSeconds.coerceAtMost(latestPossible)
        val reachStart = anchor - LOOKAROUND_SECONDS
        val reachEnd = min(anchor + LOOKAROUND_SECONDS, latestPossible)

        val history = getRecordingHistoryUseCase(server, cameraName, afterEpochSeconds = reachStart - SEGMENT_PADDING_SECONDS, beforeEpochSeconds = reachEnd)
            .getOrElse { error ->
                _uiState.update { it.copy(isLoading = false, loadError = error.message ?: "Couldn't load recordings") }
                return
            }
        val segments = history.segments.filter { it.endEpochSeconds > reachStart && it.startEpochSeconds < reachEnd }
        if (segments.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, loadError = "Nothing was recorded around this moment") }
            return
        }
        val loaded = RecordingPlaylist(segments)
        val earliest = max(reachStart, loaded.startEpochSeconds)
        val latest = min(reachEnd, loaded.endEpochSeconds)
        val range = initialClipRange(anchor.coerceIn(earliest, latest), earliest, latest)
        val window = ClipWindow.around(range, earliest, latest)

        val stream = runCatching { getRecordingStreamUseCase(server, cameraName, loaded) }
            .getOrElse { error ->
                _uiState.update { it.copy(isLoading = false, loadError = error.message ?: "Couldn't open the recording") }
                return
            }
        playlist = loaded
        watchMoments(earliest, latest)
        val startPosition = loaded.positionSecondsFor(range.startEpochSeconds)
        val resolvedStart = loaded.epochSecondsAt(startPosition)
        _uiState.update {
            it.copy(
                isLoading = false,
                loadError = null,
                earliestEpochSeconds = earliest,
                latestEpochSeconds = latest,
                recorded = RecordedSpans(segments.map { segment -> segment.startEpochSeconds..segment.endEpochSeconds }),
                window = window,
                range = range,
                playheadEpochSeconds = range.startEpochSeconds,
                previewEpochSeconds = resolvedStart,
                isPlaying = true,
                playerRequest = PlayerRequest(
                    source = VideoSource.Recording(
                        url = stream.url,
                        headers = stream.headers,
                        startPositionMs = startPosition.toMillis(),
                        posterUrl = snapshotUrl(resolvedStart, PREVIEW_SNAPSHOT_HEIGHT),
                    ),
                    playWhenReady = true,
                    // A clip is picked by eye; the viewer who wants the sound has the saved file.
                    muted = true,
                ),
            )
        }
    }

    /** After a load error: start over from the anchor, as if the editor had just opened. */
    fun retry() {
        if (_uiState.value.isLoading) return
        momentsJob?.cancel()
        playlist = null
        _uiState.value = ClipEditorUiState()
        viewModelScope.launch { load() }
    }

    /**
     * This camera's detections inside [earliest]..[latest], kept current (one still in progress
     * grows its end as the server reports it). The same titles and categories as the Moments
     * tab, so a chip here reads exactly like the card there. Asked for by interval, not paged
     * down from now, so an editor opened on an old recording of a busy camera still gets them.
     */
    private fun watchMoments(earliest: Double, latest: Double) {
        momentsJob?.cancel()
        momentsJob = viewModelScope.launch {
            val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            // A little before [earliest], so a detection already under way when the reach begins still shows.
            observeCameraMomentsBetweenUseCase(cameraName, afterEpochSeconds = earliest - SEGMENT_PADDING_SECONDS, beforeEpochSeconds = latest)
                // Marks are a nicety: a feed that fails leaves the editor fully usable without them.
                .catch { emit(emptyList()) }
                .collect { events ->
                    val moments = events
                        .filter { it.startEpochSeconds < latest && (it.endEpochSeconds ?: latest) > earliest }
                        .sortedBy { it.startEpochSeconds }
                        .map { event ->
                            val presentation = event.present(today)
                            ClipMoment(
                                id = event.id,
                                startEpochSeconds = event.startEpochSeconds,
                                endEpochSeconds = min(event.endEpochSeconds ?: latest, latest),
                                category = event.category,
                                title = presentation.title,
                                timeLabel = presentation.timeLabel,
                            )
                        }
                    _uiState.update { it.copy(moments = moments) }
                }
        }
    }

    /**
     * Frigate's recording frame at [epochSeconds] on this camera, [height] pixels tall, quantised
     * like the camera screen's scrub previews so neighbouring asks share a cache entry. Null
     * while disconnected.
     */
    fun snapshotUrl(epochSeconds: Double, height: Int): String? =
        serverUrl.value?.let { getRecordingSnapshotUrlUseCase(it, cameraName, snapshotEpochSeconds(epochSeconds), height) }

    // --- Playback ---------------------------------------------------------------------------

    fun togglePlayPause() {
        _uiState.update { current ->
            val playing = !current.isPlaying
            current.copy(isPlaying = playing, playerRequest = current.playerRequest?.copy(playWhenReady = playing))
        }
    }

    /**
     * Follows the player, and keeps it inside the selection: reaching the end loops to the start,
     * the way a trimmed video previews in a photo editor.
     */
    fun onPlayerPositionChanged(positionMs: Long) {
        val playlist = playlist ?: return
        val current = _uiState.value
        val range = current.range ?: return
        if (current.activeHandle != null || current.isScrubbing) return
        val playhead = playlist.epochSecondsAt(positionMs / 1000.0)
        val preview = current.previewEpochSeconds
        if (preview == null && playhead >= range.endEpochSeconds && current.isPlaying) {
            seekTo(range.startEpochSeconds, playWhenReady = true)
            return
        }
        val caughtUp = preview != null && !current.isBuffering && abs(playhead - preview) <= PREVIEW_TOLERANCE_SECONDS
        _uiState.update {
            it.copy(
                playheadEpochSeconds = if (preview != null && !caughtUp) it.playheadEpochSeconds else playhead,
                previewEpochSeconds = if (caughtUp) null else preview,
            )
        }
    }

    fun onBufferingChanged(isBuffering: Boolean) {
        _uiState.update { it.copy(isBuffering = isBuffering) }
    }

    /** The loaded reach ran out mid-loop (a selection ending at the very last segment): go round again. */
    fun onPlaybackEnded() {
        val range = _uiState.value.range ?: return
        seekTo(range.startEpochSeconds, playWhenReady = _uiState.value.isPlaying)
    }

    fun onPlaybackError() {
        _uiState.update { it.copy(loadError = "Couldn't play this recording", isBuffering = false) }
    }

    private fun seekTo(epochSeconds: Double, playWhenReady: Boolean) {
        val playlist = playlist ?: return
        val position = playlist.positionSecondsFor(epochSeconds)
        val resolved = playlist.epochSecondsAt(position)
        _uiState.update {
            val request = it.playerRequest ?: return@update it
            it.copy(
                playheadEpochSeconds = epochSeconds,
                previewEpochSeconds = resolved,
                isPlaying = playWhenReady,
                playerRequest = request.copy(seek = SeekCommand(++seekSequence, position.toMillis()), playWhenReady = playWhenReady),
            )
        }
    }

    private fun pauseForGesture() {
        resumeAfterGesture = _uiState.value.isPlaying
        _uiState.update { it.copy(isPlaying = false, playerRequest = it.playerRequest?.copy(playWhenReady = false)) }
    }

    // --- Trimming ---------------------------------------------------------------------------

    /** A finger took hold of [handle]: pause, and show the frame the handle sits on. */
    fun beginTrim(handle: TrimHandle) {
        val range = _uiState.value.range ?: return
        pauseForGesture()
        val at = if (handle == TrimHandle.START) range.startEpochSeconds else range.endEpochSeconds
        _uiState.update { it.copy(activeHandle = handle, previewEpochSeconds = at, playheadEpochSeconds = at) }
    }

    /**
     * Moves [handle] to [epochSeconds], as far as the clip's limits allow. Returns true when the
     * limits held it back (too short, too long, or the end of what was recorded) so the UI can
     * say so with a bump rather than letting the handle silently stop following the finger.
     */
    fun trimTo(handle: TrimHandle, epochSeconds: Double): Boolean {
        val state = _uiState.value
        val range = state.range ?: return false
        val trimmed = when (handle) {
            TrimHandle.START -> range.withStartAt(epochSeconds, state.earliestEpochSeconds)
            TrimHandle.END -> range.withEndAt(epochSeconds, state.latestEpochSeconds)
        }
        val at = if (handle == TrimHandle.START) trimmed.startEpochSeconds else trimmed.endEpochSeconds
        _uiState.update { it.copy(range = trimmed, previewEpochSeconds = at, playheadEpochSeconds = at, save = it.save.afterEdit()) }
        return abs(at - epochSeconds) > LIMIT_EPSILON_SECONDS
    }

    /**
     * The handle was let go: play the result from where it matters — the new beginning after
     * trimming the start, the last couple of seconds after trimming the end (so the viewer sees
     * exactly where it now stops, then the loop brings the start round).
     */
    fun endTrim() {
        val state = _uiState.value
        val handle = state.activeHandle ?: return
        val range = state.range ?: return
        _uiState.update { it.copy(activeHandle = null) }
        val from = when (handle) {
            TrimHandle.START -> range.startEpochSeconds
            TrimHandle.END -> max(range.startEpochSeconds, range.endEpochSeconds - END_PREVIEW_SECONDS)
        }
        seekTo(from, playWhenReady = resumeAfterGesture || handle == TrimHandle.END)
    }

    fun beginScrub() {
        pauseForGesture()
        _uiState.update { it.copy(isScrubbing = true) }
    }

    /** The playhead dragged to [epochSeconds], inside the selection: a frame preview until release. */
    fun scrubTo(epochSeconds: Double) {
        val range = _uiState.value.range ?: return
        val at = epochSeconds.coerceIn(range.startEpochSeconds, range.endEpochSeconds)
        _uiState.update { it.copy(playheadEpochSeconds = at, previewEpochSeconds = at) }
    }

    fun endScrub() {
        val at = _uiState.value.playheadEpochSeconds ?: return
        _uiState.update { it.copy(isScrubbing = false) }
        seekTo(at, playWhenReady = resumeAfterGesture)
    }

    /**
     * A tap on the filmstrip: inside the selection it moves the playhead there; outside, the
     * nearer handle jumps to it — the quick way to grow or cut a clip without dragging.
     * Returns true when a limit held the jump back, like [trimTo].
     */
    fun tapAt(epochSeconds: Double): Boolean {
        val state = _uiState.value
        val range = state.range ?: return false
        if (epochSeconds in range) {
            seekTo(epochSeconds, playWhenReady = state.isPlaying)
            return false
        }
        val handle = if (epochSeconds < range.startEpochSeconds) TrimHandle.START else TrimHandle.END
        val limited = trimTo(handle, epochSeconds)
        val trimmed = _uiState.value.range ?: return limited
        val from = if (handle == TrimHandle.START) trimmed.startEpochSeconds else max(trimmed.startEpochSeconds, trimmed.endEpochSeconds - END_PREVIEW_SECONDS)
        seekTo(from, playWhenReady = true)
        return limited
    }

    /**
     * A detection's chip: the selection becomes exactly that detection, with a beat either side
     * (see [clipRangeForMoment]), and plays from its start.
     */
    fun selectMoment(moment: ClipMoment) {
        val state = _uiState.value
        if (state.range == null) return
        applyRange(clipRangeForMoment(moment.startEpochSeconds, moment.endEpochSeconds, state.earliestEpochSeconds, state.latestEpochSeconds))
    }

    /** A length chip: the clip becomes [seconds] long from where it starts now (see [withLength]). */
    fun setLength(seconds: Double) {
        val state = _uiState.value
        val range = state.range ?: return
        applyRange(range.withLength(seconds, state.earliestEpochSeconds, state.latestEpochSeconds))
    }

    /** A whole new selection at once: brought into view on the strip if it isn't, then played from the top. */
    private fun applyRange(range: ClipRange) {
        _uiState.update { state ->
            state.copy(
                range = range,
                window = state.window?.holding(range, state.earliestEpochSeconds, state.latestEpochSeconds),
                save = state.save.afterEdit(),
            )
        }
        seekTo(range.startEpochSeconds, playWhenReady = true)
    }

    /** A different selection is a different clip: "Saved" (or a failure) was about the last one. A save in flight carries on. */
    private fun ClipSaveState.afterEdit(): ClipSaveState = if (this == ClipSaveState.Saving) this else ClipSaveState.Idle

    // --- Filmstrip --------------------------------------------------------------------------

    /** Slides the filmstrip [seconds] later (negative for earlier), stopping at the ends of what can be clipped. */
    fun panWindow(seconds: Double) {
        _uiState.update { state ->
            val window = state.window ?: return@update state
            state.copy(window = window.pannedBy(seconds, state.earliestEpochSeconds, state.latestEpochSeconds))
        }
    }

    /**
     * Pinch on the filmstrip: [factor] > 1 spreads the fingers (fewer seconds across the strip,
     * finer trimming), < 1 pinches in. [focusEpochSeconds] stays under the fingers.
     */
    fun zoomWindow(factor: Double, focusEpochSeconds: Double) {
        if (factor <= 0.0) return
        _uiState.update { state ->
            val window = state.window ?: return@update state
            val available = state.latestEpochSeconds - state.earliestEpochSeconds
            val length = (window.durationSeconds / factor).coerceIn(min(MIN_WINDOW_SECONDS, available), min(MAX_WINDOW_SECONDS, available))
            val focusFraction = window.fractionOf(focusEpochSeconds).coerceIn(0.0, 1.0)
            val start = focusEpochSeconds - focusFraction * length
            val zoomed = ClipWindow(start, start + length).pannedBy(0.0, state.earliestEpochSeconds, state.latestEpochSeconds)
            state.copy(window = zoomed)
        }
    }

    // --- Saving -----------------------------------------------------------------------------

    fun save() {
        val state = _uiState.value
        val range = state.range ?: return
        val server = serverUrl.value
        if (state.save == ClipSaveState.Saving) return
        if (server == null) {
            _uiState.update { it.copy(save = ClipSaveState.Failed("Not connected to the server")) }
            return
        }
        saveJob?.cancel()
        _uiState.update { it.copy(save = ClipSaveState.Saving) }
        saveJob = viewModelScope.launch {
            val result = saveRecordingClipUseCase(server, cameraName, range)
            _uiState.update {
                it.copy(save = result.fold(onSuccess = { ClipSaveState.Saved }, onFailure = { error -> ClipSaveState.Failed(error.message ?: "Couldn't save the clip") }))
            }
        }
    }

    /** The failure line was read (or the selection changed since): back to a plain Save button. */
    fun dismissSaveError() {
        _uiState.update { if (it.save is ClipSaveState.Failed) it.copy(save = ClipSaveState.Idle) else it }
    }

    private fun Double.toMillis(): Long = (this * 1000).toLong()

    private companion object {
        /** The same margin the camera screen keeps from "now": Frigate files segments a few seconds late. */
        const val LIVE_EDGE_SECONDS = 15.0

        /** How far either side of the anchor the editor loads, and so how far the filmstrip can pan. */
        const val LOOKAROUND_SECONDS = 900.0

        /** Asks for a little more history than the reach, so a segment straddling its start is included. */
        const val SEGMENT_PADDING_SECONDS = 60.0

        /** A seek counts as arrived, and its frame preview comes down, once playback is this close. */
        const val PREVIEW_TOLERANCE_SECONDS = 1.5

        /** After trimming the end, playback resumes this far before it. */
        const val END_PREVIEW_SECONDS = 2.5

        /** A handle this close to where the finger asked counts as following it. */
        const val LIMIT_EPSILON_SECONDS = 0.05

        /** The narrowest and widest the filmstrip can be pinched to. */
        const val MIN_WINDOW_SECONDS = 20.0
        const val MAX_WINDOW_SECONDS = 1_200.0

        /** The preview is full-width 16:9, like the camera screen's player. */
        const val PREVIEW_SNAPSHOT_HEIGHT = 720
    }
}
