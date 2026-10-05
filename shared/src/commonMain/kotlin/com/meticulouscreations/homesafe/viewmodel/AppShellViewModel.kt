package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import com.meticulouscreations.homesafe.domain.usecase.ObserveActiveConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveConnectionProblemUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** State for the app shell's persistent header: which server we're on, by which route, and which build of the app this is. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class AppShellViewModel(
    observeActiveConnectionUseCase: ObserveActiveConnectionUseCase,
    deviceInfo: DeviceInfo,
    observeConnectionProblemUseCase: ObserveConnectionProblemUseCase,
) : ViewModel() {
    val activeConnection: StateFlow<ActiveConnection?> = observeActiveConnectionUseCase()

    /**
     * True while the app is signed in but can't reach the server: the top bar's badge then says
     * "Offline" rather than naming a route nothing is travelling over. The Home page says why.
     */
    val offline: StateFlow<Boolean> =
        observeConnectionProblemUseCase().map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Shown when the route badge is tapped. See [DeviceInfo.appVersion]. */
    val appVersion: String = deviceInfo.appVersion
}
