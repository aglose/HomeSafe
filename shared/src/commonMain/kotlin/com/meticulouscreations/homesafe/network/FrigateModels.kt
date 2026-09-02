package com.meticulouscreations.homesafe.network

import kotlinx.serialization.SerialName
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

/** One recorded clip as reported by Frigate's `/api/{camera}/recordings`. */
@Serializable
data class FrigateRecording(
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
    val duration: Double? = null,
    val motion: Int? = null,
    val objects: Int? = null,
)
