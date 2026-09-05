package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.ServerStatusRepository
import dev.zacsweers.metro.Inject

@Inject
class SetCameraDetectionUseCase(private val serverStatusRepository: ServerStatusRepository) {
    suspend operator fun invoke(cameraName: String, enabled: Boolean): Result<Unit> =
        serverStatusRepository.setCameraDetection(cameraName, enabled)
}
