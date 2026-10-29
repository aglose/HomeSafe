package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A desktop stays where it is: who is home is the phones' to tell (docs/away-mode.md). */
private object NoGeofenceMonitor : GeofenceMonitor {
    override val isSupported = false
    override val access: StateFlow<LocationAccess> = MutableStateFlow(LocationAccess.UNAVAILABLE).asStateFlow()
    override suspend fun requestAccess() = Unit
    override suspend fun currentLocation(): GeoPoint? = null
    override fun watch(home: HomeLocation?) = Unit
}

actual fun createGeofenceMonitor(platformContext: PlatformContext, onTransition: suspend (exited: Boolean) -> Unit): GeofenceMonitor = NoGeofenceMonitor
