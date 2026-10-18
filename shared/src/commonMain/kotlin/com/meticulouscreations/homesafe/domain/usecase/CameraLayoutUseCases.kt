package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.CameraCrop
import com.meticulouscreations.homesafe.domain.model.CameraLayout
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import com.meticulouscreations.homesafe.domain.repository.CameraLayoutRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** See [CameraLayoutRepository.observeLayouts]: each camera's place and orientation, by name. */
@Inject
class ObserveCameraLayoutsUseCase(private val repository: CameraLayoutRepository) {
    operator fun invoke(): Flow<Map<String, CameraLayout>> = repository.observeLayouts()
}

/** See [CameraLayoutRepository.saveOrder]: the Home page's Arrange mode putting one list in order. */
@Inject
class SaveCameraOrderUseCase(private val repository: CameraLayoutRepository) {
    suspend operator fun invoke(cameraNames: List<String>) = repository.saveOrder(cameraNames)
}

/** See [CameraLayoutRepository.setOrientation]: which way up a camera's picture is shown. */
@Inject
class SetCameraOrientationUseCase(private val repository: CameraLayoutRepository) {
    suspend operator fun invoke(cameraName: String, orientation: CameraOrientation) = repository.setOrientation(cameraName, orientation)
}

/** See [CameraLayoutRepository.setFraming]: a camera's picture turned and cropped in the framing editor. */
@Inject
class SetCameraFramingUseCase(private val repository: CameraLayoutRepository) {
    suspend operator fun invoke(cameraName: String, orientation: CameraOrientation, crop: CameraCrop) =
        repository.setFraming(cameraName, orientation, crop)
}
