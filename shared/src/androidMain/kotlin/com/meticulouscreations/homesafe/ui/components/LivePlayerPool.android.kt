package com.meticulouscreations.homesafe.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One [ExoPlayer] that outlives any single composable, so a camera's video is already decoding
 * when the next screen asks for it. See [LivePlaybackPolicy] for the model; in short:
 *
 *  - Binders ([CameraStreamPlayer] instances) attach, hand it a source, and each render it into
 *    their own `TextureView`. Any number may be attached at once — the grid card and the detail
 *    screen overlap for the length of the shared-element transition — and the most recently
 *    attached surface is the one frames go to.
 *  - It plays only while at least one binder is attached and its lifecycle is started
 *    ([onBinderStarted]/[onBinderStopped]); otherwise it pauses immediately and, after
 *    [LivePlaybackPolicy.IDLE_STOP_MS], drops its network session and decoder ([needsColdStart]).
 *  - Live sources recover from go2rtc's session expiry and other IO errors by re-preparing with
 *    a fresh top-level playlist request (go2rtc mints a new session per request), backing off per
 *    [LivePlaybackPolicy.retryDelayMs] — but only while someone is watching. Recordings get no
 *    retry: binders report those errors to their caller.
 *  - [coldStartGeneration] ticks on every cold (re)start. A binder shows its poster until it has
 *    rendered a frame for the current generation, so a snapshot covers a stale frame exactly when
 *    it should (cold start, reconnect, return from a long background) and never when it shouldn't
 *    (a live-to-live quality step keeps the previous frame up).
 *
 * All calls must be made on the main thread; ExoPlayer requires it and every entry point here is
 * driven from composition, lifecycle callbacks, or [scope] (main-immediate).
 */
