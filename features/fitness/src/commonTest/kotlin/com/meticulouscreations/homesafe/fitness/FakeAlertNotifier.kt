package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission

/** Notifications as the fitness view model asks after them: whether they are allowed, and how often it asked. */
class FakeAlertNotifier(var permission: NotificationPermission = NotificationPermission.NOT_DETERMINED) : AlertNotifier {
    var asked = 0
        private set

    override val isSupported = true

    override suspend fun permissionStatus() = permission

    override suspend fun requestPermission(): Boolean {
        asked++
        permission = NotificationPermission.GRANTED
        return true
    }

    override fun openSystemSettings() = Unit

    override fun notify(notification: AlertNotification) = Unit
}
