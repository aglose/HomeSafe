package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.FaceLibrary
import com.meticulouscreations.homesafe.domain.repository.FaceRepository
import dev.zacsweers.metro.Inject

/** Who Frigate recognises, and the faces waiting to be named. */
@Inject
class GetFaceLibraryUseCase(private val repository: FaceRepository) {
    suspend operator fun invoke(): Result<FaceLibrary> = repository.getLibrary()
}

/** See [FaceRepository.createPerson]; [name] must already be a Frigate-safe key. */
@Inject
class CreatePersonUseCase(private val repository: FaceRepository) {
    suspend operator fun invoke(name: String): Result<Unit> = repository.createPerson(name)
}

/** Names one face Frigate saw; it's registered on the spot. */
@Inject
class LabelFaceAttemptUseCase(private val repository: FaceRepository) {
    suspend operator fun invoke(fileName: String, name: String): Result<Unit> = repository.labelAttempt(fileName, name)
}

/** Throws attempts away — a stranger, a blur, the back of a head. */
@Inject
class DiscardFaceAttemptsUseCase(private val repository: FaceRepository) {
    suspend operator fun invoke(fileNames: List<String>): Result<Unit> = repository.discardAttempts(fileNames)
}

/** Removes registered images from a person. */
@Inject
class DeleteFaceImagesUseCase(private val repository: FaceRepository) {
    suspend operator fun invoke(name: String, fileNames: List<String>): Result<Unit> = repository.deleteImages(name, fileNames)
}

/** Where a face image can be fetched for the current server; null when disconnected. */
@Inject
class GetFaceImageUrlUseCase(private val repository: FaceRepository) {
    operator fun invoke(folder: String, fileName: String): String? = repository.imageUrl(folder, fileName)
}
