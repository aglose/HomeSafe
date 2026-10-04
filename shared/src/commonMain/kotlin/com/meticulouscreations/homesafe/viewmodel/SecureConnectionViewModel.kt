package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.usecase.ConnectToServerUseCase
import com.meticulouscreations.homesafe.domain.usecase.ForgetBiometricCredentialsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetBiometricLoginStatusUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveConnectionProblemUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMostRecentConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveBiometricCredentialsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SignInWithBiometricsUseCase
import com.meticulouscreations.homesafe.network.isTransportFailure
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import com.meticulouscreations.homesafe.ui.components.LiveStartupMilestones
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.connection_error_biometric_failed
import homesafe.shared.generated.resources.connection_error_connect_failed
import homesafe.shared.generated.resources.connection_error_tailscale_off
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

sealed interface ConnectUiState {
    data object Idle : ConnectUiState

    /** The biometric prompt is up; nothing has been sent to the server yet. */
    data object AwaitingBiometrics : ConnectUiState

    /** Credentials are in hand and the server is being asked to accept them. */
    data object Connecting : ConnectUiState

    /**
     * Signed in. [viaBiometrics] says whether the accepted credentials came out of the biometric
     * store — in which case they are by definition what's saved — or were typed, and may be
     * newer than whatever the store holds.
     */
    data class Success(val credentials: SavedCredentials, val viaBiometrics: Boolean) : ConnectUiState
    data class Error(val message: UiText) : ConnectUiState
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
    private val observeConnectionProblemUseCase: ObserveConnectionProblemUseCase,
) : ViewModel() {

    val mostRecentConnection: StateFlow<ConnectionRecord?> =
        observeMostRecentConnectionUseCase()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val biometricLogin = getBiometricLoginStatusUseCase()

    /** True if biometric hardware is present, enrolled, and usable on this platform/device. */
    val biometricLoginAvailable: Boolean = biometricLogin.isAvailable

    /** Short user-facing name for the biometric method, e.g. "Face ID" or "fingerprint". */
    val biometricDisplayName: StringResource = biometricLogin.displayName

    private val _hasSavedBiometricCredentials = MutableStateFlow(biometricLogin.hasSavedCredentials)
    val hasSavedBiometricCredentials: StateFlow<Boolean> = _hasSavedBiometricCredentials.asStateFlow()

    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    fun connect(serverUrl: String, username: String, password: String) {
        _uiState.value = ConnectUiState.Connecting
        LiveStartupMilestones.mark("signin.submit")
        viewModelScope.launch {
            connectToServerUseCase(serverUrl, username, password)
                .onSuccess { credentials ->
                    LiveStartupMilestones.mark("signin.ok")
                    _uiState.value = ConnectUiState.Success(credentials, viaBiometrics = false)
                }
                .onFailure { error -> _uiState.value = ConnectUiState.Error(tailscaleOffMessage(error, serverUrl) ?: error.messageOr(Res.string.connection_error_connect_failed)) }
        }
    }

    /** Runs the biometric prompt, then signs in with the retrieved credentials. */
    fun signInWithBiometrics() {
        _uiState.value = ConnectUiState.AwaitingBiometrics
        viewModelScope.launch {
            signInWithBiometricsUseCase(
                onCredentialsUnlocked = {
                    LiveStartupMilestones.mark("signin.submit")
                    _uiState.value = ConnectUiState.Connecting
                },
            )
                .onSuccess { credentials ->
                    LiveStartupMilestones.mark("signin.ok")
                    _uiState.value = ConnectUiState.Success(credentials, viaBiometrics = true)
                }
                .onFailure { error ->
                    // A refused saved password is forgotten by the repository; drop the
                    // biometric button along with it so the form is the obvious next step.
                    refreshSavedCredentials()
                    _uiState.value = ConnectUiState.Error(
                        tailscaleOffMessage(error, mostRecentConnection.value?.serverUrl) ?: error.messageOr(Res.string.connection_error_biometric_failed),
                    )
                }
        }
    }

    /**
     * "Tailscale isn't connected on this device" for a sign-in to [serverUrl] that nothing
     * answered, when that is why: the address is a tailnet one and the device has no tailnet
     * address of its own. Null when the server did answer (it refused, erred, or sent something
     * that wouldn't parse), or when Tailscale isn't the reason — the failure's own message stands then.
     */
    private fun tailscaleOffMessage(error: Throwable, serverUrl: String?): UiText? {
        if (!error.isTransportFailure() || serverUrl == null) return null
        return observeConnectionProblemUseCase.explainUnanswered(serverUrl)?.let { UiText.of(Res.string.connection_error_tailscale_off) }
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

    /** The failure as it explains itself ([userMessage]), or [fallback] when it says nothing at all. */
    private fun Throwable.messageOr(fallback: StringResource): UiText =
        if (this !is LocalizedException && message.isNullOrBlank()) UiText.of(fallback) else userMessage()
}
