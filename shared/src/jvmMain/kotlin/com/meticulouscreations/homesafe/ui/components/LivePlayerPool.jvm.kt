package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * One camera's playback, outliving any single composable so its video is already decoding when
 * the next screen asks for it. The desktop counterpart of the Android and iOS holders, following
 * the same model ([LivePlaybackPolicy]):
 *
 *  - Binders ([CameraStreamPlayer] instances) attach, hand it a source, and draw [frame]. Any
 *    number may be attached at once — the grid card and the detail screen overlap for the length
 *    of the shared-element transition — and because the decoded frame lives here rather than on a
 *    per-binder surface, a second binder shows the picture the moment it appears. That is the one
 *    place this is simpler than the other two platforms, which have to bridge a real frame from
 *    the outgoing surface to the incoming one.
 *  - It plays only while at least one binder is attached and started ([onBinderStarted] /
 *    [onBinderStopped]); otherwise it pauses immediately and, after the idle window
 *    ([LivePlaybackPolicy.awaitIdleWindow]), gives up the connection entirely.
 *  - Live sources recover from go2rtc's session expiry and from network errors by opening a fresh
 *    session, backing off per [LivePlaybackPolicy.retryDelayMs], but only while someone is
 *    watching. Recordings get no retry: binders report those failures to their caller.
 *  - [coldStartGeneration] ticks on every cold (re)start. A binder shows its poster until a frame
 *    has arrived for the current generation, so a snapshot covers a stale picture exactly when it
 *    should — a first open, a reconnect, a return from a long background — and never when it
 *    shouldn't, such as a live-to-live quality step, which keeps the previous frame up.
 *
 * Where the other two platforms pause a live player and keep its session, this closes it and opens
 * a new one on the way back. FFmpeg has no pause for a network stream, and go2rtc's HLS sessions
 * expire behind a paused player anyway, so a fresh playlist request is both the simplest thing to
 * write and the only one that reliably lands at the live edge. It is warm all the same: nothing
 * bumps [coldStartGeneration], so the last frame stays on screen and no poster reappears over it.
 *
 * All calls must be made on the event dispatch thread, which is where composition, the pool and
 * [scope] all run.
 */
internal class LivePlayerHolder(val key: String?) {

    /** What a binder needs to tell its caller about a recording. Live sources never report here. */
    internal interface Listener {
        fun onRecordingEnded()
        fun onRecordingFailed()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    /** The most recent decoded frame, or null before this holder has ever shown one. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Bumped on every cold (re)start. Compose state, so binders recompose their poster on it. */
    var coldStartGeneration by mutableIntStateOf(0)
        private set

    /** The generation [frame] belongs to; while it lags behind, whatever is on screen is stale. */
    var renderedGeneration by mutableIntStateOf(-1)
        private set

    /** Playing, but starved of data — what a "Buffering" indicator keys off. */
    var isStalled by mutableStateOf(false)
        private set

    /** Whether what is playing carries an audio track this machine can decode. */
    var hasAudio by mutableStateOf(false)
        private set

    /** How far into the current recording playback has got. Meaningless for live sources. */
    var positionMs by mutableLongStateOf(0L)
        private set

    /** What this holder was last asked to play; null until the first binder hands it a source. */
    var source: VideoSource? = null
        private set

    private var session: FfmpegPlaybackSession? = null

    /**
     * Ticks on every session opened or closed, so a session's callbacks can tell whether they are
     * still wanted. Identity of the source is not enough to go on: [load] replaces [source] with an
     * equal one whenever a binder re-requests the same stream, which would orphan a healthy session.
     */
    private var sessionGeneration = 0
    private var requestedPlayWhenReady = true
    private var muted = true
    private var activeBinders = 0

    /** True while whatever the next session shows should be treated as new — see the poster. */
    private var needsColdStart = true

    /** Where a recording was when its session was dropped, so a restart resumes there. */
    private var resumePositionMs: Long? = null
    private var consecutiveFailures = 0
    private var retryJob: Job? = null
    private var idleStopJob: Job? = null

    private val listeners = mutableListOf<Listener>()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Play [next]. A no-op when it is already the current, healthy source — the shared-player fast path. */
    fun load(next: VideoSource) {
        val current = source
        if (current != null && current.url == next.url && session != null) {
            source = next
            return
        }
        source = next
        resumePositionMs = null
        consecutiveFailures = 0
        retryJob?.cancel()
        closeSession()
        if (activeBinders == 0) {
            // Nobody can see it: don't open a connection now, let the next resume start it cold.
            needsColdStart = true
            return
        }
        start(next, cold = needsColdStart || LivePlaybackPolicy.isColdSwap(current, next))
    }

