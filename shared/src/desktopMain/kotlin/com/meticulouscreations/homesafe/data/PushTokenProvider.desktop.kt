package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider

/**
 * No push service reaches a desktop app: alerts come from the app's own poller while it runs
 * (`DetectionAlertService`), and the relay never hears of this device.
 */
private object NoPushTokenProvider : PushTokenProvider {
    override val isSupported = false
    override suspend fun token(): String? = null
}

actual fun createPushTokenProvider(platformContext: PlatformContext): PushTokenProvider = NoPushTokenProvider
