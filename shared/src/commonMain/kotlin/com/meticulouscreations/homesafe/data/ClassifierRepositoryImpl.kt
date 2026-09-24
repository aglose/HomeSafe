package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.CropSubject
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.TrackedObject
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateClassifierApi
import com.meticulouscreations.homesafe.network.FrigateDetectSize
import com.meticulouscreations.homesafe.network.FrigateEvent
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.network.FrigateTimelineEntry
import com.meticulouscreations.homesafe.network.PushRelayApi
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
    private val relayApi: PushRelayApi,
    private val connectionRepository: ConnectionRepository,
) : ClassifierRepository {

    override suspend fun getModels(): Result<List<ClassifierModel>> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        return api.getModels(serverUrl).map { models -> models.map { ClassifierModel(it.name, it.objects, it.enabled) } }
    }

    override suspend fun getDataset(modelName: String): Result<ClassifierDataset> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        val config = api.getConfig(serverUrl).getOrElse { return Result.failure(it) }
        val model = config.models.firstOrNull { it.name == modelName }
            ?: return Result.failure(FrigateResponseException("No classifier named $modelName on this server"))
        val dataset = api.getDataset(serverUrl, modelName).getOrElse { return Result.failure(it) }
        val crops = api.getQueue(serverUrl, modelName).getOrElse { return Result.failure(it) }.map { UnlabeledCrop.fromFileName(it) }
        // Only frames the object on the crop; a queue without them still labels fine.
        val eventIds = crops.mapNotNull { it.eventId }.distinct()
        val events = api.getEvents(serverUrl, eventIds).getOrElse { emptyList() }.associateBy { it.id }
        val timeline = api.getTimeline(serverUrl, eventIds).getOrElse { emptyList() }.groupBy { it.sourceId }
        val meta = dataset.trainingMetadata
        return Result.success(
            ClassifierDataset(
                model = ClassifierModel(model.name, model.objects, model.enabled),
                categoryCounts = dataset.categories.mapValues { it.value.size },
                queue = crops.map { crop -> crop.copy(subject = cropSubject(crop, events, timeline, config.detectSizes)) }
                    .sortedByDescending { it.capturedEpochSeconds ?: 0.0 },
                hasTrained = meta?.hasTrained ?: false,
                newImagesSinceTraining = meta?.newImagesCount ?: 0,
            ),
        )
    }

    override suspend fun createCategory(modelName: String, category: String): Result<Unit> =
        serverUrlOrFailure().fold({ api.createCategory(it, modelName, category) }, { Result.failure(it) })

    override suspend fun getTrackedObjects(cameraName: String): Result<List<TrackedObject>> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        return api.getInProgressEvents(serverUrl, cameraName)
            .map { events ->
                events.map { TrackedObject(eventId = it.id, label = it.label, subLabel = it.subLabel, box = it.data?.box?.seenBox(epochSeconds = null)) }
            }
    }

    override suspend fun label(modelName: String, fileName: String, category: String): Result<Unit> =
        serverUrlOrFailure().fold({ api.categorize(it, modelName, fileName, category) }, { Result.failure(it) })

    override suspend fun discard(modelName: String, fileNames: List<String>): Result<Unit> =
        serverUrlOrFailure().fold({ api.deleteQueued(it, modelName, fileNames) }, { Result.failure(it) })

    override suspend fun train(modelName: String): Result<Unit> =
        serverUrlOrFailure().fold({ api.train(it, modelName) }, { Result.failure(it) })

    override suspend fun getLatestFrame(cameraName: String): Result<ByteArray> =
        serverUrlOrFailure().fold({ api.getLatestFrame(it, cameraName) }, { Result.failure(it) })

    override suspend fun addExample(modelName: String, category: String, frame: ByteArray, box: SeenBox): Result<Unit> =
        serverUrlOrFailure().fold({ relayApi.addClassifierExample(it, modelName, category, frame, box) }, { Result.failure(it) })

    override suspend fun nameTrackedObject(eventId: String, subLabel: String?): Result<Unit> =
        serverUrlOrFailure().fold({ api.setSubLabel(it, eventId, subLabel, score = subLabel?.let { MANUAL_NAME_SCORE }) }, { Result.failure(it) })

    override fun queueImageUrl(modelName: String, fileName: String): String? =
        connectionRepository.currentServerUrl.value?.let { frigateClassifierQueueImageUrl(it, modelName, fileName) }

    private fun cropSubject(
        crop: UnlabeledCrop,
        events: Map<String, FrigateEvent>,
        timeline: Map<String, List<FrigateTimelineEntry>>,
        detectSizes: Map<String, FrigateDetectSize>,
    ): CropSubject? {
        val eventId = crop.eventId ?: return null
        val event = events[eventId] ?: return null
        val data = event.data ?: return null
        val frame = detectSizes[event.camera] ?: return null
        val boxes = listOfNotNull(data.box?.seenBox(epochSeconds = null)) +
            timeline[eventId].orEmpty().mapNotNull { entry -> entry.data?.box?.seenBox(entry.timestamp) }
        if (boxes.isEmpty()) return null
        val bottomCentre = data.bottomCentreAt(crop.capturedEpochSeconds ?: event.startTime) ?: return null
        return CropSubject(frame.width, frame.height, boxes, crop.capturedEpochSeconds, bottomCentre)
    }

    private fun List<Double>.seenBox(epochSeconds: Double?): SeenBox? =
        takeIf { it.size >= 4 }?.let { SeenBox(x = it[0], y = it[1], width = it[2], height = it[3], epochSeconds = epochSeconds) }

    private fun serverUrlOrFailure(): Result<String> =
        connectionRepository.currentServerUrl.value
            ?.let { Result.success(it) }
            ?: Result.failure(FrigateResponseException("Not connected to a server"))

    private companion object {
        /**
         * A person naming the car is as sure as a name gets. It also outranks every guess the
         * classifier made about the same parked car, which is what lets the in-view strip take the
         * person's word for it: a stay is named by its best-scored sighting.
         */
        const val MANUAL_NAME_SCORE = 1.0
    }
}
