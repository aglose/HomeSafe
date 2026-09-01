package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.data.ConnectionHistoryDao
import com.meticulouscreations.homesafe.data.ConnectionHistoryEntity
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateSession
import com.meticulouscreations.homesafe.network.FrigateSessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

sealed interface ConnectUiState {
    data object Idle : ConnectUiState
    data object Connecting : ConnectUiState
    data class Error(val message: String) : ConnectUiState
}

class SecureConnectionViewModel(
    private val connectionHistoryDao: ConnectionHistoryDao,
    private val apiClient: FrigateApiClient,
    private val sessionRepository: FrigateSessionRepository,
) : ViewModel() {

    val mostRecentConnection: StateFlow<ConnectionHistoryEntity?> =
        connectionHistoryDao.mostRecentAsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    @OptIn(ExperimentalTime::class)
    fun connect(serverUrl: String, username: String, password: String, onConnected: () -> Unit) {
        _uiState.value = ConnectUiState.Connecting
        viewModelScope.launch {
            apiClient.login(serverUrl, username, password)
                .mapCatching { apiClient.getCameras(serverUrl).getOrThrow() }
                .onSuccess { cameras ->
                    sessionRepository.set(FrigateSession(serverUrl = serverUrl, cameras = cameras))
                    connectionHistoryDao.insert(
                        ConnectionHistoryEntity(
                            serverUrl = serverUrl,
                            connectedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                    _uiState.value = ConnectUiState.Idle
                    onConnected()
                }
                .onFailure { error ->
                    _uiState.value = ConnectUiState.Error(error.message ?: "Couldn't connect to server")
                }
        }
    }
}
