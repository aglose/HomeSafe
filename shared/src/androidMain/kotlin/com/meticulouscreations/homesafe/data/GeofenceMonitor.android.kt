package com.meticulouscreations.homesafe.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val HOME_FENCE_ID = "home"
private const val TAG = "HomeSafeGeofence"

/** The home the fence is armed on in this process (see [AndroidGeofenceMonitor.watch]). */
@Volatile private var armedHome: HomeLocation? = null
private const val PREFS_NAME = "homesafe_location"
private const val PREF_ASKED = "asked_for_location"

/**
 * Play Services geofencing. The fence is one circle around home, delivered as a broadcast to
 * [GeofenceBroadcastReceiver] whether or not the app is running — which is the point, and why
 * it needs "Allow all the time" ([Manifest.permission.ACCESS_BACKGROUND_LOCATION]). Android
 * insists that be asked in two steps: foreground location first, background second, the latter
 * via its own settings screen on Android 11+. [requestAccess] takes whichever step is next.
 *
 * Permission state is re-read every time the Activity resumes, because both prompts and the
 * settings trip pause it, and the user can also change the answer in system settings at any time.
 */
private class AndroidGeofenceMonitor(
    private val context: Context,
    private val activity: ComponentActivity?,
) : GeofenceMonitor {

    override val isSupported = true

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val geofencing: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val _access = MutableStateFlow(currentAccess())
    override val access: StateFlow<LocationAccess> = _access.asStateFlow()

    /** Completed on the next resume, so [requestAccess] can wait for the prompt to be over. */
    private var resumed: CompletableDeferred<Unit>? = null

    init {
        activity?.lifecycle?.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    _access.value = currentAccess()
                    resumed?.complete(Unit)
                }
            },
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun currentAccess(): LocationAccess {
        val foreground = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            foreground && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> LocationAccess.ALWAYS
            foreground -> LocationAccess.WHILE_IN_USE
            prefs.getBoolean(PREF_ASKED, false) -> LocationAccess.DENIED
            else -> LocationAccess.NOT_ASKED
        }
    }

    override suspend fun requestAccess() {
        // Permission may have changed behind our back (system settings, `pm grant` on a test
        // device) without a resume to tell us; never ask for what's already granted.
        val before = currentAccess().also { _access.value = it }
        val activity = activity ?: return
        val permissions = when (before) {
            LocationAccess.ALWAYS, LocationAccess.UNAVAILABLE -> return

            LocationAccess.WHILE_IN_USE -> arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

            LocationAccess.NOT_ASKED, LocationAccess.DENIED ->
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        prefs.edit().putBoolean(PREF_ASKED, true).apply()
        val signal = CompletableDeferred<Unit>().also { resumed = it }
        ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE)
        // The dialog (or the settings screen) pauses the Activity and its return resumes it —
        // usually. Poll as well, so an answer that arrives without a resume (or a dialog the OS
        // decided not to show) doesn't leave this waiting for the whole timeout.
        withTimeoutOrNull(PROMPT_TIMEOUT_MS) {
            while (!signal.isCompleted && currentAccess() == before) delay(POLL_MS)
        }
        resumed = null
        _access.value = currentAccess()
    }

    /**
     * Fused first (best on a real phone), then the platform's own provider (works on emulators and
     * phones without Play Services), then whatever was last known. Every step is bounded: a
     * provider that never answers must not leave "Set home here" spinning.
     */
    override suspend fun currentLocation(): GeoPoint? {
        val access = currentAccess().also { _access.value = it }
        if (access == LocationAccess.NOT_ASKED || access == LocationAccess.DENIED) return null
        return try {
            fusedLocation() ?: platformLocation() ?: lastKnownLocation()
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun fusedLocation(): GeoPoint? = withTimeoutOrNull(FIX_TIMEOUT_MS) {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationTokenSource()
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
                .addOnSuccessListener { location -> continuation.resume(location?.toGeoPoint()) }
                .addOnFailureListener { continuation.resume(null) }
                .addOnCanceledListener { continuation.resume(null) }
            continuation.invokeOnCancellation { cancel.cancel() }
        }
    }

    private suspend fun platformLocation(): GeoPoint? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location ->
                    continuation.resume(location?.toGeoPoint())
                }
                continuation.invokeOnCancellation { cancel.cancel() }
            }
        }
    }

    private fun lastKnownLocation(): GeoPoint? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return manager.allProviders.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }?.toGeoPoint()
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

    override fun watch(home: HomeLocation?) {
        // Process-wide, not per monitor: the receiver's background graph and the Activity's graph
        // can live in one process, and the second must not tear down and re-arm what the first
        // just registered — Play Services resets the fence's known state each time.
        if (home == armedHome) return
        armedHome = home
        val intent = geofencePendingIntent(context)
        geofencing.removeGeofences(intent)
        if (home == null) return
        val fence = Geofence.Builder()
            .setRequestId(HOME_FENCE_ID)
            .setCircularRegion(home.latitude, home.longitude, home.radiusMeters.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            // How long Play Services may sit on a crossing to save battery. The relay's dwell is
            // ten minutes, so a minute here costs nothing.
            .setNotificationResponsiveness(60_000)
            .build()
        val request = GeofencingRequest.Builder()
            // No initial trigger: arming the fence says nothing about where the phone is now.
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        try {
            geofencing.addGeofences(request, intent)
                .addOnSuccessListener { Log.i(TAG, "watching home: ${home.latitude}, ${home.longitude} r=${home.radiusMeters.toInt()}m") }
                .addOnFailureListener {
                    Log.w(TAG, "couldn't arm the home fence: ${it.message}")
                    armedHome = null
                }
        } catch (_: SecurityException) {
            // Background location was revoked between the check and the call; the next resume re-syncs.
            armedHome = null
        }
    }

    private companion object {
        const val REQUEST_CODE = 0x10c
        const val PROMPT_TIMEOUT_MS = 120_000L
        const val POLL_MS = 500L
        const val FIX_TIMEOUT_MS = 15_000L
    }
}

/** The one PendingIntent the fence is registered with; mutable, as the geofencing API requires. */
internal fun geofencePendingIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GeofenceBroadcastReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

actual fun createGeofenceMonitor(platformContext: PlatformContext, onTransition: suspend (exited: Boolean) -> Unit): GeofenceMonitor =
    AndroidGeofenceMonitor(
        context = platformContext.context.applicationContext,
        activity = platformContext.context as? ComponentActivity,
    )
