package com.meticulouscreations.homesafe.push

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.network.PushRelayApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Keeps the HomeSafe relay told where to push. Re-registers whenever the active server address
 * changes (LAN ↔ Tailscale) or Firebase rotates the token. The HTTP side lives in the shared
 * module ([PushRelayApi]) so iOS can do the same.
 */
object PushRegistrar {
    private const val TAG = "PushRegistrar"

    @Volatile private var lastServerUrl: String? = null
    @Volatile private var lastToken: String? = null
    @Volatile private var quietFamiliar: Boolean = false
    private var relayApi: PushRelayApi? = null

    /**
     * Follows the connection and the "only strangers" preference: each time a server becomes
     * active, or that switch flips, (re-)register this device with the relay so it knows both
     * where to push and whether to skip recognised people for this phone.
     */
    fun start(scope: CoroutineScope, appGraph: AppGraph, context: Context) {
        relayApi = appGraph.pushRelayApi
        scope.launch {
            combine(
                appGraph.connectionRepository.currentServerUrl.filterNotNull(),
                appGraph.settingsRepository.observeSettings().map { it.quietFamiliarPeople }.distinctUntilChanged(),
            ) { serverUrl, quiet -> serverUrl to quiet }.collect { (serverUrl, quiet) ->
                lastServerUrl = serverUrl
                quietFamiliar = quiet
                runCatching { register(serverUrl, currentToken()) }
                    .onFailure { Log.w(TAG, "push registration failed: ${it.message}") }
            }
        }
    }

    /** Called by the messaging service when Firebase hands out a new token. */
    fun onNewToken(token: String, context: Context) {
        lastToken = token
        val serverUrl = lastServerUrl ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { register(serverUrl, token) }
                .onFailure { Log.w(TAG, "push re-registration failed: ${it.message}") }
        }
    }

    private suspend fun register(serverUrl: String, token: String) {
        val api = relayApi ?: return
        api.registerDevice(serverUrl, token, platform = "android", name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(), quietFamiliar = quietFamiliar).getOrThrow()
        lastToken = token
        Log.i(TAG, "registered for push with ${PushRelayApi.relayUrl(serverUrl, "/devices")}")
    }

    private suspend fun currentToken(): String = lastToken ?: suspendCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
