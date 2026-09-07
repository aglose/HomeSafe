package com.meticulouscreations.homesafe.domain.model

/** Which of a camera's live streams the detail player should play. */
enum class StreamQuality(val label: String) {
    /** Join on the lighter stream for a fast first frame, then step up to the full one. */
    AUTO("Auto"),

    /** The camera's full-quality stream, and nothing else. */
    HIGH("High"),

    /** The camera's lighter sub-stream only — easier on a slow link, and typically video-only. */
    LOW("Low"),
    ;

    /** The choice after this one, so a single button can cycle through them. */
    val next: StreamQuality get() = entries[(ordinal + 1) % entries.size]
}

/** The user's live-player preferences on this device, persisted across sessions. */
data class PlaybackPreferences(
    val quality: StreamQuality = StreamQuality.AUTO,
    /** Whether the detail player plays a stream's audio track. Off by default: sound arriving unasked is a jump scare. */
    val soundOn: Boolean = false,
) {
    companion object {
        val DEFAULT = PlaybackPreferences()
    }
}
