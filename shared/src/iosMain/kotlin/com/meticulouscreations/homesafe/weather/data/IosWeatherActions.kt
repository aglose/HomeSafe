package com.meticulouscreations.homesafe.weather.data

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_notice_remind_before
import homesafe.shared.generated.resources.weather_tab_radar
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
     * "Remind me": the same notification, without its picture (iOS has moved that file into its
     * store), at the time it carries. iOS can't ask the forecast again at that moment without the
     * app running, so it says what it said; the title names the time, so it still reads true.
     */
    fun remind(request: UNNotificationRequest) {
        val content = request.content.mutableCopy() as? UNMutableNotificationContent ?: return
        val at = (content.userInfo[KEY_REMIND_AT] as? String)?.toLongOrNull() ?: return
        val delay = at - NSDate().timeIntervalSince1970
        if (delay <= 0) return
        content.setAttachments(emptyList<Any>())
        content.setCategoryIdentifier(CATEGORY)
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removeDeliveredNotificationsWithIdentifiers(listOf(request.identifier))
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(delay, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(request.identifier, content, trigger)) { _ -> }
    }
}
