package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.error
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

private const val HOLDER_POLL_INTERVAL_MS = 250L
private const val PREFERRED_FORWARD_BUFFER_SECONDS = 3.0
internal const val PREFERRED_TIMESCALE = 600

/** AVURLAsset option key for per-request HTTP headers; a string literal because the SDK constant is not exposed to Kotlin/Native. */
private const val HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"

/**
 * One [AVPlayer] that outlives any single composable — the iOS counterpart of the Android
 * `LivePlayerHolder`, with the same policy (see [LivePlaybackPolicy]): binders attach and each
 * show it through their own `AVPlayerLayer` (any number of layers may display one player at
 * once); it plays only while a started binder is attached, pauses otherwise, and drops its item
 * after [LivePlaybackPolicy.IDLE_STOP_MS] idle; live sources recover from go2rtc session expiry
 * by loading a fresh item; [coldStartGeneration] tells binders when to cover the layer with a
 * poster.
 *
 * `AVPlayerItem.status` and `AVPlayerLayer.readyForDisplay` are KVO-only with no notification
 * equivalent, and this codebase's iOS interop favours block-based APIs over raw KVO/NSObject
 * subclassing — so health is polled from directly-readable properties on a short interval,
 * combined with NotificationCenter observation for end/stall/failure events.
 */
@OptIn(ExperimentalForeignApi::class)
internal class LivePlayerHolder(val key: String?) {

    /** Recording-only events a binder relays to its caller; live sources handle theirs internally. */
    interface Listener {
        fun onRecordingEnded()
        fun onRecordingFailed()
    }

    val player: AVPlayer = AVPlayer().apply { automaticallyWaitsToMinimizeStalling = false }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationCenter = NSNotificationCenter.defaultCenter
    private val listeners = mutableListOf<Listener>()

    var source: VideoSource? = null
        private set

    var coldStartGeneration by mutableIntStateOf(0)
        private set

    private var requestedPlayWhenReady = true
    private var activeBinders = 0
    private var needsColdStart = true
    private var resumePositionMs: Long? = null
    private var consecutiveFailures = 0
    private var retryJob: Job? = null
    private var idleStopJob: Job? = null
    private var reportedErrorForItem: AVPlayerItem? = null
    private var endObserver: Any? = null
    private var failedObserver: Any? = null
    private var stalledObserver: Any? = null

