package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import dev.zacsweers.metro.Inject

/**
 * Where the OS stands on this app's notifications. [isSupported] is false on platforms with no
 * notification surface at all (desktop, a browser tab), where the permission is moot.
 */
@Inject
class GetNotificationPermissionUseCase(private val notifier: AlertNotifier) {
    val isSupported: Boolean get() = notifier.isSupported

    suspend operator fun invoke(): NotificationPermission = notifier.permissionStatus()
}

/** Shows the OS permission prompt; returns whether notifications are allowed afterwards. */
@Inject
class RequestNotificationPermissionUseCase(private val notifier: AlertNotifier) {
    suspend operator fun invoke(): Boolean = notifier.requestPermission()
}

/** Opens the OS's notification settings for this app, for when a denial leaves no other way back. */
@Inject
class OpenNotificationSettingsUseCase(private val notifier: AlertNotifier) {
    operator fun invoke() = notifier.openSystemSettings()
}

/** Posts a sample notification so the user can see what one looks like and that the OS lets them through. */
@Inject
class SendTestNotificationUseCase(private val notifier: AlertNotifier) {
    operator fun invoke() {
        notifier.notify(
            AlertNotification(
                id = TEST_NOTIFICATION_ID,
                title = "Test alert",
                body = "Notifications from HomeSafe are working. Detections will look like this.",
            ),
        )
    }

    companion object {
        const val TEST_NOTIFICATION_ID = "test"
    }
}
