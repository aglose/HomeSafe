package com.meticulouscreations.homesafe.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

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
    val detect: FrigateDetectConfig? = null,
    val motion: FrigateMotionConfig? = null,
    val objects: FrigateObjectsConfig? = null,
    val zones: Map<String, FrigateZoneConfig> = emptyMap(),
)

/** One entry of a camera's `zones` map. `coordinates` is a polygon string; `objects` a label list. */
@Serializable
internal data class FrigateZoneConfig(
    @Serializable(with = FrigateMaskListSerializer::class) val coordinates: List<String> = emptyList(),
    @Serializable(with = FrigateMaskListSerializer::class) val objects: List<String> = emptyList(),
    @SerialName("friendly_name") val friendlyName: String? = null,
)

@Serializable
internal data class FrigateDetectConfig(
    val width: Int? = null,
    val height: Int? = null,
)

/** `motion.mask`: Frigate serves it as `""`, one polygon string, or a list of them — see [FrigateMaskListSerializer]. */
@Serializable
internal data class FrigateMotionConfig(
    @Serializable(with = FrigateMaskListSerializer::class) val mask: List<String> = emptyList(),
)

/** `objects.mask` (the all-labels object filter mask), same shape as [FrigateMotionConfig.mask]; `track` is the label list. */
@Serializable
internal data class FrigateObjectsConfig(
    @Serializable(with = FrigateMaskListSerializer::class) val mask: List<String> = emptyList(),
    @Serializable(with = FrigateMaskListSerializer::class) val track: List<String> = emptyList(),
)

/** One zone as read from `/api/config`. */
data class FrigateZone(
    val name: String,
    /** Raw polygon string. */
    val coordinates: String,
    val objects: List<String>,
    val friendlyName: String?,
)

/**
 * Normalises Frigate's `mask` field, which is `""` (none), a single `"x,y,..."` string, a list of
 * such strings, or occasionally `null`, into a plain list of polygon strings.
 */
internal object FrigateMaskListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when {
        element is JsonArray -> element
        element is JsonPrimitive && element.isString && element.content.isNotBlank() -> JsonArray(listOf(element))
        else -> JsonArray(emptyList())
    }
}

/** The subset of a camera's `/api/config` entry that the detection-zones editor works with. */
data class FrigateDetectionConfig(
    val cameraName: String,
    val detectWidth: Int,
    val detectHeight: Int,
    /** Raw polygon strings, straight from `objects.mask`. */
    val objectMasks: List<String>,
    /** Raw polygon strings, straight from `motion.mask`. */
    val motionMasks: List<String>,
    val zones: List<FrigateZone> = emptyList(),
    /** `objects.track` for this camera. */
    val trackedObjects: List<String> = emptyList(),
)

/** JSON body of `PUT /api/config/set`; the values themselves ride in the query string. */
@Serializable
internal data class ConfigSetRequest(
    /** 0 applies the change live (the topic below tells the camera process what changed). */
    @SerialName("requires_restart") val requiresRestart: Int,
    @SerialName("update_topic") val updateTopic: String,
)

@Serializable
internal data class ConfigSetResponse(
    val success: Boolean = false,
    val message: String? = null,
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
