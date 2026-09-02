package com.meticulouscreations.homesafe.domain.model

/**
 * A run of recorded [segments] that Frigate's `/vod/{camera}/start/{start}/end/{end}/index.m3u8`
 * endpoint stitches into a single seekable HLS playlist.
 *
 * Frigate builds that playlist by concatenating the clips back-to-back using each clip's own
 * duration, so any gap in recording is collapsed rather than padded with black. This class mirrors
 * that layout so the app can convert between wall-clock time (what the timeline shows) and the
 * playback position the player reports (seconds into the concatenated playlist).
 */
data class RecordingPlaylist(val segments: List<RecordingSegment>) {

    init {
        require(segments.isNotEmpty()) { "A playlist needs at least one segment" }
    }

    val startEpochSeconds: Double = segments.first().startEpochSeconds
    val endEpochSeconds: Double = segments.last().endEpochSeconds
    val durationSeconds: Double = segments.sumOf { it.durationSeconds }

    /** Playback position at which each segment begins. */
    private val segmentOffsetsSeconds: DoubleArray = DoubleArray(segments.size).also { offsets ->
        var cumulative = 0.0
        segments.forEachIndexed { index, segment ->
            offsets[index] = cumulative
            cumulative += segment.durationSeconds
        }
    }

    /** True if [epochSeconds] falls within this playlist's time range (gaps included). */
    operator fun contains(epochSeconds: Double): Boolean =
        epochSeconds >= startEpochSeconds && epochSeconds < endEpochSeconds

    /**
     * Playback position for [epochSeconds]. Times inside a recording gap resolve to the start of
     * the next clip; times outside the playlist clamp to its start or end.
     */
    fun positionSecondsFor(epochSeconds: Double): Double {
        if (epochSeconds <= startEpochSeconds) return 0.0
        segments.forEachIndexed { index, segment ->
            val offset = segmentOffsetsSeconds[index]
            if (epochSeconds < segment.startEpochSeconds) return offset
            if (epochSeconds in segment) {
                return offset + (epochSeconds - segment.startEpochSeconds).coerceAtMost(segment.durationSeconds)
            }
        }
        return durationSeconds
    }

    /** Wall-clock time of playback position [positionSeconds], clamped to the playlist's range. */
    fun epochSecondsAt(positionSeconds: Double): Double {
        if (positionSeconds <= 0.0) return startEpochSeconds
        for (index in segments.indices.reversed()) {
            val offset = segmentOffsetsSeconds[index]
            if (positionSeconds >= offset) {
                val segment = segments[index]
                return (segment.startEpochSeconds + (positionSeconds - offset)).coerceAtMost(segment.endEpochSeconds)
            }
        }
        return startEpochSeconds
    }
}
