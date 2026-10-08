package com.meticulouscreations.homesafe.weather.domain

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_kind_clear_night
import homesafe.shared.generated.resources.weather_kind_mostly_clear_night
import homesafe.shared.generated.resources.weather_kind_mostly_sunny
import homesafe.shared.generated.resources.weather_kind_overcast
import homesafe.shared.generated.resources.weather_kind_rain
import homesafe.shared.generated.resources.weather_kind_sunny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeatherKindTest {

    @Test
    fun everyDocumentedWmoCodeMapsToItsKind() {
        val expected = mapOf(
            0 to WeatherKind.CLEAR,
            1 to WeatherKind.MOSTLY_CLEAR,
            2 to WeatherKind.PARTLY_CLOUDY,
            3 to WeatherKind.OVERCAST,
            45 to WeatherKind.FOG,
            48 to WeatherKind.FOG,
            51 to WeatherKind.DRIZZLE,
            53 to WeatherKind.DRIZZLE,
            55 to WeatherKind.DRIZZLE,
            56 to WeatherKind.FREEZING_DRIZZLE,
            57 to WeatherKind.FREEZING_DRIZZLE,
            61 to WeatherKind.LIGHT_RAIN,
            63 to WeatherKind.RAIN,
            65 to WeatherKind.HEAVY_RAIN,
            66 to WeatherKind.FREEZING_RAIN,
            67 to WeatherKind.FREEZING_RAIN,
            71 to WeatherKind.LIGHT_SNOW,
            73 to WeatherKind.SNOW,
            75 to WeatherKind.HEAVY_SNOW,
            77 to WeatherKind.SNOW_GRAINS,
            80 to WeatherKind.LIGHT_SHOWERS,
            81 to WeatherKind.SHOWERS,
            82 to WeatherKind.HEAVY_SHOWERS,
            85 to WeatherKind.SNOW_SHOWERS,
            86 to WeatherKind.SNOW_SHOWERS,
            95 to WeatherKind.THUNDERSTORM,
            96 to WeatherKind.HAIL_STORM,
            99 to WeatherKind.HAIL_STORM,
        )
        expected.forEach { (code, kind) -> assertEquals(kind, WeatherKind.fromCode(code), "WMO code $code") }
    }

    @Test
    fun anUnknownCodeReadsAsOvercast() {
        listOf(-1, 4, 10, 44, 60, 100, 1_000).forEach { assertEquals(WeatherKind.OVERCAST, WeatherKind.fromCode(it), "code $it") }
    }

    @Test
    fun everyKindIsReachableFromSomeCode() {
        val reached = (0..99).map { WeatherKind.fromCode(it) }.toSet()
        assertEquals(WeatherKind.entries.toSet(), reached)
    }

    @Test
    fun sunnyByDayIsClearByNight() {
        assertEquals(Res.string.weather_kind_sunny, WeatherKind.CLEAR.label(isDay = true))
        assertEquals(Res.string.weather_kind_clear_night, WeatherKind.CLEAR.label(isDay = false))
        assertEquals(Res.string.weather_kind_mostly_sunny, WeatherKind.MOSTLY_CLEAR.label(isDay = true))
        assertEquals(Res.string.weather_kind_mostly_clear_night, WeatherKind.MOSTLY_CLEAR.label(isDay = false))
    }

    @Test
    fun theLabelDefaultsToDay() {
        assertEquals(Res.string.weather_kind_sunny, WeatherKind.CLEAR.label())
    }

    @Test
    fun kindsWithoutANightWordReadTheSameEitherWay() {
        assertEquals(Res.string.weather_kind_overcast, WeatherKind.OVERCAST.label(isDay = false))
        assertEquals(Res.string.weather_kind_rain, WeatherKind.RAIN.label(isDay = false))
        WeatherKind.entries.filter { it != WeatherKind.CLEAR && it != WeatherKind.MOSTLY_CLEAR }.forEach {
            assertEquals(it.label(isDay = true), it.label(isDay = false), "$it")
        }
    }

    @Test
    fun rainFamilyFallsAsRain() {
        listOf(
            WeatherKind.DRIZZLE,
            WeatherKind.LIGHT_RAIN,
            WeatherKind.RAIN,
            WeatherKind.HEAVY_RAIN,
            WeatherKind.LIGHT_SHOWERS,
            WeatherKind.SHOWERS,
            WeatherKind.HEAVY_SHOWERS,
        ).forEach {
            assertEquals(Precipitation.RAIN, it.precipitation, "$it")
            assertTrue(it.isPrecipitation)
            assertFalse(it.isSnow)
            assertFalse(it.isStorm)
        }
    }

    @Test
    fun snowFamilyFallsAsSnow() {
        listOf(WeatherKind.LIGHT_SNOW, WeatherKind.SNOW, WeatherKind.HEAVY_SNOW, WeatherKind.SNOW_GRAINS, WeatherKind.SNOW_SHOWERS).forEach {
            assertEquals(Precipitation.SNOW, it.precipitation, "$it")
            assertTrue(it.isSnow)
        }
    }

    @Test
    fun freezingKindsAreAMix() {
        assertEquals(Precipitation.MIX, WeatherKind.FREEZING_DRIZZLE.precipitation)
        assertEquals(Precipitation.MIX, WeatherKind.FREEZING_RAIN.precipitation)
    }

    @Test
    fun stormsAreThunderstormsAndHail() {
        assertEquals(Precipitation.STORM, WeatherKind.THUNDERSTORM.precipitation)
        assertEquals(Precipitation.STORM, WeatherKind.HAIL_STORM.precipitation)
        assertTrue(WeatherKind.THUNDERSTORM.isStorm)
        assertTrue(WeatherKind.HAIL_STORM.isStorm)
    }

    @Test
    fun skyKindsFallNothing() {
        listOf(WeatherKind.CLEAR, WeatherKind.MOSTLY_CLEAR, WeatherKind.PARTLY_CLOUDY, WeatherKind.OVERCAST, WeatherKind.FOG).forEach {
            assertEquals(Precipitation.NONE, it.precipitation, "$it")
            assertFalse(it.isPrecipitation)
            assertEquals(0f, it.intensity, "$it")
        }
    }

    @Test
    fun intensityRisesWithinEachFamily() {
        assertTrue(WeatherKind.DRIZZLE.intensity < WeatherKind.LIGHT_RAIN.intensity)
        assertTrue(WeatherKind.LIGHT_RAIN.intensity < WeatherKind.RAIN.intensity)
        assertTrue(WeatherKind.RAIN.intensity < WeatherKind.HEAVY_RAIN.intensity)
        assertTrue(WeatherKind.LIGHT_SNOW.intensity < WeatherKind.SNOW.intensity)
        assertTrue(WeatherKind.SNOW.intensity < WeatherKind.HEAVY_SNOW.intensity)
        assertTrue(WeatherKind.LIGHT_SHOWERS.intensity < WeatherKind.SHOWERS.intensity)
        assertTrue(WeatherKind.SHOWERS.intensity < WeatherKind.HEAVY_SHOWERS.intensity)
        WeatherKind.entries.forEach { assertTrue(it.intensity in 0f..1f, "$it") }
    }

    @Test
    fun impliedCloudCoverRisesFromClearToOvercast() {
        val cover = listOf(WeatherKind.CLEAR, WeatherKind.MOSTLY_CLEAR, WeatherKind.PARTLY_CLOUDY, WeatherKind.FOG, WeatherKind.OVERCAST).map { it.impliedCloudCover }
        assertEquals(cover.sorted(), cover)
        assertEquals(cover.distinct(), cover)
    }

    @Test
    fun precipitatingKindsAreAtLeastMostlyCloudy() {
        WeatherKind.entries.filter { it.isPrecipitation }.forEach {
            assertTrue(it.impliedCloudCover >= 0.85f, "$it implied ${it.impliedCloudCover}")
            assertTrue(it.impliedCloudCover <= 1f, "$it implied ${it.impliedCloudCover}")
        }
    }

    @Test
    fun stormsCloseTheSkyCompletely() {
        assertEquals(1f, WeatherKind.THUNDERSTORM.impliedCloudCover)
        assertEquals(1f, WeatherKind.HAIL_STORM.impliedCloudCover)
    }

    @Test
    fun heavierRainClosesTheSkyMoreThanDrizzle() {
        assertTrue(WeatherKind.HEAVY_RAIN.impliedCloudCover > WeatherKind.DRIZZLE.impliedCloudCover)
    }

    @Test
    fun everyKindsImpliedCoverIsAFraction() {
        WeatherKind.entries.forEach { assertTrue(it.impliedCloudCover in 0f..1f, "$it") }
    }
}
