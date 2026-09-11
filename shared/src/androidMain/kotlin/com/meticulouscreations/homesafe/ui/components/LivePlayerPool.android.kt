package com.meticulouscreations.homesafe.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.meticulouscreations.homesafe.network.WhepSignalingClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOG_TAG = "HomeSafeLive"

/**
 * One long-lived player per camera that outlives any single composable, so a camera's video is
 * already decoding when the next screen asks for it. See [LivePlaybackPolicy] for the model; in
 * short:
 *
 *  - Binders ([CameraStreamPlayer] instances) attach, hand it a source, and each render it into
 *    their own surface. Any number may be attached at once — the grid card and the detail
 *    screen overlap for the length of the shared-element transition.
 *  - It plays only while at least one binder is attached and its lifecycle is started
 *    ([onBinderStarted]/[onBinderStopped]); otherwise it pauses immediately and, after
 *    [LivePlaybackPolicy.IDLE_STOP_MS], drops its network session and decoder ([needsColdStart]).
 *  - [coldStartGeneration] ticks on every cold (re)start. A binder shows its poster until it has
 *    rendered a frame for the current generation, so a snapshot covers a stale frame exactly when
 *    it should (cold start, reconnect, return from a long background) and never when it shouldn't
 *    (a live-to-live quality step keeps the previous frame up).
 *
 * Two engines sit behind one holder, and [transport] says which is showing the picture:
 *
 *  - **WebRTC** for a live source that carries a [WebRtcEndpoint], when [LiveTransportMemory]
 *    hasn't ruled it out: an [AndroidWebRtcPeer] whose remote video track feeds every attached
 *    [WebRtcTextureRenderer]. Joins are make-before-break — the previous engine keeps drawing
 *    until the new peer has a frame — and a join that fails (see [WebRtcConnectFlow]) starts HLS
 *    for the same source without a new generation, so the poster that was already up simply
 *    gives way to HLS video.
 *  - **HLS** through one [ExoPlayer], for recordings, for live sources without an endpoint, and
 *    as the fallback. ExoPlayer renders to a single surface, so when a second binder takes over
 *    the new surface is blank until the next video frame; [bindSurface] hands the incoming
 *    binder the last frame of the outgoing surface to show until then. Live HLS recovers from
 *    go2rtc's session expiry and other IO errors by re-preparing, backing off per
 *    [LivePlaybackPolicy.retryDelayMs] — only while someone is watching. Recordings get no
 *    retry: binders report those errors to their caller.
 *
 * All calls must be made on the main thread; ExoPlayer requires it and every entry point here is
 * driven from composition, lifecycle callbacks, or [scope] (main-immediate).
 */
internal class LivePlayerHolder(context: Context, val key: String?, private val webRtc: WebRtcConnectFlow) {

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

    /** Which engine is showing the picture. Compose state: binders draw (and clear) the matching surface. */
    var transport by mutableStateOf(LiveTransport.HLS)
        private set

    /** WebRTC only: ICE lost its path and is trying to get it back — the "Buffering" bit for that transport. */
    var webRtcStalled by mutableStateOf(false)
        private set

    /** WebRTC only: the peer has an audio track. */
    var webRtcHasAudio by mutableStateOf(false)
        private set

    private var requestedPlayWhenReady = true
    private var muted = true
    private var activeBinders = 0

    /**
     * The `TextureView` HLS frames currently go to, and whether it has drawn one. ExoPlayer renders to
     * a single surface, so when a second binder takes over (the detail screen opening over the
     * grid card, or the card taking back over when it closes) the new surface is blank until the
     * next video frame — on a low-rate sub-stream, long enough to read as a black flash mid
     * shared-element transition. [bridgeFrame] copies the last frame off the outgoing surface
     * for the incoming binder to show until its own first frame lands.
     */
    private var boundSurface: TextureView? = null
    private var boundSurfaceHasFrame = false

    /** Every attached WebRTC renderer; the live peer feeds them all, and a new peer inherits them. */
    private val renderers = LinkedHashSet<WebRtcTextureRenderer>()
    private var peer: AndroidWebRtcPeer? = null
    private var peerWatchJob: Job? = null
    private var joinJob: Job? = null

    /** Route HLS video to [surface]; returns the last frame of the surface it replaces, if that had one. */
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

    /** Called by the binder whose HLS surface just rendered a frame. */
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

    /** WebRTC frames go to [renderer] too, from the current peer and any that replaces it. */
    fun bindRenderer(renderer: WebRtcTextureRenderer) {
        if (renderers.add(renderer)) peer?.addSink(renderer)
    }

