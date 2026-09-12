package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meticulouscreations.homesafe.network.WhepSignalingClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
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
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIView

private const val HOLDER_POLL_INTERVAL_MS = 250L
private const val PREFERRED_FORWARD_BUFFER_SECONDS = 3.0
internal const val PREFERRED_TIMESCALE = 600

/** AVURLAsset option key for per-request HTTP headers; a string literal because the SDK constant is not exposed to Kotlin/Native. */
private const val HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"

/**
 * One long-lived player per camera — the iOS counterpart of the Android `LivePlayerHolder`,
 * with the same policy (see [LivePlaybackPolicy]): binders attach and each show it through
 * their own surfaces; it plays only while a started binder is attached, pauses otherwise, and
 * drops its session after [LivePlaybackPolicy.IDLE_STOP_MS] idle; [coldStartGeneration] tells
 * binders when to cover the picture with a poster.
 *
 * Two engines, and [transport] says which is showing the picture:
 *
 *  - **WebRTC**, for a live source that carries a [WebRtcEndpoint] when a Swift-side engine is
 *    registered ([IosWebRtc.peerFactory]) and [LiveTransportMemory] hasn't ruled it out: an
 *    [IosWebRtcPeerAdapter] whose remote video track is drawn into every attached container
 *    view. Joins are make-before-break; a failed join starts HLS for the same source without a
 *    new generation, so the poster already up gives way to HLS video.
 *  - **HLS** through one [AVPlayer] (any number of `AVPlayerLayer`s may display it), for
 *    recordings, sources without an endpoint, and as the fallback. Live sources recover from
 *    go2rtc session expiry by loading a fresh item.
 *
 * `AVPlayerItem.status` and `AVPlayerLayer.readyForDisplay` are KVO-only with no notification
 * equivalent, and this codebase's iOS interop favours block-based APIs over raw KVO/NSObject
 * subclassing — so health is polled from directly-readable properties on a short interval,
 * combined with NotificationCenter observation for end/stall/failure events.
 */
@OptIn(ExperimentalForeignApi::class)
internal class LivePlayerHolder(val key: String?, private val webRtc: WebRtcConnectFlow) {

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

    /** Which engine is showing the picture. Compose state: binders show the matching surface. */
    var transport by mutableStateOf(LiveTransport.HLS)
        private set

    /** WebRTC only: ICE lost its path and is trying to get it back. */
    var webRtcStalled by mutableStateOf(false)
        private set

    /** WebRTC only: the peer has an audio track. */
    var webRtcHasAudio by mutableStateOf(false)
        private set

    private var requestedPlayWhenReady = true
    private var muted = true
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

    /** Every attached WebRTC container and its first-frame callback; the live peer draws into them all, and a new peer inherits them. */
    private val renderers = LinkedHashMap<UIView, () -> Unit>()
    private var peer: IosWebRtcPeerAdapter? = null
    private var peerWatchJob: Job? = null
    private var joinJob: Job? = null

