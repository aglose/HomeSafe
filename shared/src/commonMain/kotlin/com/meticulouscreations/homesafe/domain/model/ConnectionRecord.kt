package com.meticulouscreations.homesafe.domain.model

/** A past successful connection to a Frigate server, used to prefill the connect screen. */
data class ConnectionRecord(
    val serverUrl: String,
    val connectedAtEpochMillis: Long,
)
