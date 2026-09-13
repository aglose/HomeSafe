package com.meticulouscreations.homesafe.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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

    fun setMuted(muted: Boolean)

    fun close()
}

/** Where the Swift app registers its engine; see `startIosApp`. */
object IosWebRtc {
    var peerFactory: IosWebRtcPeerFactory? = null
}

/** [WebRtcPeer] over a Swift-side [IosWebRtcPeer], turning its callbacks into the state the common join logic reads. */
internal class IosWebRtcPeerAdapter(factory: IosWebRtcPeerFactory, audio: Boolean) : WebRtcPeer {

    private val _state = MutableStateFlow<WebRtcPeerState>(WebRtcPeerState.Connecting)
    override val state: StateFlow<WebRtcPeerState> = _state.asStateFlow()

    private val _firstFrameReceived = MutableStateFlow(false)
    override val firstFrameReceived: StateFlow<Boolean> = _firstFrameReceived.asStateFlow()

    private val _hasAudio = MutableStateFlow(false)
    override val hasAudio: StateFlow<Boolean> = _hasAudio.asStateFlow()

    private var closed = false

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

    override fun setVideoEnabled(enabled: Boolean) = peer.setVideoEnabled(enabled)

    override fun setMuted(muted: Boolean) = peer.setMuted(muted)

    override fun close() {
        if (closed) return
        closed = true
        peer.close()
        _state.value = WebRtcPeerState.Closed
    }
}
