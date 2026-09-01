package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.DetectionSettings
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val defaultSettings = DetectionSettings(
    autoPurgeOldMedia = true,
    globalMotionDetection = true,
    coralEdgeInference = true,
    faceRecognition = false,
    pushNotificationsEnabled = false,
)

class SettingsViewModel(
    observeSettingsUseCase: ObserveSettingsUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    connectionRepository: ConnectionRepository,
) : ViewModel() {

    val settings: StateFlow<DetectionSettings> =
        observeSettingsUseCase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), defaultSettings)

    val serverUrl: StateFlow<String?> = connectionRepository.currentServerUrl

    fun updateSettings(settings: DetectionSettings) {
        viewModelScope.launch { updateSettingsUseCase(settings) }
    }
}
