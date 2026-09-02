package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.network.frigateLiveStreamUrl
import com.meticulouscreations.homesafe.network.frigateSnapshotUrl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface CameraDetailUiState {
    data object Loading : CameraDetailUiState
    data class Found(val camera: Camera, val streamUrl: String?, val posterUrl: String?) : CameraDetailUiState
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
        val posterUrl = serverUrl?.let { frigateSnapshotUrl(it, cameraName) }
        when {
            camera == null -> CameraDetailUiState.NotFound
            camera.enabled && serverUrl != null ->
                CameraDetailUiState.Found(
                    camera = camera,
                    // Full quality, always — the grid's (possibly lower-quality) stream is only
                    // used in the multi-camera list, never here.
                    streamUrl = frigateLiveStreamUrl(serverUrl, camera.liveStreamName),
                    posterUrl = posterUrl,
                )
            else -> CameraDetailUiState.Found(camera, streamUrl = null, posterUrl = posterUrl)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraDetailUiState.Loading)
}
