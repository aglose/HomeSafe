package com.meticulouscreations.homesafe.weather.domain

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_kind_clear_night
import homesafe.shared.generated.resources.weather_kind_drizzle
import homesafe.shared.generated.resources.weather_kind_fog
import homesafe.shared.generated.resources.weather_kind_freezing_drizzle
import homesafe.shared.generated.resources.weather_kind_freezing_rain
import homesafe.shared.generated.resources.weather_kind_hail_storm
import homesafe.shared.generated.resources.weather_kind_heavy_rain
import homesafe.shared.generated.resources.weather_kind_heavy_showers
import homesafe.shared.generated.resources.weather_kind_heavy_snow
import homesafe.shared.generated.resources.weather_kind_light_rain
import homesafe.shared.generated.resources.weather_kind_light_showers
import homesafe.shared.generated.resources.weather_kind_light_snow
import homesafe.shared.generated.resources.weather_kind_mostly_clear_night
import homesafe.shared.generated.resources.weather_kind_mostly_sunny
import homesafe.shared.generated.resources.weather_kind_overcast
import homesafe.shared.generated.resources.weather_kind_partly_cloudy
import homesafe.shared.generated.resources.weather_kind_rain
import homesafe.shared.generated.resources.weather_kind_showers
import homesafe.shared.generated.resources.weather_kind_snow
import homesafe.shared.generated.resources.weather_kind_snow_grains
import homesafe.shared.generated.resources.weather_kind_snow_showers
import homesafe.shared.generated.resources.weather_kind_sunny
import homesafe.shared.generated.resources.weather_kind_thunderstorm
import org.jetbrains.compose.resources.StringResource

/** What falls, when something does. */
enum class Precipitation { NONE, RAIN, SNOW, MIX, STORM }

/**
 * The sky in a word, from the WMO weather code every forecast model reports (the table is
 * WMO 4677, as Open-Meteo documents it). [intensity] is 0–1 within the kind's own family: how
 * hard it rains or snows, which is what the sky's shaders are driven by.
 */
enum class WeatherKind(
    private val label: StringResource,
    val precipitation: Precipitation = Precipitation.NONE,
    val intensity: Float = 0f,
    private val nightLabel: StringResource = label,
) {
    CLEAR(Res.string.weather_kind_sunny, nightLabel = Res.string.weather_kind_clear_night),
    MOSTLY_CLEAR(Res.string.weather_kind_mostly_sunny, nightLabel = Res.string.weather_kind_mostly_clear_night),
    PARTLY_CLOUDY(Res.string.weather_kind_partly_cloudy),
    OVERCAST(Res.string.weather_kind_overcast),
    FOG(Res.string.weather_kind_fog),
    DRIZZLE(Res.string.weather_kind_drizzle, Precipitation.RAIN, 0.18f),
    FREEZING_DRIZZLE(Res.string.weather_kind_freezing_drizzle, Precipitation.MIX, 0.22f),
    LIGHT_RAIN(Res.string.weather_kind_light_rain, Precipitation.RAIN, 0.35f),
    RAIN(Res.string.weather_kind_rain, Precipitation.RAIN, 0.6f),
    HEAVY_RAIN(Res.string.weather_kind_heavy_rain, Precipitation.RAIN, 1f),
    FREEZING_RAIN(Res.string.weather_kind_freezing_rain, Precipitation.MIX, 0.6f),
    LIGHT_SNOW(Res.string.weather_kind_light_snow, Precipitation.SNOW, 0.3f),
    SNOW(Res.string.weather_kind_snow, Precipitation.SNOW, 0.6f),
    HEAVY_SNOW(Res.string.weather_kind_heavy_snow, Precipitation.SNOW, 1f),
    SNOW_GRAINS(Res.string.weather_kind_snow_grains, Precipitation.SNOW, 0.25f),
    LIGHT_SHOWERS(Res.string.weather_kind_light_showers, Precipitation.RAIN, 0.4f),
    SHOWERS(Res.string.weather_kind_showers, Precipitation.RAIN, 0.7f),
    HEAVY_SHOWERS(Res.string.weather_kind_heavy_showers, Precipitation.RAIN, 1f),
    SNOW_SHOWERS(Res.string.weather_kind_snow_showers, Precipitation.SNOW, 0.6f),
    THUNDERSTORM(Res.string.weather_kind_thunderstorm, Precipitation.STORM, 0.85f),
    HAIL_STORM(Res.string.weather_kind_hail_storm, Precipitation.STORM, 1f),
    ;

    /** "Sunny" by day is "Clear" by night; the rest read the same either way. */
    fun label(isDay: Boolean = true): StringResource = if (isDay) label else nightLabel

    val isPrecipitation: Boolean get() = precipitation != Precipitation.NONE

    val isSnow: Boolean get() = precipitation == Precipitation.SNOW

    val isStorm: Boolean get() = precipitation == Precipitation.STORM

    /** How much of the sky the kind itself implies is covered, for when no cloud cover was measured. */
    val impliedCloudCover: Float
        get() = when (this) {
            CLEAR -> 0.02f
            MOSTLY_CLEAR -> 0.2f
            PARTLY_CLOUDY -> 0.5f
            FOG -> 0.75f
            OVERCAST -> 0.95f
            else -> if (isStorm) 1f else 0.85f + 0.15f * intensity
        }

    companion object {
        /** An unknown code reads as overcast: the dullest guess, and never a promise of sun. */
        fun fromCode(code: Int): WeatherKind = when (code) {
            0 -> CLEAR
            1 -> MOSTLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57 -> FREEZING_DRIZZLE
            61 -> LIGHT_RAIN
            63 -> RAIN
            65 -> HEAVY_RAIN
            66, 67 -> FREEZING_RAIN
            71 -> LIGHT_SNOW
            73 -> SNOW
            75 -> HEAVY_SNOW
            77 -> SNOW_GRAINS
            80 -> LIGHT_SHOWERS
            81 -> SHOWERS
            82 -> HEAVY_SHOWERS
            85, 86 -> SNOW_SHOWERS
            95 -> THUNDERSTORM
            96, 99 -> HAIL_STORM
            else -> OVERCAST
        }
    }
}
