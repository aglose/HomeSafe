package com.meticulouscreations.homesafe.ui.components

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
 * say, against the wall clock. The clock starts from the first frame, and starts again from the
 * frame in hand when it has fallen more than [MAX_DRIFT_NANOS] behind: a slow network or a laptop
 * waking up, not worth playing out.
 *
 * For [live] video a jump forward in the timestamps does the same, as there is nothing to wait for:
 * a recording's gap is part of what was recorded, and is played out. The live clock also moves to
 * suit how the frames have been arriving, looked at every
 * [ADJUST_WINDOW_NANOS]. Any that came late give it that much room, up to [MAX_ROOM_NANOS], so that
 * jitter of that size next time passes without a gap. All of them early by more than that means it
 * is running behind what has already arrived, which only makes the picture older, and it catches
 * up. A recording's frames are always early, being downloaded ahead, so its clock just runs.
 */
internal class FramePacer(private val live: Boolean) {
    private var originUs = UNSET
    private var originNanos = 0L
    private var windowEndsAtNanos = 0L
    private var shortestHoldNanos = Long.MAX_VALUE

    /** After a pause or a seek: the next frame is shown at once, and the clock runs from it. */
    fun restart() {
        originUs = UNSET
    }

    /** How long, from [nowNanos], the frame stamped [timestampUs] should wait before it is shown. */
    fun holdNanos(timestampUs: Long, nowNanos: Long): Long {
        if (originUs == UNSET || timestampUs < originUs) return startFrom(timestampUs, nowNanos)
        val hold = originNanos + (timestampUs - originUs) * NANOS_PER_US - nowNanos
        if (hold < -MAX_DRIFT_NANOS || (live && hold > MAX_DRIFT_NANOS)) return startFrom(timestampUs, nowNanos)
        if (!live) return hold.coerceAtLeast(0)
        shortestHoldNanos = min(shortestHoldNanos, hold)
        if (nowNanos < windowEndsAtNanos) return hold.coerceAtLeast(0)
        val move = when {
            shortestHoldNanos > MAX_ROOM_NANOS -> -shortestHoldNanos
            shortestHoldNanos < 0 -> min(-shortestHoldNanos, MAX_ROOM_NANOS)
            else -> 0L
        }
        originNanos += move
        startWindow(nowNanos)
        return (hold + move).coerceAtLeast(0)
    }

    private fun startFrom(timestampUs: Long, nowNanos: Long): Long {
        originUs = timestampUs
        originNanos = nowNanos
        startWindow(nowNanos)
        return 0
    }

    private fun startWindow(nowNanos: Long) {
        windowEndsAtNanos = nowNanos + ADJUST_WINDOW_NANOS
        shortestHoldNanos = Long.MAX_VALUE
    }

    companion object {
        const val MAX_DRIFT_NANOS = 1_000_000_000L
        const val ADJUST_WINDOW_NANOS = 2_000_000_000L
        const val MAX_ROOM_NANOS = 150_000_000L
        private const val NANOS_PER_US = 1000L
        private const val UNSET = Long.MIN_VALUE
    }
}
