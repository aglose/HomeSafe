package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import kotlinx.coroutines.flow.StateFlow

/**
 * Flips this phone's away switch without anyone touching it. Two signals, one per direction:
 *
 * - **Home** — reaching Frigate over the LAN (you can't from the road) or re-entering the home
 *   geofence. Immediate, and the safe direction to be wrong in: a false "home" merely keeps the
 *   ordinary alert rules.
 * - **Away** — leaving the geofence. Only *armed*: the relay waits a dwell before marking the
 *   phone away, and any "home" in between cancels it, so a walk to the mailbox isn't a departure.
 *
 * Off until the user turns it on in Settings, and inert without [LocationAccess.ALWAYS] and a
 * home location. The manual switch keeps working throughout as the override.
 */
interface PresenceAutomation {
    /** Whether this platform can watch a geofence at all. */
    val geofenceSupported: Boolean

    /** The phone's location permission, live. */
    val locationAccess: StateFlow<LocationAccess>

    /** Starts following the connection and settings; safe to call more than once. */
    fun start()

    /** Steps towards [LocationAccess.ALWAYS]; see `GeofenceMonitor.requestAccess`. */
    suspend fun requestLocationAccess()

    /** Makes where this phone is standing the household's home, for every phone's geofence. */
    suspend fun setHomeHere(): Result<Unit>

    /** Forgets the household's home; every phone stops watching. */
    suspend fun clearHome(): Result<Unit>

    /** The OS says this phone crossed the fence. Ignored while automation is off. */
    suspend fun onGeofenceTransition(exited: Boolean)

    /**
     * Re-registers the fence from what this phone last knew — after a reboot on Android, where
     * the OS forgets geofences, and on every launch on iOS, where they must be re-armed to be
     * delivered. Doesn't need the relay to be reachable.
     */
    suspend fun resyncGeofence()
}
