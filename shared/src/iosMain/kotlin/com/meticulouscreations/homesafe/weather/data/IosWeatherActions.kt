package com.meticulouscreations.homesafe.weather.data

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_tab_radar
import org.jetbrains.compose.resources.getString
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory

/**
 * The button under a weather notification: "Radar". iOS wants a notification's buttons declared
 * as a category before one arrives, and the centre keeps a single set of categories for the whole
 * app, so this is registered from `IosNotificationTaps.install` with the rest. In :shared rather
 * than the weather module because that registration is :shared's.
 */
object IosWeatherActions {
    const val CATEGORY = "weather"
    const val RADAR = "weather.radar"

    suspend fun category(): UNNotificationCategory {
        // The button's name is fetched before anything is handed to iOS. Asked for in the middle of the call, as it
        // was, the fetch suspends after the identifiers have already been made into NSStrings; by the time it comes
        // back those have been let go, and the app crashed at launch on the first one iOS tried to keep.
        val radar = getString(Res.string.weather_tab_radar)
        return UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY,
            actions = listOf(UNNotificationAction.actionWithIdentifier(RADAR, radar, UNNotificationActionOptionForeground)),
            intentIdentifiers = emptyList<String>(),
            options = 0u,
        )
    }
}
