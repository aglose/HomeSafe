package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier

/** No notification surface here this app can post to, and so nothing to schedule a check for. */
actual fun createWeatherNotifier(platformContext: PlatformContext): WeatherNotifier = object : WeatherNotifier {
    override val isSupported = false

    override suspend fun isAllowed() = false

    override fun notify(notification: WeatherNotification) = Unit
}

actual fun createWeatherCheckScheduler(platformContext: PlatformContext): WeatherCheckScheduler = object : WeatherCheckScheduler {
    override fun schedule(enabled: Boolean) = Unit
}
