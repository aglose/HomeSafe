package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateClassifierApi
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.network.frigateClassifierQueueImageUrl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ClassifierRepositoryImpl(
    private val api: FrigateClassifierApi,
    private val connectionRepository: ConnectionRepository,
) : ClassifierRepository {

    override suspend fun getModels(): Result<List<ClassifierModel>> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        return api.getModels(serverUrl).map { models -> models.map { ClassifierModel(it.name, it.objects, it.enabled) } }
    }

    override suspend fun getDataset(modelName: String): Result<ClassifierDataset> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        val model = api.getModels(serverUrl).getOrElse { return Result.failure(it) }.firstOrNull { it.name == modelName }
            ?: return Result.failure(FrigateResponseException("No classifier named $modelName on this server"))
        val dataset = api.getDataset(serverUrl, modelName).getOrElse { return Result.failure(it) }
        val queue = api.getQueue(serverUrl, modelName).getOrElse { return Result.failure(it) }
        val meta = dataset.trainingMetadata
        return Result.success(
            ClassifierDataset(
                model = ClassifierModel(model.name, model.objects, model.enabled),
                categoryCounts = dataset.categories.mapValues { it.value.size },
                queue = queue.map { UnlabeledCrop.fromFileName(it) }
                    .sortedByDescending { it.capturedEpochSeconds ?: 0.0 },
                hasTrained = meta?.hasTrained ?: false,
                newImagesSinceTraining = meta?.newImagesCount ?: 0,
            ),
        )
    }

    override suspend fun createCategory(modelName: String, category: String): Result<Unit> =
        serverUrlOrFailure().fold({ api.createCategory(it, modelName, category) }, { Result.failure(it) })

    override suspend fun label(modelName: String, fileName: String, category: String): Result<Unit> =
        serverUrlOrFailure().fold({ api.categorize(it, modelName, fileName, category) }, { Result.failure(it) })

    override suspend fun discard(modelName: String, fileNames: List<String>): Result<Unit> =
        serverUrlOrFailure().fold({ api.deleteQueued(it, modelName, fileNames) }, { Result.failure(it) })

    override suspend fun train(modelName: String): Result<Unit> =
        serverUrlOrFailure().fold({ api.train(it, modelName) }, { Result.failure(it) })

    override fun queueImageUrl(modelName: String, fileName: String): String? =
        connectionRepository.currentServerUrl.value?.let { frigateClassifierQueueImageUrl(it, modelName, fileName) }

    private fun serverUrlOrFailure(): Result<String> =
        connectionRepository.currentServerUrl.value
            ?.let { Result.success(it) }
            ?: Result.failure(FrigateResponseException("Not connected to a server"))
}
