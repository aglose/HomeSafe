package com.meticulouscreations.homesafe.finance

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_lock_failed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

@Immutable
data class FinanceLockUiState(
    /** There is no server to ask and nobody has shown the phone is theirs: the finance app shows its lock, and the drawer no figures. */
    val locked: Boolean,
    /** The server isn't answering, so whatever is shown is what the device kept. */
    val offline: Boolean,
    /** Whether this device can check a fingerprint or a face at all. */
    val canUnlock: Boolean,
    /** What it checks, for "Unlock with %1$s". */
    val method: StringResource,
    /** The system's prompt is up. */
    val checking: Boolean = false,
    /** Why the last check didn't unlock, as the system put it. */
    val error: UiText? = null,
)

/**
 * The finance app's lock, for the screen that shows it and the drawer card that mentions it: see
 * [FinanceAccess] for when the app is locked and why.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class FinanceLockViewModel(private val access: FinanceAccess) : ViewModel() {

    private data class Check(val checking: Boolean = false, val error: UiText? = null)

    private val check = MutableStateFlow(Check())

    val uiState: StateFlow<FinanceLockUiState> =
        combine(access.locked, access.online, check) { locked, online, check ->
            FinanceLockUiState(locked, !online, access.canUnlock, access.unlockMethod, check.checking, check.error.takeIf { locked })
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FinanceLockUiState(access.locked.value, !access.online.value, access.canUnlock, access.unlockMethod),
        )

    /** Whether the app is locked, as the assistant watches it: a question asked over the app is taken back when it locks. */
    val locked: StateFlow<Boolean> get() = access.locked

    /** Shows the fingerprint or face prompt. One at a time: a second ask while it is up is the same ask. */
    fun unlock() {
        if (check.value.checking || !access.locked.value || !access.canUnlock) return
        check.value = Check(checking = true)
        viewModelScope.launch {
            val result = access.unlock()
            check.update { Check(error = result.exceptionOrNull()?.let { e -> if (e is LocalizedException || !e.message.isNullOrBlank()) e.userMessage() else UiText.of(Res.string.finance_lock_failed) }) }
        }
    }

    /** The finance app was closed. */
    fun lock() {
        access.lock()
        check.value = Check()
    }
}
