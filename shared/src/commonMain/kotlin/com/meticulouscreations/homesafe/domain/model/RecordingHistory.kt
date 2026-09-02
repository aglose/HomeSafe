package com.meticulouscreations.homesafe.domain.model

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

    companion object {
        const val DEFAULT_BUCKET_SECONDS = 3600.0
        val EMPTY = RecordingHistory(emptyList())
    }
}
