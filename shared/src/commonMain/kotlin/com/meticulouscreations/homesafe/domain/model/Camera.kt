package com.meticulouscreations.homesafe.domain.model

/** A camera on the connected Frigate server, or a Nest camera its go2rtc relays (see [placement]). */
data class Camera(
    val name: String,
    val enabled: Boolean,
    /** The full-quality stream name to use for the single-camera detail view. */
    val liveStreamName: String = name,
    /** The (possibly lower-quality) stream name to use for the multi-camera grid. */
    val gridStreamName: String = name,
    val placement: CameraPlacement = CameraPlacement.Outdoor,
) {
    /** What the UI calls this camera — see [cameraDisplayName]. */
    val displayName: String get() = cameraDisplayName(name)
}

/**
 * Which side of the walls a camera watches, and with it what the app can do with it. The
 * Frigate cameras are outdoors and have everything: recordings, detections, the timeline. The
 * indoor ones are Google Nest cameras that go2rtc pulls from Google only while someone is
 * watching, so they are live video and nothing else.
 */
enum class CameraPlacement { Outdoor, Indoor }
