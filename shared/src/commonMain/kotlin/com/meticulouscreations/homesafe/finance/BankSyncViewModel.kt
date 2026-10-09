package com.meticulouscreations.homesafe.finance

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.finance.domain.BankLinkKind
import com.meticulouscreations.homesafe.finance.domain.BankLinkStart
import com.meticulouscreations.homesafe.finance.domain.BankLinkStatus
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSync
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BankSyncRepository
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_error_generic
import homesafe.shared.generated.resources.fin_bank_notice_exited
import homesafe.shared.generated.resources.fin_bank_notice_expired
import homesafe.shared.generated.resources.fin_bank_notice_linked
import homesafe.shared.generated.resources.fin_bank_notice_linked_named
import homesafe.shared.generated.resources.fin_bank_notice_unlinked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** A link waiting on Plaid's page. [opened] once the page has been handed to the browser, so a redrawn screen doesn't open it twice. */
@Immutable
data class BankLinking(val token: String, val url: String, val expiresAtEpochSeconds: Long, val opened: Boolean = false)

/** One line about what the last action came to. */
@Immutable
data class BankNotice(val text: UiText, val isError: Boolean = false)

@Immutable
data class BankSyncUiState(
    val bank: BankSync? = null,
    val loading: Boolean = true,
    /** Why the page couldn't be read at all, with what to say about it. */
    val problem: BankProblem? = null,
    val problemText: UiText? = null,
    /** The relay is being asked for a link. */
    val startingLink: Boolean = false,
    val linking: BankLinking? = null,
    /** "Sync now" was tapped and the relay hasn't answered yet. */
    val syncRequested: Boolean = false,
    /** The institution being unlinked, by id. */
    val unlinking: String? = null,
    val notice: BankNotice? = null,
) {
    val syncing: Boolean get() = syncRequested || bank?.syncing == true
}

