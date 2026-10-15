package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier

/** Each platform's way of posting a weather notification; see `WeatherNotifier.<platform>.kt`. */
expect fun createWeatherNotifier(platformContext: PlatformContext): WeatherNotifier

/** Each platform's way of checking the forecast while the app is closed. */
expect fun createWeatherCheckScheduler(platformContext: PlatformContext): WeatherCheckScheduler
