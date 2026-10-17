package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.repository.NestCameraRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** See [NestCameraRepository.observeNestCameras]: the Nest cameras, null until the server has answered. */
@Inject
class ObserveNestCamerasUseCase(private val nestCameraRepository: NestCameraRepository) {
    operator fun invoke(): Flow<List<Camera>?> = nestCameraRepository.observeNestCameras()
}

/** See [NestCameraRepository.refresh]: the home page's pull to refresh asking for the Nest cameras again. */
@Inject
class RefreshNestCamerasUseCase(private val nestCameraRepository: NestCameraRepository) {
    suspend operator fun invoke() = nestCameraRepository.refresh()
}
