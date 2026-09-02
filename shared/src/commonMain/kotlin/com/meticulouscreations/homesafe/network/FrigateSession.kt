package com.meticulouscreations.homesafe.network

import kotlin.math.floor

/**
 * Frigate's go2rtc live-stream endpoint runs on its own port (1984), separate from the
 * authenticated API — it relies on the Tailscale network boundary for trust rather than
 * app-level auth.
 */
fun frigateLiveStreamUrl(serverUrl: String, cameraName: String): String {
    val host = serverUrl.substringAfter("://").substringBefore(":").substringBefore("/")
    return "http://$host:1984/api/stream.m3u8?src=$cameraName"
}

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

/** Frigate's cached latest-snapshot endpoint — always available once a camera has processed a frame. */
fun frigateSnapshotUrl(serverUrl: String, cameraName: String): String =
    "${serverUrl.trimEnd('/')}/api/$cameraName/latest.jpg"
