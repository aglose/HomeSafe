package com.meticulouscreations.homesafe.weather.data

import androidx.compose.ui.text.font.createFontFamilyResolver
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.data.writeFile
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import com.meticulouscreations.homesafe.weather.ui.notice.WeatherNoticePainter
import com.meticulouscreations.homesafe.weather.ui.notice.toPng
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.CoreGraphics.CGRectCreateDictionaryRepresentation
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAttachment
import platform.UserNotifications.UNNotificationAttachmentOptionsThumbnailClippingRectKey
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/** `userInfo` key holding when a weather notification stops being news (epoch seconds, as text), for [IosWeatherNotifier.sweep]. */
private const val KEY_EXPIRES = "homesafe_weather_expires"

/** The card's width in pixels: 360 points on a 3x screen. */
private const val CARD_WIDTH_PX = 1080

/** A card with a chart, in the card's own dp: taller than Android's, since iOS shows an attachment at whatever shape it has. */
private const val CARD_HEIGHT_DP = 240

/**
 * Local notifications through `UNUserNotificationCenter`, carrying their [WeatherDeepLink] in
 * `userInfo` so the notification delegate (AlertNotifier.ios.kt) knows a tap on one opens the
 * weather app, and where in it. Without permission the centre drops the request, which is the
 * behaviour wanted.
 *
 * What one looks like: the place is its subtitle; its picture (the tile, and the forecast behind
 * the notification as a chart) is attached, so the banner wears the tile as its thumbnail and a
 * long press opens the whole card; a government warning is time-sensitive (it breaks through a
 * Focus where the app has that entitlement) and carries the agency's words (an excerpt where it ran long; a tap opens the rest); the weather's
 * notifications stack together, apart from the cameras'; and one whose news is over is taken
 * down at the next check ([sweep]), since iOS has no way to give a notification an end.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosWeatherNotifier : WeatherNotifier {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override val isSupported = true

    override suspend fun isAllowed(): Boolean = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            continuation.resume(status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional || status == UNAuthorizationStatusEphemeral)
        }
    }

    // The centre answers on a queue of its own once it has taken the request or refused it.
    override suspend fun notify(notification: WeatherNotification): Boolean {
        // Painted off the main thread: a check runs from the open app as well as from the background.
        // A picture that can't be painted must not cost the notification: it goes out as text.
        val picture = withContext(Dispatchers.Default) { runCatching { picture(notification) }.getOrNull() }
        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            notification.place?.let { setSubtitle(it) }
            // iOS shows as much of the body as fits and the rest on a long press: a warning gets its excerpt, not just its headline.
            setBody(notification.detail ?: notification.body)
            setSound(UNNotificationSound.defaultSound)
            setThreadIdentifier(THREAD)
            setCategoryIdentifier(IosWeatherActions.CATEGORY)
            setInterruptionLevel(if (notification.urgent) UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive else UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive)
            // Which of the app's notifications the summary leads with: a warning, then rain about to start, then the day's outlook.
            setRelevanceScore(
                when {
                    notification.urgent -> 1.0
                    notification.eventEpochSeconds != null -> 0.8
                    else -> 0.5
                },
            )
            setUserInfo(notification.link.toMap() + listOfNotNull(notification.expiresEpochSeconds?.let { KEY_EXPIRES to it.toString() }))
            picture?.let { setAttachments(listOf(it)) }
        }
        val request = UNNotificationRequest.requestWithIdentifier(notification.id, content, trigger = null)
        return suspendCancellableCoroutine { continuation ->
            center.addNotificationRequest(request) { error -> continuation.resume(error == null) }
        }
    }

    /** The card as a PNG the centre can attach, its thumbnail cut to the tile. */
    private fun picture(notification: WeatherNotification): UNNotificationAttachment? {
        val art = notification.art
        val height = WeatherNoticePainter.cardHeight(art, CARD_WIDTH_PX, CARD_HEIGHT_DP)
        val png = WeatherNoticePainter.card(art, CARD_WIDTH_PX, height, createFontFamilyResolver()).toPng() ?: return null
        // The centre moves the file into its own store, so a temp file named for the notification is fine.
        val path = NSTemporaryDirectory() + "homesafe_weather_${notification.id.filter { it.isLetterOrDigit() }}.png"
        if (!writeFile(path, png)) return null
        val tile = WeatherNoticePainter.tileBounds(CARD_WIDTH_PX, height)
        val clip = CFBridgingRelease(CGRectCreateDictionaryRepresentation(CGRectMake(tile.left.toDouble(), tile.top.toDouble(), tile.width.toDouble(), tile.height.toDouble())))
        return UNNotificationAttachment.attachmentWithIdentifier("card", NSURL.fileURLWithPath(path), mapOf<Any?, Any?>(UNNotificationAttachmentOptionsThumbnailClippingRectKey to clip), null)
    }

    override suspend fun sweep(nowEpochSeconds: Long) {
        val over = suspendCancellableCoroutine { continuation ->
            center.getDeliveredNotificationsWithCompletionHandler { delivered ->
                continuation.resume(
                    delivered.orEmpty().filterIsInstance<UNNotification>().mapNotNull { shown ->
                        val expires = (shown.request.content.userInfo[KEY_EXPIRES] as? String)?.toLongOrNull()
                        shown.request.identifier.takeIf { expires != null && expires <= nowEpochSeconds }
                    },
                )
            }
        }
        if (over.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(over)
    }

    private companion object {
        /** One stack for the weather's notifications in Notification Centre, apart from each camera's. */
        const val THREAD = "weather"
    }
}

