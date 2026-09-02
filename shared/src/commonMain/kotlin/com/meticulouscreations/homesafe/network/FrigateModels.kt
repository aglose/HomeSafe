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
    val live: FrigateLiveConfig? = null,
)

/**
 * A camera's `live.streams` config: friendly name -> go2rtc stream name. Entirely user-defined,
 * with no fixed naming convention (not even a "_sub" suffix) — see [FrigateCamera] for how this
 * is turned into a best-effort main/grid stream pair.
 */
@Serializable
internal data class FrigateLiveConfig(
    val streams: Map<String, String>? = null,
)

/** A camera as reported by Frigate's `/api/config`. */
data class FrigateCamera(
    val name: String,
    val enabled: Boolean,
    /** The full-quality stream name to use for the single-camera detail view. */
    val liveStreamName: String,
    /** The (possibly lower-quality) stream name to use for the multi-camera grid. */
    val gridStreamName: String,
)
