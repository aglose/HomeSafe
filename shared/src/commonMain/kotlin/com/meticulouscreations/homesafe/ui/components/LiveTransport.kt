package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.network.WhepSignaling
import com.meticulouscreations.homesafe.network.WhepSignalingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.ExperimentalTime

/** Which engine a live source is being played through. */
enum class LiveTransport {
    /** A peer connection straight to go2rtc: frames arrive as the camera sends them. */
    WEBRTC,

    /** go2rtc's HLS playlist through the platform's media player: a couple of seconds behind, but works anywhere HTTP does. */
    HLS,
}

/** A peer connection's coarse state, as its platform engine reports it. */
sealed interface WebRtcPeerState {
    /** Created, offering, or waiting for ICE to find a path. */
    data object Connecting : WebRtcPeerState

    /** ICE found a path; media is (or is about to be) flowing. */
    data object Connected : WebRtcPeerState

    /** ICE lost the path; it may come back on its own (a Wi-Fi blip) or turn into [Failed]. */
    data object Disconnected : WebRtcPeerState

    /** Not coming back: ICE gave up, DTLS failed, or the engine reported an error. */
    data class Failed(val reason: String) : WebRtcPeerState

    /** [WebRtcPeer.close] was called. */
    data object Closed : WebRtcPeerState
}

/**
 * One receive-only peer connection to a go2rtc stream, as the platform engine exposes it to the
 * common join logic. Everything that touches libwebrtc lives behind this; [WebRtcConnectFlow]
 * only sequences it.
 */
interface WebRtcPeer {
    val state: StateFlow<WebRtcPeerState>

    /** True once the remote video track has delivered a decoded frame to this peer. Never goes back to false. */
    val firstFrameReceived: StateFlow<Boolean>

    /** True while the peer has a remote audio track it can play. */
    val hasAudio: StateFlow<Boolean>

    /**
     * Creates the offer for the tracks this peer was made for, waits for ICE gathering to
     * finish so the offer carries every host candidate, and returns the SDP. Throws when the
     * engine can't.
     */
    suspend fun createOffer(): String

    /** Applies the server's answer; ICE starts on return. Throws when the answer can't be applied. */
    suspend fun setAnswer(sdp: String)

    /**
     * Stops or resumes delivering the remote video to whatever is drawing it, without tearing
     * the connection down. The engine keeps decoding either way (there is no way to pause a
     * WHEP stream), so resuming shows the next frame at once — and what was last drawn stays
     * on screen meanwhile, rather than being replaced by black.
     */
    fun setVideoEnabled(enabled: Boolean)

    fun setMuted(muted: Boolean)

    /** Tears the connection down. Idempotent. */
    fun close()
}

fun interface WebRtcPeerFactory {
    /** A new peer that will offer to receive video, and audio too when [audio]. */
    fun create(audio: Boolean): WebRtcPeer
}

/** Why a WebRTC join didn't produce a picture. */
sealed interface WebRtcFailure {
    /**
     * The signaling server answered but not with an SDP answer, or couldn't be reached at all, or
     * not inside [LivePlaybackPolicy.WEBRTC_SIGNALING_TIMEOUT_MS] ([status] null for the last two).
     */
    data class Signaling(val status: Int?) : WebRtcFailure

    /** The offer, or ICE after the answer, took longer than [LivePlaybackPolicy.WEBRTC_CONNECT_TIMEOUT_MS]. */
    data object IceTimeout : WebRtcFailure

    /** Connected, but no decoded frame inside [LivePlaybackPolicy.WEBRTC_FIRST_FRAME_TIMEOUT_MS]. */
    data object NoFirstFrame : WebRtcFailure

    /** The engine reported an error at some stage. */
    data class PeerFailed(val reason: String) : WebRtcFailure
}

sealed interface WebRtcConnectResult {
    /** [peer] is connected and has rendered at least one frame; the caller now owns it. */
    data class Connected(val peer: WebRtcPeer) : WebRtcConnectResult

    /** The peer has been closed; play HLS instead. */
    data class Failed(val reason: WebRtcFailure) : WebRtcConnectResult
}

