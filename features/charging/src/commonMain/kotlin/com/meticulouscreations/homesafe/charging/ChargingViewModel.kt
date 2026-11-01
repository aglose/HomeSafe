package com.meticulouscreations.homesafe.charging

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.charging.data.ChargingRelayApi
import com.meticulouscreations.homesafe.charging.domain.ChargeSession
import com.meticulouscreations.homesafe.charging.domain.ChargeSessionDetail
import com.meticulouscreations.homesafe.charging.domain.ChargingSummary
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_error_signed_out
import homesafe.shared.generated.resources.charging_error_unreachable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Immutable
data class ChargingUiState(
    /** The relay has answered at least once. */
    val loaded: Boolean = false,
    val summary: ChargingSummary = ChargingSummary(),
    val refreshing: Boolean = false,
    /** Why the last ask failed. What was shown before it stays. */
    val error: UiText? = null,
    /** Charges beyond the summary's, fetched as the list asks for them. */
    val older: List<ChargeSession> = emptyList(),
    val moreSessions: Boolean = false,
    val loadingMore: Boolean = false,
    /** The charge whose page is open, once it has been fetched. */
    val detail: ChargeSessionDetail? = null,
    val saving: Boolean = false,
    /** What the time since something is counted from. */
    val nowEpochSeconds: Long = 0,
) {
    /** Every charge fetched so far, newest first. */
    val sessions: List<ChargeSession> get() = if (older.isEmpty()) summary.sessions else summary.sessions + older
}

/**
 * What the Teslas cost to charge. Nothing is kept on the device: the relay holds every charge, so
 * this asks it while the drawer's card or the app is on screen, and often while a car is charging.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ChargingViewModel(
    private val api: ChargingRelayApi,
    private val connectionRepository: ConnectionRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChargingUiState(nowEpochSeconds = clock.now().epochSeconds))
    val uiState: StateFlow<ChargingUiState> = _uiState.asStateFlow()

    private var follow: Job? = null
    private var askedAt = 0L

    /** Asks the relay as long as [active]: every little while with a car charging, otherwise now and then. */
    fun setActive(active: Boolean) {
        follow?.cancel()
        follow = null
        if (!active) return
        follow = viewModelScope.launch {
            // The drawer's card asked a moment ago: the app opening doesn't ask again.
            delay((every() - (clock.now().epochSeconds - askedAt)).coerceAtLeast(0) * 1000)
            while (isActive) {
                ask()
                delay(every() * 1000)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { ask() }
    }

    private fun every(): Long = if (_uiState.value.summary.charging.isNotEmpty()) LIVE_SECONDS else QUIET_SECONDS

    private suspend fun ask() {
        val server = server() ?: return
        askedAt = clock.now().epochSeconds
        _uiState.update { it.copy(refreshing = true) }
        api.summary(server).fold(
            onSuccess = { summary -> show(summary) },
            onFailure = { failure -> fail(failure) },
        )
    }

    private fun show(summary: ChargingSummary) {
        _uiState.update {
            it.copy(
                loaded = true,
                summary = summary,
                refreshing = false,
                error = null,
                saving = false,
                // Older charges are taken up where the newest page ends, whatever it now holds.
                older = it.older.filter { old -> summary.sessions.none { new -> new.id == old.id } },
                moreSessions = if (it.older.isEmpty()) summary.moreSessions else it.moreSessions,
                nowEpochSeconds = clock.now().epochSeconds,
            )
        }
    }

    private fun fail(failure: Throwable) {
        _uiState.update { it.copy(refreshing = false, saving = false, loadingMore = false, error = failure.userMessage(Res.string.charging_error_unreachable), nowEpochSeconds = clock.now().epochSeconds) }
    }

    /** The server to ask, or none and says so. */
    private fun server(): String? {
        val server = connectionRepository.currentServerUrl.value
        if (server == null) _uiState.update { it.copy(refreshing = false, error = UiText.of(Res.string.charging_error_signed_out)) }
        return server
    }

    fun loadMore() {
        val state = _uiState.value
        val oldest = state.sessions.lastOrNull()?.pageKey ?: return
        if (state.loadingMore || !state.moreSessions) return
        val server = server() ?: return
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            api.olderSessions(server, oldest).fold(
                onSuccess = { (sessions, more) ->
                    _uiState.update { state ->
                        // A list keyed by id can't hold a charge twice, and one can start between two pages being asked for.
                        val older = (state.older + sessions).distinctBy { it.id }.filter { old -> state.summary.sessions.none { it.id == old.id } }
                        state.copy(older = older, moreSessions = more, loadingMore = false)
                    }
                },
                onFailure = { fail(it) },
            )
        }
    }

    /** Fetches a charge hour by hour for its page. */
    fun openSession(id: Long) {
        val server = server() ?: return
        _uiState.update { state -> state.copy(detail = state.detail?.takeIf { it.session.id == id }) }
        viewModelScope.launch {
            api.session(server, id).fold(
                onSuccess = { detail -> _uiState.update { it.copy(detail = detail, error = null) } },
                onFailure = { fail(it) },
            )
        }
    }

    /** What was paid for a charge, and a note on it; null takes either away. */
    fun saveSession(id: Long, cost: Double?, note: String?) {
        val server = server() ?: return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            api.saveSession(server, id, cost, note).fold(
                onSuccess = { detail ->
                    _uiState.update { state ->
                        state.copy(detail = detail, older = state.older.map { if (it.id == id) detail.session else it }, saving = false)
                    }
                    ask()
                },
                onFailure = { fail(it) },
            )
        }
    }

    /** The plan the household is on: everything charged at home is priced by it, past charges too. */
    fun choosePlan(id: String) {
        val server = server() ?: return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            api.choosePlan(server, id).fold(
                onSuccess = { summary ->
                    show(summary)
                    // An open charge's page and any older charges were priced on the last plan.
                    _uiState.update { it.copy(older = emptyList(), moreSessions = summary.moreSessions) }
                },
                onFailure = { fail(it) },
            )
        }
    }

    private companion object {
        const val LIVE_SECONDS = 30L
        const val QUIET_SECONDS = 300L
    }
}
