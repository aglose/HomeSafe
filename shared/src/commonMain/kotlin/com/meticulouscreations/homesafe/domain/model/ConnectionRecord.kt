package com.meticulouscreations.homesafe.domain.model

/** A past successful connection to a Frigate server, used to prefill the connect screen. */
data class ConnectionRecord(
    val serverUrl: String,
    /** The server's private LAN URL the user entered alongside [serverUrl], if any. */
    val localUrl: String?,
    val connectedAtEpochMillis: Long,
)
