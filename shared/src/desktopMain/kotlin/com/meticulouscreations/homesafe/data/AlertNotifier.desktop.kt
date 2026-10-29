package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.desktop.DesktopNotice
import com.meticulouscreations.homesafe.desktop.DesktopNotifications
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.isMacOs

/**
 * The desktop's notifications, shown by the desktop app from its menu-bar icon
 * ([DesktopNotifications]). No push reaches a desktop, so these are the in-app poller's
 * (`DetectionAlertService`), while the app is running.
 *
 * A detection is posted once: its picture and clip arrive as further posts of the same id, which
 * the phones fold into the notification already shown, and a desktop notification can't be
 * changed once it is up. A summary or a silent post (more of a visit already announced) shows nothing.
 */
private class DesktopAlertNotifier : AlertNotifier {
    private val shown = LinkedHashSet<String>()

    override val isSupported = true

    override suspend fun permissionStatus() = NotificationPermission.GRANTED

    override suspend fun requestPermission() = true

    /** The Mac's notification settings, where HomeSafe's banners can be turned down or off. */
    override fun openSystemSettings() {
        if (isMacOs) runCatching { ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.notifications").start() }
    }

    override fun notify(notification: AlertNotification) {
        if (notification.silent || notification.summary) return
        synchronized(shown) {
            if (!shown.add(notification.id)) return
            if (shown.size > REMEMBERED) shown.remove(shown.first())
        }
        val link = notification.door?.toUri() ?: notification.parkingMap?.toUri() ?: notification.target?.toUri()
        DesktopNotifications.post(DesktopNotice(notification.title, notification.body, notification.urgent, link))
    }

    private companion object {
        /** How many detections' ids are remembered, so a late update to one isn't shown as new. */
        const val REMEMBERED = 200
    }
}

actual fun createAlertNotifier(platformContext: PlatformContext): AlertNotifier = DesktopAlertNotifier()
