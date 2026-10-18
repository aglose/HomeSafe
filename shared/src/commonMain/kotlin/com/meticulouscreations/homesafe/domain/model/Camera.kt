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
    /**
     * The picture is a Google Nest camera's, whoever plays it: a [CameraSource.Nest] camera, or a
     * Frigate camera reading a Nest stream (see [camerasWithNest]). Its shape is whatever the
     * model makes it (a doorbell's is tall), so it is shown at that shape rather than stretched.
     */
    val fromNest: Boolean = source == CameraSource.Nest,
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

/**
 * Frigate's [frigate] cameras and the [nest] cameras as one list. A Frigate camera reading one
 * of the Nest streams (by its own name or either stream it plays) is that Nest camera, with
 * Frigate's recordings, snapshots and settings behind it: it takes the Nest camera's place
 * (indoors, or outdoors for an `outdoor_` stream) and is marked [Camera.fromNest], and the Nest
 * entry itself is left out. The rest of the Nest cameras follow Frigate's.
 */
fun camerasWithNest(frigate: List<Camera>, nest: List<Camera>): List<Camera> {
    val nestByStream = nest.associateBy { it.name }
    val played = HashSet<String>()
    val merged = frigate.map { camera ->
        val stream = listOf(camera.name, camera.liveStreamName, camera.gridStreamName).firstNotNullOfOrNull { nestByStream[it] }
        if (stream == null) {
            camera
        } else {
            played += stream.name
            camera.copy(placement = stream.placement, fromNest = true)
        }
    }
    return merged + nest.filterNot { it.name in played }
}
