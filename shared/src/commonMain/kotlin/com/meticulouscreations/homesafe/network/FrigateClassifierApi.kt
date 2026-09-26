package com.meticulouscreations.homesafe.network

import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Frigate's custom-classification endpoints (0.16+): the models declared under
 * `classification.custom`, each model's labelled dataset and its queue of unlabelled crops, and
 * the calls that move a crop into a category, throw it away, or retrain. Kept apart from
 * [FrigateApiClient] because it's a separate feature with its own vocabulary. All routes need
 * the `admin` role, which the app's login has.
 */
@Inject
class FrigateClassifierApi(private val httpClient: HttpClient) {

    /** The custom models in `/api/config`, object models only (those with `object_config`). */
    suspend fun getModels(serverUrl: String): Result<List<FrigateClassifierModel>> = getConfig(serverUrl).map { it.models }

    /** [getModels], plus each camera's detect resolution, which a queued crop was cut from. */
    suspend fun getConfig(serverUrl: String): Result<FrigateClassifierConfig> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/config")
        check(response.status.isSuccess()) { "Couldn't load config: ${response.status}" }
        val root = response.body<FrigateClassificationRoot>()
        FrigateClassifierConfig(
            models = root.classification?.custom.orEmpty().map { (name, model) ->
                FrigateClassifierModel(
                    name = name,
                    enabled = model.enabled,
                    objects = model.objectConfig?.objects.orEmpty(),
                )
            },
            detectSizes = root.cameras.mapValues { (_, camera) ->
                FrigateDetectSize(
                    width = camera.detect?.width ?: FrigateApiClient.DEFAULT_DETECT_WIDTH,
                    height = camera.detect?.height ?: FrigateApiClient.DEFAULT_DETECT_HEIGHT,
                )
            },
        )
    }

    /**
     * The tracked objects [eventIds] name, for where each queued crop's object stood. Asked for in
     * batches so a full queue's worth of ids stays a sensible URL; ids Frigate no longer has are
     * simply missing from the answer.
     */
    suspend fun getEvents(serverUrl: String, eventIds: Collection<String>): Result<List<FrigateEvent>> = runCatching {
        eventIds.distinct().chunked(EVENT_IDS_PER_REQUEST).flatMap { batch ->
            val response = httpClient.get("${serverUrl.trimEnd('/')}/api/event_ids") { parameter("ids", batch.joinToString(",")) }
            check(response.status.isSuccess()) { "Couldn't load events: ${response.status}" }
            response.body<List<FrigateEvent>>()
        }
    }

    /**
     * The boxes Frigate kept for [eventIds] at each lifecycle moment (first seen, parked, moved,
     * gone), oldest first, in batches like [getEvents]. A queued crop can only be framed exactly
     * when one of these is the box it was cut from; see `CropSubject.boxInCrop`.
     */
    suspend fun getTimeline(serverUrl: String, eventIds: Collection<String>): Result<List<FrigateTimelineEntry>> = runCatching {
        eventIds.distinct().chunked(EVENT_IDS_PER_REQUEST).flatMap { batch ->
            val response = httpClient.get("${serverUrl.trimEnd('/')}/api/timeline") {
                parameter("source_id", batch.joinToString(","))
                parameter("limit", TIMELINE_LIMIT)
            }
            check(response.status.isSuccess()) { "Couldn't load timeline: ${response.status}" }
            response.body<List<FrigateTimelineEntry>>()
        }
    }

    /** What [cameraName] is tracking right now: its events that haven't ended. */
    suspend fun getInProgressEvents(serverUrl: String, cameraName: String): Result<List<FrigateEvent>> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/events") {
            parameter("cameras", cameraName)
            parameter("in_progress", 1)
            parameter("limit", IN_PROGRESS_LIMIT)
        }
        check(response.status.isSuccess()) { "Couldn't load tracked objects: ${response.status}" }
        response.body()
    }

    /**
     * Category -> file names, plus training bookkeeping.
     *
     * Frigate answers in one of two shapes: the categories and the training metadata wrapped in an
     * object, or the bare `category -> file names` map on the builds that keep no metadata. Read
     * only as the wrapped shape, a bare answer parses into no categories at all — which is the
     * labelling screen with nothing to file a crop under — so take either. See [classifierDataset].
     */
    suspend fun getDataset(serverUrl: String, modelName: String): Result<FrigateClassifierDataset> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/classification/$modelName/dataset")
        check(response.status.isSuccess()) { "Couldn't load dataset: ${response.status}" }
        classifierDataset(response.body())
    }

    /** File names of crops waiting to be labelled (the model's `train/` folder). */
    suspend fun getQueue(serverUrl: String, modelName: String): Result<List<String>> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/classification/$modelName/train")
        check(response.status.isSuccess()) { "Couldn't load queue: ${response.status}" }
        response.body()
    }

    suspend fun createCategory(serverUrl: String, modelName: String, category: String): Result<Unit> =
        postExpectingSuccess("${serverUrl.trimEnd('/')}/api/classification/$modelName/dataset/$category/create", body = null)

    /** Moves one queued crop into [category]; Frigate renames it on the way. */
    suspend fun categorize(serverUrl: String, modelName: String, fileName: String, category: String): Result<Unit> =
        postExpectingSuccess(
            "${serverUrl.trimEnd('/')}/api/classification/$modelName/dataset/categorize",
            body = CategorizeRequest(category = category, trainingFile = fileName),
        )

    /** Deletes queued crops that aren't worth labelling (blurry, half a bumper). */
    suspend fun deleteQueued(serverUrl: String, modelName: String, fileNames: List<String>): Result<Unit> =
        postExpectingSuccess("${serverUrl.trimEnd('/')}/api/classification/$modelName/train/delete", body = DeleteQueuedRequest(ids = fileNames))

    /**
     * Names a tracked object — [subLabel] null clears the name. Frigate updates the object it is
     * still tracking as well as its event row, so the name shows everywhere the event does. [score]
     * is what the name claims to be sure of; a person naming a car is sure.
     */
    suspend fun setSubLabel(serverUrl: String, eventId: String, subLabel: String?, score: Double?): Result<Unit> =
        postExpectingSuccess(
            "${serverUrl.trimEnd('/')}/api/events/$eventId/sub_label",
            body = SubLabelRequest(subLabel = subLabel.orEmpty(), subLabelScore = score),
        )

    /** [cameraName]'s latest detect frame at full detect resolution, as Frigate encodes it: what a car gets boxed on. */
    suspend fun getLatestFrame(serverUrl: String, cameraName: String): Result<ByteArray> = runCatching {
        val response = httpClient.get(frigateSnapshotUrl(serverUrl, cameraName))
        check(response.status.isSuccess()) { "Couldn't load the camera's frame: ${response.status}" }
        response.body<ByteArray>()
    }

    /**
     * A frame of [cameraName]'s recording at [epochSeconds], scaled to [height] pixels tall (null
     * keeps the recorded size): the picture a finished detection's car is cut from once Frigate's
     * own crops of it are gone.
     */
    suspend fun getRecordingFrame(serverUrl: String, cameraName: String, epochSeconds: Double, height: Int?): Result<ByteArray> = runCatching {
        val response = httpClient.get(frigateRecordingSnapshotUrl(serverUrl, cameraName, epochSeconds, height))
        check(response.status.isSuccess()) { "Couldn't load the recording: ${response.status}" }
        response.body<ByteArray>()
    }

    /** Starts training in the background on the server; Frigate hot-loads the result when done (about half a minute on the real box). */
    suspend fun train(serverUrl: String, modelName: String): Result<Unit> =
        postExpectingSuccess("${serverUrl.trimEnd('/')}/api/classification/$modelName/train", body = null)

    private suspend inline fun <reified T : Any> postExpectingSuccess(url: String, body: T?): Result<Unit> = runCatching {
        val response = httpClient.post(url) {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val result = runCatching { response.body<ConfigSetResponse>() }.getOrNull()
        if (!response.status.isSuccess() || result?.success == false) {
            throw FrigateResponseException(result?.message ?: "Request failed: ${response.status}")
        }
    }

    private companion object {
        const val EVENT_IDS_PER_REQUEST = 50

        /** A car that parks and pulls away a few times adds a handful of entries; this is far past a batch's worth. */
        const val TIMELINE_LIMIT = 5_000

        /** More than a camera ever tracks at once; Frigate would otherwise cap the answer at its default. */
        const val IN_PROGRESS_LIMIT = 50
    }
}

