package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.FaceLibrary
import com.meticulouscreations.homesafe.domain.model.KnownPerson
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.FaceRepository
import com.meticulouscreations.homesafe.network.FrigateFaceApi
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.network.frigateFaceImageUrl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FaceRepositoryImpl(
    private val api: FrigateFaceApi,
    private val connectionRepository: ConnectionRepository,
) : FaceRepository {

    override suspend fun getLibrary(): Result<FaceLibrary> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        return api.getFaces(serverUrl).map { folders -> folders.toLibrary() }
    }

    override suspend fun createPerson(name: String): Result<Unit> =
        serverUrlOrFailure().fold({ api.createPerson(it, name) }, { Result.failure(it) })

    override suspend fun labelAttempt(fileName: String, name: String): Result<Unit> =
        serverUrlOrFailure().fold({ api.classifyAttempt(it, fileName, name) }, { Result.failure(it) })

    override suspend fun discardAttempts(fileNames: List<String>): Result<Unit> =
        serverUrlOrFailure().fold({ api.deleteImages(it, FrigateFaceApi.TRAIN_FOLDER, fileNames) }, { Result.failure(it) })

    override suspend fun deleteImages(name: String, fileNames: List<String>): Result<Unit> =
        serverUrlOrFailure().fold({ api.deleteImages(it, name, fileNames) }, { Result.failure(it) })

    override fun imageUrl(folder: String, fileName: String): String? =
        connectionRepository.currentServerUrl.value?.let { frigateFaceImageUrl(it, folder, fileName) }

    private fun serverUrlOrFailure(): Result<String> =
        connectionRepository.currentServerUrl.value
            ?.let { Result.success(it) }
            ?: Result.failure(FrigateResponseException("Not connected to a server"))
}

/** Splits Frigate's folder map into people and the attempts queue; people sort by name, attempts newest first. */
internal fun Map<String, List<String>>.toLibrary(): FaceLibrary = FaceLibrary(
    people = filterKeys { it != FrigateFaceApi.TRAIN_FOLDER }
        .map { (name, files) -> KnownPerson(name, files.sortedByDescending { registeredEpoch(it) }) }
        .sortedBy { it.name },
    attempts = this[FrigateFaceApi.TRAIN_FOLDER].orEmpty()
        .map { UnlabeledCrop.fromFileName(it) }
        .sortedByDescending { it.capturedEpochSeconds ?: 0.0 },
)

/** Registered images are named `<name>_<epoch>.webp` by Frigate; anything else sorts last. */
private fun registeredEpoch(fileName: String): Double =
    fileName.substringBeforeLast('.').substringAfterLast('_').toDoubleOrNull() ?: 0.0
