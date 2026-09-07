package com.meticulouscreations.homesafe.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Play Services says the phone crossed the home fence. Runs whether or not the app is open —
 * often it isn't, which is why the relay call below authenticates with this install's device
 * secret rather than a session. `goAsync` buys the ten seconds a background receiver gets.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence error ${event.errorCode}")
            return
        }
        val exited = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> true
            Geofence.GEOFENCE_TRANSITION_ENTER -> false
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val done = withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    BackgroundGraph.get(context).presenceAutomation.onGeofenceTransition(exited)
                    true
                }
                Log.i(TAG, "home fence ${if (exited) "exited" else "entered"} -> ${if (done == true) "reported" else "timed out"}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "HomeSafeGeofence"
        const val RECEIVER_BUDGET_MS = 9_000L
    }
}

/**
 * Android forgets every geofence on reboot (and when the app is force-stopped, or location
 * toggled off and on); re-arm it from what this phone last knew.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(9_000L) { BackgroundGraph.get(context).presenceAutomation.resyncGeofence() }
            } finally {
                pending.finish()
            }
        }
    }
}
