package com.meticulouscreations.homesafe.domain.model

/** A camera on the connected Frigate server. */
data class Camera(
    val name: String,
    val enabled: Boolean,
    /** The full-quality stream name to use for the single-camera detail view. */
    val liveStreamName: String = name,
    /** The (possibly lower-quality) stream name to use for the multi-camera grid. */
    val gridStreamName: String = name,
) {
    /** What the UI calls this camera — see [cameraDisplayName]. */
    val displayName: String get() = cameraDisplayName(name)
}
