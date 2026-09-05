package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.MaskPolygon
import com.meticulouscreations.homesafe.domain.repository.DetectionConfigRepository
import dev.zacsweers.metro.Inject

@Inject
class SaveDetectionMasksUseCase(private val repository: DetectionConfigRepository) {
    /** See [DetectionConfigRepository.saveMasks]. */
    suspend operator fun invoke(cameraName: String, layer: MaskLayer, masks: List<MaskPolygon>): Result<Unit> =
        repository.saveMasks(cameraName, layer, masks)
}
