package com.meticulouscreations.homesafe.domain.model

/** A playable HLS URL for a [RecordingPlaylist], plus any HTTP headers the player must send with every request. */
data class RecordingStream(
    val url: String,
    val headers: Map<String, String>,
)