internal class LivePlayerHolder(context: Context, val key: String?) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Tuned for live low-latency HLS rather than DefaultLoadControl's VOD-oriented
                // defaults (15s/50s/2.5s/5s). bufferForPlaybackMs=250 is deliberately below the
                // 500ms segment duration go2rtc actually serves (measured against the real server:
                // #EXTINF:0.500 per segment) so playback can start once roughly half of the first
                // segment has been fetched and demuxed, instead of waiting for the whole thing.
                .setBufferDurationsMs(1_500, 8_000, 250, 1_000)
                .build(),
        )
        .build()
        .apply { repeatMode = Player.REPEAT_MODE_OFF }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What this player was last asked to play; null until the first binder hands it a source. */
    var source: VideoSource? = null
        private set

    /** Bumped on every cold (re)start. Compose state, so binders recompose their poster on it. */
    var coldStartGeneration by mutableIntStateOf(0)
        private set

    private var requestedPlayWhenReady = true
    private var activeBinders = 0

    /**
     * The `TextureView` frames currently go to, and whether it has drawn one. ExoPlayer renders to
     * a single surface, so when a second binder takes over (the detail screen opening over the
     * grid card, or the card taking back over when it closes) the new surface is blank until the
     * next video frame — on a low-rate sub-stream, long enough to read as a black flash mid
     * shared-element transition. [bridgeFrame] copies the last frame off the outgoing surface
     * for the incoming binder to show until its own first frame lands.
     */
    private var boundSurface: TextureView? = null
    private var boundSurfaceHasFrame = false

    /** Route video to [surface]; returns the last frame of the surface it replaces, if that had one. */
    fun bindSurface(surface: TextureView): Bitmap? {
        val previous = boundSurface
        val bridge = if (previous != null && previous !== surface && boundSurfaceHasFrame && previous.isAvailable) {
            runCatching { previous.bitmap }.getOrNull()
        } else {
            null
        }
        boundSurface = surface
        boundSurfaceHasFrame = false
        player.setVideoTextureView(surface)
        return bridge
    }

    /** Called by the binder whose surface just rendered a frame. */
    fun onSurfaceRenderedFrame(surface: TextureView) {
        if (surface === boundSurface) boundSurfaceHasFrame = true
    }

    /** Detach [surface]; a no-op if another binder has since taken over (ExoPlayer checks identity). */
    fun unbindSurface(surface: TextureView) {
        player.clearVideoTextureView(surface)
        if (boundSurface === surface) {
            boundSurface = null
            boundSurfaceHasFrame = false
        }
    }

    /** True while the player holds no usable session for [source]: never loaded, stopped when idle, or failed. */
    private var needsColdStart = true

    /** Where a recording was when its player was stopped for idleness, so a cold restart resumes there instead of at the clip's start. */
    private var resumePositionMs: Long? = null
    private var consecutiveFailures = 0
    private var retryJob: Job? = null
    private var idleStopJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            consecutiveFailures = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    init {
        player.addListener(listener)
    }

    /** Play [next]. A no-op when it's already the current, healthy source — that's the shared-player fast path. */
    fun load(next: VideoSource) {
        val current = source
        if (current != null && current.url == next.url && !needsColdStart) {
            source = next
            return
        }
        source = next
        resumePositionMs = null
        consecutiveFailures = 0
        retryJob?.cancel()
        if (activeBinders == 0) {
            // Nobody can see it: don't open a session now, let the next resume start it cold.
            needsColdStart = true
            return
        }
        // No live session (stopped idle, or failed) means whatever is on screen is stale too.
        start(next, cold = needsColdStart || LivePlaybackPolicy.isColdSwap(current, next))
    }

    fun setPlayWhenReady(value: Boolean) {
        requestedPlayWhenReady = value
        applyPlayWhenReady()
    }

    /**
     * Volume, not track selection: flipping the audio renderer on and off would re-select
     * tracks and stall for a beat, whereas volume is instant — and the player's volume survives
     * every source swap and cold restart, so the choice sticks for the life of the holder.
     */
    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    /** A binder is attached and its lifecycle is started: someone can see this player. */
    fun onBinderStarted() {
        activeBinders++
        idleStopJob?.cancel()
        idleStopJob = null
        if (activeBinders == 1) resume()
    }

    /** A binder stopped or left composition. At zero, nobody can see this player. */
    fun onBinderStopped() {
        activeBinders--
        if (activeBinders == 0) suspendPlayback()
    }

    fun release() {
        retryJob?.cancel()
        idleStopJob?.cancel()
        scope.cancel()
        player.removeListener(listener)
        player.release()
    }

    private fun resume() {
        // A user coming back is a fresh budget, whatever happened while they were away.
        consecutiveFailures = 0
        val current = source ?: return
        if (needsColdStart) {
            start(current, cold = true)
            return
        }
        // Warm: the session is still alive and the playlist still being refreshed, so this is a
        // jump to the live edge on data the player already has — the last frame stays up until
        // the next one lands.
        if (current is VideoSource.Live) player.seekToDefaultPosition()
        applyPlayWhenReady()
    }

    private fun suspendPlayback() {
        retryJob?.cancel()
        applyPlayWhenReady()
        idleStopJob = scope.launch {
            delay(LivePlaybackPolicy.IDLE_STOP_MS)
            if (source is VideoSource.Recording) resumePositionMs = player.currentPosition
            player.stop()
            needsColdStart = true
        }
    }

    private fun applyPlayWhenReady() {
        player.playWhenReady = requestedPlayWhenReady && activeBinders > 0
    }

    private fun start(toLoad: VideoSource, cold: Boolean) {
        needsColdStart = false
        if (cold) coldStartGeneration++
        val dataSourceFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(toLoad.headers)
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(toLoad.url))
        when (toLoad) {
            is VideoSource.Live -> player.setMediaSource(mediaSource)
            is VideoSource.Recording -> player.setMediaSource(mediaSource, resumePositionMs ?: toLoad.startPositionMs)
        }
        resumePositionMs = null
        player.prepare()
        applyPlayWhenReady()
    }

    private fun handleError(error: PlaybackException) {
        // Whatever happened, the current session is gone; the next resume must start over.
        needsColdStart = true
        val failed = source as? VideoSource.Live ?: return // recordings: binders report to their caller
        if (!isRecoverableIoError(error.errorCode) || activeBinders == 0) return
        consecutiveFailures++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(LivePlaybackPolicy.retryDelayMs(consecutiveFailures))
            if (source == failed && activeBinders > 0 && needsColdStart) start(failed, cold = true)
        }
    }

    /**
     * IO errors — including the HTTP 404 go2rtc returns once a live HLS session has aged out —
     * are fixed by re-preparing. Decoder and other non-IO failures are not assumed to be.
     */
    private fun isRecoverableIoError(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> true
        else -> false
    }
}

/**
 * Hands out one [LivePlayerHolder] per key and keeps it for [LivePlaybackPolicy.POOL_RELEASE_MS]
 * after its last binder leaves, so navigating grid → detail → grid, or away to another tab and
 * back, rebinds to a player that's already running (or paused on its last frame) instead of
 * connecting from scratch. A null key gets a private, unpooled holder released with its binder.
 */
internal object LivePlayerPool {

    private class Entry(val holder: LivePlayerHolder) {
        var refCount = 0
        var releaseJob: Job? = null
    }

    private val entries = HashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun acquire(context: Context, key: String?): LivePlayerHolder {
        val appContext = context.applicationContext
        if (key == null) return LivePlayerHolder(appContext, key = null)
        val entry = entries.getOrPut(key) { Entry(LivePlayerHolder(appContext, key)) }
        entry.releaseJob?.cancel()
        entry.releaseJob = null
        entry.refCount++
        return entry.holder
    }

    fun release(holder: LivePlayerHolder) {
        val key = holder.key
        val entry = key?.let { entries[it] }
        if (entry == null || entry.holder !== holder) {
            holder.release()
            return
        }
        entry.refCount--
        if (entry.refCount > 0) return
        entry.releaseJob = scope.launch {
            delay(LivePlaybackPolicy.POOL_RELEASE_MS)
            if (entries[key] === entry && entry.refCount == 0) {
                entries.remove(key)
                entry.holder.release()
            }
        }
    }
}
