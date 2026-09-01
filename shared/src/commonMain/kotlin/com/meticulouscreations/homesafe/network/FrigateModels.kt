package com.meticulouscreations.homesafe.network

import kotlinx.serialization.Serializable

@Serializable
internal data class LoginRequest(
    val user: String,
    val password: String,
)

@Serializable
internal data class FrigateConfigResponse(
    val cameras: Map<String, FrigateCameraConfig> = emptyMap(),
)

@Serializable
internal data class FrigateCameraConfig(
    val enabled: Boolean = true,
)

/** A camera as reported by Frigate's `/api/config`. */
data class FrigateCamera(
    val name: String,
    val enabled: Boolean,
)
