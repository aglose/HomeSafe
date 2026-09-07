package com.meticulouscreations.homesafe.domain.platform

/** Where the OS stands on letting this app post notifications. */
enum class NotificationPermission {
    /** The user has allowed notifications (or the OS grants them without asking). */
    GRANTED,

    /** The user has said no in a way the app can't ask again; only the system settings screen can undo it. */
    DENIED,

    /** Not asked yet, or asked once and refused once — the OS will still show the prompt. */
    NOT_DETERMINED,
}

/** One system notification about a detection: what was seen, where and when, and its thumbnail if there is one. */
data class AlertNotification(
    /** Stable per detection, so a re-post updates the existing notification instead of stacking a duplicate. */
    val id: String,
    val title: String,
    val body: String,
    /** JPEG bytes, or null for a text-only notification. */
    val thumbnail: ByteArray? = null,
    /** Away mode: nobody is home and a person was seen. Posted louder, on its own channel, so it can't be muted with the everyday ones. */
    val urgent: Boolean = false,
)

/**
 * Posts local system notifications for detections. Local, not push: this app has no cloud relay
 * and Frigate has no way to reach a native app, so the notifications are minted on the device by
 * [com.meticulouscreations.homesafe.data.DetectionAlertService] while the app is running and then
 * shown through the platform's own notification surface — permission prompt, channel, banner,
 * notification shade, all the usual.
 *
 * A domain-level port: the domain and its use cases talk to this interface, and each platform
 * supplies the implementation from the data layer (see `createAlertNotifier`).
 */
interface AlertNotifier {
    /** False where the platform has no notification surface this app can use (desktop, a browser tab). */
    val isSupported: Boolean

    suspend fun permissionStatus(): NotificationPermission

    /** Shows the OS permission prompt; returns whether notifications are allowed afterwards. */
    suspend fun requestPermission(): Boolean

    /** Opens the OS's notification settings for this app, for when [NotificationPermission.DENIED] leaves no other way. */
    fun openSystemSettings()

    /** Posts (or replaces, by [AlertNotification.id]) one notification. A no-op without permission. */
    fun notify(notification: AlertNotification)
}
