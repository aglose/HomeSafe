package com.meticulouscreations.homesafe.ui.components

import kotlin.math.max
import kotlin.math.min

private const val HLS_PATH = "/api/stream.m3u8?"
private const val CONTINUOUS_PATH = "/api/stream.mp4?"

/**
 * go2rtc's continuous fMP4 for the same video as one of its HLS playlists, or null for any other
 * URL. The desktop has no WebRTC, and HLS doesn't suit a player that decodes as it goes: go2rtc
 * cuts a segment when it is asked for one, so FFmpeg gets its frames in bursts up to half a second
 * apart, and a join waits for a playlist and a segment. This is one long response with each frame
 * sent as the camera produces it. `video` is what makes go2rtc leave a silent stream's audio out,
 * as the HLS URL without it would; a URL asking for sound has it already.
 */
internal fun go2rtcContinuousUrl(url: String): String? {
    if (HLS_PATH !in url) return null
    val asksForVideo = url.substringAfter('?').split('&').any { it == "video" || it.startsWith("video=") }
    return (if (asksForVideo) url else "$url&video").replace(HLS_PATH, CONTINUOUS_PATH)
}

/**
 * Says how long to hold each decoded frame so that pictures appear at the pace their timestamps
 * say, against the wall clock. The clock starts from the first frame.
 *
 * A recording's clock starts again from the frame in hand when it has fallen more than
 * [MAX_DRIFT_NANOS] behind (a slow network, a laptop waking up), not worth playing out. A gap in
 * its timestamps is part of what was recorded, and is played out.
 *
 * Live video keeps its clock through a stall: what built up meanwhile is late, so it goes straight
 * through with no hold until the picture has caught up, rather than the clock starting from the
 * oldest of it and staying that far behind for good. The clock starts again only on a jump forward
 * in the timestamps, as there is nothing to wait for, or when every frame for a whole
 * [ADJUST_WINDOW_NANOS] has been over [MAX_DRIFT_NANOS] late without getting at least
 * [MIN_DRAIN_NANOS] less so: not a backlog draining, however slowly, but a clock that is out.
 *
 * The live clock also moves to suit how frames have been arriving, looked at over the same window.
 * Any that came late give it that much room, up to [MAX_ROOM_NANOS] in all, so that jitter of that
 * size next time passes without a gap. All of them early by more than that means it is running
 * behind what has already arrived, which only makes the picture older, and it catches up.
 */
internal class FramePacer(private val live: Boolean) {
    private var originUs = UNSET
    private var originNanos = 0L
    private var roomNanos = 0L
    private var windowEndsAtNanos = NO_WINDOW
    private var shortestHoldNanos = Long.MAX_VALUE
    private var longestHoldNanos = Long.MIN_VALUE
    private var firstHoldNanos = 0L

    /** After a pause or a seek: the next frame is shown at once, and the clock runs from it. */
    fun restart() {
        originUs = UNSET
    }

    /** How long, from [nowNanos], the frame stamped [timestampUs] should wait before it is shown. */
    fun holdNanos(timestampUs: Long, nowNanos: Long): Long {
        if (originUs == UNSET || timestampUs < originUs) return startFrom(timestampUs, nowNanos)
        val hold = originNanos + (timestampUs - originUs) * NANOS_PER_US - nowNanos
        if (!live) return if (hold < -MAX_DRIFT_NANOS) startFrom(timestampUs, nowNanos) else hold.coerceAtLeast(0)
        if (hold > MAX_DRIFT_NANOS) return startFrom(timestampUs, nowNanos)
        // A window opens on a frame rather than on the wall clock, so one stall can't close a window on the frame after it alone.
        if (windowEndsAtNanos == NO_WINDOW) {
            windowEndsAtNanos = nowNanos + ADJUST_WINDOW_NANOS
            firstHoldNanos = hold
        }
        shortestHoldNanos = min(shortestHoldNanos, hold)
        longestHoldNanos = max(longestHoldNanos, hold)
        if (nowNanos < windowEndsAtNanos) return hold.coerceAtLeast(0)
        if (longestHoldNanos < -MAX_DRIFT_NANOS && hold - firstHoldNanos < MIN_DRAIN_NANOS) return startFrom(timestampUs, nowNanos)
        val move = when {
            shortestHoldNanos > MAX_ROOM_NANOS -> -shortestHoldNanos
            shortestHoldNanos < 0 -> min(-shortestHoldNanos, MAX_ROOM_NANOS - roomNanos)
            else -> 0L
        }
        originNanos += move
        roomNanos = (roomNanos + move).coerceAtLeast(0)
        closeWindow()
        return (hold + move).coerceAtLeast(0)
    }

    private fun startFrom(timestampUs: Long, nowNanos: Long): Long {
        originUs = timestampUs
        originNanos = nowNanos
        roomNanos = 0
        closeWindow()
        // This frame is exactly on the clock it started, and opens the first window.
        windowEndsAtNanos = nowNanos + ADJUST_WINDOW_NANOS
        longestHoldNanos = 0
        return 0
    }

    private fun closeWindow() {
        windowEndsAtNanos = NO_WINDOW
        shortestHoldNanos = Long.MAX_VALUE
        longestHoldNanos = Long.MIN_VALUE
    }

    companion object {
        const val MAX_DRIFT_NANOS = 1_000_000_000L
        const val ADJUST_WINDOW_NANOS = 2_000_000_000L
        const val MAX_ROOM_NANOS = 150_000_000L
        const val MIN_DRAIN_NANOS = 50_000_000L
        private const val NANOS_PER_US = 1000L
        private const val UNSET = Long.MIN_VALUE
        private const val NO_WINDOW = Long.MAX_VALUE
    }
}
