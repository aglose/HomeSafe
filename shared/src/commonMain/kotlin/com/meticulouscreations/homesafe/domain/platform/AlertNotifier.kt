package com.meticulouscreations.homesafe.domain.platform

import com.meticulouscreations.homesafe.navigation.MomentDeepLink

/** Where the OS stands on letting this app post notifications. */
enum class NotificationPermission {
    /** The user has allowed notifications (or the OS grants them without asking). */
    GRANTED,

    /** The user has said no in a way the app can't ask again; only the system settings screen can undo it. */
    DENIED,

    /** Not asked yet, or asked once and refused once — the OS will still show the prompt. */
    NOT_DETERMINED,
}

/**
 * One system notification about a detection: what was seen, where and when, where a tap goes,
 * and — once they've been fetched — its picture and its clip.
 *
 * Posted in stages under one [id]: text first, the moment the detection is judged worth a
 * notification, then again with [thumbnail], then again with [animation] if Frigate has one.
 * Each post replaces the last, so the alert is never held back waiting on a download.
 */
data class AlertNotification(
    /** Stable per detection, so a re-post updates the existing notification instead of stacking a duplicate. */
    val id: String,
    val title: String,
    val body: String,
    /** What a tap opens: the detection full screen on its camera. Null opens the app as it was. */
    val target: MomentDeepLink? = null,
    /** JPEG bytes, or null while there's no picture yet. */
    val thumbnail: ByteArray? = null,
    /**
     * Frigate's `preview.gif` of the detection, or null. iOS attaches it as-is and plays it in the
     * expanded notification; Android has no animated notification surface, so it flips through
     * a handful of its frames once and settles on one (see AlertNotifier.android.kt).
     */
    val animation: ByteArray? = null,
    /** Away mode: nobody is home and a person was seen. Posted louder, on its own channel, so it can't be muted with the everyday ones. */
    val urgent: Boolean = false,
    /**
     * More of a visit already notified (the relay's `silent` push): posted, or re-posted after
     * being swiped away, without a sound. A post that isn't silent makes one even over an [id]
     * already showing — the visit brought something new.
     */
    val silent: Boolean = false,
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

    /**
     * Posts (or replaces, by [AlertNotification.id]) one notification. A no-op without permission.
     * A re-post of the same id is an update: it must not sound or buzz again.
     */
    fun notify(notification: AlertNotification)
}
