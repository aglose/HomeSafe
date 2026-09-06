package com.meticulouscreations.homesafe.network

import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Frigate's face-recognition library (0.16+): the folder of registered images per person under
 * `clips/faces/<name>/`, and the `train/` folder where Frigate drops every face it looked at
 * along with its best guess. Unlike the custom classifiers there is no training step — filing
 * an attempt under a person registers that face on the spot. All routes need the `admin` role.
 */
@Inject
class FrigateFaceApi(private val httpClient: HttpClient) {

    /** Folder name -> image files. Includes [TRAIN_FOLDER] for the attempts waiting to be filed. `{}` until the first face is seen. */
    suspend fun getFaces(serverUrl: String): Result<Map<String, List<String>>> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/faces")
        check(response.status.isSuccess()) { "Couldn't load faces: ${response.status}" }
        response.body()
    }

    /** Makes an (empty) person folder so it can be picked when filing attempts. */
    suspend fun createPerson(serverUrl: String, name: String): Result<Unit> =
        postExpectingSuccess("${serverUrl.trimEnd('/')}/api/faces/$name/create", body = null)

    /** Files one attempt from `train/` under [name]: Frigate moves the file and adds its embedding to that person. */
    suspend fun classifyAttempt(serverUrl: String, fileName: String, name: String): Result<Unit> =
        postExpectingSuccess("${serverUrl.trimEnd('/')}/api/faces/train/$name/classify", body = ClassifyFaceRequest(trainingFile = fileName))

    /** Deletes images from a person's folder — or attempts, when [name] is [TRAIN_FOLDER]. */
    suspend fun deleteImages(serverUrl: String, name: String, fileNames: List<String>): Result<Unit> =
        postExpectingSuccess("${serverUrl.trimEnd('/')}/api/faces/$name/delete", body = DeleteFaceImagesRequest(ids = fileNames))

    suspend fun renamePerson(serverUrl: String, oldName: String, newName: String): Result<Unit> = runCatching {
        val response = httpClient.put("${serverUrl.trimEnd('/')}/api/faces/$oldName/rename") {
            contentType(ContentType.Application.Json)
            setBody(RenameFaceRequest(newName = newName))
        }
        val result = runCatching { response.body<ConfigSetResponse>() }.getOrNull()
        if (!response.status.isSuccess() || result?.success == false) {
            throw FrigateResponseException(result?.message ?: "Request failed: ${response.status}")
        }
    }

    private suspend inline fun <reified T : Any> postExpectingSuccess(url: String, body: T?): Result<Unit> = runCatching {
        val response = httpClient.post(url) {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val result = runCatching { response.body<ConfigSetResponse>() }.getOrNull()
        // Frigate answers `create` with success=false and "Successfully created face folder." on the
        // happy path (a long-standing quirk), so a 2xx is trusted over the body's flag there.
        if (!response.status.isSuccess()) {
            throw FrigateResponseException(result?.message ?: "Request failed: ${response.status}")
        }
    }

    companion object {
        /** Frigate's folder of unfiled attempts. */
        const val TRAIN_FOLDER = "train"
    }
}

/** A face image, registered or attempt. Authenticated like everything else; Coil rides the app's Ktor client. */
fun frigateFaceImageUrl(serverUrl: String, folder: String, fileName: String): String =
    "${serverUrl.trimEnd('/')}/clips/faces/$folder/$fileName"

@Serializable
internal data class ClassifyFaceRequest(@SerialName("training_file") val trainingFile: String)

@Serializable
internal data class DeleteFaceImagesRequest(val ids: List<String>)

@Serializable
internal data class RenameFaceRequest(@SerialName("new_name") val newName: String)
