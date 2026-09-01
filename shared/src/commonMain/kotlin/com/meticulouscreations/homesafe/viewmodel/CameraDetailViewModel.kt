package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.network.frigateLiveStreamUrl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface CameraDetailUiState {
    data object Loading : CameraDetailUiState
    data class Found(val camera: Camera, val streamUrl: String?) : CameraDetailUiState
    data object NotFound : CameraDetailUiState
}

class CameraDetailViewModel(
    cameraName: String,
    observeCamerasUseCase: ObserveCamerasUseCase,
    connectionRepository: ConnectionRepository,
) : ViewModel() {

    val uiState: StateFlow<CameraDetailUiState> = combine(
        observeCamerasUseCase(),
        connectionRepository.currentServerUrl,
    ) { cameras, serverUrl ->
        val camera = cameras.firstOrNull { it.name == cameraName }
        when {
            camera == null -> CameraDetailUiState.NotFound
            camera.enabled && serverUrl != null ->
                CameraDetailUiState.Found(camera, frigateLiveStreamUrl(serverUrl, cameraName))
            else -> CameraDetailUiState.Found(camera, streamUrl = null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraDetailUiState.Loading)
}
