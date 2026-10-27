package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The APNs device token iOS handed this install, as the 64 hex digits the relay pushes to
 * straight through Apple's push service (see "APNs" in relay/relay.py). iOS gives it to the app
 * delegate after `registerForRemoteNotifications()`, at every launch and again whenever it
 * changes; `iOSApp.swift` passes it on through `onApnsToken` (IosApp.kt), which also re-registers
 * with the relay. Null until then, so a registration made before it arrives has no token, and the
 * one that follows it does.
 */
object IosPushToken {
    private val current = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = current.asStateFlow()

    fun set(token: String) {
        current.value = token
    }
}

/** iOS pushes through APNs: the token is whatever iOS has handed [IosPushToken] so far. */
private class ApnsPushTokenProvider : PushTokenProvider {
    override val isSupported = true
    override suspend fun token(): String? = IosPushToken.token.value
}

actual fun createPushTokenProvider(platformContext: PlatformContext): PushTokenProvider = ApnsPushTokenProvider()