    fun setPlayWhenReady(value: Boolean) {
        val wasPlaying = isPlaying()
        requestedPlayWhenReady = value
        if (isPlaying() == wasPlaying) return
        if (isPlaying() && source is VideoSource.Live) {
            // Coming off a pause on a live stream: reopen rather than resume, so playback picks up
            // at the live edge instead of at whatever the last session had already buffered.
            restartLive(cold = false)
        } else {
            session?.setPaused(!isPlaying())
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        session?.setMuted(value)
    }

    /** Seek within the current recording. */
    fun seekTo(positionMs: Long) {
        if (source !is VideoSource.Recording) return
        this.positionMs = positionMs
        session?.seekTo(positionMs)
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
        closeSession()
        scope.cancel()
    }

    private fun isPlaying(): Boolean = requestedPlayWhenReady && activeBinders > 0

    private fun resume() {
        // A user coming back is a fresh budget, whatever happened while they were away.
        consecutiveFailures = 0
        val current = source ?: return
        when {
            // Dropped for idleness, or never opened: a full cold start, poster and all.
            session == null && needsColdStart -> start(current, cold = true)

            // Inside the idle window. Live reopens at the live edge, keeping the frame that is
            // already on screen; a recording simply carries on from where it was paused.
            current is VideoSource.Live -> restartLive(cold = false)

            session == null -> start(current, cold = false)

            else -> session?.setPaused(false)
        }
    }

    private fun suspendPlayback() {
        retryJob?.cancel()
        session?.setPaused(true)
        // A live session paused is a live session going stale: nothing arrives, and go2rtc will
        // expire it out from under us, so drop it now and reopen on the way back.
        if (source is VideoSource.Live) closeSession()
        idleStopJob = scope.launch {
            LivePlaybackPolicy.awaitIdleWindow()
            if (source is VideoSource.Recording) resumePositionMs = positionMs
            closeSession()
            needsColdStart = true
        }
    }

    private fun restartLive(cold: Boolean) {
        val live = source as? VideoSource.Live ?: return
        closeSession()
        start(live, cold = cold)
    }

    private fun start(toLoad: VideoSource, cold: Boolean) {
        closeSession()
        needsColdStart = false
        if (cold) coldStartGeneration++
        val startPositionMs = when (toLoad) {
            is VideoSource.Live -> 0L
            is VideoSource.Recording -> resumePositionMs ?: toLoad.startPositionMs
        }
        resumePositionMs = null
        // So a timeline that reads this shows where playback is about to resume rather than
        // where the last recording happened to stop.
        positionMs = startPositionMs
        sessionGeneration++
        val started = FfmpegPlaybackSession(
            source = toLoad,
            startPositionMs = startPositionMs,
            scope = scope,
            listener = SessionListener(toLoad, sessionGeneration),
        )
        session = started
        started.start(playing = isPlaying(), muted = muted)
    }

    private fun closeSession() {
        if (session == null) return
        session?.close()
        session = null
        sessionGeneration++
        isStalled = false
    }

    /**
     * Routes one session's callbacks, ignoring any that arrive from a session this holder has
     * already moved on from — a decode thread can be part-way through reporting when its source
     * is swapped, and the frame it is carrying belongs to the camera that is no longer on screen.
     */
    private inner class SessionListener(
        private val forSource: VideoSource,
        private val generation: Int,
    ) : FfmpegPlaybackSession.Listener {

        private fun isCurrent(): Boolean = generation == sessionGeneration

        override fun onFrame(image: ImageBitmap, positionMs: Long) {
            if (!isCurrent()) return
            frame = image
            renderedGeneration = coldStartGeneration
            isStalled = false
            consecutiveFailures = 0
            if (forSource is VideoSource.Recording) this@LivePlayerHolder.positionMs = positionMs
        }

        override fun onAudioAvailability(hasAudio: Boolean) {
            if (isCurrent()) this@LivePlayerHolder.hasAudio = hasAudio
        }

        override fun onStalled(stalled: Boolean) {
            if (isCurrent()) isStalled = stalled
        }

        override fun onEnded() {
            if (!isCurrent()) return
            when (forSource) {
                // A live stream does not "end"; the server closed it, so treat it as a failure
                // and let the retry loop bring the picture back.
                is VideoSource.Live -> handleFailure()

                is VideoSource.Recording -> listeners.toList().forEach { it.onRecordingEnded() }
            }
        }

        override fun onFailed() {
            if (isCurrent()) handleFailure()
        }
    }

    /**
     * A live source lost its session: drop it and try again, with no give-up while somebody is
     * still watching. Unlike ExoPlayer, FFmpeg does not tell us apart an expired HLS session from
     * a decoder that will never work, so everything is retried — the back-off settles at one
     * request every 30 s ([LivePlaybackPolicy.retryDelayMs]), which is cheap enough to leave
     * running, and it is what brings the video back on its own after the server reboots.
     */
    private fun handleFailure() {
        closeSession()
        needsColdStart = true
        val failed = source
        val failedGeneration = sessionGeneration
        if (failed !is VideoSource.Live) {
            listeners.toList().forEach { it.onRecordingFailed() }
            return
        }
        if (activeBinders == 0) return
        consecutiveFailures++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(LivePlaybackPolicy.retryDelayMs(consecutiveFailures))
            if (sessionGeneration == failedGeneration && activeBinders > 0) start(failed, cold = true)
        }
    }
}

/**
 * Hands out one [LivePlayerHolder] per key and keeps it for [LivePlaybackPolicy.POOL_RELEASE_MS]
 * after its last binder leaves, so navigating grid → detail → grid, or away to another tab and
 * back, rebinds to a player that is already running (or paused on its last frame) instead of
 * connecting from scratch. A null key gets a private, unpooled holder released with its binder.
 */
internal object LivePlayerPool {

    private class Entry(val holder: LivePlayerHolder) {
        var refCount = 0
        var releaseJob: Job? = null
    }

    private val entries = HashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    fun acquire(key: String?): LivePlayerHolder {
        if (key == null) return LivePlayerHolder(key = null)
        val entry = entries.getOrPut(key) { Entry(LivePlayerHolder(key)) }
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
