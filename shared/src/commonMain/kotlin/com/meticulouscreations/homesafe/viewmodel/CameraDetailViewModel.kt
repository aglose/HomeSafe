package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.RecordingHistory
import com.meticulouscreations.homesafe.domain.model.RecordingPlaylist
import com.meticulouscreations.homesafe.domain.model.RecordingSegment
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetEventThumbnailUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingHistoryUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingStreamUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveActiveConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMomentsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveServerOverviewUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.UpdateSettingsUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.SeekCommand
import com.meticulouscreations.homesafe.ui.components.VideoSource
import com.meticulouscreations.homesafe.ui.components.liveAudioCodecs
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

sealed interface CameraDetailUiState {
    data object Loading : CameraDetailUiState
    data class Found(
        val camera: Camera,
        val streamUrl: String?,
        /** The camera's grid-quality stream, used to fast-join live before upgrading to [streamUrl]; see [planLiveJoin]. */
        val gridStreamUrl: String?,
        val posterUrl: String?,
    ) : CameraDetailUiState
    data object NotFound : CameraDetailUiState
}

/**
 * How to (re)join live playback for a camera: fast-join on the grid stream first when it's a
 * genuinely different (and, on the real server today, already-primed-by-the-grid) stream from
 * the full-quality one, upgrading afterward — or join on the full-quality stream directly and
 * skip the upgrade entirely when the two names are the same, which is every camera on the real
 * server today (no `live.streams` split configured yet). That equality check is what keeps this
 * inert rather than a redundant reconnect once dual-quality streams are actually configured.
 */
internal data class LiveJoinPlan(val joinUrl: String, val upgradeToUrl: String?)

internal fun planLiveJoin(gridStreamUrl: String, liveStreamUrl: String): LiveJoinPlan =
    if (gridStreamUrl != liveStreamUrl) {
        LiveJoinPlan(joinUrl = gridStreamUrl, upgradeToUrl = liveStreamUrl)
    } else {
        LiveJoinPlan(joinUrl = liveStreamUrl, upgradeToUrl = null)
    }

/**
 * The moment a scrub/seek preview snapshot is taken for, quantised to [SNAPSHOT_STEP_SECONDS]:
 * scrubbing across a minute then asks Frigate for a handful of frames rather than one per pixel,
 * and the frame previewed while dragging is byte-for-byte the one that stays up as the poster
 * after release (same URL, same cache entry), so there is no flicker between the two.
 */
internal fun snapshotEpochSeconds(epochSeconds: Double): Double =
    floor(epochSeconds / SNAPSHOT_STEP_SECONDS) * SNAPSHOT_STEP_SECONDS

internal const val SNAPSHOT_STEP_SECONDS = 2.0

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
    /**
     * A moment the player has been sent to but hasn't reached yet: its recording snapshot covers
     * the surface (which would otherwise keep showing the frame from *before* the seek) until
     * playback reports a position close to it. Null once caught up, or while live.
     */
    val seekPreviewEpochSeconds: Double? = null,
    val isPlaying: Boolean = true,
    val isBuffering: Boolean = false,
    val isLoadingPlaylist: Boolean = false,
    /** The user's speaker choice for this screen; silent until they opt in. Carried into every [playerRequest]. */
    val isMuted: Boolean = true,
    /** Whether what's playing has an audio track this platform can decode — the speaker button is inert otherwise. */
    val hasAudio: Boolean = false,
    val historyError: String? = null,
    val playerRequest: PlayerRequest? = null,
) {
    val isLive: Boolean get() = playlist == null
}

/** The bell under the player: this camera's alert switch, and whether the app is delivering alerts at all. */
@Immutable
data class CameraAlertsUiState(
    /** Some place on this camera still notifies (see [AlertSettings.alertsEnabledOn]). */
    val enabled: Boolean = true,
    /** The master switch on the Settings tab. Off means the bell's choice is kept but nothing arrives. */
    val pushNotificationsEnabled: Boolean = false,
)

