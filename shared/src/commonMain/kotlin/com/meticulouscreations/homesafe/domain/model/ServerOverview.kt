package com.meticulouscreations.homesafe.domain.model

import kotlin.math.roundToInt

/**
 * A snapshot of the connected Frigate server, as the Settings tab reports it: identity, load,
 * disk, the detection hardware, retention policy, and each camera's pipeline switches. Built from
 * `/api/stats` and `/api/config` together; see `ServerStatusRepository`.
 */
data class ServerOverview(
    val version: String,
    /** The newest release Frigate knows of; non-null and different from [version] means an update is available. */
    val latestVersion: String?,
    val uptimeSeconds: Long,
    val cpuPercent: Double?,
    val memoryPercent: Double?,
    /** The recordings mount, or null if the server didn't report one. */
    val recordingsStorage: StorageUsage?,
    val detector: DetectorInfo?,
    val gpus: List<GpuLoad>,
    val retention: RetentionPolicy,
    val faceRecognitionEnabled: Boolean,
    val licensePlateRecognitionEnabled: Boolean,
    val semanticSearchEnabled: Boolean,
    val cameras: List<CameraPipeline>,
    /** Whether the signed-in account may change config. Viewers see the switches but can't flip them. */
    val canEditConfig: Boolean,
) {
    val updateAvailable: Boolean
        get() = latestVersion != null && latestVersion != version && !version.startsWith(latestVersion)
}

data class StorageUsage(val path: String, val usedMb: Double, val totalMb: Double) {
    val usedFraction: Float get() = if (totalMb > 0) (usedMb / totalMb).toFloat().coerceIn(0f, 1f) else 0f
}

data class DetectorInfo(
    /** The detector's config key, e.g. "onnx" or "coral". */
    val name: String,
    /** The backend type: "onnx", "edgetpu", "openvino", "cpu", ... */
    val type: String,
    val modelType: String?,
    val modelFileName: String?,
    val inputWidth: Int?,
    val inputHeight: Int?,
    /** Average inference time in milliseconds, or null before the detector has run. */
    val inferenceMs: Double?,
)

data class GpuLoad(val name: String, val gpuPercent: Double?, val memoryPercent: Double?, val decoderPercent: Double?)

/** Frigate's `record` retention, in days. Null event retention means Frigate's default applies. */
data class RetentionPolicy(val continuousDays: Double, val motionDays: Double, val alertDays: Double?, val detectionDays: Double?)

/** One camera's detection pipeline: the two live switches and what they're producing right now. */
data class CameraPipeline(
    val name: String,
    val enabled: Boolean,
    val detectionEnabled: Boolean,
    val motionEnabled: Boolean,
    val cameraFps: Double?,
    val detectionFps: Double?,
    val skippedFps: Double?,
    val zones: List<CameraZone> = emptyList(),
) {
    val displayName: String get() = cameraDisplayName(name)
}

/** A zone drawn on a camera: Frigate's key, shown by its friendly name when one is set. */
data class CameraZone(val name: String, val friendlyName: String? = null) {
    val displayName: String
        get() = friendlyName ?: zoneDisplayName(name).replaceFirstChar(Char::uppercase)
}

/** "3 days, 2 hours" / "2 hours, 5 minutes" / "40 seconds" — the largest two units that apply. */
fun formatUptime(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val days = total / 86_400
    val hours = (total % 86_400) / 3_600
    val minutes = (total % 3_600) / 60
    fun unit(n: Long, name: String) = "$n $name${if (n == 1L) "" else "s"}"
    return when {
        days > 0 -> "${unit(days, "day")}, ${unit(hours, "hour")}"
        hours > 0 -> "${unit(hours, "hour")}, ${unit(minutes, "minute")}"
        minutes > 0 -> unit(minutes, "minute")
        else -> unit(total, "second")
    }
}

/** Megabytes as Frigate reports them, rendered the way its own UI does (decimal units): "412.7 GB", "1.2 TB", "512 MB". */
fun formatMegabytes(mb: Double): String = when {
    mb >= 1_000_000 -> "${(mb / 1_000_000).format1()} TB"
    mb >= 1_000 -> "${(mb / 1_000).format1()} GB"
    else -> "${mb.roundToInt()} MB"
}

/** "9%" — a whole-number percentage, or an em dash when the server didn't report one. */
fun formatPercent(value: Double?): String = value?.let { "${it.roundToInt()}%" } ?: "—"

/** "7 days" / "1 day" / "1.5 days" */
fun formatRetentionDays(days: Double): String {
    val text = if (days == days.toLong().toDouble()) days.toLong().toString() else days.format1()
    return "$text day${if (days == 1.0) "" else "s"}"
}

/** One decimal, trailing ".0" dropped: 412.65 -> "412.7", 2.0 -> "2". */
private fun Double.format1(): String {
    val scaled = (this * 10).roundToInt()
    val whole = scaled / 10
    val tenth = scaled % 10
    return if (tenth == 0) "$whole" else "$whole.$tenth"
}
