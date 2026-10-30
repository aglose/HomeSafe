package com.meticulouscreations.homesafe.ui.components

import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSNumber
import platform.UIKit.UIView
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The WebRTC engine on iOS lives on the Swift side (`iosApp/iosApp/WebRtc/WebRtcPeerBridge.swift`,
 * over the `WebRTC` Swift package), because the Kotlin framework is a static library with no
 * cinterop against libwebrtc. Swift implements these three interfaces and registers a factory
 * in [IosWebRtc] at launch; the holder consults [IosWebRtc.peerFactory] and plays HLS when
 * there is none (unit tests, a build without the bridge).
 *
 * Every call across this boundary is on the main thread, in both directions: the bridge hops
 * its libwebrtc delegate callbacks to main before invoking the listener.
 */
interface IosWebRtcPeerFactory {
    /** A receive-only peer for video, and audio too when [audio]; [listener] hears its state. */
    fun create(audio: Boolean, listener: IosWebRtcPeerListener): IosWebRtcPeer
}

interface IosWebRtcPeerListener {
    fun onConnected()
    fun onDisconnected()
    fun onFailed(reason: String)
    fun onClosed()

    /** The remote video track delivered its first decoded frame (to any renderer or none). */
    fun onFirstFrame()

    /** The peer received a remote audio track it can play. */
    fun onAudioTrack()
}

interface IosWebRtcPeer {
    /** Creates the offer, waits for ICE gathering to complete, and hands back the SDP — or an error message. */
    fun createOffer(completion: (sdp: String?, error: String?) -> Unit)

    /** Applies the server's answer; [completion] gets an error message or null. */
    fun setAnswer(sdp: String, completion: (error: String?) -> Unit)

    /**
     * Draws the remote video into [container] (a view sized to the video box, filled edge to
     * edge, stretched like every other live surface). [onFirstFrame] fires once that renderer
     * has drawn — a fresh renderer on a peer that is already streaming still waits for its own frame.
     */
    fun attachRenderer(container: UIView, onFirstFrame: () -> Unit)

    fun detachRenderer(container: UIView)

    fun setVideoEnabled(enabled: Boolean)

    /**
     * Stops receiving the remote video, decoder and all, or starts it again, with the connection
     * kept up; restarted, the picture moves again at the stream's next keyframe. Only asked of a
     * peer whose answer is applied and whose first frame has come. A restart that fails reports
     * [IosWebRtcPeerListener.onFailed], since the video would otherwise stay still for good.
     */
    fun setVideoReceiving(receiving: Boolean)

    fun setMuted(muted: Boolean)

    /** Asks the engine for its statistics report; [completion] gets it, or null from a peer that is closed. */
    fun collectStats(completion: (report: IosWebRtcStatsReport?) -> Unit)

    fun close()
}

/** One entry of the engine's statistics report: an `RTCStatistics`, with its values as the engine gave them. */
class IosWebRtcStat(val id: String, val type: String, val values: Map<String, Any>)

/** A statistics report taken at [timestampMs] on the engine's own clock. */
class IosWebRtcStatsReport(val timestampMs: Double, val stats: List<IosWebRtcStat>)

/** Where the Swift app registers its engine; see `startIosApp`. */
object IosWebRtc {
    var peerFactory: IosWebRtcPeerFactory? = null
}

/**
 * [WebRtcPeer] over a Swift-side [IosWebRtcPeer], turning its callbacks into the state the common join logic reads.
 *
 * Video left disabled for [RECEIVE_GRACE_MS] stops being received and decoded
 * ([IosWebRtcPeer.setVideoReceiving]) until it is enabled again, as on Android: libwebrtc decodes
 * a track nobody draws, so Home's cameras would otherwise decode under every other screen for
 * the whole idle window. Not before the first frame: a join waits for that.
 */
internal class IosWebRtcPeerAdapter(factory: IosWebRtcPeerFactory, audio: Boolean) : WebRtcPeer {

