package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider

/** iOS doesn't register for push yet (no APNs token flows to the relay), so it can't say who it is either. */
private class UnavailablePushTokenProvider : PushTokenProvider {
    override val isSupported = false
    override suspend fun token(): String? = null
}

actual fun createPushTokenProvider(platformContext: PlatformContext): PushTokenProvider = UnavailablePushTokenProvider()
