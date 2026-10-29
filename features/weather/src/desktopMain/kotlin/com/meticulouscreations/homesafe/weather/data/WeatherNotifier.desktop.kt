package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.desktop.DesktopNotice
import com.meticulouscreations.homesafe.desktop.DesktopNotifications
import com.meticulouscreations.homesafe.navigation.WeatherDeepLinks
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The weather's notifications from the desktop app's menu-bar icon, as the cameras' are; a click opens what it was about. */
private object DesktopWeatherNotifier : WeatherNotifier {
    override val isSupported = true

    override suspend fun isAllowed() = true

    override suspend fun notify(notification: WeatherNotification): Boolean {
        val title = listOfNotNull(notification.title, notification.place).joinToString(" · ")
        DesktopNotifications.post(DesktopNotice(title, notification.detail ?: notification.body, notification.urgent) { WeatherDeepLinks.open(notification.link) })
        return true
    }
}

actual fun createWeatherNotifier(platformContext: PlatformContext): WeatherNotifier = DesktopWeatherNotifier

/**
 * The weather check while the desktop app is open, every [INTERVAL_MS]: a computer has no
 * background refresh to grant like iOS's or a WorkManager job like Android's, but it is often left
 * running all day. The desktop app hands over its way to the app graph's check at launch
 * ([register]), since this module can't reach the graph.
 */
object DesktopWeatherRefresh {
    private const val INTERVAL_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var alertCheck: (() -> WeatherAlertCheck)? = null
    private var job: Job? = null

    fun register(alertCheck: () -> WeatherAlertCheck) {
        this.alertCheck = alertCheck
    }

    fun schedule(enabled: Boolean) {
        job?.cancel()
        job = null
        val check = alertCheck ?: return
        if (!enabled) return
        job = scope.launch {
            while (isActive) {
                delay(INTERVAL_MS)
                runCatching { check().run() }
            }
        }
    }
}

actual fun createWeatherCheckScheduler(platformContext: PlatformContext): WeatherCheckScheduler = object : WeatherCheckScheduler {
    override fun schedule(enabled: Boolean) = DesktopWeatherRefresh.schedule(enabled)
}
