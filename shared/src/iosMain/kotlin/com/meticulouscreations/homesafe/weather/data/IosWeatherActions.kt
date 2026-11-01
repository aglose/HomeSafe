package com.meticulouscreations.homesafe.weather.data

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_notice_remind_before
import homesafe.shared.generated.resources.weather_tab_radar
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The buttons under a weather notification: "Radar", and on rain about to start "Remind me". iOS
 * wants a notification's buttons declared as a category before one arrives, and the centre keeps a
 * single set of categories for the whole app, so these are registered from
 * `IosNotificationTaps.install` with the rest. In :shared rather than the weather module because
 * that registration is :shared's.
 */
object IosWeatherActions {
    const val CATEGORY = "weather"
    const val CATEGORY_SOON = "weather.soon"
    const val RADAR = "weather.radar"
    const val REMIND = "weather.remind"

    /** `userInfo` key holding when "Remind me" should say it again (epoch seconds, as text). */
    const val KEY_REMIND_AT = "homesafe_weather_remind_at"

    suspend fun categories(): Set<UNNotificationCategory> {
        // The buttons' names are fetched before anything is handed to iOS. Asked for in the middle of the call, as it
        // was, the fetch suspends after the identifiers have already been made into NSStrings; by the time it comes
        // back those have been let go, and the app crashed at launch on the first one iOS tried to keep.
        val radar = getString(Res.string.weather_tab_radar)
        val remind = getString(Res.string.weather_notice_remind_before)
        return setOf(
            category(CATEGORY, listOf(UNNotificationAction.actionWithIdentifier(RADAR, radar, UNNotificationActionOptionForeground))),
            category(
                CATEGORY_SOON,
                listOf(
                    UNNotificationAction.actionWithIdentifier(RADAR, radar, UNNotificationActionOptionForeground),
                    // No "foreground": the app needn't come up to set a reminder.
                    UNNotificationAction.actionWithIdentifier(REMIND, remind, 0u),
                ),
            ),
        )
    }

    private fun category(identifier: String, actions: List<UNNotificationAction>) = UNNotificationCategory.categoryWithIdentifier(
        identifier = identifier,
        actions = actions,
        intentIdentifiers = emptyList<String>(),
        options = 0u,
    )

    /**
     * Whether the switches still let a reminder go out. Set by the weather module, which knows
     * them, when the app starts; until then a button pressed is taken at its word.
     */
    var mayRemind: (suspend () -> Boolean)? = null

    /**
     * "Remind me": the same notification, without its picture (iOS has moved that file into its
     * store), at the time it carries. iOS can't ask the forecast again at that moment without the
     * app running, so it says what it said; the title names the time, so it still reads true. The
     * notification on show comes down only once the reminder is safely with the centre, and [done]
     * is called only then, so iOS doesn't suspend the app before it gets there.
     */
    fun remind(request: UNNotificationRequest, done: () -> Unit) {
        MainScope().launch {
            val allowed = runCatching { mayRemind?.invoke() ?: true }.getOrDefault(true)
            val content = request.content.mutableCopy() as? UNMutableNotificationContent
            val at = (content?.userInfo?.get(KEY_REMIND_AT) as? String)?.toLongOrNull()
            val delay = at?.let { it - NSDate().timeIntervalSince1970 }
            if (!allowed || content == null || delay == null || delay <= 0) {
                done()
                return@launch
            }
            content.setAttachments(emptyList<Any>())
            content.setCategoryIdentifier(CATEGORY)
            val center = UNUserNotificationCenter.currentNotificationCenter()
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(delay, repeats = false)
            // Its own identifier, so what is on show and what is to come aren't one request to the centre.
            val reminder = UNNotificationRequest.requestWithIdentifier(REMINDER_PREFIX + request.identifier, content, trigger)
            center.addNotificationRequest(reminder) { error ->
                if (error == null) center.removeDeliveredNotificationsWithIdentifiers(listOf(request.identifier))
                done()
            }
        }
    }

    /** Calls off every reminder still waiting: the switches have turned rain off since. */
    fun cancelReminders() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getPendingNotificationRequestsWithCompletionHandler { pending ->
            val ids = pending.orEmpty().filterIsInstance<UNNotificationRequest>().map { it.identifier }.filter { it.startsWith(REMINDER_PREFIX) }
            if (ids.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(ids)
        }
    }

    private const val REMINDER_PREFIX = "weather.reminder:"
}
