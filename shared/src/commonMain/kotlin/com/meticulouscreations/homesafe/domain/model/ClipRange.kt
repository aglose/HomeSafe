package com.meticulouscreations.homesafe.domain.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A stretch of a camera's continuous recording picked out in the clip editor: what plays on a
 * loop in its preview, and what is saved. Wall-clock epoch seconds, like everything on the timeline.
 */
data class ClipRange(val startEpochSeconds: Double, val endEpochSeconds: Double) {
    init {
        require(endEpochSeconds >= startEpochSeconds) { "A clip can't end before it starts" }
    }

    val durationSeconds: Double get() = endEpochSeconds - startEpochSeconds

    operator fun contains(epochSeconds: Double): Boolean = epochSeconds in startEpochSeconds..endEpochSeconds

    /**
     * The start handle dragged to [epochSeconds]: it never passes [earliestEpochSeconds], never
     * comes within [ClipLimits.MIN_SECONDS] of the end, and never lets the clip grow past
     * [ClipLimits.MAX_SECONDS]. The end stays where it is.
     */
    fun withStartAt(epochSeconds: Double, earliestEpochSeconds: Double): ClipRange {
        val lowest = max(earliestEpochSeconds, endEpochSeconds - ClipLimits.MAX_SECONDS)
        val highest = endEpochSeconds - ClipLimits.MIN_SECONDS
        return copy(startEpochSeconds = epochSeconds.coerceIn(min(lowest, highest), highest))
    }

    /** The end handle's counterpart to [withStartAt], bounded by [latestEpochSeconds] instead. */
    fun withEndAt(epochSeconds: Double, latestEpochSeconds: Double): ClipRange {
        val lowest = startEpochSeconds + ClipLimits.MIN_SECONDS
        val highest = min(latestEpochSeconds, startEpochSeconds + ClipLimits.MAX_SECONDS)
        return copy(endEpochSeconds = epochSeconds.coerceIn(lowest, max(lowest, highest)))
    }
}

/** How short and how long a clip may be, and how long a freshly opened editor proposes. */
object ClipLimits {
    /** Anything shorter is a frame or two once Frigate cuts it on keyframes. */
    const val MIN_SECONDS = 2.0

    /** Frigate remuxes a clip on request; ten minutes is a few seconds' work and a sane download on a phone. */
    const val MAX_SECONDS = 600.0

    /** Enough to hold most of what a person or car does passing through a frame. */
    const val DEFAULT_SECONDS = 20.0

    /** Snapping to a detection starts this long before it, so the clip opens on the approach. */
    const val MOMENT_LEAD_IN_SECONDS = 2.0

    /** ...and runs this long after it ends, so it doesn't cut off mid-step. */
    const val MOMENT_TAIL_SECONDS = 3.0

    /** The editor's one-tap lengths, like a camera app's duration chips. */
    val PRESET_SECONDS: List<Double> = listOf(10.0, 30.0, 60.0, 120.0)
}

/**
 * The clip that holds a detection from [startEpochSeconds] to [endEpochSeconds], with
 * [ClipLimits.MOMENT_LEAD_IN_SECONDS] before and [ClipLimits.MOMENT_TAIL_SECONDS] after. A
 * detection longer than [ClipLimits.MAX_SECONDS] (a car parked all afternoon) keeps its start
 * and is cut at the limit; one near the edges of what can be clipped is slid inwards whole.
 */
fun clipRangeForMoment(startEpochSeconds: Double, endEpochSeconds: Double, earliestEpochSeconds: Double, latestEpochSeconds: Double): ClipRange {
    val from = startEpochSeconds - ClipLimits.MOMENT_LEAD_IN_SECONDS
    val to = max(endEpochSeconds, startEpochSeconds) + ClipLimits.MOMENT_TAIL_SECONDS
    val length = (to - from).coerceIn(ClipLimits.MIN_SECONDS, ClipLimits.MAX_SECONDS)
    return fitClip(from, length, earliestEpochSeconds, latestEpochSeconds)
}

/**
 * This clip at [seconds] long (within [ClipLimits]), keeping its start where it can: when that
 * would run past [latestEpochSeconds] the whole clip slides back instead of coming up short.
 */
fun ClipRange.withLength(seconds: Double, earliestEpochSeconds: Double, latestEpochSeconds: Double): ClipRange =
    fitClip(startEpochSeconds, seconds.coerceIn(ClipLimits.MIN_SECONDS, ClipLimits.MAX_SECONDS), earliestEpochSeconds, latestEpochSeconds)

