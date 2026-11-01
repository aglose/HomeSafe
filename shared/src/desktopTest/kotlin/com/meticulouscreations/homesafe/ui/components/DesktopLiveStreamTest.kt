package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLiveStreamTest {
    @Test
    fun `a silent stream's playlist becomes the continuous stream of its video alone`() {
        assertEquals(
            "http://box:1984/api/stream.mp4?src=front_sub&video",
            go2rtcContinuousUrl("http://box:1984/api/stream.m3u8?src=front_sub"),
        )
    }

    @Test
    fun `a playlist asking for sound keeps what it asked for`() {
        assertEquals(
            "http://box:1984/api/stream.mp4?src=front&video&audio=opus,aac",
            go2rtcContinuousUrl("http://box:1984/api/stream.m3u8?src=front&video&audio=opus,aac"),
        )
    }

    @Test
    fun `a stream merely named video still asks for its video`() {
        assertEquals(
            "http://box:1984/api/stream.mp4?src=video&video",
            go2rtcContinuousUrl("http://box:1984/api/stream.m3u8?src=video"),
        )
    }

    @Test
    fun `anything but go2rtc's playlist is left alone`() {
        assertNull(go2rtcContinuousUrl("https://box:8971/vod/front/start/1/end/2/index.m3u8"))
    }

    @Test
    fun `frames due later wait for their time`() {
        val pacer = FramePacer(live = true)
        assertEquals(0, pacer.holdNanos(timestampUs = 0, nowNanos = 0))
        assertEquals(40 * MS, pacer.holdNanos(timestampUs = 40_000, nowNanos = 0))
    }

    @Test
    fun `a frame that arrives late is shown at once and leaves the clock alone`() {
        val pacer = FramePacer(live = true)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        assertEquals(0, pacer.holdNanos(timestampUs = 40_000, nowNanos = 300 * MS))
        assertEquals(20 * MS, pacer.holdNanos(timestampUs = 320_000, nowNanos = 300 * MS))
    }

    @Test
    fun `falling a long way behind starts the clock again from the frame in hand`() {
        val pacer = FramePacer(live = true)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        assertEquals(0, pacer.holdNanos(timestampUs = 40_000, nowNanos = 5_000 * MS))
        assertEquals(40 * MS, pacer.holdNanos(timestampUs = 80_000, nowNanos = 5_000 * MS))
    }

    @Test
    fun `a jump forward in the timestamps doesn't freeze the picture until it comes round`() {
        val pacer = FramePacer(live = true)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        assertEquals(0, pacer.holdNanos(timestampUs = 60_000_000, nowNanos = 40 * MS))
    }

    @Test
    fun `a stream steadily ahead of the clock is caught up with`() {
        val pacer = FramePacer(live = true)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        // Every frame for the whole window could have been shown half a second sooner.
        var now = 0L
        var timestampUs = 500_000L
        while (now < FramePacer.ADJUST_WINDOW_NANOS) {
            assertEquals(500 * MS, pacer.holdNanos(timestampUs, now))
            now += 40 * MS
            timestampUs += 40_000
        }
        assertEquals(0, pacer.holdNanos(timestampUs, now))
        assertEquals(40 * MS, pacer.holdNanos(timestampUs + 40_000, now))
    }

    @Test
    fun `frames that came late give the clock that much room to ride out the next lot`() {
        val pacer = FramePacer(live = true)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        assertEquals(0, pacer.holdNanos(timestampUs = 40_000, nowNanos = 100 * MS))
        val atWindowEnd = FramePacer.ADJUST_WINDOW_NANOS
        // On time by the old clock, and now 60 ms early by the new.
        assertEquals(60 * MS, pacer.holdNanos(timestampUs = atWindowEnd / 1000, nowNanos = atWindowEnd))
    }

    @Test
    fun `the room for late frames has a limit`() {
        val pacer = FramePacer(live = true)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        assertEquals(0, pacer.holdNanos(timestampUs = 40_000, nowNanos = 640 * MS))
        val atWindowEnd = FramePacer.ADJUST_WINDOW_NANOS
        assertEquals(FramePacer.MAX_ROOM_NANOS, pacer.holdNanos(timestampUs = atWindowEnd / 1000, nowNanos = atWindowEnd))
    }

    @Test
    fun `one frame running ahead isn't taken for a backlog`() {
        val pacer = FramePacer(live = true)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        pacer.holdNanos(timestampUs = 500_000, nowNanos = 0)
        // Then one right on time, so the shortest wait in the window is nothing.
        pacer.holdNanos(timestampUs = 40_000, nowNanos = 40 * MS)
        val atWindowEnd = FramePacer.ADJUST_WINDOW_NANOS
        assertEquals(40 * MS, pacer.holdNanos(timestampUs = (atWindowEnd + 40 * MS) / 1000, nowNanos = atWindowEnd))
    }

    @Test
    fun `a recording's frames, always early, are never skipped towards`() {
        val pacer = FramePacer(live = false)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        var now = 0L
        while (now < 2 * FramePacer.ADJUST_WINDOW_NANOS) {
            assertEquals(500 * MS, pacer.holdNanos((now + 500 * MS) / 1000, now))
            now += 40 * MS
        }
    }

    @Test
    fun `restarting shows the next frame at once and runs the clock from it`() {
        val pacer = FramePacer(live = false)
        pacer.holdNanos(timestampUs = 0, nowNanos = 0)
        pacer.restart()
        assertEquals(0, pacer.holdNanos(timestampUs = 700_000, nowNanos = 10 * MS))
        assertEquals(40 * MS, pacer.holdNanos(timestampUs = 740_000, nowNanos = 10 * MS))
    }

    @Test
    fun `the frame rate comes out every couple of seconds`() {
        val meter = FrameRateMeter()
        var now = 1L
        var last: Int? = null
        repeat(51) {
            last = meter.onFrame(now)
            if (it < 50) assertNull(last)
            now += 40 * MS
        }
        assertEquals(25, last)
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
