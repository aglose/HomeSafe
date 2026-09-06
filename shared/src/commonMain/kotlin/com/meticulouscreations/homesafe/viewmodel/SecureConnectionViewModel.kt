package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.usecase.ConnectToServerUseCase
import com.meticulouscreations.homesafe.domain.usecase.ForgetBiometricCredentialsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetBiometricLoginStatusUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMostRecentConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveBiometricCredentialsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SignInWithBiometricsUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConnectUiState {
    data object Idle : ConnectUiState

    /** The biometric prompt is up; nothing has been sent to the server yet. */
    data object AwaitingBiometrics : ConnectUiState

    /** Credentials are in hand and the server is being asked to accept them. */
    data object Connecting : ConnectUiState
    data class Success(val credentials: SavedCredentials) : ConnectUiState
    data class Error(val message: String) : ConnectUiState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class SecureConnectionViewModel(
    private val connectToServerUseCase: ConnectToServerUseCase,
    private val signInWithBiometricsUseCase: SignInWithBiometricsUseCase,
    private val saveBiometricCredentialsUseCase: SaveBiometricCredentialsUseCase,
    private val forgetBiometricCredentialsUseCase: ForgetBiometricCredentialsUseCase,
    observeMostRecentConnectionUseCase: ObserveMostRecentConnectionUseCase,
    private val getBiometricLoginStatusUseCase: GetBiometricLoginStatusUseCase,
) : ViewModel() {

    val mostRecentConnection: StateFlow<ConnectionRecord?> =
        observeMostRecentConnectionUseCase()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val biometricLogin = getBiometricLoginStatusUseCase()

    /** True if biometric hardware is present, enrolled, and usable on this platform/device. */
    val biometricLoginAvailable: Boolean = biometricLogin.isAvailable

    /** Short user-facing name for the biometric method, e.g. "Face ID" or "fingerprint". */
    val biometricDisplayName: String = biometricLogin.displayName

    private val _hasSavedBiometricCredentials = MutableStateFlow(biometricLogin.hasSavedCredentials)
    val hasSavedBiometricCredentials: StateFlow<Boolean> = _hasSavedBiometricCredentials.asStateFlow()

    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    fun connect(serverUrl: String, username: String, password: String) {
        _uiState.value = ConnectUiState.Connecting
        viewModelScope.launch {
            connectToServerUseCase(serverUrl, username, password)
                .onSuccess { credentials -> _uiState.value = ConnectUiState.Success(credentials) }
                .onFailure { error -> _uiState.value = ConnectUiState.Error(error.message ?: "Couldn't connect to server") }
        }
    }

    /** Runs the biometric prompt, then signs in with the retrieved credentials. */
    fun signInWithBiometrics() {
        _uiState.value = ConnectUiState.AwaitingBiometrics
        viewModelScope.launch {
            signInWithBiometricsUseCase(onCredentialsUnlocked = { _uiState.value = ConnectUiState.Connecting })
                .onSuccess { credentials -> _uiState.value = ConnectUiState.Success(credentials) }
                .onFailure { error -> _uiState.value = ConnectUiState.Error(error.message ?: "Biometric sign-in failed") }
        }
    }

    /**
     * Prompts for biometric auth and, on success, saves [credentials] for future biometric
     * login. Suspends until the save flow (including its own biometric prompt) resolves, so
     * callers can navigate onward right after — regardless of whether the user completed or
     * cancelled the save.
     */
    suspend fun saveBiometricCredentials(credentials: SavedCredentials): Result<Unit> {
        val result = saveBiometricCredentialsUseCase(credentials)
        if (result.isSuccess) refreshSavedCredentials()
        return result
    }

    /** Forgets any saved biometric credentials. Does not require a biometric prompt. */
    fun forgetBiometricCredentials() {
        forgetBiometricCredentialsUseCase()
        refreshSavedCredentials()
    }

    fun dismissError() {
        if (_uiState.value is ConnectUiState.Error) {
            _uiState.value = ConnectUiState.Idle
        }
    }

    private fun refreshSavedCredentials() {
        _hasSavedBiometricCredentials.value = getBiometricLoginStatusUseCase().hasSavedCredentials
    }
}
