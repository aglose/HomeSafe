package com.meticulouscreations.homesafe.domain.model

/** Which of a camera's live streams the detail player should play. */
enum class StreamQuality {
    /** Join on the lighter stream for a fast first frame, then step up to the full one. */
    AUTO,

    /** The camera's full-quality stream, and nothing else. */
    HIGH,

    /** The camera's lighter sub-stream only — easier on a slow link, and typically video-only. */
    LOW,
    ;

    /** The choice after this one, so a single button can cycle through them. */
    val next: StreamQuality get() = entries[(ordinal + 1) % entries.size]
}

/**
 * How a picture that isn't the shape of its box sits in it: whole, with bars at the sides or top
 * and bottom ([Fit]), or covering the box and cropped ([Fill]). Only the Nest cameras, whose
 * pictures come in several shapes, are laid out this way; Frigate's always fill their 16:9 box.
 */
enum class VideoFit {
    Fit,
    Fill,
    ;

    /** The other one, for a button that flips between them. */
    val other: VideoFit get() = if (this == Fit) Fill else Fit
}

/** The user's live-player preferences on this device, persisted across sessions. */
data class PlaybackPreferences(
    val quality: StreamQuality = StreamQuality.AUTO,
    /** Whether the detail player plays a stream's audio track. Off by default: sound arriving unasked is a jump scare. */
    val soundOn: Boolean = false,
    /** How the Nest cameras' pictures sit in their cards and on their page: whole, by default, so nothing is cut off. */
    val nestFit: VideoFit = VideoFit.Fit,
) {
    companion object {
        val DEFAULT = PlaybackPreferences()
    }
}
