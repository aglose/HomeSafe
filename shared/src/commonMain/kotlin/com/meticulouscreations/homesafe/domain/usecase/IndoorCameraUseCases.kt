package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.repository.IndoorCameraRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** See [IndoorCameraRepository.observeIndoorCameras]: the Nest cameras, null until the server has answered. */
@Inject
class ObserveIndoorCamerasUseCase(private val indoorCameraRepository: IndoorCameraRepository) {
    operator fun invoke(): Flow<List<Camera>?> = indoorCameraRepository.observeIndoorCameras()
}

/** See [IndoorCameraRepository.refresh]: the home page's pull to refresh asking for the Nest cameras again. */
@Inject
class RefreshIndoorCamerasUseCase(private val indoorCameraRepository: IndoorCameraRepository) {
    suspend operator fun invoke() = indoorCameraRepository.refresh()
}
