package com.meticulouscreations.homesafe.network

import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    suspend fun getModels(serverUrl: String): Result<List<FrigateClassifierModel>> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/config")
        check(response.status.isSuccess()) { "Couldn't load config: ${response.status}" }
        response.body<FrigateClassificationRoot>().classification?.custom.orEmpty().map { (name, model) ->
            FrigateClassifierModel(
                name = name,
                enabled = model.enabled,
                objects = model.objectConfig?.objects.orEmpty(),
            )
        }
    }

    /** Category -> file names, plus training bookkeeping. */
    suspend fun getDataset(serverUrl: String, modelName: String): Result<FrigateClassifierDataset> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/classification/$modelName/dataset")
        check(response.status.isSuccess()) { "Couldn't load dataset: ${response.status}" }
        response.body()
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
}

/** A queued crop's image. Authenticated like everything else; Coil rides the app's Ktor client. */
fun frigateClassifierQueueImageUrl(serverUrl: String, modelName: String, fileName: String): String =
    "${serverUrl.trimEnd('/')}/clips/$modelName/train/$fileName"

data class FrigateClassifierModel(val name: String, val enabled: Boolean, val objects: List<String>)

@Serializable
internal data class FrigateClassificationRoot(val classification: FrigateClassificationBlock? = null)

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

@Serializable
data class FrigateClassifierDataset(
    val categories: Map<String, List<String>> = emptyMap(),
    @SerialName("training_metadata") val trainingMetadata: FrigateTrainingMetadata? = null,
)

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

@Serializable
internal data class DeleteQueuedRequest(val ids: List<String>)
