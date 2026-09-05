package com.meticulouscreations.homesafe.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    val record: FrigateRecordConfig? = null,
    val detectors: Map<String, FrigateDetectorConfig> = emptyMap(),
    val model: FrigateModelConfig? = null,
    @SerialName("face_recognition") val faceRecognition: FrigateEnabledConfig? = null,
    val lpr: FrigateEnabledConfig? = null,
    @SerialName("semantic_search") val semanticSearch: FrigateEnabledConfig? = null,
)

/** Any config block whose only interesting field is `enabled` (face recognition, LPR, semantic search, ...). */
@Serializable
internal data class FrigateEnabledConfig(val enabled: Boolean = false)

/** The global `record` block; retention is what the Settings tab reports. Days can be fractional in Frigate. */
@Serializable
internal data class FrigateRecordConfig(
    val enabled: Boolean = true,
    val continuous: FrigateRetentionDays? = null,
    val motion: FrigateRetentionDays? = null,
    val alerts: FrigateEventRecordConfig? = null,
    val detections: FrigateEventRecordConfig? = null,
)

@Serializable
internal data class FrigateRetentionDays(val days: Double = 0.0)

@Serializable
internal data class FrigateEventRecordConfig(val retain: FrigateRetentionDays? = null)

/** One entry of `detectors`: its `type` is the backend ("onnx", "edgetpu", "cpu", ...). */
@Serializable
internal data class FrigateDetectorConfig(val type: String = "")

/** The global `model` block, as much of it as the Settings tab shows. */
@Serializable
internal data class FrigateModelConfig(
    val path: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("model_type") val modelType: String? = null,
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
    val enabled: Boolean = true,
    val width: Int? = null,
    val height: Int? = null,
)

/** `motion.mask`: Frigate serves it as `""`, one polygon string, or a list of them — see [FrigateMaskListSerializer]. */
@Serializable
internal data class FrigateMotionConfig(
    val enabled: Boolean = true,
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

/**
 * JSON body of `PUT /api/config/set`. Values ride either in the query string (strings and
 * lists — masks, zones) or in [configData], a nested object merged into the config. Query
 * values are written to config.yml verbatim as strings, so a boolean must go through
 * [configData] to land as a real `true`/`false` rather than `'true'`.
 */
@Serializable
internal data class ConfigSetRequest(
    /** 0 applies the change live (the topic below tells the camera process what changed). */
    @SerialName("requires_restart") val requiresRestart: Int,
    @SerialName("update_topic") val updateTopic: String,
    @SerialName("config_data") val configData: JsonObject? = null,
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

/**
 * `GET /api/stats`, the parts the Settings tab reports. Field set observed against Frigate
 * 0.17.2. `cpu_usages` is keyed by pid except for the `frigate.full_system` roll-up; the
 * per-process rows are dropped on the floor here. Percentages arrive as strings ("9.4", "8.0%").
 */
@Serializable
internal data class FrigateStatsResponse(
    val service: FrigateServiceStats? = null,
    val cameras: Map<String, FrigateCameraStats> = emptyMap(),
    val detectors: Map<String, FrigateDetectorStats> = emptyMap(),
    @SerialName("cpu_usages") val cpuUsages: Map<String, FrigateProcessStats> = emptyMap(),
    @SerialName("gpu_usages") val gpuUsages: Map<String, FrigateGpuStats> = emptyMap(),
    @SerialName("detection_fps") val detectionFps: Double? = null,
)

@Serializable
internal data class FrigateServiceStats(
    val version: String = "",
    @SerialName("latest_version") val latestVersion: String? = null,
    /** Seconds since the Frigate process started. */
    val uptime: Long = 0,
    /** Keyed by mount path (`/media/frigate/recordings`, `/dev/shm`, ...); sizes in megabytes. */
    val storage: Map<String, FrigateStorageStats> = emptyMap(),
)

@Serializable
internal data class FrigateStorageStats(
    val total: Double = 0.0,
    val used: Double = 0.0,
    val free: Double = 0.0,
    @SerialName("mount_type") val mountType: String? = null,
)

@Serializable
internal data class FrigateCameraStats(
    @SerialName("camera_fps") val cameraFps: Double = 0.0,
    @SerialName("process_fps") val processFps: Double = 0.0,
    @SerialName("skipped_fps") val skippedFps: Double = 0.0,
    @SerialName("detection_fps") val detectionFps: Double = 0.0,
)

@Serializable
internal data class FrigateDetectorStats(
    /** Milliseconds per inference, averaged. */
    @SerialName("inference_speed") val inferenceSpeed: Double = 0.0,
)

@Serializable
internal data class FrigateProcessStats(
    val cpu: String? = null,
    val mem: String? = null,
)

@Serializable
internal data class FrigateGpuStats(
    val gpu: String? = null,
    val mem: String? = null,
    val enc: String? = null,
    val dec: String? = null,
)

/** `GET /api/profile`: who the session cookie belongs to. `role` is "admin" or "viewer". */
@Serializable
internal data class FrigateProfileResponse(
    val username: String = "",
    val role: String? = null,
)

/** `/api/stats` distilled: what the server is, how hard it's working, and what each camera's pipeline is doing. */
data class FrigateServerStats(
    val version: String,
    val latestVersion: String?,
    val uptimeSeconds: Long,
    /** Whole-system CPU %, from Frigate's `frigate.full_system` roll-up; null if it wasn't reported. */
    val cpuPercent: Double?,
    /** Whole-system memory %, same source. */
    val memoryPercent: Double?,
    /** Keyed by mount path, sizes in megabytes. */
    val storage: Map<String, FrigateStorage>,
    val detectors: List<FrigateDetector>,
    val gpus: List<FrigateGpu>,
    val cameras: Map<String, FrigateCameraPipeline>,
    val totalDetectionFps: Double?,
)

data class FrigateStorage(val totalMb: Double, val usedMb: Double, val freeMb: Double)

data class FrigateDetector(val name: String, val inferenceMs: Double)

data class FrigateGpu(val name: String, val gpuPercent: Double?, val memoryPercent: Double?, val decoderPercent: Double?)

/**
 * A camera's throughput from `/api/stats`. Deliberately not its `detection_enabled` flag: that
 * only follows Frigate's own MQTT/websocket toggle and stays stale after a `config/set`, whereas
 * `/api/config`'s `detect.enabled` reflects both paths — so the switch reads the config.
 */
data class FrigateCameraPipeline(
    val cameraFps: Double,
    val processFps: Double,
    val skippedFps: Double,
    val detectionFps: Double,
)

/** The server-wide bits of `/api/config` the Settings tab shows and edits. */
data class FrigateServerConfig(
    val retention: FrigateRetention,
    val detectors: Map<String, String>,
    val model: FrigateModelInfo?,
    val faceRecognitionEnabled: Boolean,
    val licensePlateRecognitionEnabled: Boolean,
    val semanticSearchEnabled: Boolean,
    val cameras: List<FrigateCameraPipelineConfig>,
)

data class FrigateRetention(
    val continuousDays: Double,
    val motionDays: Double,
    val alertDays: Double?,
    val detectionDays: Double?,
)

data class FrigateModelInfo(val modelType: String?, val path: String?, val width: Int?, val height: Int?)

data class FrigateCameraPipelineConfig(
    val name: String,
    val enabled: Boolean,
    val detectEnabled: Boolean,
    val motionEnabled: Boolean,
)
