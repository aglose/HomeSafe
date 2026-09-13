package com.meticulouscreations.homesafe.domain.repository

/**
 * Where a server's live video, snapshots and thumbnails can be fetched from. The URL shapes are
 * Frigate's business and live in the data layer; the domain only knows it can ask for them.
 * Every call takes the server URL explicitly so a LAN ↔ Tailscale route change re-points every
 * derived URL as soon as the caller observes the new address.
 */
interface MediaUrlRepository {
    /**
     * The live HLS stream for one of a camera's go2rtc stream names. [audioCodecs] non-empty asks
     * for an audio track in those codecs (the single-camera view wants sound; the grid doesn't).
     */
    fun liveStreamUrl(serverUrl: String, streamName: String, audioCodecs: List<String> = emptyList()): String

    /**
     * Where a WebRTC session for one of a camera's stream names is negotiated: the player POSTs
     * its SDP offer here and gets the answer back. The lower-latency route to the same video as
     * [liveStreamUrl]; players keep that HLS URL as the fallback.
     */
    fun liveWebRtcSignalingUrl(serverUrl: String, streamName: String): String

    /**
     * The camera's most recent frame, optionally scaled server-side to [height]. A non-null
     * [cacheBuster] is appended so the image is fetched afresh rather than served from a cache.
     */
    fun cameraSnapshotUrl(serverUrl: String, cameraName: String, height: Int? = null, cacheBuster: Long? = null): String

    /** A detection's small, object-cropped thumbnail. */
    fun eventThumbnailUrl(serverUrl: String, eventId: String): String

    /** A frame from [cameraName]'s recordings at [epochSeconds], optionally scaled to [height]. */
    fun recordingSnapshotUrl(serverUrl: String, cameraName: String, epochSeconds: Double, height: Int? = null): String
}
