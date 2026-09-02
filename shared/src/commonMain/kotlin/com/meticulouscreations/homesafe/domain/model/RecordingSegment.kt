package com.meticulouscreations.homesafe.domain.model

/** One recorded clip of a camera's continuous recording (Frigate writes roughly 10-second segments). */
data class RecordingSegment(
    val startEpochSeconds: Double,
    val endEpochSeconds: Double,
    /** Frigate's own duration for the clip; can differ from `end - start` by a few milliseconds. */
    val durationSeconds: Double = endEpochSeconds - startEpochSeconds,
    /** Number of motion frames Frigate counted in this clip. */
    val motion: Int = 0,
    /** Number of frames with detected objects in this clip. */
    val objects: Int = 0,
) {
    /**
     * Frigate silently drops empty clips and clips at or above its `MAX_SEGMENT_DURATION` (600s)
     * when it builds a VOD playlist, so a segment outside that range never becomes playable video.
     */
    val isPlayable: Boolean
        get() = durationSeconds > 0.0 && durationSeconds < MAX_PLAYABLE_DURATION_SECONDS

    operator fun contains(epochSeconds: Double): Boolean =
        epochSeconds >= startEpochSeconds && epochSeconds < endEpochSeconds

    companion object {
        const val MAX_PLAYABLE_DURATION_SECONDS = 600.0
    }
}