    private val pollJob = scope.launch {
        while (isActive) {
            delay(HOLDER_POLL_INTERVAL_MS)
            if (transport != LiveTransport.HLS) continue
            val item = player.currentItem ?: continue
            when (source) {
                is VideoSource.Live -> if (item.error != null) {
                    scheduleLiveRetry()
                } else if (item.status == AVPlayerItemStatusReadyToPlay) {
                    consecutiveFailures = 0
                }

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

    /** WebRTC frames are drawn into [container] too, by the current peer and any that replaces it; [onFrame] fires when it has drawn. */
    fun bindRenderer(container: UIView, onFrame: () -> Unit) {
        if (renderers.put(container, onFrame) == null) peer?.addRenderer(container, onFrame)
    }

    fun unbindRenderer(container: UIView) {
        if (renderers.remove(container) != null) peer?.removeRenderer(container)
    }

    /**
     * Play [next]. When it's already the current, healthy source — the shared-player fast path —
     * nothing restarts; but a WebRTC endpoint arriving for a source HLS is carrying (the detail
     * screen's warm join names only the URL, its view model's request adds the endpoint) starts
     * a join alongside, and the peer takes over when it has a frame.
     */
    fun load(next: VideoSource) {
        val current = source
        if (current != null && current.url == next.url && !needsColdStart) {
            source = next
            val endpoint = (next as? VideoSource.Live)?.webRtc
            val hlsCarrying = transport == LiveTransport.HLS && joinJob == null
            if (endpoint != null && hlsCarrying && activeBinders > 0 && webRtcAllowed(next.url)) startWebRtc(next, endpoint)
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

    /** Sticks across item replacements, cold restarts and either engine: `muted` lives on the holder. */
    fun setMuted(muted: Boolean) {
        this.muted = muted
        player.muted = muted
        peer?.setMuted(muted)
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
        joinJob?.cancel()
        dropPeer()
        renderers.clear()
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
        // Warm: jump to the live edge of the item the player still holds; the layer keeps its
        // last frame meanwhile. A peer has no buffer to skip; re-enabling its track is the resume.
        if (current is VideoSource.Live && transport == LiveTransport.HLS) player.seekToTime(kCMTimePositiveInfinity.readValue())
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
            joinJob?.cancel()
            joinJob = null
            dropPeer()
            clearObservers()
            player.replaceCurrentItemWithPlayerItem(null)
            needsColdStart = true
        }
    }

    private fun applyPlayWhenReady() {
        val playing = requestedPlayWhenReady && activeBinders > 0
        if (playing) player.play() else player.pause()
        peer?.setVideoEnabled(playing)
    }

    private fun webRtcAllowed(streamUrl: String): Boolean = IosWebRtc.peerFactory != null && LiveTransportMemory.shared.allowsWebRtc(streamUrl)

    private fun start(toLoad: VideoSource, cold: Boolean) {
        needsColdStart = false
        if (cold) coldStartGeneration++
        joinJob?.cancel()
        joinJob = null
        val endpoint = (toLoad as? VideoSource.Live)?.webRtc
        if (endpoint != null && webRtcAllowed(toLoad.url)) startWebRtc(toLoad, endpoint) else startHls(toLoad)
    }

    /** Joins in the background while whatever is on screen stays there; see the Android holder for the reasoning. */
    private fun startWebRtc(toLoad: VideoSource.Live, endpoint: WebRtcEndpoint) {
        val startedAt = NSDate().timeIntervalSince1970
        joinJob = scope.launch {
            val result = webRtc.connect(endpoint, streamKey = toLoad.url)
            val elapsedMs = ((NSDate().timeIntervalSince1970 - startedAt) * 1000).toLong()
            joinJob = null
            if (source?.url != toLoad.url) {
                (result as? WebRtcConnectResult.Connected)?.peer?.close()
                return@launch
            }
            when (result) {
                is WebRtcConnectResult.Connected -> {
                    NSLog("HomeSafeLive: %s webrtc joined in %d ms", key ?: "-", elapsedMs)
                    adopt(result.peer as IosWebRtcPeerAdapter)
                }

                is WebRtcConnectResult.Failed -> {
                    NSLog("HomeSafeLive: %s webrtc join failed after %d ms (%s); playing HLS", key ?: "-", elapsedMs, result.reason.toString())
                    if (transport != LiveTransport.HLS || player.currentItem == null) startHls(toLoad)
                }
            }
        }
    }

    private fun adopt(newPeer: IosWebRtcPeerAdapter) {
        val previous = peer
        peerWatchJob?.cancel()
        peer = newPeer
        renderers.forEach { (container, onFrame) -> newPeer.addRenderer(container, onFrame) }
        newPeer.setMuted(muted)
        newPeer.setVideoEnabled(requestedPlayWhenReady && activeBinders > 0)
        webRtcStalled = false
        webRtcHasAudio = newPeer.hasAudio.value
        transport = LiveTransport.WEBRTC
        consecutiveFailures = 0
        previous?.close()
        // The HLS session, if one was carrying this camera, has nothing left to show.
        clearObservers()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        watch(newPeer)
    }

    private fun watch(watched: IosWebRtcPeerAdapter) {
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
        // A working peer's last frame is on the containers over the player layer; a fresh
        // generation puts the poster over both until HLS draws, and the binders hide the containers.
        if (peer != null && !needsColdStart) coldStartGeneration++
        dropPeer()
        transport = LiveTransport.HLS
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

    /** The connection went away after it had been adopted: start over (which consults the memory again), backing off like HLS does. */
    private fun handleWebRtcLoss() {
        needsColdStart = true
        dropPeer()
        val failed = source as? VideoSource.Live ?: return
        if (activeBinders == 0) return
        consecutiveFailures++
        NSLog("HomeSafeLive: %s webrtc connection lost; retry %d", key ?: "-", consecutiveFailures)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(LivePlaybackPolicy.retryDelayMs(consecutiveFailures))
            if (source == failed && activeBinders > 0 && needsColdStart) start(failed, cold = true)
        }
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
        if (transport != LiveTransport.HLS) return // a stale item notification under a working peer
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

    /** One signaling client for every holder; a peer is only ever made when a Swift-side factory is registered. */
    private val webRtc: WebRtcConnectFlow by lazy {
        WebRtcConnectFlow(
            signaling = WhepSignalingClient(HttpClient(Darwin) { install(HttpTimeout) }),
            peers = { audio ->
                val factory = IosWebRtc.peerFactory ?: error("no WebRTC engine registered")
                IosWebRtcPeerAdapter(factory, audio)
            },
            memory = LiveTransportMemory.shared,
        )
    }

    fun acquire(key: String?): LivePlayerHolder {
        if (key == null) return LivePlayerHolder(key = null, webRtc = webRtc)
        val entry = entries.getOrPut(key) { Entry(LivePlayerHolder(key, webRtc)) }
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
