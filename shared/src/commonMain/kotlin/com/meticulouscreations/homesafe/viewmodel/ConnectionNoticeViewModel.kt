package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ConnectionProblem
import com.meticulouscreations.homesafe.domain.usecase.ObserveConnectionProblemUseCase
import com.meticulouscreations.homesafe.domain.usecase.ReconnectToServerUseCase
import com.meticulouscreations.homesafe.network.TailscaleApp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Why the server can't be reached, for whichever screen wants to say so: the home page's notice
 * and the top bar's badge both read it, so the two never disagree. [retry] is the notice's button:
 * one pass at reaching the server now, rather than waiting for the next automatic one. Where the
 * platform allows, [openTailscale] is its other one.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ConnectionNoticeViewModel(
    observeConnectionProblem: ObserveConnectionProblemUseCase,
    private val reconnectToServer: ReconnectToServerUseCase,
    private val tailscaleApp: TailscaleApp,
) : ViewModel() {

    /** Whether the notice can offer to open Tailscale: it is installed, and the platform allows it. */
    val canOpenTailscale: Boolean get() = tailscaleApp.canOpen

    /** Brings Tailscale to the front, for the person to connect it. The app retries by itself once they are back. */
    fun openTailscale() {
        tailscaleApp.open()
    }

    val problem: StateFlow<ConnectionProblem?> =
        observeConnectionProblem().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _retrying = MutableStateFlow(false)

    /** True while a [retry] is asking the server, so the button can say it is working. */
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    private var retryWork: Job? = null

    fun retry() {
        if (retryWork?.isActive == true) return
        _retrying.value = true
        retryWork = viewModelScope.launch {
            try {
                reconnectToServer()
            } finally {
                _retrying.value = false
            }
        }
    }
}
