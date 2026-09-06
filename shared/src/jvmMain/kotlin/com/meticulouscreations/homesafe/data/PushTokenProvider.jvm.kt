package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider

/** Desktop has no push token; matches the precedent set by [createAlertNotifier]'s desktop fallback. */
private class UnavailablePushTokenProvider : PushTokenProvider {
    override val isSupported = false
    override suspend fun token(): String? = null
}

actual fun createPushTokenProvider(platformContext: PlatformContext): PushTokenProvider = UnavailablePushTokenProvider()
