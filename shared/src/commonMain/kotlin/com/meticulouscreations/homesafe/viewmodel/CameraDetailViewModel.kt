package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.RecordingHistory
import com.meticulouscreations.homesafe.domain.model.RecordingPlaylist
import com.meticulouscreations.homesafe.domain.model.RecordingSegment
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingHistoryUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingStreamUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.network.frigateLiveStreamUrl
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.SeekCommand
import com.meticulouscreations.homesafe.ui.components.VideoSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

sealed interface CameraDetailUiState {
    data object Loading : CameraDetailUiState
    data class Found(val camera: Camera, val streamUrl: String?) : CameraDetailUiState
    data object NotFound : CameraDetailUiState
}

/** How much history the timeline shows, ending at "now". */
enum class TimelineSpan(val label: String, val seconds: Long, val tickSeconds: Long) {
    ONE_HOUR("1h", 3_600, 900),
    THREE_HOURS("3h", 10_800, 1_800),
    TWELVE_HOURS("12h", 43_200, 7_200),
    ONE_DAY("24h", 86_400, 14_400),
}

/**
 * Live-vs-history playback state, YouTube-live style: the player is either at the live edge
 * ([playlist] == null) or somewhere inside a recording [playlist], and the timeline lets the
 * user move between the two.
 */
@Immutable
data class PlaybackUiState(
    val span: TimelineSpan = TimelineSpan.THREE_HOURS,
    /** Recorded coverage inside the timeline window, for drawing. */
    val segments: List<RecordingSegment> = emptyList(),
    /** The recording playlist being played, or null when live. */
    val playlist: RecordingPlaylist? = null,
    /** Wall-clock time of the frame on screen while playing history; null when live. */
    val playheadEpochSeconds: Double? = null,
    /** Where the user's finger is while dragging the timeline; null otherwise. */
    val scrubEpochSeconds: Double? = null,
    val isPlaying: Boolean = true,
    val isBuffering: Boolean = false,
    val isLoadingPlaylist: Boolean = false,
    val historyError: String? = null,
    val playerRequest: PlayerRequest? = null,
) {
    val isLive: Boolean get() = playlist == null
}