/**
 * Remembers, per stream, how WebRTC has been going, so a cold start can choose well:
 *
 *  - **Failing.** A card that rebinds every few seconds shouldn't pay the connect timeout each
 *    time before showing a picture. A stream is allowed
 *    [LivePlaybackPolicy.WEBRTC_FAILURES_BEFORE_FALLBACK] failed joins; after that it stays on
 *    HLS until [LivePlaybackPolicy.WEBRTC_FALLBACK_TTL_MS] has passed since the last failure
 *    ([allowsWebRtc]). Any successful join wipes the failures.
 *  - **Proven.** A stream that joined over WebRTC within [LivePlaybackPolicy.WEBRTC_PROVEN_TTL_MS]
 *    is trusted to do so again quickly ([recentlyConnected]); one that hasn't — a route this app
 *    has never joined on, or not for a week — gets HLS started alongside the join so a picture is
 *    up at HLS speed whatever ICE does (see the holders' `start`).
 *
 * A stream's key is its HLS URL — host included, so the LAN and Tailscale routes to the same
 * camera are separate entries, and moving between networks naturally gets a fresh try.
 *
 * The proven record outlives the process once a [Store] is [restore]d: a cold launch is exactly
 * when an HLS shadow costs most (three extra sessions against go2rtc while the cards are joining)
 * and when it is least needed, since the same routes joined fine the last time the app was open.
 * Failures stay session-scoped — a blocked port is worth re-checking after a relaunch.
 *
 * Not thread-safe; every caller runs on the main thread, like the player holders that use it.
 */
