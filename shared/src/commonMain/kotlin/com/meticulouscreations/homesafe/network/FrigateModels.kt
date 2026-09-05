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

/**
 * One detection as reported by Frigate's `/api/events`. Field set observed against Frigate
 * 0.17.2: thumbnails are NOT inline here anymore (`thumbnail` is null) — they come from
 * `/api/events/{id}/thumbnail.jpg`. [endTime] is null while an event is still in progress.
 */
@Serializable
data class FrigateEvent(
    val id: String,
    val label: String,
    @SerialName("sub_label") val subLabel: String? = null,
    val camera: String,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double? = null,
    @SerialName("has_clip") val hasClip: Boolean = false,
    @SerialName("has_snapshot") val hasSnapshot: Boolean = false,
    val zones: List<String> = emptyList(),
    val data: FrigateEventData? = null,
)

@Serializable
data class FrigateEventData(
    @SerialName("top_score") val topScore: Double? = null,
    val score: Double? = null,
    val type: String? = null,
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
