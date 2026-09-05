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

    /**
     * Null until the first read of the local camera cache lands (a few milliseconds), so the
     * screen can tell "not loaded yet" from "this server has no cameras" and never flashes the
     * empty-state message on the way in.
     */
    val cameras: StateFlow<List<Camera>?> =
        observeCamerasUseCase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val serverUrl: StateFlow<String?> = connectionRepository.currentServerUrl
}
