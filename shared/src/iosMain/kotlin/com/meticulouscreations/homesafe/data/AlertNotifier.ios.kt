package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.navigation.MomentDeepLinks
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
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.coroutines.resume

/** `userInfo` key marking a post as an update to one already shown (its picture or clip arriving). */
private const val KEY_UPDATE = "homesafe_update"

/**
 * The notification center's delegate: how iOS tells the app a notification is about to show
 * while it's in front, and that one was tapped.
 *
 * iOS hides a local notification while its app is in the foreground unless told otherwise, so
 * the first post of a detection asks for the banner and sound — a detection is worth seeing
 * whichever tab is open — and its updates (picture, clip) only refresh the entry in the list.
 *
 * A tap hands the detection to [MomentDeepLinks], which the shell opens full screen. iOS delivers
 * the tap that launches the app before `didFinishLaunching` returns, so this is installed from
 * `startIosApp` at launch, not when the notifier is first used.
 */
internal object IosNotificationTaps : NSObject(), UNUserNotificationCenterDelegateProtocol {
    fun install() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        if (center.delegate !== this) center.delegate = this
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        val isUpdate = willPresentNotification.request.content.userInfo[KEY_UPDATE] != null
        withCompletionHandler(
            if (isUpdate) {
                UNNotificationPresentationOptionList
            } else {
                UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound or UNNotificationPresentationOptionList
            },
        )
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val info = didReceiveNotificationResponse.notification.request.content.userInfo
        MomentDeepLink.from { key -> info[key] as? String }?.let(MomentDeepLinks::open)
        withCompletionHandler()
    }
}

/**
 * Local notifications through `UNUserNotificationCenter`, posted in stages under one identifier
 * (see [AlertNotification]). The clip is Frigate's preview GIF, attached as-is: iOS plays an
 * animated GIF attachment in the expanded notification on its own.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosAlertNotifier : AlertNotifier {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    init {
        IosNotificationTaps.install()
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
        val isUpdate = notification.thumbnail != null || notification.animation != null
        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            setBody(notification.body)
            // Only the first post sounds; a picture or clip arriving is an update, not a second alert.
            if (!isUpdate) setSound(UNNotificationSound.defaultSound)
            setUserInfo(notification.target?.toMap().orEmpty() + if (isUpdate) mapOf(KEY_UPDATE to "1") else emptyMap())
        }
        // The clip if there is one, else the picture. Attachments must be files; the center moves
        // it into its own store, so a temp file named for the detection is fine.
        val (bytes, extension) = notification.animation?.let { it to "gif" } ?: notification.thumbnail?.let { it to "jpg" } ?: (null to "")
        if (bytes != null) {
            val path = NSTemporaryDirectory() + "homesafe_alert_${notification.id.filter { it.isLetterOrDigit() }}.$extension"
            if (writeToFile(path, bytes)) {
                UNNotificationAttachment.attachmentWithIdentifier("preview", NSURL.fileURLWithPath(path), null, null)
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
