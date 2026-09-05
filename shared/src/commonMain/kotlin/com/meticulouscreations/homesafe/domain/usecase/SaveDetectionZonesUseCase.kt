package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.repository.DetectionConfigRepository
import dev.zacsweers.metro.Inject

@Inject
class SaveDetectionZonesUseCase(private val repository: DetectionConfigRepository) {
    /** See [DetectionConfigRepository.saveZones]. */
    suspend operator fun invoke(cameraName: String, zones: List<DetectionZone>, previous: List<DetectionZone>): Result<Unit> =
        repository.saveZones(cameraName, zones, previous)
}
