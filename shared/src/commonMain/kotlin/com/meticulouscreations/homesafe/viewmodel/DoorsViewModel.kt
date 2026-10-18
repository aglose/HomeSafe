package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorEvent
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.usecase.ObserveDoorsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshDoorsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetDoorNotifyUseCase
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_load_failed
import homesafe.shared.generated.resources.doors_save_failed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One opening or closing in the history, with its door's name already looked up. */
@Immutable
data class DoorHistoryEntry(val doorName: String, val open: Boolean, val atMillis: Long, val notified: Boolean)

/** The history's entries of one calendar day, [daysAgo] before today (0 is today), newest first. */
@Immutable
data class DoorHistoryDay(val daysAgo: Int, val entries: List<DoorHistoryEntry>)

@Immutable
data class DoorsUiState(
    val doors: List<Door> = emptyList(),
    val history: List<DoorHistoryDay> = emptyList(),
    /** What "Open for 5 min" is counted from; moves on every [DoorsViewModel.TICK_MS]. */
    val nowMillis: Long = 0,
    /** Whether the relay has answered at least once, so "no doors" isn't said before it has. */
    val loaded: Boolean = false,
    /** The relay can hear the sensors (see [DoorsSnapshot.listening]). */
    val listening: Boolean = true,
    /** False for a relay from before door sensors. */
    val supported: Boolean = true,
    val error: UiText? = null,
)

/** The Doors page: every door sensor, how it stands, when its openings push, and the last week of them. */
@OptIn(ExperimentalTime::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DoorsViewModel(
    observeDoorsUseCase: ObserveDoorsUseCase,
    private val refreshDoorsUseCase: RefreshDoorsUseCase,
    private val setDoorNotifyUseCase: SetDoorNotifyUseCase,
    private val clock: Clock,
) : ViewModel() {

    private data class Local(val refreshed: Boolean = false, val error: UiText? = null)

    private val local = MutableStateFlow(Local())

    /** The clock, read again every [TICK_MS] while the page is up, so "Open for 5 min" keeps counting between polls. */
    private val ticks = flow {
        while (true) {
            emit(clock.now().toEpochMilliseconds())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<DoorsUiState> = combine(observeDoorsUseCase(), local, ticks) { snapshot, local, now ->
        DoorsUiState(
            doors = snapshot.doors,
            history = history(snapshot, now),
            nowMillis = now,
            loaded = local.refreshed || snapshot != DoorsSnapshot.EMPTY,
            // Until the relay has answered, nothing says it can't hear them.
            listening = snapshot.listening || snapshot == DoorsSnapshot.EMPTY,
            supported = snapshot.supported,
            error = local.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DoorsUiState())

    /** Re-reads the doors now: each visit to the page, and its refresh button. */
    fun refresh() {
        viewModelScope.launch {
            val result = refreshDoorsUseCase()
            local.update { it.copy(refreshed = true, error = result.exceptionOrNull()?.userMessage(Res.string.doors_load_failed)) }
        }
    }

    fun setNotify(doorId: String, notify: DoorNotify) {
        viewModelScope.launch {
            val result = setDoorNotifyUseCase(doorId, notify)
            local.update { it.copy(error = result.exceptionOrNull()?.userMessage(Res.string.doors_save_failed)) }
        }
    }

    private fun history(snapshot: DoorsSnapshot, nowMillis: Long): List<DoorHistoryDay> {
        val names = snapshot.doors.associate { it.id to it.name }
        val zone = TimeZone.currentSystemDefault()
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
        return snapshot.events
            .mapNotNull { event -> names[event.doorId]?.let { name -> event.toEntry(name) } }
            .groupBy { Instant.fromEpochMilliseconds(it.atMillis).toLocalDateTime(zone).date.daysUntil(today) }
            .map { (daysAgo, entries) -> DoorHistoryDay(daysAgo, entries) }
    }

    private fun DoorEvent.toEntry(name: String) = DoorHistoryEntry(doorName = name, open = open, atMillis = atMillis, notified = pushed != null)

    companion object {
        const val TICK_MS = 30_000L
    }
}
