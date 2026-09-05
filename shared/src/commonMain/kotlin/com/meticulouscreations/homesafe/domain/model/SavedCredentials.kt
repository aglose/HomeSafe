package com.meticulouscreations.homesafe.domain.model

import kotlinx.serialization.Serializable

/** Login credentials for a Frigate server, saved locally behind a biometric check. */
@Serializable
data class SavedCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    /** The server's private LAN URL, if the user entered one. Defaults so credentials saved before it existed still decode. */
    val localUrl: String? = null,
)
