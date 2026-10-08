package com.meticulouscreations.homesafe.finance

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetRepository
import com.meticulouscreations.homesafe.finance.domain.BudgetSheetFigures
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_budget_error_generic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.time.Clock

@Immutable
data class BudgetUiState(
    val budget: Budget? = null,
    val loading: Boolean = true,
    /** Why the page couldn't be read at all, with what to say about it. */
    val problem: BankProblem? = null,
    val problemText: UiText? = null,
    /** An earlier month being looked back at, as `YYYY-MM`; null for the month under way. */
    val month: String? = null,
    /** "Sync now" was tapped and the relay hasn't answered yet. */
    val syncRequested: Boolean = false,
    /** The settings are being saved. */
    val saving: Boolean = false,
    /** The purchases being put in a bucket, by id. */
    val tagging: Set<String> = emptySet(),
    val notice: BankNotice? = null,
    /** When the page was last read, for saying how long ago the cards were. */
    val readAtEpochSeconds: Long = 0,
) {
    val syncing: Boolean get() = syncRequested || budget?.syncing == true
}

/**
 * The Budget page: the month's card spending against its limits, as the relay keeps it. The relay
 * reads the cards every hour, so while the page is up this asks it again every minute for what
 * that found; every change made here (a purchase tagged, a limit set) is answered with the month
 * as it then stands.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class BudgetViewModel(
    private val repository: BudgetRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BudgetUiState())
    val uiState: StateFlow<BudgetUiState> = _uiState.asStateFlow()

    private var active = false
    private var pollJob: Job? = null
    private var syncJob: Job? = null

    /** A save, or a sync being asked for. */
    private var actionJob: Job? = null

    /** Tags go to the relay one at a time, in the order they were tapped. */
    private val tagOrder = Mutex()

    /** The month under way, once the relay has said which it is. */
    private var currentMonth: String? = null

    /** What the sheet was last reported as, so a relay that won't take it isn't told again and again. */
    private var reported: BudgetConfigPatch? = null

    /** The page came on screen, or left it. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (active) poll() else pollJob?.cancel()
    }

    /** Pulled to refresh. */
    fun refresh() {
        if (active) poll() else viewModelScope.launch { read() }
    }

    private fun poll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                read()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun read() {
        repository.budget(_uiState.value.month)
            .onSuccess(::show)
            .onFailure { e ->
                if (e is CancellationException) throw e
                if (shutOut(e)) return
                // Anything else (the server out of reach) leaves the month already shown in place.
                _uiState.update { it.copy(loading = false, problem = (e as? BankSyncException)?.problem ?: BankProblem.OTHER, problemText = e.shown()) }
            }
    }

    private fun show(budget: Budget) {
        if (budget.isCurrentMonth) currentMonth = budget.month
        _uiState.update { it.copy(budget = budget, loading = false, problem = null, problemText = null, readAtEpochSeconds = clock.now().epochSeconds) }
        if (budget.syncing) followSync()
    }

    /** The relay answers a change with a month: the one on screen is shown, any other means asking for the one that is. */
    private suspend fun applied(budget: Budget) {
        val shown = _uiState.value.month ?: currentMonth
        if (shown == null || budget.month == shown) show(budget) else read()
    }

    /**
     * Whether [e] is the relay refusing this account or session. The household's spending isn't
     * left on screen for someone the relay no longer shows it to: what was read goes, whatever
     * was under way stops, and the page says why.
     */
    private fun shutOut(e: Throwable): Boolean {
        val problem = (e as? BankSyncException)?.problem
        if (problem != BankProblem.NOT_ALLOWED && problem != BankProblem.SIGNED_OUT) return false
        _uiState.value = BudgetUiState(loading = false, problem = problem, problemText = e.shown())
        val running = listOf(syncJob, actionJob)
        syncJob = null
        actionJob = null
        running.forEach { it?.cancel() }
        return true
    }

    /** Looks back at [month] (`YYYY-MM`), or with null returns to the month under way. */
    fun showMonth(month: String?) {
        val wanted = month?.takeIf { it != currentMonth }
        if (wanted == _uiState.value.month) return
        _uiState.update { it.copy(month = wanted, loading = true) }
        if (active) poll() else viewModelScope.launch { read() }
    }

    fun syncNow() {
        if (_uiState.value.syncing) return
        _uiState.update { it.copy(syncRequested = true, notice = null) }
        actionJob = viewModelScope.launch {
            repository.syncNow()
                .onSuccess { budget ->
                    _uiState.update { it.copy(syncRequested = false) }
                    applied(budget)
                    if (budget.syncing) followSync()
                }
                .onFailure { e -> failed(e) { it.copy(syncRequested = false) } }
        }
    }

    /** Asks again every few seconds while the relay is reading the cards, so new purchases show as they land. */
    private fun followSync() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            var asked = 0
            while (isActive && asked++ < SYNC_POLL_LIMIT) {
                delay(SYNC_POLL_MS)
                val result = repository.budget(_uiState.value.month)
                result.exceptionOrNull()?.let { e ->
                    if (e is CancellationException) throw e
                    if (shutOut(e)) return@launch
                }
                val budget = result.getOrNull() ?: continue
                _uiState.update { it.copy(budget = budget, readAtEpochSeconds = clock.now().epochSeconds) }
                if (!budget.syncing) return@launch
            }
            // Still at it after minutes: stop saying so; the next read shows how it ended.
            _uiState.update { s -> s.copy(budget = s.budget?.copy(syncing = false)) }
        }
    }

    /** Says whose a purchase is; with [remember], whose every purchase from its merchant is. */
    fun tag(purchaseId: String, bucket: BucketId, remember: Boolean) {
        if (purchaseId in _uiState.value.tagging) return
        _uiState.update { it.copy(tagging = it.tagging + purchaseId, notice = null) }
        viewModelScope.launch {
            // Several can be tapped before the first is answered. Each answer is the month as it
            // then stood, so they are sent in turn: a later one's answer never lands before an earlier one's.
            tagOrder.withLock { repository.tag(purchaseId, bucket, remember) }
                .onSuccess { budget ->
                    _uiState.update { it.copy(tagging = it.tagging - purchaseId) }
                    applied(budget)
                }
                .onFailure { e -> failed(e) { it.copy(tagging = it.tagging - purchaseId) } }
        }
    }

    fun save(patch: BudgetConfigPatch) {
        _uiState.update { it.copy(saving = true, notice = null) }
        actionJob = viewModelScope.launch {
            repository.save(patch)
                .onSuccess { budget ->
                    _uiState.update { it.copy(saving = false) }
                    applied(budget)
                }
                .onFailure { e -> failed(e) { it.copy(saving = false) } }
        }
    }

    fun forgetRule(merchantKey: String) {
        actionJob = viewModelScope.launch {
            repository.forgetRule(merchantKey)
                .onSuccess { applied(it) }
                .onFailure { e -> failed(e) { it } }
        }
    }

    private fun failed(e: Throwable, settle: (BudgetUiState) -> BudgetUiState) {
        if (e is CancellationException) throw e
        if (shutOut(e)) return
        _uiState.update { settle(it).copy(notice = BankNotice(e.shown(), isError = true)) }
    }

    /**
     * The budget sheet was read. The relay can't read take-home or the bills out of it (only this
     * app parses the sheet), and needs both to know where spending starts to come out of savings,
     * so they are reported whenever they differ from what it has. A relay that has no people yet
     * is given the sheet's.
     */
    fun onSheet(finance: PersonalFinance?) {
        val budget = _uiState.value.budget ?: return
        if (finance == null || !budget.isCurrentMonth) return
        val figures = BudgetSheetFigures.of(finance, budget.config.cardPaidLines)
        val stale = figures.takeHome != null && figures.billsOffCard != null && !figures.sameAs(budget.config.sheet)
        val people = finance.people.takeIf { budget.config.people.isEmpty() && it.isNotEmpty() }
        if (!stale && people == null) return
        val patch = BudgetConfigPatch(people = people, sheet = figures.takeIf { stale })
        if (patch == reported) return
        reported = patch
        viewModelScope.launch {
            repository.save(patch)
                .onSuccess { applied(it) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // Refused is refused; a request that never arrived is sent again the next time the sheet is looked at.
                    if (e !is BankSyncException) reported = null
                    shutOut(e)
                }
        }
    }

    private fun BudgetSheetFigures.sameAs(other: BudgetSheetFigures): Boolean {
        fun near(a: Double?, b: Double?) = a != null && b != null && abs(a - b) < 1.0
        return near(takeHome, other.takeHome) && near(billsOffCard, other.billsOffCard)
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    /** The app's own words when the failure is one it understands, the server's as written when it gave some, a plain line otherwise. */
    private fun Throwable.shown(): UiText = if (this !is LocalizedException && message.isNullOrBlank()) UiText.of(Res.string.fin_budget_error_generic) else userMessage()

    internal companion object {
        const val POLL_MS = 60_000L
        const val SYNC_POLL_MS = 2_500L
        const val SYNC_POLL_LIMIT = 72
    }
}
