package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.weather.domain.WeatherNoticePreferences
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferencesRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The notification switches out of the weather app's preferences, for Settings → Notifications.
 * A change does what the weather app's own settings do with one: the background check is started
 * or stopped to match, and switched on, a look at the forecast goes out now rather than at the
 * next check. That look outlives the Settings page, so it runs in the app's scope.
 */
@Inject
@ContributesBinding(AppScope::class)
class WeatherNoticePreferencesImpl(
    private val preferences: WeatherPreferencesRepository,
    private val alertCheck: WeatherAlertCheck,
    private val appScope: CoroutineScope,
) : WeatherNoticePreferences {
    override fun observe(): Flow<WeatherNoticeSettings> = preferences.observe().map { it.notices }.distinctUntilChanged()

    override suspend fun update(change: (WeatherNoticeSettings) -> WeatherNoticeSettings) {
        preferences.update { it.copy(notices = change(it.notices)) }
        alertCheck.syncSchedule()
        if (observe().first().enabled) appScope.launch { alertCheck.run() }
    }
}
