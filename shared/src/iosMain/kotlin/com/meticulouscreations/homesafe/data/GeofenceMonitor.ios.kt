package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLCircularRegion
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLRegion
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.darwin.NSObject

private const val HOME_REGION_ID = "home"

/**
 * CoreLocation region monitoring. The OS watches the one circle around home on the app's behalf,
 * relaunching the app in the background to deliver a crossing if it has to — provided the app
 * has "Always" location and, on that relaunch, creates a `CLLocationManager` with a delegate
 * before anything else. That last part is why the iOS graph is built at process start
 * (see `startIosApp`) rather than when the first screen appears.
 *
 * A crossing is handed to [onTransition] under a UIKit background task, so the relay call can
 * finish before iOS suspends the process again.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosGeofenceMonitor(private val onTransition: suspend (exited: Boolean) -> Unit) : GeofenceMonitor {

    override val isSupported = true

    private val manager = CLLocationManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _access = MutableStateFlow(LocationAccess.NOT_ASKED)
    override val access: StateFlow<LocationAccess> = _access.asStateFlow()

    private var authorizationChanged: CompletableDeferred<Unit>? = null
    private var locationFix: CompletableDeferred<GeoPoint?>? = null

    // Kept as a property: CLLocationManager holds its delegate weakly.
    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            _access.value = manager.authorizationStatus.toAccess()
            authorizationChanged?.complete(Unit)
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val fix = (didUpdateLocations.lastOrNull() as? CLLocation)?.coordinate?.useContents { GeoPoint(latitude, longitude) }
            locationFix?.complete(fix)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            locationFix?.complete(null)
        }

        @ObjCSignatureOverride
        override fun locationManager(manager: CLLocationManager, didEnterRegion: CLRegion) = crossed(exited = false)

        @ObjCSignatureOverride
        override fun locationManager(manager: CLLocationManager, didExitRegion: CLRegion) = crossed(exited = true)
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        _access.value = manager.authorizationStatus.toAccess()
    }

    private fun CLAuthorizationStatus.toAccess(): LocationAccess = when (this) {
        kCLAuthorizationStatusNotDetermined -> LocationAccess.NOT_ASKED
        kCLAuthorizationStatusAuthorizedWhenInUse -> LocationAccess.WHILE_IN_USE
        kCLAuthorizationStatusAuthorizedAlways -> LocationAccess.ALWAYS
        else -> LocationAccess.DENIED
    }

    /**
     * iOS grants "Always" in two steps too: "While Using" from the first prompt, then a second
     * prompt (shown by the system at a moment of its choosing, or right away if the app was
     * granted "Always" provisionally) to upgrade it. Ask for whichever is next.
     */
    override suspend fun requestAccess() {
        val signal = CompletableDeferred<Unit>().also { authorizationChanged = it }
        when (_access.value) {
            LocationAccess.NOT_ASKED -> manager.requestWhenInUseAuthorization()
            LocationAccess.WHILE_IN_USE -> manager.requestAlwaysAuthorization()
            else -> return
        }
        withTimeoutOrNull(PROMPT_TIMEOUT_MS) { signal.await() }
        authorizationChanged = null
    }

    override suspend fun currentLocation(): GeoPoint? {
        if (_access.value != LocationAccess.WHILE_IN_USE && _access.value != LocationAccess.ALWAYS) return null
        val fix = CompletableDeferred<GeoPoint?>().also { locationFix = it }
        manager.requestLocation()
        return withTimeoutOrNull(FIX_TIMEOUT_MS) { fix.await() }.also { locationFix = null }
    }

    override fun watch(home: HomeLocation?) {
        val current = manager.monitoredRegions.filterIsInstance<CLCircularRegion>().firstOrNull { it.identifier == HOME_REGION_ID }
        if (home != null && current != null && current.matches(home)) return // same fence: leave it, and any event queued for it, alone
        current?.let { manager.stopMonitoringForRegion(it) }
        if (home == null) return
        val region = CLCircularRegion(
            center = CLLocationCoordinate2DMake(home.latitude, home.longitude),
            radius = home.radiusMeters,
            identifier = HOME_REGION_ID,
        )
        region.notifyOnEntry = true
        region.notifyOnExit = true
        manager.startMonitoringForRegion(region)
    }

    private fun CLCircularRegion.matches(home: HomeLocation): Boolean =
        radius == home.radiusMeters && center.useContents { latitude == home.latitude && longitude == home.longitude }

    private fun crossed(exited: Boolean) {
        val application = UIApplication.sharedApplication
        val task = application.beginBackgroundTaskWithExpirationHandler(null)
        scope.launch {
            try {
                withTimeoutOrNull(CROSSING_BUDGET_MS) { onTransition(exited) }
            } finally {
                application.endBackgroundTask(task)
            }
        }
    }

    private companion object {
        const val PROMPT_TIMEOUT_MS = 120_000L
        const val FIX_TIMEOUT_MS = 15_000L
        const val CROSSING_BUDGET_MS = 20_000L
    }
}

actual fun createGeofenceMonitor(platformContext: PlatformContext, onTransition: suspend (exited: Boolean) -> Unit): GeofenceMonitor =
    IosGeofenceMonitor(onTransition)