class CameraDetailViewModel(
    private val cameraName: String,
    observeCamerasUseCase: ObserveCamerasUseCase,
    private val connectionRepository: ConnectionRepository,
    private val getRecordingHistoryUseCase: GetRecordingHistoryUseCase,
    private val getRecordingStreamUseCase: GetRecordingStreamUseCase,
    private val clock: () -> Double = ::epochSecondsNow,
) : ViewModel() {

    val uiState: StateFlow<CameraDetailUiState> = combine(
        observeCamerasUseCase(),
        connectionRepository.currentServerUrl,
    ) { cameras, serverUrl ->
        val camera = cameras.firstOrNull { it.name == cameraName }
        when {
            camera == null -> CameraDetailUiState.NotFound
            camera.enabled && serverUrl != null ->
                CameraDetailUiState.Found(camera, frigateLiveStreamUrl(serverUrl, cameraName))
            else -> CameraDetailUiState.Found(camera, streamUrl = null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraDetailUiState.Loading)

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    /** Ticks once a second while observed; drives the timeline's live edge and the "behind live" label. */
    val nowEpochSeconds: StateFlow<Double> = flow {
        while (true) {
            emit(clock())
            delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock())

    private var history: RecordingHistory = RecordingHistory.EMPTY
    private var seekSequence = 0L
    private var playlistLoadJob: Job? = null

    init {
        // While live, follow the camera's live URL (it appears once connected, disappears if the camera is disabled).
        viewModelScope.launch {
            uiState.collect { state ->
                val liveUrl = (state as? CameraDetailUiState.Found)?.streamUrl
                _playback.update { current ->
                    when {
                        !current.isLive -> current
                        liveUrl == null -> current.copy(playerRequest = null)
                        current.playerRequest?.source == VideoSource.Live(liveUrl) -> current
                        else -> current.copy(playerRequest = PlayerRequest(VideoSource.Live(liveUrl)), isPlaying = true)
                    }
                }
            }
        }
        // Keep the timeline's coverage fresh: new segments land every few seconds while a camera records.
        viewModelScope.launch {
            combine(
                connectionRepository.currentServerUrl,
                _playback.map { it.span }.distinctUntilChanged(),
            ) { serverUrl, span -> serverUrl to span }
                .collectLatest { (serverUrl, span) ->
                    if (serverUrl == null) return@collectLatest
                    while (true) {
                        refreshHistory(serverUrl, span)
                        delay(HISTORY_REFRESH_INTERVAL_MS)
                    }
                }
        }
    }

    fun setSpan(span: TimelineSpan) {
        _playback.update { it.copy(span = span) }
    }

    fun onScrubStart() {
        _playback.update { it.copy(scrubEpochSeconds = it.playheadEpochSeconds ?: clock()) }
    }

    fun onScrub(epochSeconds: Double) {
        val now = clock()
        _playback.update { it.copy(scrubEpochSeconds = epochSeconds.coerceIn(now - it.span.seconds, now)) }
    }

    fun onScrubEnd() {
        val target = _playback.value.scrubEpochSeconds ?: return
        seekTo(target)
    }

    /** Jump to [epochSeconds]: an in-place seek if the current playlist covers it, a playlist swap otherwise, live if it's at the edge. */
    fun seekTo(epochSeconds: Double) {
        val now = clock()
        val target = epochSeconds.coerceAtMost(now)
        val latestRecorded = history.latestEndEpochSeconds
        if (latestRecorded == null || target >= latestRecorded || target >= now - LIVE_EDGE_SECONDS) {
            goLive()
            return
        }

        val current = _playback.value
        val playlist = current.playlist
        val request = current.playerRequest
        if (playlist != null && target in playlist && request?.source is VideoSource.Recording && !current.isLoadingPlaylist) {
            val seek = SeekCommand(id = ++seekSequence, positionMs = playlist.positionSecondsFor(target).toMillis())
            _playback.update {
                it.copy(playheadEpochSeconds = target, scrubEpochSeconds = null, playerRequest = request.copy(seek = seek))
            }
            return
        }

        val next = history.playlistFor(target)
        if (next == null) goLive() else loadPlaylist(next, target)
    }

    fun goLive() {
        playlistLoadJob?.cancel()
        val liveUrl = (uiState.value as? CameraDetailUiState.Found)?.streamUrl
        _playback.update {
            it.copy(
                playlist = null,
                playheadEpochSeconds = null,
                scrubEpochSeconds = null,
                isLoadingPlaylist = false,
                isPlaying = true,
                playerRequest = liveUrl?.let { url -> PlayerRequest(VideoSource.Live(url)) },
            )
        }
    }

    fun togglePlayPause() {
        _playback.update { current ->
            val playing = !current.isPlaying
            current.copy(isPlaying = playing, playerRequest = current.playerRequest?.copy(playWhenReady = playing))
        }
    }

    fun onPlayerPositionChanged(positionMs: Long) {
        _playback.update { current ->
            val playlist = current.playlist
            if (playlist == null || current.scrubEpochSeconds != null || current.isLoadingPlaylist) {
                current
            } else {
                current.copy(playheadEpochSeconds = playlist.epochSecondsAt(positionMs / 1000.0))
            }
        }
    }

    fun onBufferingChanged(isBuffering: Boolean) {
        _playback.update { it.copy(isBuffering = isBuffering) }
    }

    /**
     * The recording playlist ran out: continue with whatever was recorded next, or rejoin live.
     * The continuation starts where the old playlist ended, not at the next playlist's start —
     * when new segments have landed in the same hour bucket, "next" contains the clips just played.
     */
    fun onPlaybackEnded() {
        val playlist = _playback.value.playlist ?: return
        val next = history.playlistAfter(playlist)
        if (next == null) goLive() else loadPlaylist(next, playlist.endEpochSeconds)
    }

    fun onPlaybackError() {
        if (!_playback.value.isLive) goLive()
    }

    private fun loadPlaylist(playlist: RecordingPlaylist, epochSeconds: Double) {
        val serverUrl = connectionRepository.currentServerUrl.value
        if (serverUrl == null) {
            goLive()
            return
        }
        playlistLoadJob?.cancel()
        val startEpoch = epochSeconds.coerceIn(playlist.startEpochSeconds, playlist.endEpochSeconds)
        _playback.update {
            it.copy(playlist = playlist, playheadEpochSeconds = startEpoch, scrubEpochSeconds = null, isLoadingPlaylist = true)
        }
        playlistLoadJob = viewModelScope.launch {
            val stream = runCatching { getRecordingStreamUseCase(serverUrl, cameraName, playlist) }
                .getOrElse {
                    goLive()
                    return@launch
                }
            _playback.update { current ->
                current.copy(
                    isLoadingPlaylist = false,
                    playerRequest = PlayerRequest(
                        source = VideoSource.Recording(
                            url = stream.url,
                            headers = stream.headers,
                            startPositionMs = playlist.positionSecondsFor(startEpoch).toMillis(),
                        ),
                        playWhenReady = current.isPlaying,
                    ),
                )
            }
        }
    }

    private suspend fun refreshHistory(serverUrl: String, span: TimelineSpan) {
        val now = clock()
        getRecordingHistoryUseCase(
            serverUrl = serverUrl,
            cameraName = cameraName,
            afterEpochSeconds = now - span.seconds - HISTORY_LOOKBEHIND_PADDING_SECONDS,
            beforeEpochSeconds = now,
        )
            .onSuccess { loaded ->
                history = loaded
                _playback.update { it.copy(segments = loaded.segments, historyError = null) }
            }
            .onFailure { error ->
                _playback.update { it.copy(historyError = error.message ?: "Couldn't load recordings") }
            }
    }

    private fun Double.toMillis(): Long = (this * 1000).toLong()

    private companion object {
        const val HISTORY_REFRESH_INTERVAL_MS = 20_000L

        /** Frigate takes a little while to move a finished segment into its database, so the last few seconds before "now" are never scrubbable. */
        const val LIVE_EDGE_SECONDS = 15.0

        /** Fetch slightly more than the window so a segment straddling its left edge still renders. */
        const val HISTORY_LOOKBEHIND_PADDING_SECONDS = 60.0
    }
}

@OptIn(ExperimentalTime::class)
private fun epochSecondsNow(): Double = Clock.System.now().toEpochMilliseconds() / 1000.0
