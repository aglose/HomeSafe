package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission

import com.meticulouscreations.homesafe.PlatformContext

/** Desktop has no notification surface wired up; matches the precedent set by [createClipDownloader]'s desktop fallback. */
private class UnavailableAlertNotifier : AlertNotifier {
    override val isSupported = false
    override suspend fun permissionStatus() = NotificationPermission.DENIED
    override suspend fun requestPermission() = false
    override fun openSystemSettings() = Unit
    override fun notify(notification: AlertNotification) = Unit
}

actual fun createAlertNotifier(platformContext: PlatformContext): AlertNotifier = UnavailableAlertNotifier()
