package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel

/**
 * Frigate's custom classifiers (e.g. the known-cars model) and the human-in-the-loop labelling
 * that makes them better. The server owns all of it; nothing is cached here.
 */
interface ClassifierRepository {
    suspend fun getModels(): Result<List<ClassifierModel>>
    suspend fun getDataset(modelName: String): Result<ClassifierDataset>
    /** [category] is a Frigate-safe key (see [com.meticulouscreations.homesafe.domain.model.DetectionZone.slug]). */
    suspend fun createCategory(modelName: String, category: String): Result<Unit>
    suspend fun label(modelName: String, fileName: String, category: String): Result<Unit>
    suspend fun discard(modelName: String, fileNames: List<String>): Result<Unit>
    suspend fun train(modelName: String): Result<Unit>
    /** Where a queued crop's image lives, for the current server; null when disconnected. */
    fun queueImageUrl(modelName: String, fileName: String): String?
}
