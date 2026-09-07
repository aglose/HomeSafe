package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor

/**
 * Builds the platform's [GeofenceMonitor]. [onTransition] is how a platform that delivers
 * crossings to *this process* (iOS, through the location manager's delegate) hands them on;
 * Android delivers them to a broadcast receiver instead, which builds its own graph and calls
 * `PresenceAutomation.onGeofenceTransition` directly, so its actual ignores the callback.
 */
expect fun createGeofenceMonitor(platformContext: PlatformContext, onTransition: suspend (exited: Boolean) -> Unit): GeofenceMonitor
