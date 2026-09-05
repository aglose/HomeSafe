package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
