package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.IosApp
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Local notifications through `UNUserNotificationCenter`, marked with [WeatherDeepLink.KEY] so
 * the notification delegate (AlertNotifier.ios.kt) knows a tap on one opens the weather app.
 * Without permission the centre drops the request, which is the behaviour wanted.
 */
private class IosWeatherNotifier : WeatherNotifier {
    override val isSupported = true

    override fun notify(notification: WeatherNotification) {
        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            setBody(notification.body)
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(mapOf(WeatherDeepLink.KEY to "1"))
        }
        val request = UNNotificationRequest.requestWithIdentifier(notification.id, content, trigger = null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, withCompletionHandler = null)
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
 * [register], once.
 */
@OptIn(ExperimentalForeignApi::class)
object IosWeatherRefresh {
    private const val TASK_ID = "com.meticulouscreations.homesafe.weather.refresh"

    /** iOS won't run a refresh sooner than this; it usually waits a good deal longer. */
    private const val EARLIEST_SECONDS = 30.0 * 60

    private var registered = false

    /** Whether the notification switch is on, as last told: a refresh that fires after it was switched off asks for no more. */
    private var wanted = false

    fun register() {
        if (registered) return
        // Registering an identifier the bundle doesn't list is an exception, not an error to handle:
        // look before asking, so a host without the Info.plist entry simply has no background check.
        val permitted = NSBundle.mainBundle.objectForInfoDictionaryKey("BGTaskSchedulerPermittedIdentifiers") as? List<*>
        if (permitted?.contains(TASK_ID) != true) return
        registered = true
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(TASK_ID, usingQueue = null) { task ->
            val job = MainScope().launch {
                val posted = runCatching { IosApp.graph.weatherAlertCheck.run() }.isSuccess
                if (wanted) submit()
                task?.setTaskCompletedWithSuccess(posted)
            }
            // Out of time: iOS wants the task handed back promptly or it counts against the app.
            task?.expirationHandler = {
                job.cancel()
                task?.setTaskCompletedWithSuccess(false)
            }
        }
    }

    fun schedule(enabled: Boolean) {
        wanted = enabled
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
