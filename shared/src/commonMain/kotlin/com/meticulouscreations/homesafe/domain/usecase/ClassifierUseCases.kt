package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import dev.zacsweers.metro.Inject

/** The server's custom classifiers, e.g. the known-cars model. */
@Inject
class GetClassifierModelsUseCase(private val repository: ClassifierRepository) {
    suspend operator fun invoke(): Result<List<ClassifierModel>> = repository.getModels()
}

/** One classifier's categories, training state and the queue of crops waiting for a label. */
@Inject
class GetClassifierDatasetUseCase(private val repository: ClassifierRepository) {
    suspend operator fun invoke(modelName: String): Result<ClassifierDataset> = repository.getDataset(modelName)
}

/** See [ClassifierRepository.createCategory]; [category] must already be a Frigate-safe key. */
@Inject
class CreateClassifierCategoryUseCase(private val repository: ClassifierRepository) {
    suspend operator fun invoke(modelName: String, category: String): Result<Unit> = repository.createCategory(modelName, category)
}

/** Files one queued crop under [category]; Frigate moves the file server-side. */
@Inject
class LabelClassifierCropUseCase(private val repository: ClassifierRepository) {
    suspend operator fun invoke(modelName: String, fileName: String, category: String): Result<Unit> =
        repository.label(modelName, fileName, category)
}

/** Throws queued crops away. */
@Inject
class DiscardClassifierCropsUseCase(private val repository: ClassifierRepository) {
    suspend operator fun invoke(modelName: String, fileNames: List<String>): Result<Unit> = repository.discard(modelName, fileNames)
}

/** Kicks off a retrain on the server; completion is observed by re-reading the dataset. */
@Inject
class TrainClassifierUseCase(private val repository: ClassifierRepository) {
    suspend operator fun invoke(modelName: String): Result<Unit> = repository.train(modelName)
}

/** Where a queued crop's image can be fetched for the current server; null when disconnected. */
@Inject
class GetClassifierQueueImageUrlUseCase(private val repository: ClassifierRepository) {
    operator fun invoke(modelName: String, fileName: String): String? = repository.queueImageUrl(modelName, fileName)
}
