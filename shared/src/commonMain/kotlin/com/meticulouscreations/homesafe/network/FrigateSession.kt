package com.meticulouscreations.homesafe.network

import kotlin.math.floor

/**
 * Frigate's go2rtc live-stream endpoint runs on its own port (1984), separate from the
 * authenticated API — it relies on the Tailscale network boundary for trust rather than
 * app-level auth.
 *
 * With no query beyond `src`, go2rtc's HLS is video-only (measured against the real server:
 * `CODECS="avc1..."` even though the camera publishes Opus). Naming [audioCodecs] asks for
 * `video&audio=<codecs>`, and go2rtc adds whichever of those the camera actually has — or
 * nothing, leaving the stream exactly as it was, so asking never breaks playback. Pass the
 * player's own decodable list (`liveAudioCodecs`); the grid passes none, keeping its several
 * simultaneous players silent and cheap.
 */
fun frigateLiveStreamUrl(serverUrl: String, cameraName: String, audioCodecs: List<String> = emptyList()): String {
    val audio = if (audioCodecs.isEmpty()) "" else "&video&audio=${audioCodecs.joinToString(",")}"
    return "http://${go2rtcHost(serverUrl)}:1984/api/stream.m3u8?src=$cameraName$audio"
}

/**
 * go2rtc's WebRTC signaling endpoint for one of a camera's stream names: an SDP offer is POSTed
 * here and the answer comes back in the response (WHEP-style; see `WhepSignalingClient`). Same
 * port and trust model as [frigateLiveStreamUrl]. Which tracks the peer asks for is in the offer,
 * not the URL, so there is no audio parameter.
 */
fun frigateWebRtcSignalingUrl(serverUrl: String, cameraName: String): String =
    "http://${go2rtcHost(serverUrl)}:1984/api/webrtc?src=$cameraName"

/** go2rtc shares Frigate's host; only the port differs. */
private fun go2rtcHost(serverUrl: String): String = serverUrl.substringAfter("://").substringBefore(":").substringBefore("/")

/**
 * Frigate's seekable HLS playlist of everything [cameraName] recorded between
 * [startEpochSeconds] and [endEpochSeconds]. Unlike the live stream this goes through Frigate's
 * own (authenticated) port, so the player must send the session cookie with it.
 */
fun frigateRecordingStreamUrl(
    serverUrl: String,
    cameraName: String,
    startEpochSeconds: Double,
    endEpochSeconds: Double,
): String =
    "${serverUrl.trimEnd('/')}/vod/$cameraName" +
        "/start/${formatEpochSeconds(startEpochSeconds)}" +
        "/end/${formatEpochSeconds(endEpochSeconds)}" +
        "/index.m3u8"

/**
 * Renders epoch seconds for a Frigate URL with millisecond precision. `Double.toString()` is not
 * usable here: on the JVM it prints anything ≥ 1e7 in scientific notation ("1.7E9"), which
 * Frigate rejects. Truncates rather than rounds so a rendered start never lands *after* the clip
 * it was taken from (which would make Frigate trim the clip's head) and a rendered end never
 * lands after the clip's real end (which would pull the *next* clip into the playlist as a
 * millisecond-long sliver).
 */
fun formatEpochSeconds(seconds: Double): String {
    val totalMillis = floor(seconds * 1000.0).toLong()
    val whole = totalMillis / 1000
    val millis = totalMillis % 1000
    return "$whole.${millis.toString().padStart(3, '0')}"
}

/**
 * The same URL over the opposite scheme, or null if it carries no scheme to swap. Used to
 * recover when a saved URL's scheme no longer matches what the server speaks.
 */
fun swapUrlScheme(url: String): String? = when {
    url.startsWith("https://") -> "http://" + url.removePrefix("https://")
    url.startsWith("http://") -> "https://" + url.removePrefix("http://")
    else -> null
}

/** A detection's thumbnail — small, cropped to the object. Authenticated, like snapshots. */
fun frigateEventThumbnailUrl(serverUrl: String, eventId: String): String =
    "${serverUrl.trimEnd('/')}/api/events/$eventId/thumbnail.jpg"

/**
 * A detection's clip as a seekable HLS playlist, served by the same `/vod` machinery as camera
 * recordings — so it plays through [com.meticulouscreations.homesafe.ui.components.VideoSource.Recording]
 * with the session cookie, no new player path needed.
 */
fun frigateEventClipUrl(serverUrl: String, eventId: String): String =
    "${serverUrl.trimEnd('/')}/vod/event/$eventId/index.m3u8"

/**
 * A detection's clip as a single downloadable MP4 file — Frigate's export endpoint, distinct
 * from the HLS playlist [frigateEventClipUrl] serves for in-app playback. This is what a
 * "download to the device" feature has to hit, since an HLS playlist isn't a file a platform
 * download API can save as one piece.
 */
fun frigateEventClipDownloadUrl(serverUrl: String, eventId: String): String =
    "${serverUrl.trimEnd('/')}/api/events/$eventId/clip.mp4"

/**
 * Frigate's latest-frame endpoint — the most recent frame the detect process handled, JPEG-encoded
 * on request (a few milliseconds, served with `no-store`). Always available once a camera has
 * processed a frame. [height] asks Frigate to scale it down server-side, which is worth doing
 * for thumbnails refreshed every second: a 480-tall frame is ~30 KB.
 */
fun frigateSnapshotUrl(serverUrl: String, cameraName: String, height: Int? = null): String =
    "${serverUrl.trimEnd('/')}/api/$cameraName/latest.jpg" + (height?.let { "?h=$it" } ?: "")

/**
 * A frame from [cameraName]'s recordings at [epochSeconds], extracted by Frigate on request
 * (~150 ms on the real server; ~30 KB at [height] 480). The "first picture" for any playback
 * that starts at a known moment — a history seek, an event clip — and for scrub previews.
 * 404 when nothing was recorded at that time.
 */
fun frigateRecordingSnapshotUrl(serverUrl: String, cameraName: String, epochSeconds: Double, height: Int? = null): String =
    "${serverUrl.trimEnd('/')}/api/$cameraName/recordings/${formatEpochSeconds(epochSeconds)}/snapshot.jpg" +
        (height?.let { "?height=$it" } ?: "")
