package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.EventFrame
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.TrackedObject
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop

/**
 * Frigate's custom classifiers (e.g. the known-cars model) and the human-in-the-loop labelling
 * that makes them better. The server owns all of it; nothing is cached here.
 */
interface ClassifierRepository {
    suspend fun getModels(): Result<List<ClassifierModel>>
    suspend fun getDataset(modelName: String): Result<ClassifierDataset>

    /** What [cameraName] is tracking right now, for labelling a car while it's still in view. */
    suspend fun getTrackedObjects(cameraName: String): Result<List<TrackedObject>>

    /** [category] is a Frigate-safe key (see [com.meticulouscreations.homesafe.domain.model.DetectionZone.slug]). */
    suspend fun createCategory(modelName: String, category: String): Result<Unit>
    suspend fun label(modelName: String, fileName: String, category: String): Result<Unit>
    suspend fun discard(modelName: String, fileNames: List<String>): Result<Unit>
    suspend fun train(modelName: String): Result<Unit>

    /** [cameraName]'s latest frame at detect resolution, as JPEG bytes: what a car gets boxed on. */
    suspend fun getLatestFrame(cameraName: String): Result<ByteArray>

    /** Adds [box] of [frame] to [modelName]'s dataset under [category], cropped the way Frigate's classifier would crop it. */
    suspend fun addExample(modelName: String, category: String, frame: ByteArray, box: SeenBox): Result<Unit>

    /** Names a tracked object (its event) as a person would: fully sure. Null clears the name. */
    suspend fun nameTrackedObject(eventId: String, subLabel: String?): Result<Unit>

    /**
     * The crops waiting for a label, as [getDataset] lists them but without looking up each one's
     * event: cheap enough to ask for just to find one detection's crops.
     */
    suspend fun getQueue(modelName: String): Result<List<UnlabeledCrop>>

    /** One detection as Frigate has it now, name included; null when Frigate no longer has it. */
    suspend fun getDetection(eventId: String): Result<MomentEvent?>

    /**
     * A still of [eventId]'s object and its box on it: the recording at the moment Frigate kept its
     * biggest box, at the camera's detect resolution. Fails when there's no box or no recording.
     */
    suspend fun getEventFrame(eventId: String): Result<EventFrame>

    /** Where a queued crop's image lives, for the current server; null when disconnected. */
    fun queueImageUrl(modelName: String, fileName: String): String?
}