/**
 * The bank sync page: the institutions linked through Plaid, linking another, and asking for a
 * sync. Linking happens on Plaid's page in the browser, so once a link is started this asks the
 * relay how it's getting on every few seconds until the person comes back with it made, gives up,
 * or the link lapses.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class BankSyncViewModel(
    private val repository: BankSyncRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BankSyncUiState())
    val uiState: StateFlow<BankSyncUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var linkJob: Job? = null
    private var syncJob: Job? = null

    /** A sync being asked for, or an unlinking. */
    private var actionJob: Job? = null

    /** The page came up, or asked again. */
    fun load() {
        if (loadJob?.isActive == true) return
        _uiState.update { it.copy(loading = it.bank == null) }
        loadJob = viewModelScope.launch {
            repository.status()
                .onSuccess { bank ->
                    _uiState.update { it.copy(bank = bank, loading = false, problem = null, problemText = null) }
                    if (bank.syncing) followSync()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (shutOut(e)) return@onFailure
                    // Anything else (the server out of reach) leaves the balances already shown in place.
                    _uiState.update { it.copy(loading = false, problem = (e as? BankSyncException)?.problem ?: BankProblem.OTHER, problemText = e.shown()) }
                }
        }
    }

    /**
     * Whether [e] is the relay refusing this account or session. The household's balances aren't
     * left on screen for someone the relay no longer shows them to: what was read goes, whatever
     * was under way stops (so a late answer can't bring it back), and the page says why.
     */
    private fun shutOut(e: Throwable): Boolean {
        val problem = (e as? BankSyncException)?.problem
        if (problem != BankProblem.NOT_ALLOWED && problem != BankProblem.SIGNED_OUT) return false
        val text = e.shown()
        // The job this is called from is cancelled with the rest; its state is written first.
        _uiState.value = BankSyncUiState(loading = false, problem = problem, problemText = text)
        val running = listOf(loadJob, linkJob, syncJob, actionJob)
        loadJob = null
        linkJob = null
        syncJob = null
        actionJob = null
        running.forEach { it?.cancel() }
        return true
    }

    fun link(kind: BankLinkKind) = startLink { repository.startLink(kind) }

    /** A fresh sign-in to an institution that asked for one. */
    fun relink(institutionId: String) = startLink { repository.startRelink(institutionId) }

    private fun startLink(start: suspend () -> Result<BankLinkStart>) {
        // One link at a time: starting another would stop asking about the one open in the browser,
        // and a sign-in finished there would never be collected. It has to be cancelled first.
        if (_uiState.value.startingLink || _uiState.value.linking != null) return
        _uiState.update { it.copy(startingLink = true, notice = null) }
        linkJob = viewModelScope.launch {
            start()
                .onSuccess { link ->
                    _uiState.update { it.copy(startingLink = false, linking = BankLinking(link.token, link.url, link.expiresAtEpochSeconds)) }
                    followLink(link)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (shutOut(e)) return@onFailure
                    _uiState.update { it.copy(startingLink = false, notice = BankNotice(e.shown(), isError = true)) }
                }
        }
    }

    /** Plaid's page has been handed to the browser. */
    fun onLinkOpened() = _uiState.update { s -> s.copy(linking = s.linking?.copy(opened = true)) }

    /** Stops waiting on a link. Plaid's page, if still open, lapses on its own. */
    fun cancelLink() {
        linkJob?.cancel()
        _uiState.update { it.copy(startingLink = false, linking = null) }
    }

    private suspend fun followLink(link: BankLinkStart) {
        var failures = 0
        val expired = BankNotice(UiText.of(Res.string.fin_bank_notice_expired), isError = true)
        while (true) {
            delay(LINK_POLL_MS)
            // The relay is asked first, even past the link's own deadline: a sign-in finished while
            // the app sat suspended behind the browser is still there to collect, and the relay
            // looks for one before it calls a link expired.
            val result = repository.linkProgress(link.token)
            val lapsed = clock.now().epochSeconds >= link.expiresAtEpochSeconds
            val progress = result.getOrNull()
            if (progress == null) {
                val e = result.exceptionOrNull()
                if (e is CancellationException) throw e
                if (e != null && shutOut(e)) return
                if (lapsed) return endLink(expired)
                // Only the relay answering with a problem counts against the link. Not reaching it
                // at all is what happens while the app sits behind the browser, or behind the
                // bank's own app for an OAuth sign-in, where Android holds back its network for
                // as long as that takes: the link is waited on until it lapses.
                if (e is BankSyncException && ++failures >= LINK_POLL_FAILURES) return endLink(BankNotice(e.shown(), isError = true))
                continue
            }
            failures = 0
            when (progress.status) {
                BankLinkStatus.PENDING -> if (lapsed) return endLink(expired)

                BankLinkStatus.LINKED -> {
                    val names = progress.institutions
                    val linked = if (names.isEmpty()) UiText.of(Res.string.fin_bank_notice_linked) else UiText.of(Res.string.fin_bank_notice_linked_named, names.joinToString(", ").asUiText())
                    _uiState.update { it.copy(bank = progress.bank ?: it.bank, linking = null, notice = BankNotice(linked)) }
                    // The relay reads the new institution straight away; its accounts show as that lands.
                    followSync()
                    return
                }

                BankLinkStatus.EXITED -> return endLink(BankNotice(UiText.of(Res.string.fin_bank_notice_exited)))

                BankLinkStatus.EXPIRED -> return endLink(expired)
            }
        }
    }

    private fun endLink(notice: BankNotice) = _uiState.update { it.copy(linking = null, notice = notice) }

    fun syncNow() {
        if (_uiState.value.syncing || _uiState.value.unlinking != null) return
        _uiState.update { it.copy(syncRequested = true, notice = null) }
        actionJob = viewModelScope.launch {
            repository.syncNow()
                .onSuccess { bank ->
                    _uiState.update { it.copy(bank = bank, syncRequested = false) }
                    if (bank.syncing) followSync()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (shutOut(e)) return@onFailure
                    _uiState.update { it.copy(syncRequested = false, notice = BankNotice(e.shown(), isError = true)) }
                }
        }
    }

    /** Asks again every few seconds while the relay is reading the institutions, so the balances show as they land. */
    private fun followSync() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            var asked = 0
            while (isActive && asked++ < SYNC_POLL_LIMIT) {
                delay(SYNC_POLL_MS)
                val result = repository.status()
                result.exceptionOrNull()?.let { e ->
                    if (e is CancellationException) throw e
                    if (shutOut(e)) return@launch
                }
                val bank = result.getOrNull() ?: continue
                _uiState.update { it.copy(bank = bank) }
                if (!bank.syncing) return@launch
            }
            // Still at it after minutes: stop saying so; the next opening of the page shows how it ended.
            _uiState.update { s -> s.copy(bank = s.bank?.copy(syncing = false)) }
        }
    }

    fun unlink(institutionId: String) {
        if (_uiState.value.unlinking != null || _uiState.value.syncRequested) return
        val name = _uiState.value.bank?.institutions?.firstOrNull { it.id == institutionId }?.name.orEmpty()
        _uiState.update { it.copy(unlinking = institutionId, notice = null) }
        actionJob = viewModelScope.launch {
            repository.unlink(institutionId)
                .onSuccess { bank -> _uiState.update { it.copy(bank = bank, unlinking = null, notice = BankNotice(UiText.of(Res.string.fin_bank_notice_unlinked, name.asUiText()))) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (shutOut(e)) return@onFailure
                    _uiState.update { it.copy(unlinking = null, notice = BankNotice(e.shown(), isError = true)) }
                }
        }
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    /**
     * The app's own words when the failure is one it understands, the relay's as written when it
     * answered with some, a plain line otherwise: anything else is the network's, whose message
     * is a URL and a timeout.
     */
    private fun Throwable.shown(): UiText = if (this is LocalizedException) userMessage() else UiText.of(Res.string.fin_bank_error_generic)

    internal companion object {
        const val LINK_POLL_MS = 3_000L
        const val LINK_POLL_FAILURES = 5
        const val SYNC_POLL_MS = 2_500L
        const val SYNC_POLL_LIMIT = 72
    }
}
