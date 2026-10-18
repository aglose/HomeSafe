package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.usecase.GetEventThumbnailUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveStationaryObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshStationaryObjectsUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * [subjects] as Home's glance row and the In View page show them: each presented for [today],
 * with its thumbnail on [serverUrl]. Built here, not in the card, so a LAN/Tailscale route flip
 * re-points every thumbnail at once.
 */
internal fun inViewItems(subjects: List<StationaryObject>, serverUrl: String?, today: LocalDate, thumbnailUrl: GetEventThumbnailUrlUseCase): List<InViewItem> =
    subjects.map { subject ->
        InViewItem(
            subject = subject,
            presentation = subject.present(today),
            thumbnailUrl = serverUrl?.let { thumbnailUrl(it, subject.thumbnailEventId) },
        )
    }

/**
 * The In View page: the household's cars standing in view of a camera right now, with everything
 * Home's one-line pills leave out (when each arrived, when it was last seen, its picture large),
 * and the way to check and tag them. Collecting [items] keeps the strip's poll running; it is
 * null until the device's copy has been read, so the page doesn't say "nothing parked" first.
 */
@OptIn(ExperimentalTime::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class InViewViewModel(
    observeStationaryObjectsUseCase: ObserveStationaryObjectsUseCase,
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    getEventThumbnailUrlUseCase: GetEventThumbnailUrlUseCase,
    private val refreshStationaryObjectsUseCase: RefreshStationaryObjectsUseCase,
    clock: Clock,
) : ViewModel() {

    val items: StateFlow<List<InViewItem>?> = combine(
        observeStationaryObjectsUseCase(),
        observeCurrentServerUrlUseCase(),
    ) { subjects, serverUrl ->
        inViewItems(subjects, serverUrl, clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date, getEventThumbnailUrlUseCase)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Asks the server again now: the page's refresh button. */
    fun refresh() {
        refreshStationaryObjectsUseCase()
    }
}
