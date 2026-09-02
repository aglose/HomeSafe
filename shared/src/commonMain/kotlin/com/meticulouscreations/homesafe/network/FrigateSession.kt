package com.meticulouscreations.homesafe.network

/**
 * Frigate's go2rtc live-stream endpoint runs on its own port (1984), separate from the
 * authenticated API — it relies on the Tailscale network boundary for trust rather than
 * app-level auth.
 */
fun frigateLiveStreamUrl(serverUrl: String, cameraName: String): String {
    val host = serverUrl.substringAfter("://").substringBefore(":").substringBefore("/")
    return "http://$host:1984/api/stream.m3u8?src=$cameraName"
}

/** Frigate's cached latest-snapshot endpoint — always available once a camera has processed a frame. */
fun frigateSnapshotUrl(serverUrl: String, cameraName: String): String =
    "${serverUrl.trimEnd('/')}/api/$cameraName/latest.jpg"
