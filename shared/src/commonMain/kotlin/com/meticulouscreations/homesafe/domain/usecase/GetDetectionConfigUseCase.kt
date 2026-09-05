package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.CameraDetectionConfig
import com.meticulouscreations.homesafe.domain.repository.DetectionConfigRepository
import dev.zacsweers.metro.Inject

@Inject
class GetDetectionConfigUseCase(private val repository: DetectionConfigRepository) {
    suspend operator fun invoke(cameraName: String): Result<CameraDetectionConfig> = repository.getDetectionConfig(cameraName)
}
