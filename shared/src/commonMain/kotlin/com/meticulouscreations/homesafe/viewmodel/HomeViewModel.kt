package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    observeCamerasUseCase: ObserveCamerasUseCase,
    connectionRepository: ConnectionRepository,
) : ViewModel() {

    val cameras: StateFlow<List<Camera>> =
        observeCamerasUseCase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val serverUrl: StateFlow<String?> = connectionRepository.currentServerUrl
}