    private val _state = MutableStateFlow<WebRtcPeerState>(WebRtcPeerState.Connecting)
    override val state: StateFlow<WebRtcPeerState> = _state.asStateFlow()

    private val _firstFrameReceived = MutableStateFlow(false)
    override val firstFrameReceived: StateFlow<Boolean> = _firstFrameReceived.asStateFlow()

    private val _hasAudio = MutableStateFlow(false)
    override val hasAudio: StateFlow<Boolean> = _hasAudio.asStateFlow()

    private var closed = false

    private val scope = MainScope()
    private var videoEnabled = true

    /** Whether the bridge was last told to receive the video; see the class doc. */
    private var receiving = true
    private var stopReceivingJob: Job? = null

    private val listener = object : IosWebRtcPeerListener {
        override fun onConnected() {
            _state.value = WebRtcPeerState.Connected
        }

        override fun onDisconnected() {
            _state.value = WebRtcPeerState.Disconnected
        }

        override fun onFailed(reason: String) {
            _state.value = WebRtcPeerState.Failed(reason)
        }

        override fun onClosed() {
            _state.value = WebRtcPeerState.Closed
        }

        override fun onFirstFrame() {
            _firstFrameReceived.value = true
        }

        override fun onAudioTrack() {
            _hasAudio.value = true
        }
    }

    private val peer: IosWebRtcPeer = factory.create(audio, listener)

    override suspend fun createOffer(): String = suspendCancellableCoroutine { continuation ->
        peer.createOffer { sdp, error ->
            if (!continuation.isActive) return@createOffer
            if (sdp != null) continuation.resume(sdp) else continuation.resumeWithException(IllegalStateException(error ?: "offer failed"))
        }
    }

    override suspend fun setAnswer(sdp: String) = suspendCancellableCoroutine { continuation ->
        peer.setAnswer(sdp) { error ->
            if (!continuation.isActive) return@setAnswer
            if (error == null) continuation.resume(Unit) else continuation.resumeWithException(IllegalStateException(error))
        }
    }

    fun addRenderer(container: UIView, onFirstFrame: () -> Unit) {
        if (!closed) peer.attachRenderer(container, onFirstFrame)
    }

    fun removeRenderer(container: UIView) {
        if (!closed) peer.detachRenderer(container)
    }

    override fun setVideoEnabled(enabled: Boolean) {
        if (closed) return
        peer.setVideoEnabled(enabled)
        if (videoEnabled == enabled) return
        videoEnabled = enabled
        stopReceivingJob?.cancel()
        stopReceivingJob = null
        if (enabled) {
            setReceiving(true)
        } else {
            stopReceivingJob = scope.launch {
                delay(RECEIVE_GRACE_MS)
                // A first frame still to come once the grace is up: try again until it has come,
                // or until enabling the video or closing the peer cancels this.
                while (!setReceiving(false)) delay(RECEIVE_RETRY_MS)
            }
        }
    }

    /** Tells the bridge to start or stop receiving the video; false when it can't be stopped yet (no first frame). */
    private fun setReceiving(receive: Boolean): Boolean {
        if (closed || receiving == receive) return true
        if (!receive && !_firstFrameReceived.value) return false
        receiving = receive
        peer.setVideoReceiving(receive)
        return true
    }

    override fun setMuted(muted: Boolean) = peer.setMuted(muted)

    override suspend fun receiverStats(): WebRtcReceiverStats? {
        if (closed) return null
        val report = suspendCancellableCoroutine<IosWebRtcStatsReport?> { continuation ->
            peer.collectStats { report -> if (continuation.isActive) continuation.resume(report) }
        } ?: return null
        // The engine's numbers and flags are all NSNumber; the common reader wants Kotlin's.
        val stats = report.stats.map { stat ->
            RtcStat(stat.id, stat.type, stat.values.mapValues { (_, value) -> (value as? NSNumber)?.doubleValue ?: value })
        }
        return webRtcReceiverStats(stats, report.timestampMs)
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        peer.close()
        _state.value = WebRtcPeerState.Closed
    }
}
