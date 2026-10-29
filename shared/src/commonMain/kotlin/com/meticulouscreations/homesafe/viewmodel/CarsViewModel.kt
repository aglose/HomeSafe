package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.domain.model.ParkingStreet
import com.meticulouscreations.homesafe.domain.usecase.ObserveCarsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveParkingStreetsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshCarsUseCase
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.household_cars_unreachable
import homesafe.shared.generated.resources.household_cars_unreachable_kept
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Immutable
data class CarsUiState(
    val cars: List<HouseholdCar> = emptyList(),
    /** The watched streets and their curbs' signs, for the map's parking rules. */
    val streets: List<ParkingStreet> = emptyList(),
    /** Whether the relay has answered, so "no cars" isn't said before it has. */
    val loaded: Boolean = false,
    /** What "seen 5 min ago" is counted from; moves on every [CarsViewModel.TICK_MS]. */
    val nowEpochSeconds: Long = 0,
    val error: UiText? = null,
)

/** The cars' map: each of the household's Teslas where it was last seen, and what the sign says where one is parked. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class CarsViewModel(
    observeCarsUseCase: ObserveCarsUseCase,
    observeParkingStreetsUseCase: ObserveParkingStreetsUseCase,
    private val refreshCarsUseCase: RefreshCarsUseCase,
    private val clock: Clock,
) : ViewModel() {

    /** Whether a refresh has answered, and how the last one failed (null when it didn't). */
    private data class Local(val refreshed: Boolean = false, val failure: Throwable? = null)

    private val local = MutableStateFlow(Local())

    private val ticks = flow {
        while (true) {
            emit(clock.now().epochSeconds)
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<CarsUiState> = combine(observeCarsUseCase(), observeParkingStreetsUseCase(), local, ticks) { cars, streets, local, now ->
        CarsUiState(
            cars = cars.orEmpty(),
            streets = streets,
            loaded = cars != null || local.refreshed,
            nowEpochSeconds = now,
            error = local.failure?.let { failed(it, cars) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarsUiState())

    /** Reads the cars again now: each visit to the page, and its pull to refresh. */
    fun refresh() {
        viewModelScope.launch {
            val result = refreshCarsUseCase()
            local.update { it.copy(refreshed = true, failure = result.exceptionOrNull()) }
        }
    }

    /**
     * What to say about a failed refresh: what the relay said, when it said something meant for
     * the reader; otherwise that the server couldn't be reached (a timeout's text, with the URL
     * in it, is for the logs), and, with cars already on the map, that they are its last answer.
     */
    private fun failed(failure: Throwable, cars: List<HouseholdCar>?): UiText = (failure as? LocalizedException)?.text
        ?: UiText.of(if (cars.isNullOrEmpty()) Res.string.household_cars_unreachable else Res.string.household_cars_unreachable_kept)

    companion object {
        const val TICK_MS = 30_000L
    }
}
