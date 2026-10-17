package com.meticulouscreations.homesafe.domain.model

/** A camera on the connected Frigate server, or a Google Nest camera its go2rtc relays (see [source]). */
data class Camera(
    val name: String,
    val enabled: Boolean,
    /** The full-quality stream name to use for the single-camera detail view. */
    val liveStreamName: String = name,
    /** The (possibly lower-quality) stream name to use for the multi-camera grid. */
    val gridStreamName: String = name,
    val placement: CameraPlacement = CameraPlacement.Outdoor,
    val source: CameraSource = CameraSource.Frigate,
) {
    /** What the UI calls this camera — see [cameraDisplayName]. */
    val displayName: String get() = cameraDisplayName(name)
}

/** Which side of the walls a camera watches: which list it is in on the Home page. */
enum class CameraPlacement { Outdoor, Indoor }

/**
 * Where a camera's video comes from, and with it what the app can do with it. Frigate's cameras
 * have everything: recordings, detections, the timeline. A Nest camera is a go2rtc stream pulled
 * from Google only while someone is watching, so it is live video and nothing else.
 */
enum class CameraSource { Frigate, Nest }
