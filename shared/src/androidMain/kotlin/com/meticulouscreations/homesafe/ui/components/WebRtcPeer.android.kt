package com.meticulouscreations.homesafe.ui.components

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A receive-only libwebrtc peer connection, exposed to the common join logic as [WebRtcPeer].
 *
 * No STUN or TURN: the server is reached over the LAN or Tailscale, both of which are plain
 * routes between host addresses, so the offer carries only host candidates and gathers them
 * in a few milliseconds. Renderers attach as sinks on the remote video track; any number can,
 * so the grid card and the detail screen both draw during the shared-element transition, and
 * the peer counts frames itself for [firstFrameReceived] regardless of who is drawing.
 *
 * libwebrtc calls its observers on its own signaling thread; anything that touches this
 * object's collections is hopped to the main thread, where every caller of this class lives.
 */
internal class AndroidWebRtcPeer(factory: PeerConnectionFactory, audio: Boolean) : WebRtcPeer {

    private val _state = MutableStateFlow<WebRtcPeerState>(WebRtcPeerState.Connecting)
    override val state: StateFlow<WebRtcPeerState> = _state.asStateFlow()

    private val _firstFrameReceived = MutableStateFlow(false)
    override val firstFrameReceived: StateFlow<Boolean> = _firstFrameReceived.asStateFlow()

    private val _hasAudio = MutableStateFlow(false)
    override val hasAudio: StateFlow<Boolean> = _hasAudio.asStateFlow()

    private val mainThread = Handler(Looper.getMainLooper())
    private val gatheringComplete = CompletableDeferred<Unit>()
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private val sinks = LinkedHashSet<VideoSink>()
    private var videoEnabled = true
    private var muted = true
    private var closed = false

    private val frameCounter = VideoSink { if (!_firstFrameReceived.value) _firstFrameReceived.value = true }

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) gatheringComplete.complete(Unit)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> _state.value = WebRtcPeerState.Connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> _state.value = WebRtcPeerState.Disconnected
                PeerConnection.PeerConnectionState.FAILED -> _state.value = WebRtcPeerState.Failed("peer connection failed")
                PeerConnection.PeerConnectionState.CLOSED -> _state.value = WebRtcPeerState.Closed
                PeerConnection.PeerConnectionState.NEW, PeerConnection.PeerConnectionState.CONNECTING -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            val track = receiver.track() ?: return
            mainThread.post { adoptTrack(track) }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private val connection: PeerConnection = checkNotNull(
        factory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            },
            observer,
        ),
    ) { "libwebrtc refused to create a peer connection" }

    init {
        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
        if (audio) connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
    }

    override suspend fun createOffer(): String {
        val offer = suspendCancellableCoroutine { continuation ->
            connection.createOffer(
                object : SdpObserverAdapter() {
                    override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
                    override fun onCreateFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error ?: "createOffer failed"))
                },
                MediaConstraints(),
            )
        }
        suspendCancellableCoroutine { continuation ->
            connection.setLocalDescription(
                object : SdpObserverAdapter() {
                    override fun onSetSuccess() = continuation.resume(Unit)
                    override fun onSetFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error ?: "setLocalDescription failed"))
                },
                offer,
            )
        }
        // No trickle: wait for every host candidate so the one signaling round trip carries them all.
        gatheringComplete.await()
        return checkNotNull(connection.localDescription) { "no local description after gathering" }.description
    }

    override suspend fun setAnswer(sdp: String) {
        suspendCancellableCoroutine { continuation ->
            connection.setRemoteDescription(
                object : SdpObserverAdapter() {
                    override fun onSetSuccess() = continuation.resume(Unit)
                    override fun onSetFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error ?: "setRemoteDescription failed"))
                },
                SessionDescription(SessionDescription.Type.ANSWER, sdp),
            )
        }
    }

    /** Frames go to [sink] from now on (and to every other sink still attached). */
    fun addSink(sink: VideoSink) {
        if (closed || !sinks.add(sink)) return
        videoTrack?.addSink(sink)
    }

    fun removeSink(sink: VideoSink) {
        if (!sinks.remove(sink)) return
        videoTrack?.removeSink(sink)
    }

    override fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        videoTrack?.setEnabled(enabled)
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        audioTrack?.setVolume(if (muted) 0.0 else 1.0)
    }

    override fun close() {
        if (closed) return
        closed = true
        videoTrack?.let { track ->
            track.removeSink(frameCounter)
            sinks.forEach(track::removeSink)
        }
        sinks.clear()
        videoTrack = null
        audioTrack = null
        connection.close()
        connection.dispose()
        _state.value = WebRtcPeerState.Closed
    }

    private fun adoptTrack(track: MediaStreamTrack) {
        if (closed) return
        when (track) {
            is VideoTrack -> {
                videoTrack = track
                track.setEnabled(videoEnabled)
                track.addSink(frameCounter)
                sinks.forEach(track::addSink)
            }

            is AudioTrack -> {
                audioTrack = track
                track.setVolume(if (muted) 0.0 else 1.0)
                _hasAudio.value = true
            }
        }
    }

    /** libwebrtc's four-method observer, so each call site overrides only the pair it's waiting on. */
    private abstract class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
