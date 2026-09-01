package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.data.BiometricCredentialStore
import com.meticulouscreations.homesafe.data.ConnectionHistoryDao
import com.meticulouscreations.homesafe.data.ConnectionHistoryEntity
import com.meticulouscreations.homesafe.data.SavedCredentials
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
    data class Success(val credentials: SavedCredentials) : ConnectUiState
    data class Error(val message: String) : ConnectUiState
}

class SecureConnectionViewModel(
    private val connectionHistoryDao: ConnectionHistoryDao,
    private val apiClient: FrigateApiClient,
    private val sessionRepository: FrigateSessionRepository,
    private val biometricCredentialStore: BiometricCredentialStore,
) : ViewModel() {

    val mostRecentConnection: StateFlow<ConnectionHistoryEntity?> =
        connectionHistoryDao.mostRecentAsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True if biometric hardware is present, enrolled, and usable on this platform/device. */
    val biometricLoginAvailable: Boolean = biometricCredentialStore.isAvailable()

    /** Short user-facing name for the biometric method, e.g. "Face ID" or "fingerprint". */
    val biometricDisplayName: String = biometricCredentialStore.displayName()

    private val _hasSavedBiometricCredentials = MutableStateFlow(biometricCredentialStore.hasSavedCredentials())
    val hasSavedBiometricCredentials: StateFlow<Boolean> = _hasSavedBiometricCredentials.asStateFlow()

    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    @OptIn(ExperimentalTime::class)
    fun connect(serverUrl: String, username: String, password: String) {
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
                    _uiState.value = ConnectUiState.Success(
                        SavedCredentials(serverUrl = serverUrl, username = username, password = password),
                    )
                }
                .onFailure { error ->
                    _uiState.value = ConnectUiState.Error(error.message ?: "Couldn't connect to server")
                }
        }
    }

    /** Runs the biometric prompt, then reuses [connect] with the retrieved credentials on success. */
    fun signInWithBiometrics() {
        _uiState.value = ConnectUiState.Connecting
        viewModelScope.launch {
            biometricCredentialStore.authenticateAndRetrieve()
                .onSuccess { credentials ->
                    connect(credentials.serverUrl, credentials.username, credentials.password)
                }
                .onFailure { error ->
                    _uiState.value = ConnectUiState.Error(error.message ?: "Biometric sign-in failed")
                }
        }
    }

    /**
     * Prompts for biometric auth and, on success, saves [credentials] for future biometric
     * login. Suspends until the save flow (including its own biometric prompt) resolves, so
     * callers can navigate onward right after — regardless of whether the user completed or
     * cancelled the save.
     */
    suspend fun saveBiometricCredentials(credentials: SavedCredentials): Result<Unit> {
        val result = biometricCredentialStore.save(credentials)
        if (result.isSuccess) {
            _hasSavedBiometricCredentials.value = true
        }
        return result
    }

    /** Forgets any saved biometric credentials. Does not require a biometric prompt. */
    fun forgetBiometricCredentials() {
        biometricCredentialStore.clear()
        _hasSavedBiometricCredentials.value = false
    }

    fun dismissError() {
        if (_uiState.value is ConnectUiState.Error) {
            _uiState.value = ConnectUiState.Idle
        }
    }
}
