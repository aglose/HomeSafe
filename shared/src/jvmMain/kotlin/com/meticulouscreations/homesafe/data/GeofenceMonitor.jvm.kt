package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** No geofence here; the manual switch is all there is, as on every platform before this one. */
private class UnavailableGeofenceMonitor : GeofenceMonitor {
    override val isSupported = false
    override val access: StateFlow<LocationAccess> = MutableStateFlow(LocationAccess.UNAVAILABLE)
    override suspend fun requestAccess() = Unit
    override suspend fun currentLocation(): GeoPoint? = null
    override fun watch(home: HomeLocation?) = Unit
}

actual fun createGeofenceMonitor(platformContext: PlatformContext, onTransition: suspend (exited: Boolean) -> Unit): GeofenceMonitor =
    UnavailableGeofenceMonitor()
