package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.repository.CameraRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class ObserveCamerasUseCase(private val cameraRepository: CameraRepository) {
    operator fun invoke(): Flow<List<Camera>> = cameraRepository.observeCameras()
}
