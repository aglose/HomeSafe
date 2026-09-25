package com.meticulouscreations.homesafe.fakefrigate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [FakeFrigateState] rendered in the JSON shapes Frigate 0.17 serves — only the fields the app
 * reads, with Frigate's own snake_case names. Kept apart from the routing so a scenario can be
 * checked against the app's parsers without starting a server.
 */
internal object FrigateJson {

    fun config(state: FakeFrigateState): JsonObject = buildJsonObject {
        val server = state.server
        putJsonObject("cameras") {
            state.cameras.forEach { camera -> put(camera.name, camera(camera)) }
        }
        putJsonObject("record") {
            put("enabled", true)
            putJsonObject("continuous") { put("days", server.continuousRetentionDays) }
            putJsonObject("motion") { put("days", server.motionRetentionDays) }
            putJsonObject("alerts") { putJsonObject("retain") { put("days", server.alertRetentionDays) } }
            putJsonObject("detections") { putJsonObject("retain") { put("days", server.detectionRetentionDays) } }
        }
        putJsonObject("detectors") {
            putJsonObject(server.detectorName) { put("type", server.detectorType) }
        }
        putJsonObject("model") {
            put("path", "/config/model_cache/yolo.onnx")
            put("width", 320)
            put("height", 320)
            put("model_type", "yolo-generic")
        }
        putJsonObject("face_recognition") { put("enabled", server.faceRecognitionEnabled) }
        putJsonObject("lpr") { put("enabled", server.licensePlateRecognitionEnabled) }
        putJsonObject("semantic_search") { put("enabled", server.semanticSearchEnabled) }
        putJsonObject("classification") {
            putJsonObject("custom") {
                state.classifiers.forEach { classifier ->
                    putJsonObject(classifier.name) {
                        put("enabled", classifier.enabled)
                        putJsonObject("object_config") { put("objects", strings(classifier.objects)) }
                    }
                }
            }
        }
    }

    private fun camera(camera: FakeCamera): JsonObject = buildJsonObject {
        put("enabled", camera.enabled)
        putJsonObject("detect") {
            put("enabled", camera.detectEnabled)
            put("width", camera.detectWidth)
            put("height", camera.detectHeight)
        }
        putJsonObject("motion") {
            put("enabled", camera.motionEnabled)
            put("mask", strings(camera.motionMasks))
        }
        putJsonObject("objects") {
            put("mask", strings(camera.objectMasks))
            put("track", strings(camera.trackedObjects))
        }
        putJsonObject("zones") {
            camera.zones.forEach { zone ->
                putJsonObject(zone.name) {
                    put("coordinates", zone.coordinates)
                    put("objects", strings(zone.objects))
                    zone.friendlyName?.let { put("friendly_name", it) }
                }
            }
        }
        if (camera.liveStreams.isNotEmpty()) {
            putJsonObject("live") {
                putJsonObject("streams") { camera.liveStreams.forEach { (name, stream) -> put(name, stream) } }
            }
        }
    }

    fun events(events: List<FakeEvent>): JsonArray = buildJsonArray {
        events.forEach { add(event(it)) }
    }

    private fun event(event: FakeEvent): JsonObject = buildJsonObject {
        put("id", event.id)
        put("label", event.label)
        put("sub_label", event.subLabel)
        put("camera", event.camera)
        put("start_time", event.startTime)
        put("end_time", event.endTime)
        put("has_clip", event.hasClip)
        put("has_snapshot", event.hasSnapshot)
        put("zones", strings(event.zones))
        putJsonObject("data") {
            put("top_score", event.score)
            put("score", event.score)
            event.subLabelScore?.let { put("sub_label_score", it) }
            put("type", "object")
            putJsonArray("box") { event.box.forEach { add(it) } }
        }
    }

    fun recordings(recordings: List<FakeRecording>): JsonArray = buildJsonArray {
        recordings.forEach { recording ->
            add(
                buildJsonObject {
                    put("start_time", recording.startTime)
                    put("end_time", recording.endTime)
                    put("duration", recording.endTime - recording.startTime)
                    put("motion", recording.motion)
                    put("objects", recording.objects)
                },
            )
        }
    }

    fun stats(state: FakeFrigateState): JsonObject = buildJsonObject {
        val server = state.server
        putJsonObject("service") {
            put("version", server.version)
            put("latest_version", server.latestVersion)
            put("uptime", server.uptimeSeconds)
            putJsonObject("storage") {
                putJsonObject("/media/frigate/recordings") {
                    put("total", server.recordingsTotalMb)
                    put("used", server.recordingsUsedMb)
                    put("free", server.recordingsTotalMb - server.recordingsUsedMb)
                    put("mount_type", "ext4")
                }
            }
        }
        putJsonObject("cameras") {
            state.cameras.forEach { camera ->
                putJsonObject(camera.name) {
                    val fps = if (camera.enabled) 5.0 else 0.0
                    put("camera_fps", fps)
                    put("process_fps", fps)
                    put("skipped_fps", 0.0)
                    put("detection_fps", if (camera.detectEnabled) 1.2 else 0.0)
                }
            }
        }
        putJsonObject("detectors") {
            putJsonObject(server.detectorName) { put("inference_speed", server.inferenceMs) }
        }
        putJsonObject("cpu_usages") {
            putJsonObject("frigate.full_system") {
                put("cpu", server.cpuPercent)
                put("mem", server.memoryPercent)
            }
        }
        putJsonObject("gpu_usages") {}
        put("detection_fps", state.cameras.count { it.detectEnabled } * 1.2)
    }

    fun profile(user: FakeUser): JsonObject = buildJsonObject {
        put("username", user.username)
        put("role", user.role)
    }

    fun faces(state: FakeFrigateState): JsonObject = buildJsonObject {
        state.faces.forEach { (folder, files) -> put(folder, strings(files)) }
    }

    fun dataset(classifier: FakeClassifier): JsonObject = buildJsonObject {
        putJsonObject("categories") {
            classifier.categories.forEach { (category, files) -> put(category, strings(files)) }
        }
        putJsonObject("training_metadata") {
            val count = classifier.categories.values.sumOf { it.size }
            put("has_trained", classifier.trainings > 0)
            put("last_training_image_count", if (classifier.trainings > 0) count else 0)
            put("current_image_count", count)
            put("new_images_count", if (classifier.trainings > 0) 0 else count)
            put("dataset_changed", classifier.trainings == 0)
        }
    }

    fun success(message: String): JsonObject = buildJsonObject {
        put("success", true)
        put("message", message)
    }

    fun failure(message: String): JsonObject = buildJsonObject {
        put("success", false)
        put("message", message)
    }

    fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