/** [length] seconds from [start], slid (never shrunk, unless there's less than that to clip at all) to lie in [earliest]..[latest]. */
private fun fitClip(start: Double, length: Double, earliest: Double, latest: Double): ClipRange {
    val fitted = min(length, max(0.0, latest - earliest))
    val from = start.coerceIn(earliest, max(earliest, latest - fitted))
    return ClipRange(from, from + fitted)
}

/**
 * The editor's first proposal: [ClipLimits.DEFAULT_SECONDS] centred on [anchorEpochSeconds] (where
 * the camera screen was playing), slid inwards rather than cut short when that runs past what can
 * be clipped, [earliestEpochSeconds]..[latestEpochSeconds].
 */
fun initialClipRange(anchorEpochSeconds: Double, earliestEpochSeconds: Double, latestEpochSeconds: Double): ClipRange {
    val available = max(0.0, latestEpochSeconds - earliestEpochSeconds)
    val length = min(ClipLimits.DEFAULT_SECONDS, available)
    val start = (anchorEpochSeconds - length / 2).coerceIn(earliestEpochSeconds, latestEpochSeconds - length)
    return ClipRange(start, start + length)
}

/**
 * The stretch of time the editor's filmstrip spans. It can be panned (the filmstrip auto-scrolls
 * while a handle is held at its edge) but never past [ClipRange]-able history either side.
 */
data class ClipWindow(val startEpochSeconds: Double, val endEpochSeconds: Double) {
    val durationSeconds: Double get() = endEpochSeconds - startEpochSeconds

    /** [seconds] later (negative for earlier), stopping flush against [earliestEpochSeconds] / [latestEpochSeconds]. */
    fun pannedBy(seconds: Double, earliestEpochSeconds: Double, latestEpochSeconds: Double): ClipWindow {
        val shift = seconds.coerceIn(earliestEpochSeconds - startEpochSeconds, max(earliestEpochSeconds - startEpochSeconds, latestEpochSeconds - endEpochSeconds))
        return ClipWindow(startEpochSeconds + shift, endEpochSeconds + shift)
    }

    /** Where [epochSeconds] falls across the window, 0 at its start and 1 at its end (unclamped). */
    fun fractionOf(epochSeconds: Double): Double = if (durationSeconds <= 0.0) 0.0 else (epochSeconds - startEpochSeconds) / durationSeconds

    /** The moment at [fraction] of the way across the window. */
    fun epochAt(fraction: Double): Double = startEpochSeconds + fraction * durationSeconds

    /**
     * This window if [range] already lies inside it — so a change the viewer can see isn't
     * accompanied by the strip jumping under their finger — else a fresh one [around] it.
     */
    fun holding(range: ClipRange, earliestEpochSeconds: Double, latestEpochSeconds: Double): ClipWindow =
        if (range.startEpochSeconds >= startEpochSeconds && range.endEpochSeconds <= endEpochSeconds) this else around(range, earliestEpochSeconds, latestEpochSeconds)

    companion object {
        /** Two minutes: a 20 s clip is a comfortable sixth of the strip, and a second is still a few pixels. */
        const val DEFAULT_SECONDS = 120.0

        /**
         * A [DEFAULT_SECONDS] window centred on [range] (widened to hold it with room to spare),
         * inside [earliestEpochSeconds]..[latestEpochSeconds] wherever that is long enough.
         */
        fun around(range: ClipRange, earliestEpochSeconds: Double, latestEpochSeconds: Double): ClipWindow {
            val length = max(DEFAULT_SECONDS, range.durationSeconds * 1.5)
            val centre = (range.startEpochSeconds + range.endEpochSeconds) / 2
            val latestStart = max(earliestEpochSeconds, latestEpochSeconds - length)
            val start = (centre - length / 2).coerceIn(earliestEpochSeconds, latestStart)
            return ClipWindow(start, start + length)
        }
    }
}

/** "homesafe_front_door_1758844800_20s.mp4": the camera, when the clip starts, and how long it runs. */
fun clipFileName(cameraName: String, range: ClipRange): String {
    val safeCameraName = cameraName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    return "homesafe_${safeCameraName}_${range.startEpochSeconds.toLong()}_${range.durationSeconds.roundToLong()}s.mp4"
}
