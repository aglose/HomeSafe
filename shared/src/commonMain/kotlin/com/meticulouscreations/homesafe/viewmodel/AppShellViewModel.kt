package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import com.meticulouscreations.homesafe.domain.usecase.ObserveActiveConnectionUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.StateFlow

/** State for the app shell's persistent header: which server we're on, by which route, and which build of the app this is. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class AppShellViewModel(observeActiveConnectionUseCase: ObserveActiveConnectionUseCase, deviceInfo: DeviceInfo) : ViewModel() {
    val activeConnection: StateFlow<ActiveConnection?> = observeActiveConnectionUseCase()

    /** Shown when the route badge is tapped. See [DeviceInfo.appVersion]. */
    val appVersion: String = deviceInfo.appVersion
}
