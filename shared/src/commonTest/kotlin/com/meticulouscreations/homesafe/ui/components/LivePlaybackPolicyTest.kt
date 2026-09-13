package com.meticulouscreations.homesafe.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LivePlaybackPolicyTest {

    private val live = VideoSource.Live("http://frigate:1984/api/stream.m3u8?src=cam", posterUrl = "http://frigate:8971/api/cam/latest.jpg")
    private val liveFull = VideoSource.Live("http://frigate:1984/api/stream.m3u8?src=cam_full")
    private val recording = VideoSource.Recording("http://frigate:8971/vod/cam/start/1/end/2/index.m3u8", headers = emptyMap(), startPositionMs = 0)

    @Test
    fun theVeryFirstSourceIsAColdStart() {
        assertTrue(LivePlaybackPolicy.isColdSwap(previous = null, next = live))
    }

    @Test
    fun liveToLiveIsWarmSoTheLastFrameStaysUpThroughAQualityStep() {
        assertFalse(LivePlaybackPolicy.isColdSwap(previous = live, next = liveFull))
        assertFalse(LivePlaybackPolicy.isColdSwap(previous = liveFull, next = live))
    }

    @Test
    fun aLiveSourceGainingOrLosingItsWebRtcEndpointIsStillTheSameCameraSoTheSwapIsWarm() {
        val webRtc = live.copy(webRtc = WebRtcEndpoint("http://frigate:1984/api/webrtc?src=cam", audio = false))
        assertFalse(LivePlaybackPolicy.isColdSwap(previous = live, next = webRtc))
        assertFalse(LivePlaybackPolicy.isColdSwap(previous = webRtc, next = liveFull))
        assertEquals(live.url, webRtc.url)
    }

    @Test
    fun anythingInvolvingARecordingIsCold() {
        assertTrue(LivePlaybackPolicy.isColdSwap(previous = live, next = recording))
        assertTrue(LivePlaybackPolicy.isColdSwap(previous = recording, next = live))
        assertTrue(LivePlaybackPolicy.isColdSwap(previous = recording, next = recording))
    }

    @Test
    fun retriesStartFastAndDoubleUpToTheCap() {
        assertEquals(250, LivePlaybackPolicy.retryDelayMs(1))
        assertEquals(500, LivePlaybackPolicy.retryDelayMs(2))
        assertEquals(1_000, LivePlaybackPolicy.retryDelayMs(3))
        assertEquals(8_000, LivePlaybackPolicy.retryDelayMs(6))
        assertEquals(30_000, LivePlaybackPolicy.retryDelayMs(8))
        assertEquals(30_000, LivePlaybackPolicy.retryDelayMs(9))
    }

    @Test
    fun retryDelayNeverOverflowsForAbsurdFailureCounts() {
        assertEquals(30_000, LivePlaybackPolicy.retryDelayMs(1_000))
        assertEquals(250, LivePlaybackPolicy.retryDelayMs(0))
    }

    private val gridEndpoint = WebRtcEndpoint("http://frigate:1984/api/webrtc?src=cam_sub", audio = false)
    private val fullEndpoint = WebRtcEndpoint("http://frigate:1984/api/webrtc?src=cam", audio = true)
    private val fullSilentEndpoint = WebRtcEndpoint("http://frigate:1984/api/webrtc?src=cam", audio = false)

    @Test
    fun aPeerServesTheSameStreamAgain() {
        assertTrue(LivePlaybackPolicy.canServe(have = gridEndpoint, want = gridEndpoint))
        assertTrue(LivePlaybackPolicy.canServe(have = fullEndpoint, want = fullEndpoint))
    }

    @Test
    fun aPeerWithAudioServesTheSilentRequestForTheSameStreamButNotTheReverse() {
        // Single-stream camera: the detail screen's peer (with sound) also plays the grid card, muted.
        assertTrue(LivePlaybackPolicy.canServe(have = fullEndpoint, want = fullSilentEndpoint))
        // WHEP can't add audio to a peer that offered without it.
        assertFalse(LivePlaybackPolicy.canServe(have = fullSilentEndpoint, want = fullEndpoint))
    }

    @Test
    fun aPeerNeverServesADifferentStream() {
        assertFalse(LivePlaybackPolicy.canServe(have = gridEndpoint, want = fullEndpoint))
        assertFalse(LivePlaybackPolicy.canServe(have = fullEndpoint, want = gridEndpoint))
    }

    @Test
    fun theCheapGridPeerIsKeptIndefinitelyAndTheFullQualityOneBriefly() {
        assertNull(LivePlaybackPolicy.standbyTtlMs(gridEndpoint))
        assertEquals(LivePlaybackPolicy.STANDBY_PEER_TTL_MS, LivePlaybackPolicy.standbyTtlMs(fullEndpoint))
    }

    @Test
    fun theIdleWindowIsLongerWhileTheAppIsOnScreen() {
        assertEquals(LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS, LivePlaybackPolicy.idleStopMs(appInForeground = true))
        assertEquals(LivePlaybackPolicy.IDLE_STOP_MS, LivePlaybackPolicy.idleStopMs(appInForeground = false))
        assertTrue(LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS > LivePlaybackPolicy.IDLE_STOP_MS)
        assertTrue(LivePlaybackPolicy.POOL_RELEASE_MS > LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS)
    }

    @Test
    fun anUnwatchedPlayerOnScreenWaitsTheLongWindow() = runTest {
        val inForeground = MutableStateFlow(true)
        var released = false
        launch {
            LivePlaybackPolicy.awaitIdleWindow(inForeground)
            released = true
        }
        advanceTimeBy(LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS - 1)
        runCurrent()
        assertFalse(released)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(released)
    }

    @Test
    fun goingToTheBackgroundRestartsTheCountdownWithTheShortWindow() = runTest {
        val inForeground = MutableStateFlow(true)
        var released = false
        launch {
            LivePlaybackPolicy.awaitIdleWindow(inForeground)
            released = true
        }
        // A minute unwatched on another tab, then the phone goes in a pocket.
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(released)
        inForeground.value = false
        advanceTimeBy(LivePlaybackPolicy.IDLE_STOP_MS - 1)
        runCurrent()
        assertFalse(released, "the short window is counted from the moment the app left the screen")
        advanceTimeBy(2)
        runCurrent()
        assertTrue(released)
    }

    @Test
    fun comingBackOnScreenRestartsTheCountdownWithTheLongWindow() = runTest {
        val inForeground = MutableStateFlow(false)
        var released = false
        launch {
            LivePlaybackPolicy.awaitIdleWindow(inForeground)
            released = true
        }
        advanceTimeBy(LivePlaybackPolicy.IDLE_STOP_MS - 1_000)
        inForeground.value = true
        advanceTimeBy(LivePlaybackPolicy.IDLE_STOP_MS)
        runCurrent()
        assertFalse(released, "back on screen, the player earns the long window again")
        advanceTimeBy(LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS)
        runCurrent()
        assertTrue(released)
    }
}
