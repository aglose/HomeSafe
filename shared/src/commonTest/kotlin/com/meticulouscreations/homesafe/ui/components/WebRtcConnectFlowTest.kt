package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.network.WhepSignaling
import com.meticulouscreations.homesafe.network.WhepSignalingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcConnectFlowTest {

    private val endpoint = WebRtcEndpoint("http://frigate:1984/api/webrtc?src=cam", audio = false)
    private val streamKey = "http://frigate:1984/api/stream.m3u8?src=cam"

    /** A scripted peer: the test decides when ICE connects and when the first frame lands. */
    private class FakePeer : WebRtcPeer {
        override val state = MutableStateFlow<WebRtcPeerState>(WebRtcPeerState.Connecting)
        override val firstFrameReceived = MutableStateFlow(false)
        override val hasAudio: StateFlow<Boolean> = MutableStateFlow(false)
        var offerDelayMs = 0L
        var offerFails = false
        var answerFails = false
        var answer: String? = null
        var closed = false

        override suspend fun createOffer(): String {
            delay(offerDelayMs)
            if (offerFails) error("no decoder")
            return "v=0\r\noffer"
        }

        override suspend fun setAnswer(sdp: String) {
            if (answerFails) error("bad answer")
            answer = sdp
        }

        override fun setVideoEnabled(enabled: Boolean) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() {
            closed = true
            state.value = WebRtcPeerState.Closed
        }
    }

    private class FakeSignaling : WhepSignaling {
        var delayMs = 0L
        var failWith: WhepSignalingException? = null
        var unreachable = false
        val offers = mutableListOf<String>()

        override suspend fun exchange(signalingUrl: String, offerSdp: String): String {
            offers += offerSdp
            delay(delayMs)
            failWith?.let { throw it }
            if (unreachable) error("connect refused")
            return "v=0\r\nanswer"
        }
    }

    private class Harness {
        val peer = FakePeer()
        val signaling = FakeSignaling()
        val memory = LiveTransportMemory(now = { 0L })
        val flow = WebRtcConnectFlow(signaling, { peer }, memory, connectTimeoutMs = 3_000, firstFrameTimeoutMs = 5_000)
    }

    @Test
    fun aJoinThatConnectsAndRendersHandsThePeerBack() = runTest {
        val h = Harness()
        val result = CompletableDeferred<WebRtcConnectResult>()
        launch { result.complete(h.flow.connect(endpoint, streamKey)) }
        runCurrent()
        assertEquals(listOf("v=0\r\noffer"), h.signaling.offers)
        assertEquals("v=0\r\nanswer", h.peer.answer)
        assertFalse(result.isCompleted, "waits for ICE")

        h.peer.state.value = WebRtcPeerState.Connected
        runCurrent()
        assertFalse(result.isCompleted, "waits for the first frame")

        h.peer.firstFrameReceived.value = true
        runCurrent()
        val connected = assertIs<WebRtcConnectResult.Connected>(result.await())
        assertSame(h.peer, connected.peer)
        assertFalse(h.peer.closed)
        assertTrue(h.memory.allowsWebRtc(streamKey))
    }

    @Test
    fun iceThatNeverConnectsTimesOutClosesThePeerAndCountsAgainstTheStream() = runTest {
        val h = Harness()
        val result = CompletableDeferred<WebRtcConnectResult>()
        launch { result.complete(h.flow.connect(endpoint, streamKey)) }
        advanceTimeBy(2_999)
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.IceTimeout), result.await())
        assertTrue(h.peer.closed)
        // One failure is a retry-worthy blip; the memory only stops trying after the second.
        assertTrue(h.memory.allowsWebRtc(streamKey))
        h.memory.markFailed(streamKey)
        assertFalse(h.memory.allowsWebRtc(streamKey))
    }

    @Test
    fun theConnectBudgetCoversSignalingToo() = runTest {
        val h = Harness()
        h.signaling.delayMs = 10_000
        val result = CompletableDeferred<WebRtcConnectResult>()
        launch { result.complete(h.flow.connect(endpoint, streamKey)) }
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.IceTimeout), result.await())
        assertTrue(h.peer.closed)
    }

    @Test
    fun aConnectedPeerThatNeverDeliversAFrameIsGivenUpOn() = runTest {
        val h = Harness()
        val result = CompletableDeferred<WebRtcConnectResult>()
        launch { result.complete(h.flow.connect(endpoint, streamKey)) }
        runCurrent()
        h.peer.state.value = WebRtcPeerState.Connected
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.NoFirstFrame), result.await())
        assertTrue(h.peer.closed)
    }

    @Test
    fun aSignalingRefusalReportsTheStatus() = runTest {
        val h = Harness()
        h.signaling.failWith = WhepSignalingException(404, "no such stream")

        val result = h.flow.connect(endpoint, streamKey)

        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.Signaling(404)), result)
        assertTrue(h.peer.closed)
    }

    @Test
    fun anUnreachableSignalingServerIsASignalingFailureWithNoStatus() = runTest {
        val h = Harness()
        h.signaling.unreachable = true

        val result = h.flow.connect(endpoint, streamKey)

        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.Signaling(null)), result)
        assertTrue(h.peer.closed)
    }

    @Test
    fun anEngineErrorAtAnyStageIsAPeerFailure() = runTest {
        val offerBroken = Harness().apply { peer.offerFails = true }
        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.PeerFailed("no decoder")), offerBroken.flow.connect(endpoint, streamKey))
        assertTrue(offerBroken.peer.closed)

        val answerBroken = Harness().apply { peer.answerFails = true }
        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.PeerFailed("bad answer")), answerBroken.flow.connect(endpoint, streamKey))

        val iceBroken = Harness()
        val result = CompletableDeferred<WebRtcConnectResult>()
        launch { result.complete(iceBroken.flow.connect(endpoint, streamKey)) }
        runCurrent()
        iceBroken.peer.state.value = WebRtcPeerState.Failed("dtls")
        runCurrent()
        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.PeerFailed("dtls")), result.await())
    }

    @Test
    fun aPeerThatCannotBeCreatedIsAJoinFailureNotAnException() = runTest {
        val h = Harness()
        val flow = WebRtcConnectFlow(h.signaling, { error("libwebrtc missing") }, h.memory)

        val result = flow.connect(endpoint, streamKey)

        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.PeerFailed("libwebrtc missing")), result)
        assertTrue(h.signaling.offers.isEmpty())
        h.memory.markFailed(streamKey)
        assertFalse(h.memory.allowsWebRtc(streamKey), "counted like any other failed join")
    }

    @Test
    fun aPeerThatFailsWhileWaitingForItsFirstFrameDoesNotWaitOutTheFrameTimeout() = runTest {
        val h = Harness()
        val result = CompletableDeferred<WebRtcConnectResult>()
        launch { result.complete(h.flow.connect(endpoint, streamKey)) }
        runCurrent()
        h.peer.state.value = WebRtcPeerState.Connected
        advanceTimeBy(500)
        h.peer.state.value = WebRtcPeerState.Failed("ice lost")
        runCurrent()

        assertEquals(WebRtcConnectResult.Failed(WebRtcFailure.PeerFailed("ice lost")), result.await())
    }

    @Test
    fun cancellingTheJoinClosesThePeer() = runTest {
        val h = Harness()
        val job = launch { h.flow.connect(endpoint, streamKey) }
        runCurrent()
        assertFalse(h.peer.closed)

        job.cancel()
        runCurrent()

        assertTrue(h.peer.closed)
        // A cancelled join says nothing about the route.
        assertTrue(h.memory.allowsWebRtc(streamKey))
    }

    @Test
    fun aSuccessfulJoinWipesEarlierFailures() = runTest {
        val h = Harness()
        h.memory.markFailed(streamKey)
        val result = CompletableDeferred<WebRtcConnectResult>()
        launch { result.complete(h.flow.connect(endpoint, streamKey)) }
        runCurrent()
        h.peer.state.value = WebRtcPeerState.Connected
        h.peer.firstFrameReceived.value = true
        runCurrent()

        assertIs<WebRtcConnectResult.Connected>(result.await())
        h.memory.markFailed(streamKey)
        assertTrue(h.memory.allowsWebRtc(streamKey), "the count restarted from zero")
    }
}
