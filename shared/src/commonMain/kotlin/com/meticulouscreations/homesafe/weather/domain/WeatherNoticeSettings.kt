package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow

/** Which of the weather app's notifications are wanted. All on to begin with; [enabled] is the master switch. */
@Immutable
data class WeatherNoticeSettings(
    val enabled: Boolean = true,
    /** "Rain starting in about 20 min", from the quarter-hour forecast. */
    val precipitationSoon: Boolean = true,
    /** The morning's look at today and the evening's at tomorrow, sent only when there is something to say. */
    val dailyOutlook: Boolean = true,
    /** Government warnings for the place. */
    val severeAlerts: Boolean = true,
    /** Wind, heat, cold, strong sun and bad air, as part of the daily outlook. */
    val extremes: Boolean = true,
)

/**
 * The weather app's notification switches, for Settings → Notifications; the weather app's own
 * settings change the same ones. A change also starts or stops the background check that sends them.
 */
interface WeatherNoticePreferences {
    fun observe(): Flow<WeatherNoticeSettings>

    suspend fun update(change: (WeatherNoticeSettings) -> WeatherNoticeSettings)
}