    fun unbindRenderer(renderer: WebRtcTextureRenderer) {
        if (renderers.remove(renderer)) peer?.removeSink(renderer)
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
            handleHlsError(error)
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
     * tracks and stall for a beat, whereas volume is instant — and the choice survives every
     * source swap and cold restart, on either engine, for the life of the holder.
     */
    fun setMuted(muted: Boolean) {
        this.muted = muted
        player.volume = if (muted) 0f else 1f
        peer?.setMuted(muted)
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
        joinJob?.cancel()
        dropPeer()
        renderers.clear()
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
        // Warm: the session is still alive, so this is a jump to the live edge on data the
        // player already has — the last frame stays up until the next one lands. A WebRTC peer
        // has no buffer to skip; re-enabling its track is the whole resume.
        if (current is VideoSource.Live && transport == LiveTransport.HLS) player.seekToDefaultPosition()
        applyPlayWhenReady()
    }

    private fun suspendPlayback() {
        retryJob?.cancel()
        applyPlayWhenReady()
        idleStopJob = scope.launch {
            delay(LivePlaybackPolicy.IDLE_STOP_MS)
            if (source is VideoSource.Recording) resumePositionMs = player.currentPosition
            joinJob?.cancel()
            joinJob = null
            dropPeer()
            player.stop()
            needsColdStart = true
        }
    }

    private fun applyPlayWhenReady() {
        val playing = requestedPlayWhenReady && activeBinders > 0
        player.playWhenReady = playing
        // A paused or unwatched peer stops decoding; the connection itself stays up for the idle window.
        peer?.setVideoEnabled(playing)
    }

    private fun start(toLoad: VideoSource, cold: Boolean) {
        needsColdStart = false
        if (cold) coldStartGeneration++
        joinJob?.cancel()
        joinJob = null
        val endpoint = (toLoad as? VideoSource.Live)?.webRtc
        if (endpoint != null && LiveTransportMemory.shared.allowsWebRtc(toLoad.url)) {
            startWebRtc(toLoad, endpoint)
        } else {
            startHls(toLoad)
        }
    }

    /**
     * Joins in the background while whatever is on screen stays there. On success the peer takes
     * over ([adopt]); on failure HLS starts for the same source. Either way the picture never
     * goes black on the way: a cold start already has the poster up, and a warm swap keeps the
     * previous engine's frame until the new one draws.
     */
    private fun startWebRtc(toLoad: VideoSource.Live, endpoint: WebRtcEndpoint) {
        val startedAt = SystemClock.elapsedRealtime()
        joinJob = scope.launch {
            val result = webRtc.connect(endpoint, streamKey = toLoad.url)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (source?.url != toLoad.url) {
                // Superseded while joining: the newer load owns the holder now.
                (result as? WebRtcConnectResult.Connected)?.peer?.close()
                return@launch
            }
            when (result) {
                is WebRtcConnectResult.Connected -> {
                    Log.d(LOG_TAG, "$key: webrtc joined in ${elapsed}ms")
                    adopt(result.peer as AndroidWebRtcPeer)
                }

                is WebRtcConnectResult.Failed -> {
                    Log.w(LOG_TAG, "$key: webrtc join failed after ${elapsed}ms (${result.reason}); playing HLS")
                    startHls(toLoad)
                }
            }
        }
    }

    private fun adopt(newPeer: AndroidWebRtcPeer) {
        val previous = peer
        peerWatchJob?.cancel()
        peer = newPeer
        renderers.forEach(newPeer::addSink)
        newPeer.setMuted(muted)
        newPeer.setVideoEnabled(requestedPlayWhenReady && activeBinders > 0)
        webRtcStalled = false
        webRtcHasAudio = newPeer.hasAudio.value
        transport = LiveTransport.WEBRTC
        consecutiveFailures = 0
        previous?.close()
        // The HLS session, if one was carrying this camera, has nothing left to show.
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        watch(newPeer)
    }

    private fun watch(watched: AndroidWebRtcPeer) {
        peerWatchJob = scope.launch {
            launch { watched.hasAudio.collect { if (peer === watched) webRtcHasAudio = it } }
            watched.state.collect { state ->
                if (peer !== watched) return@collect
                when (state) {
                    WebRtcPeerState.Connected -> webRtcStalled = false
                    WebRtcPeerState.Disconnected -> webRtcStalled = true
                    is WebRtcPeerState.Failed, WebRtcPeerState.Closed -> handleWebRtcLoss()
                    WebRtcPeerState.Connecting -> Unit
                }
            }
        }
    }

    private fun dropPeer() {
        peerWatchJob?.cancel()
        peerWatchJob = null
        val dropped = peer ?: return
        peer = null
        dropped.close()
        webRtcStalled = false
        webRtcHasAudio = false
    }

    private fun startHls(toLoad: VideoSource) {
        // A working peer's last frame is on the renderers on top of the HLS surface; a fresh
        // generation puts the poster over both until HLS draws, and the binders clear the renderers.
        if (peer != null && !needsColdStart) coldStartGeneration++
        dropPeer()
        transport = LiveTransport.HLS
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

    /** The connection went away after it had been adopted: start over (which consults the memory again), backing off like HLS does. */
    private fun handleWebRtcLoss() {
        needsColdStart = true
        dropPeer()
        val failed = source as? VideoSource.Live ?: return
        if (activeBinders == 0) return
        consecutiveFailures++
        Log.w(LOG_TAG, "$key: webrtc connection lost; retry $consecutiveFailures")
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(LivePlaybackPolicy.retryDelayMs(consecutiveFailures))
            if (source == failed && activeBinders > 0 && needsColdStart) start(failed, cold = true)
        }
    }

    private fun handleHlsError(error: PlaybackException) {
        if (transport != LiveTransport.HLS) return // a stopped HLS session under a working peer
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
    private var webRtc: WebRtcConnectFlow? = null

    fun acquire(context: Context, key: String?): LivePlayerHolder {
        val appContext = context.applicationContext
        if (key == null) return LivePlayerHolder(appContext, key = null, webRtc = connectFlow(appContext))
        val entry = entries.getOrPut(key) { Entry(LivePlayerHolder(appContext, key, connectFlow(appContext))) }
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

    /** One signaling client and peer factory for every holder; libwebrtc itself is initialised on the first peer. */
    private fun connectFlow(appContext: Context): WebRtcConnectFlow = webRtc ?: WebRtcConnectFlow(
        signaling = WhepSignalingClient(HttpClient(OkHttp) { install(HttpTimeout) }),
        peers = { audio -> AndroidWebRtcPeer(WebRtcRuntime.peerConnectionFactory(appContext), audio) },
        memory = LiveTransportMemory.shared,
    ).also { webRtc = it }
}
