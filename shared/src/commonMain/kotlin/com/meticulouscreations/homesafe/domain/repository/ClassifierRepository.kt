package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.TrackedObject

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

    /** Where a queued crop's image lives, for the current server; null when disconnected. */
    fun queueImageUrl(modelName: String, fileName: String): String?
}
