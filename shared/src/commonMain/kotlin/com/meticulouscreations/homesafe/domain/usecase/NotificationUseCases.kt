package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.domain.repository.RelayPushStatus
import com.meticulouscreations.homesafe.text.TextLoader
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.settings_test_notification_body
import homesafe.shared.generated.resources.settings_test_notification_title
import kotlinx.coroutines.flow.StateFlow

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

/**
 * True while the relay pushes this phone its alerts. Then what it hears follows the relay's policy
 * for when someone is home, not the per-zone rules, which only drive the in-app poller.
 */
@Inject
class ObserveRelayPushUseCase(private val status: RelayPushStatus) {
    operator fun invoke(): StateFlow<Boolean> = status.pushRegistered
}

/** Posts a sample notification so the user can see what one looks like and that the OS lets them through. */
@Inject
class SendTestNotificationUseCase(private val notifier: AlertNotifier, private val textLoader: TextLoader) {
    suspend operator fun invoke() {
        notifier.notify(
            AlertNotification(
                id = TEST_NOTIFICATION_ID,
                title = textLoader.load(UiText.of(Res.string.settings_test_notification_title)),
                body = textLoader.load(UiText.of(Res.string.settings_test_notification_body)),
            ),
        )
    }

    companion object {
        const val TEST_NOTIFICATION_ID = "test"
    }
}