actual fun createWeatherNotifier(platformContext: PlatformContext): WeatherNotifier = IosWeatherNotifier()

/**
 * The weather check as an iOS background app refresh. iOS decides when (and whether) to grant
 * one, from how the app is used, so this is a best effort beside the check that runs whenever
 * the weather app is opened: each grant runs one check and asks for the next.
 *
 * The task's identifier is listed under `BGTaskSchedulerPermittedIdentifiers` in Info.plist, and
 * its handler must be registered before the app finishes launching: `startIosApp` calls
 * [register], once, with a way to the app graph's check (this module can't reach the graph).
 */
@OptIn(ExperimentalForeignApi::class)
object IosWeatherRefresh {
    private const val TASK_ID = "com.meticulouscreations.homesafe.weather.refresh"

    /** iOS won't run a refresh sooner than this; it usually waits a good deal longer. */
    private const val EARLIEST_SECONDS = 30.0 * 60

    private var registered = false

    fun register(alertCheck: () -> WeatherAlertCheck) {
        if (registered) return
        // Registering an identifier the bundle doesn't list is an exception, not an error to handle:
        // look before asking, so a host without the Info.plist entry simply has no background check.
        val permitted = NSBundle.mainBundle.objectForInfoDictionaryKey("BGTaskSchedulerPermittedIdentifiers") as? List<*>
        if (permitted?.contains(TASK_ID) != true) return
        registered = true
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(TASK_ID, usingQueue = null) { task ->
            // Handed back exactly once, by whichever comes first: the check finishing or iOS calling time.
            var done = false
            fun finish(success: Boolean) {
                if (done) return
                done = true
                task?.setTaskCompletedWithSuccess(success)
            }
            val job = MainScope().launch {
                val check = alertCheck()
                check.run()
                // Asks for the next one if the switch (read from the database, since iOS may have
                // launched the app for this with no screen ever built) is still on.
                runCatching { check.syncSchedule() }
                finish(true)
            }
            // Out of time: iOS wants the task handed back promptly or it counts against the app.
            task?.expirationHandler = {
                job.cancel()
                finish(false)
            }
        }
    }

    fun schedule(enabled: Boolean) {
        if (!registered) return
        if (enabled) submit() else BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK_ID)
    }

    private fun submit() {
        val request = BGAppRefreshTaskRequest(TASK_ID).apply { earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(EARLIEST_SECONDS) }
        // Fails (returning false) where background refresh is off or unavailable, as on a simulator; nothing to do about it.
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }
}

actual fun createWeatherCheckScheduler(platformContext: PlatformContext): WeatherCheckScheduler = object : WeatherCheckScheduler {
    override fun schedule(enabled: Boolean) = IosWeatherRefresh.schedule(enabled)
}
