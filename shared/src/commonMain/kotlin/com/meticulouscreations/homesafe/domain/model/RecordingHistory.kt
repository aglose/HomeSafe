package com.meticulouscreations.homesafe.domain.model

import kotlin.math.abs
import kotlin.math.floor

/**
 * Everything a camera recorded inside some time window, sorted by time with unplayable clips
 * removed, plus the logic for carving it into the playlists the player actually loads.
 *
 * Playlists are cut on wall-clock hour boundaries (segments are grouped by the hour their start
 * falls in), which is also how Frigate's own UI plays recordings: an hour is ~360 clips, small
 * enough for Frigate's playlist mapper, yet big enough that scrubbing around within it is a plain
 * in-player seek with no reload.
 */
class RecordingHistory(segments: List<RecordingSegment>) {

    val segments: List<RecordingSegment> = segments.filter { it.isPlayable }.sortedBy { it.startEpochSeconds }

    val isEmpty: Boolean get() = segments.isEmpty()

    /** End of the most recent recording, i.e. how close to "now" history can be scrubbed. */
    val latestEndEpochSeconds: Double? = this.segments.maxOfOrNull { it.endEpochSeconds }

    fun segmentAt(epochSeconds: Double): RecordingSegment? = segments.firstOrNull { epochSeconds in it }

    fun firstSegmentStartingAtOrAfter(epochSeconds: Double): RecordingSegment? =
        segments.firstOrNull { it.startEpochSeconds >= epochSeconds }

    /**
     * The playlist to load in order to play [epochSeconds]. If nothing was recorded at that exact
     * moment, this resolves to the playlist holding the next recording after it (so scrubbing into
     * a gap plays the first thing that exists). Null when there is nothing recorded at or after it.
     */
    fun playlistFor(epochSeconds: Double, bucketSeconds: Double = DEFAULT_BUCKET_SECONDS): RecordingPlaylist? {
        val anchor = segmentAt(epochSeconds) ?: firstSegmentStartingAtOrAfter(epochSeconds) ?: return null
        val bucketStart = floor(anchor.startEpochSeconds / bucketSeconds) * bucketSeconds
        val bucketEnd = bucketStart + bucketSeconds
        val members = segments.filter { it.startEpochSeconds >= bucketStart && it.startEpochSeconds < bucketEnd }
        return RecordingPlaylist(members)
    }

    /** The playlist that continues where [playlist] ends, or null if it was the most recent one. */
    fun playlistAfter(playlist: RecordingPlaylist, bucketSeconds: Double = DEFAULT_BUCKET_SECONDS): RecordingPlaylist? =
        firstSegmentStartingAtOrAfter(playlist.endEpochSeconds)
            ?.let { playlistFor(it.startEpochSeconds, bucketSeconds) }

    /**
     * Where a step of [deltaSeconds] from [epochSeconds] lands, back for a negative one, counted
     * in footage rather than on the clock: a gap in recording takes none of the step, as it takes
     * no time in the player. So ten seconds back from just after a gap is ten seconds from the end
     * of what was recorded before it, where the same step on the clock would land in the gap and
     * play from the very clip it left.
     *
     * A clip's footage is what the player has of it, as [RecordingPlaylist] lays it out:
     * [RecordingSegment.durationSeconds] from the clip's start, which can be a little off its
     * length on the clock. Counting in that keeps a step here the same frame as moving the player
     * by as much.
     *
     * A step forward past the last recording stops at its end. A step back stops at the first
     * recording's start. Null when there is nowhere to go: nothing was recorded, or a step back
     * has no footage behind it.
     */
    fun skipFrom(epochSeconds: Double, deltaSeconds: Double): Double? {
        if (segments.isEmpty()) return null
        var remaining = abs(deltaSeconds)
        if (deltaSeconds >= 0) {
            for (segment in segments) {
                if (segment.endEpochSeconds <= epochSeconds) continue
                val at = (epochSeconds - segment.startEpochSeconds).coerceIn(0.0, segment.durationSeconds)
                val ahead = segment.durationSeconds - at
                if (remaining < ahead) return (segment.startEpochSeconds + at + remaining).coerceAtMost(segment.endEpochSeconds)
                remaining -= ahead
            }
            return latestEndEpochSeconds
        }
        var stepped = false
        for (segment in segments.asReversed()) {
            if (segment.startEpochSeconds >= epochSeconds) continue
            val at = (epochSeconds - segment.startEpochSeconds).coerceAtMost(segment.durationSeconds)
            if (remaining <= at) return segment.startEpochSeconds + at - remaining
            remaining -= at
            stepped = true
        }
        return if (stepped) segments.first().startEpochSeconds else null
    }

    companion object {
        const val DEFAULT_BUCKET_SECONDS = 3600.0
        val EMPTY = RecordingHistory(emptyList())
    }
}