@OptIn(ExperimentalTime::class)
@AssistedInject
class CameraDetailViewModel(
    @Assisted private val cameraName: String,
    observeCamerasUseCase: ObserveCamerasUseCase,
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    observeActiveConnectionUseCase: ObserveActiveConnectionUseCase,
    private val getRecordingHistoryUseCase: GetRecordingHistoryUseCase,
    private val getRecordingStreamUseCase: GetRecordingStreamUseCase,
    observeMomentsUseCase: ObserveMomentsUseCase,
    observeSettingsUseCase: ObserveSettingsUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    observeServerOverviewUseCase: ObserveServerOverviewUseCase,
    private val getLiveStreamUrlUseCase: GetLiveStreamUrlUseCase,
    private val getCameraSnapshotUrlUseCase: GetCameraSnapshotUrlUseCase,
    private val getEventThumbnailUrlUseCase: GetEventThumbnailUrlUseCase,
    private val getRecordingSnapshotUrlUseCase: GetRecordingSnapshotUrlUseCase,
    private val clock: Clock,
) : ViewModel() {

    /** One view model per camera; the screen keys it by [cameraName]. */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(cameraName: String): CameraDetailViewModel
    }

    private val serverUrl: StateFlow<String?> = observeCurrentServerUrlUseCase()

    /** The signed-in server and its route, for this screen's own header (it replaces the shell's bar). */
    val activeConnection: StateFlow<ActiveConnection?> = observeActiveConnectionUseCase()

    /** Wall-clock epoch seconds from the injected clock, so tests can pin it. */
    private fun now(): Double = clock.now().toEpochMilliseconds() / 1000.0

    /**
     * This camera's newest detections for the "Recent Activity" strip. Same feed and mapper as the
     * Moments tab, so a detection reads identically in both places; capped small because this is a
     * glance, not the list — the Moments tab is where the full history lives.
     */
    @OptIn(ExperimentalTime::class)
    val recentMoments: StateFlow<List<MomentItem>> = combine(
        observeMomentsUseCase(),
        serverUrl,
    ) { events, serverUrl ->
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        events
            .filter { it.cameraName == cameraName }
            .take(RECENT_MOMENTS)
            .map { MomentItem(it, it.present(today), serverUrl?.let { url -> getEventThumbnailUrlUseCase(url, it.id) }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<CameraDetailUiState> = combine(
        observeCamerasUseCase(),
        serverUrl,
    ) { cameras, serverUrl ->
        val camera = cameras.firstOrNull { it.name == cameraName }
        val posterUrl = serverUrl?.let { getCameraSnapshotUrlUseCase(it, cameraName) }
        when {
            camera == null -> CameraDetailUiState.NotFound
            camera.enabled && serverUrl != null ->
                CameraDetailUiState.Found(
                    camera = camera,
                    // Full quality *and* sound: only the single-camera view asks go2rtc for an audio track.
                    streamUrl = getLiveStreamUrlUseCase(serverUrl, camera.liveStreamName, audioCodecs = liveAudioCodecs),
                    gridStreamUrl = getLiveStreamUrlUseCase(serverUrl, camera.gridStreamName),
                    posterUrl = posterUrl,
                )
            else -> CameraDetailUiState.Found(camera, streamUrl = null, gridStreamUrl = null, posterUrl = posterUrl)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraDetailUiState.Loading)

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val settings: StateFlow<AlertSettings> =
        observeSettingsUseCase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertSettings.DEFAULT)

    /**
     * This camera's zone keys, from the same server overview the Settings tab lists them from
     * (observing it here is what starts that poll while this screen is up). Empty until the
     * first config read lands, or for a camera with none drawn — the bell still works on the
     * camera's "anywhere" place meanwhile.
     */
    private val zoneNames: StateFlow<List<String>> = observeServerOverviewUseCase()
        .map { overview -> overview?.cameras?.firstOrNull { it.name == cameraName }?.zones?.map { it.name } ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val alerts: StateFlow<CameraAlertsUiState> = combine(settings, zoneNames) { alerts, zones ->
        CameraAlertsUiState(enabled = alerts.alertsEnabledOn(cameraName, zones), pushNotificationsEnabled = alerts.pushNotificationsEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraAlertsUiState())

    /** Ticks once a second while observed; drives the timeline's live edge and the "behind live" label. */
    val nowEpochSeconds: StateFlow<Double> = flow {
        while (true) {
            emit(now())
            delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), now())

    private var history: RecordingHistory = RecordingHistory.EMPTY
    private var seekSequence = 0L
    private var playlistLoadJob: Job? = null
    private var qualityUpgradeJob: Job? = null
    private var momentJob: Job? = null

    init {
        // While live, follow the camera's live URL (it appears once connected, disappears if the camera is disabled).
        viewModelScope.launch {
            uiState.collect { state ->
                val found = state as? CameraDetailUiState.Found
                val liveUrl = found?.streamUrl
                val gridUrl = found?.gridStreamUrl
                val posterUrl = found?.posterUrl
                val alreadyJoined = { src: VideoSource? ->
                    src is VideoSource.Live && src.posterUrl == posterUrl && src.url in listOfNotNull(gridUrl, liveUrl)
                }
                _playback.update { current ->
                    when {
                        !current.isLive -> current
                        liveUrl == null -> current.copy(playerRequest = null)
                        alreadyJoined(current.playerRequest?.source) -> current
                        else -> current.copy(playerRequest = joinLive(found, current.isMuted), isPlaying = true)
                    }
                }
            }
        }
        // Keep the timeline's coverage fresh: new segments land every few seconds while a camera records.
        viewModelScope.launch {
            combine(
                serverUrl,
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
        _playback.update { it.copy(scrubEpochSeconds = it.playheadEpochSeconds ?: now()) }
    }

    fun onScrub(epochSeconds: Double) {
        val now = now()
        _playback.update { it.copy(scrubEpochSeconds = epochSeconds.coerceIn(now - it.span.seconds, now)) }
    }

    fun onScrubEnd() {
        val target = _playback.value.scrubEpochSeconds ?: return
        seekTo(target)
    }

    /** Jump to [epochSeconds]: an in-place seek if the current playlist covers it, a playlist swap otherwise, live if it's at the edge. */
    fun seekTo(epochSeconds: Double) {
        val now = now()
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
            val positionSeconds = playlist.positionSecondsFor(target)
            val seek = SeekCommand(id = ++seekSequence, positionMs = positionSeconds.toMillis())
            // Preview the moment playback will actually land on (a target inside a gap resolves to
            // the next clip's start), so "caught up" is measurable against it.
            val resolved = playlist.epochSecondsAt(positionSeconds)
            _playback.update {
                it.copy(
                    playheadEpochSeconds = target,
                    scrubEpochSeconds = null,
                    seekPreviewEpochSeconds = resolved,
                    playerRequest = request.copy(seek = seek),
                )
            }
            return
        }

        val next = history.playlistFor(target)
        if (next == null) goLive() else loadPlaylist(next, target)
    }

    fun goLive() {
        playlistLoadJob?.cancel()
        val found = uiState.value as? CameraDetailUiState.Found
        _playback.update {
            it.copy(
                playlist = null,
                playheadEpochSeconds = null,
                scrubEpochSeconds = null,
                seekPreviewEpochSeconds = null,
                isLoadingPlaylist = false,
                isPlaying = true,
                playerRequest = joinLive(found, it.isMuted),
            )
        }
    }

    /**
     * Builds the [PlayerRequest] for (re)joining live, applying [planLiveJoin] and — when it
     * calls for an upgrade — scheduling it. Returns null when the camera has no live URL yet
     * (disabled, or still loading).
     */
    private fun joinLive(found: CameraDetailUiState.Found?, muted: Boolean): PlayerRequest? {
        val liveUrl = found?.streamUrl ?: return null
        val gridUrl = found.gridStreamUrl ?: liveUrl
        val plan = planLiveJoin(gridUrl, liveUrl)
        if (plan.upgradeToUrl != null) scheduleQualityUpgrade(plan.upgradeToUrl, found.posterUrl) else qualityUpgradeJob?.cancel()
        return PlayerRequest(VideoSource.Live(plan.joinUrl, found.posterUrl), muted = muted)
    }

    /**
     * Waits for the grid-quality join to settle before stepping up to full quality, rather than
     * reacting to the player's own first-frame event: that event isn't part of [CameraStreamPlayer]'s
     * cross-platform callback surface today, and a short fixed delay is enough to avoid upgrading
     * mid-stall without adding a fourth platform-specific signal just for this one swap.
     */
    private fun scheduleQualityUpgrade(liveUrl: String, posterUrl: String?) {
        qualityUpgradeJob?.cancel()
        qualityUpgradeJob = viewModelScope.launch {
            delay(QUALITY_UPGRADE_DELAY_MS)
            _playback.update { current ->
                // Carry forward current.isPlaying, not PlayerRequest's own default(true): the user
                // may have paused during the few seconds the grid-quality join was standing in, and
                // this swap must not silently resume playback out from under a paused viewer.
                if (current.isLive) {
                    current.copy(playerRequest = PlayerRequest(VideoSource.Live(liveUrl, posterUrl), playWhenReady = current.isPlaying, muted = current.isMuted))
                } else {
                    current
                }
            }
        }
    }

    fun togglePlayPause() {
        _playback.update { current ->
            val playing = !current.isPlaying
            current.copy(isPlaying = playing, playerRequest = current.playerRequest?.copy(playWhenReady = playing))
        }
    }

    fun toggleMuted() {
        _playback.update { current ->
            val muted = !current.isMuted
            current.copy(isMuted = muted, playerRequest = current.playerRequest?.copy(muted = muted))
        }
    }

    fun onAudioAvailabilityChanged(hasAudio: Boolean) {
        _playback.update { if (it.hasAudio == hasAudio) it else it.copy(hasAudio = hasAudio) }
    }

    /** The bell: silence this camera everywhere, or bring it back (see [AlertSettings.withAlertsOn]). */
    fun setAlertsEnabled(enabled: Boolean) {
        val updated = settings.value.withAlertsOn(cameraName, zoneNames.value, enabled)
        viewModelScope.launch { updateSettingsUseCase(updated) }
    }

    /**
     * Play the recording of a detection from where it began: the timeline widens to the
     * narrowest span that shows the moment (or the widest, for something older than any),
     * history is (re)loaded so it's sure to cover that moment, and the player seeks there.
     */
    fun playMoment(epochSeconds: Double) {
        val serverUrl = serverUrl.value ?: return
        val span = TimelineSpan.entries.firstOrNull { now() - epochSeconds <= it.seconds } ?: TimelineSpan.entries.last()
        momentJob?.cancel()
        momentJob = viewModelScope.launch {
            _playback.update { it.copy(span = span, scrubEpochSeconds = null) }
            refreshHistory(serverUrl, span, mustCover = epochSeconds)
            seekTo(epochSeconds)
        }
    }

    fun onPlayerPositionChanged(positionMs: Long) {
        _playback.update { current ->
            val playlist = current.playlist
            if (playlist == null || current.scrubEpochSeconds != null || current.isLoadingPlaylist) {
                current
            } else {
                val playhead = playlist.epochSecondsAt(positionMs / 1000.0)
                val preview = current.seekPreviewEpochSeconds
                val caughtUp = preview != null && !current.isBuffering && abs(playhead - preview) <= SEEK_PREVIEW_TOLERANCE_SECONDS
                current.copy(
                    playheadEpochSeconds = playhead,
                    seekPreviewEpochSeconds = if (caughtUp) null else preview,
                )
            }
        }
    }

    /**
     * Frigate's recording snapshot for [epochSeconds] on this camera, sized for the player
     * surface; null while disconnected. What the scrub preview and seek poster show.
     */
    fun recordingSnapshotUrl(epochSeconds: Double): String? =
        serverUrl.value?.let {
            getRecordingSnapshotUrlUseCase(it, cameraName, snapshotEpochSeconds(epochSeconds), height = SNAPSHOT_HEIGHT)
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
        val serverUrl = serverUrl.value
        if (serverUrl == null) {
            goLive()
            return
        }
        playlistLoadJob?.cancel()
        qualityUpgradeJob?.cancel()
        val startEpoch = epochSeconds.coerceIn(playlist.startEpochSeconds, playlist.endEpochSeconds)
        val resolvedStart = playlist.epochSecondsAt(playlist.positionSecondsFor(startEpoch))
        _playback.update {
            it.copy(
                playlist = playlist,
                playheadEpochSeconds = startEpoch,
                scrubEpochSeconds = null,
                seekPreviewEpochSeconds = resolvedStart,
                isLoadingPlaylist = true,
            )
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
                            posterUrl = recordingSnapshotUrl(resolvedStart),
                        ),
                        playWhenReady = current.isPlaying,
                        muted = current.isMuted,
                    ),
                )
            }
        }
    }

    /**
     * Reloads [history] for the timeline's window. [mustCover] — by default whatever is playing —
     * lying before that window (a Recent Activity moment older than the widest span) pulls in
     * that moment's own hour bucket as well, so playback there has a playlist to continue into
     * rather than being dropped back to live at the next refresh.
     */
    private suspend fun refreshHistory(
        serverUrl: String,
        span: TimelineSpan,
        mustCover: Double? = _playback.value.playheadEpochSeconds,
    ) {
        val now = now()
        val windowStart = now - span.seconds - HISTORY_LOOKBEHIND_PADDING_SECONDS
        val window = getRecordingHistoryUseCase(serverUrl, cameraName, afterEpochSeconds = windowStart, beforeEpochSeconds = now)
        val bucketStart = mustCover?.takeIf { it < windowStart }?.let { floor(it / RecordingHistory.DEFAULT_BUCKET_SECONDS) * RecordingHistory.DEFAULT_BUCKET_SECONDS }
        val extra = bucketStart?.let {
            getRecordingHistoryUseCase(
                serverUrl,
                cameraName,
                afterEpochSeconds = it - HISTORY_LOOKBEHIND_PADDING_SECONDS,
                beforeEpochSeconds = it + RecordingHistory.DEFAULT_BUCKET_SECONDS + HISTORY_LOOKBEHIND_PADDING_SECONDS,
            )
        }
        window
            .map { loaded -> extra?.getOrNull()?.let { RecordingHistory((loaded.segments + it.segments).distinct()) } ?: loaded }
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

        const val RECENT_MOMENTS = 3

        /** How long a fast live join plays the grid-quality stream before stepping up to full quality. */
        const val QUALITY_UPGRADE_DELAY_MS = 3_000L

        /** Playback within this of a seek's target counts as having arrived, and the preview snapshot comes down. */
        const val SEEK_PREVIEW_TOLERANCE_SECONDS = 3.0

        /** The detail player is full-width 16:9; 720 tall is sharp there and ~60 KB per frame. */
        const val SNAPSHOT_HEIGHT = 720
    }
}
