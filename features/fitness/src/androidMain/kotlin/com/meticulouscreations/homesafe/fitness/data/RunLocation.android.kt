package com.meticulouscreations.homesafe.fitness.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.appicon.appLaunchIntent
import com.meticulouscreations.homesafe.data.createSilentChannel
import com.meticulouscreations.homesafe.fitness.domain.GpsFix
import com.meticulouscreations.homesafe.fitness.domain.GpsSignal
import com.meticulouscreations.homesafe.fitness.domain.Recording
import com.meticulouscreations.homesafe.fitness.domain.RunLocation
import com.meticulouscreations.homesafe.fitness.ui.RunFormat
import com.meticulouscreations.homesafe.shared.R
import com.meticulouscreations.homesafe.ui.notification.NotificationGlyph
import com.meticulouscreations.homesafe.ui.notification.drawableRes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "HomeSafeRun"

/** About one position a second, as a running watch takes them. */
private const val FIX_INTERVAL_MILLIS = 1_000L

/** A position that says nothing of how sure it is, taken as this unsure. */
private const val UNKNOWN_ACCURACY_METERS = 50.0

/**
 * The phone's GPS through Google Play's fused location, at its highest accuracy, about once a
 * second, while [RunRecordingService] keeps the app running in the foreground with the screen
 * off. Only the "while the app is in use" location is asked for: a foreground service started
 * from a tap may go on using it after the screen goes off, and nothing is wanted after the run.
 *
 * Positions go to the recorder and into the app's database, and nowhere else.
 *
 * What it does is logged under `HomeSafeRun`, without positions.
 */
private class AndroidRunLocation(private val context: Context, activity: FragmentActivity?) : RunLocation {
    private var pendingRequest: CompletableDeferred<Boolean>? = null

    // Registered on the registry directly, as HeartRateLink.android.kt does: the graph is built
    // too late in onCreate for the lifecycle-bound register().
    private val permissionLauncher = activity?.activityResultRegistry?.register(
        "homesafe.run_location",
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingRequest?.complete(hasPermission())
        pendingRequest = null
    }

    override val isSupported: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    override fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (hasPermission()) return@withContext true
        val launcher = permissionLauncher ?: return@withContext false
        val request = pendingRequest ?: CompletableDeferred<Boolean>().also { asking ->
            pendingRequest = asking
            // The ongoing notification is how a run with the screen off says it is still going; asked for with the location where it isn't already allowed.
            val wanted = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            try {
                launcher.launch(wanted.toTypedArray())
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't ask for location", e)
                pendingRequest = null
                return@withContext false
            }
        }
        request.await()
    }

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<GpsFix> = callbackFlow {
        val service = Intent(context, RunRecordingService::class.java)
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background
            // (ForegroundServiceStartNotAllowedException is an IllegalStateException). Positions
            // still come while the app is on screen.
            Log.w(TAG, "Couldn't keep the app running for the run", e)
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { place ->
                    trySend(GpsFix(place.time, place.latitude, place.longitude, if (place.hasAccuracy()) place.accuracy.toDouble() else UNKNOWN_ACCURACY_METERS))
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(FIX_INTERVAL_MILLIS / 2)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            Log.i(TAG, "Following the GPS")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location was taken away", e)
        }
        awaitClose {
            client.removeLocationUpdates(callback)
            context.stopService(service)
            RunRecordingService.status.value = null
            Log.i(TAG, "Stopped following the GPS")
        }
    }

    override fun notify(recording: Recording) {
        RunRecordingService.status.value = recording
    }
}

/**
 * Keeps the app running while a run is recorded, with an ongoing notification of its time
 * (ticking by itself, as a stopwatch) and distance. Started and stopped by [AndroidRunLocation]
 * alone; a tap on the notification brings the app back.
 */
class RunRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null
    private val manager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager.createSilentChannel(CHANNEL_ID, getString(R.string.notif_channel_run_name), getString(R.string.notif_channel_run_description), NotificationManager.IMPORTANCE_LOW)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(status.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: SecurityException) {
            // Location was taken away between the tap and here.
            Log.w(TAG, "Couldn't run in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Couldn't run in the foreground", e)
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }
        if (following == null) {
            following = scope.launch {
                // Only what the notification shows: the stopwatch ticks by itself.
                status.filterNotNull().map(::Shown).distinctUntilChanged().collect { manager.notify(NOTIFICATION_ID, notification(it.recording)) }
            }
        }
        // Not brought back by Android if the app is stopped: the run is finished where it stopped when the app next starts.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(recording: Recording?): Notification {
        val launch = appLaunchIntent()
        val open = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(NotificationGlyph.RUN.drawableRes)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        if (recording == null) return builder.setContentTitle(getString(R.string.notif_run_title)).build()
        val distance = getString(R.string.notif_run_distance, RunFormat.miles(recording.meters))
        return if (recording.paused) {
            builder.setContentTitle(getString(R.string.notif_run_paused_title))
                .setContentText(getString(R.string.notif_run_paused_text, distance, RunFormat.clock(recording.movingMillis / 1_000)))
                .setUsesChronometer(false)
                .setShowWhen(false)
                .build()
        } else {
            builder.setContentTitle(getString(R.string.notif_run_title))
                .setContentText(if (recording.signal == GpsSignal.SEARCHING) getString(R.string.notif_run_searching) else distance)
                // The stopwatch counts up from when the run would have begun had it never paused.
                .setWhen(System.currentTimeMillis() - recording.movingMillisAt(System.currentTimeMillis().coerceAtLeast(recording.asOfMillis)))
                .setShowWhen(true)
                .setUsesChronometer(true)
                .build()
        }
    }

    /** What the notification shows of a run, so it is posted again only when that changes. */
    private data class Shown(val recording: Recording) {
        private val miles = RunFormat.miles(recording.meters)
        private val paused = recording.paused
        private val searching = recording.signal == GpsSignal.SEARCHING
        private val beganAtSecond = (recording.asOfMillis - recording.movingMillis) / 1_000

        override fun equals(other: Any?): Boolean =
            other is Shown && miles == other.miles && paused == other.paused && searching == other.searching && (paused || beganAtSecond == other.beganAtSecond)

        override fun hashCode(): Int = miles.hashCode()
    }

    companion object {
        private const val CHANNEL_ID = "run_recording"
        private const val NOTIFICATION_ID = 0x52554e

        /** The run being recorded, as the recorder last said; null when there is none. */
        internal val status = MutableStateFlow<Recording?>(null)
    }
}

actual fun createRunLocation(platformContext: PlatformContext): RunLocation =
    AndroidRunLocation(platformContext.context.applicationContext, platformContext.context as? FragmentActivity)
