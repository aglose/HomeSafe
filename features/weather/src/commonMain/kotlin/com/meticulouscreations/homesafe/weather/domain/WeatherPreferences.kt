package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable

enum class TemperatureUnit { FAHRENHEIT, CELSIUS }

/** Everything that isn't a temperature: wind, rain, distance and pressure. */
enum class MeasureSystem { IMPERIAL, METRIC }

/** How the weather app writes its numbers. Forecasts are held in metric and converted as they are shown. */
@Immutable
data class WeatherUnits(
    val temperature: TemperatureUnit = TemperatureUnit.FAHRENHEIT,
    val measures: MeasureSystem = MeasureSystem.IMPERIAL,
)

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

@Immutable
data class WeatherPreferences(
    val units: WeatherUnits = WeatherUnits(),
    val notices: WeatherNoticeSettings = WeatherNoticeSettings(),
    /** Hold the sky still: one frame of the same scene, for anyone moving pictures bother, or to save the battery. */
    val stillSky: Boolean = false,
    /** The place the app was last showing, to open on again. */
    val selectedPlaceId: String? = null,
    /**
     * Where the phone last was when the app could ask, kept so the background check has
     * somewhere to forecast for when it can't get a fix of its own.
     */
    val lastKnown: Place? = null,
)