/** A queued crop's image. Authenticated like everything else; Coil rides the app's Ktor client. */
fun frigateClassifierQueueImageUrl(serverUrl: String, modelName: String, fileName: String): String =
    "${serverUrl.trimEnd('/')}/clips/$modelName/train/$fileName"

data class FrigateClassifierModel(val name: String, val enabled: Boolean, val objects: List<String>)

data class FrigateDetectSize(val width: Int, val height: Int)

/** One lifecycle moment of a tracked object, from `/api/timeline`; [data] carries the box at that moment. */
@Serializable
data class FrigateTimelineEntry(
    @SerialName("source_id") val sourceId: String,
    val timestamp: Double,
    val data: FrigateTimelineData? = null,
)

@Serializable
data class FrigateTimelineData(
    /** `[x, y, w, h]`, each a fraction of the detect frame; absent on entries that aren't about an object's position. */
    val box: List<Double>? = null,
)

data class FrigateClassifierConfig(
    val models: List<FrigateClassifierModel>,
    /** Camera name -> detect resolution. */
    val detectSizes: Map<String, FrigateDetectSize> = emptyMap(),
)

@Serializable
internal data class FrigateClassificationRoot(
    val classification: FrigateClassificationBlock? = null,
    val cameras: Map<String, FrigateCameraConfig> = emptyMap(),
)

