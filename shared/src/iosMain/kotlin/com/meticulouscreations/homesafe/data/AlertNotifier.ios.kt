package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAttachment
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.coroutines.resume

/**
 * Local notifications through `UNUserNotificationCenter`. iOS hides a local notification while
 * its app is in the foreground unless the center's delegate says otherwise, so [delegate] asks
 * for the banner in that case too — a detection is worth seeing whichever tab is open.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosAlertNotifier : AlertNotifier {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    private val delegate = object : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(
                UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound or UNNotificationPresentationOptionList,
            )
        }
    }

    init {
        center.delegate = delegate
    }

    override val isSupported = true

    override suspend fun permissionStatus(): NotificationPermission = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> NotificationPermission.GRANTED
                UNAuthorizationStatusDenied -> NotificationPermission.DENIED
                else -> NotificationPermission.NOT_DETERMINED
            }
            continuation.resume(status)
        }
    }

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, _ -> continuation.resume(granted) }
    }

    override fun openSystemSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun notify(notification: AlertNotification) {
        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            setBody(notification.body)
            setSound(UNNotificationSound.defaultSound)
        }
        notification.thumbnail?.let { bytes ->
            // Attachments must be files; the center copies it, so a temp file is fine.
            val path = NSTemporaryDirectory() + "homesafe_alert_${notification.id.filter { it.isLetterOrDigit() }}.jpg"
            if (writeToFile(path, bytes)) {
                UNNotificationAttachment.attachmentWithIdentifier("thumbnail", NSURL.fileURLWithPath(path), null, null)
                    ?.let { content.setAttachments(listOf(it)) }
            }
        }
        val request = UNNotificationRequest.requestWithIdentifier(notification.id, content, trigger = null)
        center.addNotificationRequest(request, withCompletionHandler = null)
    }

    /** Plain POSIX file I/O rather than NSData, for the same reason as the clip downloader. */
    private fun writeToFile(path: String, bytes: ByteArray): Boolean {
        val file = fopen(path, "wb") ?: return false
        try {
            bytes.usePinned { pinned ->
                if (bytes.isNotEmpty()) fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
            }
        } finally {
            fclose(file)
        }
        return true
    }
}

actual fun createAlertNotifier(platformContext: PlatformContext): AlertNotifier = IosAlertNotifier()
