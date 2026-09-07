package com.meticulouscreations.homesafe.domain.platform

import com.meticulouscreations.homesafe.domain.model.HomeLocation
import kotlinx.coroutines.flow.StateFlow

/** How much of the phone's location this app may see. Only [ALWAYS] lets a geofence fire in the background. */
enum class LocationAccess {
    /** No location API on this platform (desktop, web). */
    UNAVAILABLE,
    NOT_ASKED,

    /** Granted while the app is open: enough to set home, not enough for the fence to notice you leaving. */
    WHILE_IN_USE,
    ALWAYS,
    DENIED,
}

data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * The platform's geofence: one circle around home, watched by the OS so it fires when the app is
 * in the background or not running at all. A domain-level port; Android backs it with Play
 * Services geofencing, iOS with CoreLocation region monitoring, and the rest say [isSupported]
 * is false. Crossings don't come back through this interface — the OS delivers them to whatever
 * the platform wakes (a broadcast receiver, a delegate), which hands them to
 * `PresenceAutomation.onGeofenceTransition`.
 */
interface GeofenceMonitor {
    val isSupported: Boolean

    /** Live: changes when the user answers a prompt or flips it in system settings. */
    val access: StateFlow<LocationAccess>

    /**
     * Takes the next step towards [LocationAccess.ALWAYS] — the OS insists on asking for
     * foreground access first and background access second, sometimes via its own settings
     * screen. Returns once the prompt (or settings trip) is over; read [access] for the answer.
     */
    suspend fun requestAccess()

    /** One fix of where the phone is right now, or null if it can't get one. Needs at least [LocationAccess.WHILE_IN_USE]. */
    suspend fun currentLocation(): GeoPoint?

    /** Watches [home], replacing any earlier fence; null stops watching. Needs [LocationAccess.ALWAYS] to be useful. */
    fun watch(home: HomeLocation?)
}