    private val pollJob = scope.launch {
        while (isActive) {
            delay(HOLDER_POLL_INTERVAL_MS)
            val item = player.currentItem ?: continue
            when (source) {
                is VideoSource.Live -> if (item.error != null) scheduleLiveRetry() else if (item.status == AVPlayerItemStatusReadyToPlay) consecutiveFailures = 0
                is VideoSource.Recording -> if (item.status == AVPlayerItemStatusFailed) reportRecordingFailure(item)
                null -> Unit
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

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

    fun onBinderStarted() {
        activeBinders++
        idleStopJob?.cancel()
        idleStopJob = null
        if (activeBinders == 1) resume()
    }

    fun onBinderStopped() {
        activeBinders--
        if (activeBinders == 0) suspendPlayback()
    }

    fun release() {
        pollJob.cancel()
        retryJob?.cancel()
        idleStopJob?.cancel()
        scope.cancel()
        clearObservers()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
    }

    private fun resume() {
        consecutiveFailures = 0
        val current = source ?: return
        if (needsColdStart) {
            start(current, cold = true)
            return
        }
        // Warm: jump to the live edge of the item the player still holds; the layer keeps its last frame meanwhile.
        if (current is VideoSource.Live) player.seekToTime(kCMTimePositiveInfinity.readValue())
        applyPlayWhenReady()
    }

    private fun suspendPlayback() {
        retryJob?.cancel()
        applyPlayWhenReady()
        idleStopJob = scope.launch {
            delay(LivePlaybackPolicy.IDLE_STOP_MS)
            if (source is VideoSource.Recording) {
                val seconds = CMTimeGetSeconds(player.currentTime())
                if (!seconds.isNaN()) resumePositionMs = (seconds * 1000).toLong()
            }
            clearObservers()
            player.replaceCurrentItemWithPlayerItem(null)
            needsColdStart = true
        }
    }

    private fun applyPlayWhenReady() {
        if (requestedPlayWhenReady && activeBinders > 0) player.play() else player.pause()
    }

    private fun start(toLoad: VideoSource, cold: Boolean) {
        needsColdStart = false
        if (cold) coldStartGeneration++
        // Each start registers a fresh set of observers on the new item; without this the previous
        // item's observer tokens would sit in NotificationCenter forever.
        clearObservers()
        val options: Map<Any?, *>? = toLoad.headers.takeIf { it.isNotEmpty() }?.let { mapOf(HTTP_HEADER_FIELDS_KEY to it) }
        val asset = AVURLAsset(uRL = NSURL(string = toLoad.url), options = options)
        val item = AVPlayerItem(asset = asset).apply { preferredForwardBufferDuration = PREFERRED_FORWARD_BUFFER_SECONDS }
        player.replaceCurrentItemWithPlayerItem(item)
        if (toLoad is VideoSource.Recording) {
            val startMs = resumePositionMs ?: toLoad.startPositionMs
            player.seekToTime(
                time = CMTimeMakeWithSeconds(startMs / 1000.0, PREFERRED_TIMESCALE),
                toleranceBefore = kCMTimeZero.readValue(),
                toleranceAfter = kCMTimeZero.readValue(),
            )
        }
        resumePositionMs = null
        registerObservers(item)
        applyPlayWhenReady()
    }

    private fun registerObservers(item: AVPlayerItem) {
        endObserver = notificationCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            when (source) {
                is VideoSource.Live -> scheduleLiveRetry()
                is VideoSource.Recording -> listeners.toList().forEach { it.onRecordingEnded() }
                null -> Unit
            }
        }
        failedObserver = notificationCenter.addObserverForName(
            AVPlayerItemFailedToPlayToEndTimeNotification,
            item,
            NSOperationQueue.mainQueue,
        ) { onStallOrFailure(item) }
        stalledObserver = notificationCenter.addObserverForName(
            AVPlayerItemPlaybackStalledNotification,
            item,
            NSOperationQueue.mainQueue,
        ) { onStallOrFailure(item) }
    }

    private fun clearObservers() {
        endObserver?.let { notificationCenter.removeObserver(it) }
        failedObserver?.let { notificationCenter.removeObserver(it) }
        stalledObserver?.let { notificationCenter.removeObserver(it) }
        endObserver = null
        failedObserver = null
        stalledObserver = null
    }

    private fun onStallOrFailure(item: AVPlayerItem) {
        when (source) {
            is VideoSource.Live -> scheduleLiveRetry()
            is VideoSource.Recording -> reportRecordingFailure(item)
            null -> Unit
        }
    }

    private fun reportRecordingFailure(item: AVPlayerItem) {
        if (reportedErrorForItem === item) return
        reportedErrorForItem = item
        listeners.toList().forEach { it.onRecordingFailed() }
    }

    private fun scheduleLiveRetry() {
        needsColdStart = true
        val failed = source as? VideoSource.Live ?: return
        if (activeBinders == 0 || retryJob?.isActive == true) return
        consecutiveFailures++
        retryJob = scope.launch {
            delay(LivePlaybackPolicy.retryDelayMs(consecutiveFailures))
            if (source == failed && activeBinders > 0 && needsColdStart) start(failed, cold = true)
        }
    }
}

/** See the Android `LivePlayerPool`: one holder per key, kept briefly past its last binder. */
internal object LivePlayerPool {

    private class Entry(val holder: LivePlayerHolder) {
        var refCount = 0
        var releaseJob: Job? = null
    }

    private val entries = HashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
