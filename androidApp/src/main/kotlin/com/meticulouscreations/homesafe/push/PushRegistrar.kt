package com.meticulouscreations.homesafe.push

import android.content.Context
import android.util.Log
import com.meticulouscreations.homesafe.data.BackgroundGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Firebase's side of device registration. Registration itself lives in the shared module
 * (`DeviceRegistrar`, started by `App`) and follows the connection; the one thing only Android
 * knows is when Firebase rotates the token, which arrives here from the messaging service —
 * often with no Activity, so it goes through the background graph and the relay's device
 * secret rather than a session.
 */
object PushRegistrar {
    private const val TAG = "PushRegistrar"

    /** Called by the messaging service when Firebase hands out a new token. */
    fun onNewToken(token: String, context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            BackgroundGraph.get(context).deviceRegistrar.onPushTokenChanged(token)
                .onSuccess { Log.i(TAG, "re-registered with the relay after a token rotation") }
                .onFailure { Log.w(TAG, "push re-registration failed: ${it.message}") }
        }
    }
}