class LiveTransportMemory(
    private val now: () -> Long = { currentTimeMillis() },
    private val failuresBeforeFallback: Int = LivePlaybackPolicy.WEBRTC_FAILURES_BEFORE_FALLBACK,
    private val fallbackTtlMs: Long = LivePlaybackPolicy.WEBRTC_FALLBACK_TTL_MS,
    private val provenTtlMs: Long = LivePlaybackPolicy.WEBRTC_PROVEN_TTL_MS,
) {
    private class Record(var failures: Int, var lastFailureAt: Long)

    private val records = HashMap<String, Record>()
    private val lastConnectedAt = HashMap<String, Long>()
    private var store: Store? = null

    /** Where the proven record is kept between launches. */
    interface Store {
        /** Stream key to the epoch millis it last joined at. */
        fun load(): Map<String, Long>

        fun save(connectedAt: Map<String, Long>)
    }

    /**
     * Picks up what [store] kept from earlier launches — only records still inside the proven
     * window, and never over a fresher one from this session — and keeps it up to date from now on.
     */
    fun restore(store: Store) {
        this.store = store
        val now = now()
        for ((key, at) in store.load()) {
            if (now - at < provenTtlMs && (lastConnectedAt[key] ?: Long.MIN_VALUE) < at) lastConnectedAt[key] = at
        }
    }

    private fun persist() {
        store?.save(HashMap(lastConnectedAt))
    }

    /** Whether the next cold start of [streamKey] should try WebRTC before HLS. */
    fun allowsWebRtc(streamKey: String): Boolean {
        val record = records[streamKey] ?: return true
        if (now() - record.lastFailureAt >= fallbackTtlMs) {
            records.remove(streamKey)
            return true
        }
        return record.failures < failuresBeforeFallback
    }

    /**
     * Whether [streamKey] joined over WebRTC within [LivePlaybackPolicy.WEBRTC_PROVEN_TTL_MS] and
     * has not failed since — i.e. a fresh join can be expected to show a frame within a keyframe
     * interval, and needn't be shadowed by an HLS session.
     */
    fun recentlyConnected(streamKey: String): Boolean {
        val at = lastConnectedAt[streamKey] ?: return false
        if (now() - at >= provenTtlMs) {
            lastConnectedAt.remove(streamKey)
            return false
        }
        return true
    }

    fun markFailed(streamKey: String) {
        val record = records.getOrPut(streamKey) { Record(failures = 0, lastFailureAt = 0L) }
        record.failures++
        record.lastFailureAt = now()
        if (lastConnectedAt.remove(streamKey) != null) persist()
    }

    fun markConnected(streamKey: String) {
        records.remove(streamKey)
        lastConnectedAt[streamKey] = now()
        persist()
    }

    fun clear() {
        records.clear()
        lastConnectedAt.clear()
        persist()
    }

    companion object {
        /** The app-wide record every player holder consults. */
        val shared = LiveTransportMemory()

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
}

/**
 * The join itself, platform-free: make a peer, offer, trade the offer for an answer, wait for
 * ICE, wait for the first decoded frame — each stage inside the budget [LivePlaybackPolicy]
 * gives it — and tell [memory] how it went. On any failure the peer is closed before returning,
 * so a caller that gets [WebRtcConnectResult.Failed] has nothing to clean up and can start HLS
 * immediately. Cancelling the call closes the peer too.
 */
class WebRtcConnectFlow(
    private val signaling: WhepSignaling,
    private val peers: WebRtcPeerFactory,
    private val memory: LiveTransportMemory,
    private val connectTimeoutMs: Long = LivePlaybackPolicy.WEBRTC_CONNECT_TIMEOUT_MS,
    private val firstFrameTimeoutMs: Long = LivePlaybackPolicy.WEBRTC_FIRST_FRAME_TIMEOUT_MS,
    private val signalingTimeoutMs: Long = LivePlaybackPolicy.WEBRTC_SIGNALING_TIMEOUT_MS,
) {
    /**
     * [streamKey] identifies the stream in [memory]; the source's HLS URL. [onPeerCreated] is
     * handed the peer before the offer is made, for a caller that wants its frames drawn from the
     * very first one — the join itself still owns the peer until it returns
     * [WebRtcConnectResult.Connected], and closes it on failure.
     */
    suspend fun connect(endpoint: WebRtcEndpoint, streamKey: String, onPeerCreated: (WebRtcPeer) -> Unit = {}): WebRtcConnectResult {
        // The engine can refuse to build a peer at all (no native library, a bad configuration);
        // that is a join failure like any other, not an exception for the caller to survive.
        val peer = try {
            peers.create(endpoint.audio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            memory.markFailed(streamKey)
            return WebRtcConnectResult.Failed(WebRtcFailure.PeerFailed(e.message ?: "peer creation failed"))
        }
        val result = try {
            onPeerCreated(peer)
            attempt(peer, endpoint)
        } catch (e: CancellationException) {
            peer.close()
            throw e
        }
        when (result) {
            is WebRtcConnectResult.Connected -> memory.markConnected(streamKey)

            is WebRtcConnectResult.Failed -> {
                peer.close()
                memory.markFailed(streamKey)
            }
        }
        return result
    }

    private suspend fun attempt(peer: WebRtcPeer, endpoint: WebRtcEndpoint): WebRtcConnectResult {
        val stream = endpoint.signalingUrl.substringAfterLast("src=")
        connectStages(peer, endpoint, stream).reason?.let { return WebRtcConnectResult.Failed(it) }

        val frameOrFailure = withTimeoutOrNull(firstFrameTimeoutMs) {
            combine(peer.firstFrameReceived, peer.state) { frame, state -> frame to state }
                .first { (frame, state) -> frame || state is WebRtcPeerState.Failed }
        } ?: return WebRtcConnectResult.Failed(WebRtcFailure.NoFirstFrame)
        val (frame, state) = frameOrFailure
        if (!frame) return WebRtcConnectResult.Failed(WebRtcFailure.PeerFailed((state as WebRtcPeerState.Failed).reason))
        LiveStartupMilestones.mark("rtc.frame $stream")
        return WebRtcConnectResult.Connected(peer)
    }

    /** Non-null so a timeout (null from `withTimeoutOrNull`) is distinguishable from "reached a connection". */
    private class StageOutcome(val reason: WebRtcFailure?)

    /**
     * Offer, signaling, ICE. The local stages share [connectTimeoutMs], a stage that is slow on
     * its own being as good a sign of a dead route as one that never completes. Signaling has its
     * own, longer bound ([signalingTimeoutMs]): go2rtc answers only once it has a session with the
     * camera, and for a stream nobody was watching that means dialling the camera first — a
     * second or more of a server that is plainly there, which must not be mistaken for a dead one.
     */
    private suspend fun connectStages(peer: WebRtcPeer, endpoint: WebRtcEndpoint, stream: String): StageOutcome {
        LiveStartupMilestones.mark("rtc.start $stream")
        val offer = try {
            withTimeoutOrNull(connectTimeoutMs) { peer.createOffer() } ?: return StageOutcome(WebRtcFailure.IceTimeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StageOutcome(WebRtcFailure.PeerFailed(e.message ?: "offer failed"))
        }
        LiveStartupMilestones.mark("rtc.offer $stream")
        val answer = try {
            withTimeoutOrNull(signalingTimeoutMs) { signaling.exchange(endpoint.signalingUrl, offer) }
                ?: return StageOutcome(WebRtcFailure.Signaling(null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: WhepSignalingException) {
            return StageOutcome(WebRtcFailure.Signaling(e.status))
        } catch (e: Exception) {
            return StageOutcome(WebRtcFailure.Signaling(null))
        }
        LiveStartupMilestones.mark("rtc.answer $stream")
        try {
            peer.setAnswer(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StageOutcome(WebRtcFailure.PeerFailed(e.message ?: "answer rejected"))
        }
        val settled = withTimeoutOrNull(connectTimeoutMs) {
            peer.state.first { it is WebRtcPeerState.Connected || it is WebRtcPeerState.Failed || it is WebRtcPeerState.Closed }
        } ?: return StageOutcome(WebRtcFailure.IceTimeout)
        return when (settled) {
            is WebRtcPeerState.Failed -> StageOutcome(WebRtcFailure.PeerFailed(settled.reason))

            is WebRtcPeerState.Closed -> StageOutcome(WebRtcFailure.PeerFailed("closed"))

            else -> {
                // Only a connection counts: milestones are once per stream, and a failed first
                // attempt recorded here would be paired with a later attempt's first frame.
                LiveStartupMilestones.mark("rtc.ice $stream")
                StageOutcome(null)
            }
        }
    }
}