@Serializable
internal data class FrigateClassificationBlock(val custom: Map<String, FrigateCustomClassifierConfig> = emptyMap())

@Serializable
internal data class FrigateCustomClassifierConfig(
    val enabled: Boolean = true,
    @SerialName("object_config") val objectConfig: FrigateClassifierObjectConfig? = null,
)

@Serializable
internal data class FrigateClassifierObjectConfig(
    @Serializable(with = FrigateMaskListSerializer::class) val objects: List<String> = emptyList(),
)

data class FrigateClassifierDataset(
    val categories: Map<String, List<String>> = emptyMap(),
    val trainingMetadata: FrigateTrainingMetadata? = null,
)

private val datasetJson = Json { ignoreUnknownKeys = true }
private val categoryFilesSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

/**
 * Reads either shape of the `/dataset` answer (see [FrigateClassifierApi.getDataset]): the wrapped
 * one is the object whose `categories` is itself an object, and anything else is read as the bare
 * `category -> file names` map, where every value is that category's list of files.
 */
internal fun classifierDataset(body: JsonElement): FrigateClassifierDataset {
    val root = body as? JsonObject ?: return FrigateClassifierDataset()
    val categories = root["categories"] as? JsonObject
    if (categories == null && !root.containsKey("training_metadata")) {
        return FrigateClassifierDataset(categories = datasetJson.decodeFromJsonElement(categoryFilesSerializer, root))
    }
    return FrigateClassifierDataset(
        categories = categories?.let { datasetJson.decodeFromJsonElement(categoryFilesSerializer, it) } ?: emptyMap(),
        trainingMetadata = (root["training_metadata"] as? JsonObject)
            ?.let { datasetJson.decodeFromJsonElement(FrigateTrainingMetadata.serializer(), it) },
    )
}

@Serializable
data class FrigateTrainingMetadata(
    @SerialName("has_trained") val hasTrained: Boolean = false,
    @SerialName("last_training_image_count") val lastTrainingImageCount: Int = 0,
    @SerialName("current_image_count") val currentImageCount: Int = 0,
    @SerialName("new_images_count") val newImagesCount: Int = 0,
    @SerialName("dataset_changed") val datasetChanged: Boolean = false,
)

@Serializable
internal data class CategorizeRequest(
    val category: String,
    @SerialName("training_file") val trainingFile: String,
)

/** Frigate reads an empty [subLabel] as "clear it". */
@Serializable
internal data class SubLabelRequest(val subLabel: String, val subLabelScore: Double? = null)

@Serializable
internal data class DeleteQueuedRequest(val ids: List<String>)
