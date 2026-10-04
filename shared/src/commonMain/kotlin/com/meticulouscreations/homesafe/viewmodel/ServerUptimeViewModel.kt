package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.UptimeRange
import com.meticulouscreations.homesafe.domain.usecase.GetServerUptimeUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveActiveConnectionUseCase
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.uptime_load_failed
import homesafe.shared.generated.resources.uptime_not_kept_yet
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ServerUptimeUiState(
    val range: UptimeRange = UptimeRange.Day,
    val isLoading: Boolean = true,
    /** The record for [range]; while another range loads, the one last shown stays up. */
    val uptime: ServerUptime? = null,
    val error: UiText? = null,
    /** How the app is reaching the server right now, so the screen can say the record came over the home network. */
    val route: ConnectionRoute? = null,
)

/**
 * The server's uptime screen: the relay's record of what the box could reach, over the chosen
 * range. It reloads when the range changes, on [refresh], and when the app moves between the
 * home network's address and Tailscale's, since the answer has to come over the new one.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ServerUptimeViewModel(
    private val getServerUptime: GetServerUptimeUseCase,
    observeActiveConnection: ObserveActiveConnectionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUptimeUiState())
    val uiState: StateFlow<ServerUptimeUiState> = _uiState.asStateFlow()

    private var loading: Job? = null

    init {
        viewModelScope.launch {
            observeActiveConnection().map { it?.activeUrl to it?.route }.distinctUntilChanged().collectLatest { (_, route) ->
                _uiState.update { it.copy(route = route) }
                load()
            }
        }
    }

    fun selectRange(range: UptimeRange) {
        if (range == _uiState.value.range) return
        _uiState.update { it.copy(range = range) }
        load()
    }

    fun refresh() = load()

    private fun load() {
        loading?.cancel()
        val range = _uiState.value.range
        _uiState.update { it.copy(isLoading = true) }
        loading = viewModelScope.launch {
            val result = getServerUptime(range)
            // Whatever a superseded read came back with, it is not this screen's answer any more.
            ensureActive()
            result.fold(
                onSuccess = { uptime -> _uiState.update { it.copy(isLoading = false, uptime = uptime, error = null) } },
                onFailure = { failure -> _uiState.update { it.copy(isLoading = false, error = failure.uptimeError()) } },
            )
        }
    }

    /** A relay from before it kept the record answers 404, which is a thing to say plainly, not an error code to show. */
    private fun Throwable.uptimeError(): UiText =
        if (message.orEmpty().contains("404")) UiText.of(Res.string.uptime_not_kept_yet) else userMessage(Res.string.uptime_load_failed)
}
