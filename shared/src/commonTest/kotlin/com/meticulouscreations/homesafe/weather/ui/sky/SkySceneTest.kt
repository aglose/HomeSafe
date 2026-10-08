package com.meticulouscreations.homesafe.weather.ui.sky

import androidx.compose.ui.graphics.Color
import com.meticulouscreations.homesafe.weather.WeatherData
import com.meticulouscreations.homesafe.weather.domain.Astronomy
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SkySceneTest {

    private val lat = 45.5234
    private val lon = -122.6762

    /** Solar noon in Portland on 9 October 2025 and local midnight after it. */
    private val noon = Instant.parse("2025-10-09T19:58:00Z").epochSeconds
    private val midnight = Instant.parse("2025-10-10T07:00:00Z").epochSeconds

    private fun scene(
        kind: WeatherKind,
        cloud: Int? = null,
        wind: Double = 10.0,
        direction: Int = 270,
        visibility: Double? = null,
        epoch: Long = noon,
        latitude: Double = lat,
    ) = SkyScene.of(kind, cloud, wind, direction, visibility, latitude, lon, epoch)

    // ---- The sun and moon ---------------------------------------------------------------------

    @Test
    fun theSunIsHighAtNoonAndWellDownAtMidnight() {
        assertTrue(scene(WeatherKind.CLEAR).sunAltitude > 30f)
        assertTrue(scene(WeatherKind.CLEAR, epoch = midnight).sunAltitude < -30f)
    }

    @Test
    fun theSunIsDrawnHigherInTheSkyAtNoonThanAtMidnight() {
        assertTrue(scene(WeatherKind.CLEAR).sunY < scene(WeatherKind.CLEAR, epoch = midnight).sunY)
    }

    @Test
    fun theMoonsCycleIsTheAstronomysAtThatInstant() {
        assertEquals(Astronomy.moonPhase(noon).cycle.toFloat(), scene(WeatherKind.CLEAR).moonCycle, 1e-6f)
    }

    // ---- Cloud, rain, snow, fog, storm ---------------------------------------------------------

    @Test
    fun aThunderstormIsAllStormAndHeavyRainWithThunder() {
        val s = scene(WeatherKind.THUNDERSTORM, cloud = 100)
        assertEquals(1f, s.storm)
        assertEquals(1f, s.thunder)
        assertEquals(0.9625f, s.rain, 1e-4f)
        assertEquals(0f, s.snow)
        assertEquals(1f, s.cloudCover)
        // Rain hangs a little mist in the air.
        assertEquals(0.231f, s.fog, 1e-3f)
    }

    @Test
    fun rainIsScaledByItsKindsIntensityWithNoThunder() {
        val s = scene(WeatherKind.RAIN, cloud = 100)
        assertEquals(0.6f, s.rain, 1e-4f)
        assertEquals(0f, s.snow)
        assertEquals(0.57f, s.storm, 1e-4f)
        assertEquals(0f, s.thunder)
        assertEquals(0.144f, s.fog, 1e-3f)
    }

    @Test
    fun heavierRainIsStormierAndWetter() {
        val light = scene(WeatherKind.DRIZZLE, cloud = 100)
        val heavy = scene(WeatherKind.HEAVY_RAIN, cloud = 100)
        assertTrue(heavy.rain > light.rain)
        assertTrue(heavy.storm > light.storm)
    }

    @Test
    fun snowIsSnowNotRain() {
        val s = scene(WeatherKind.SNOW, cloud = 100)
        assertEquals(0.6f, s.snow, 1e-4f)
        assertEquals(0f, s.rain)
        assertEquals(0.32f, s.storm, 1e-4f)
        assertEquals(0.204f, s.fog, 1e-3f)
    }

    @Test
    fun aWinterMixIsBothRainAndSnow() {
        val s = scene(WeatherKind.FREEZING_RAIN, cloud = 100)
        assertEquals(0.45f, s.rain, 1e-4f)
        assertEquals(0.45f, s.snow, 1e-4f)
    }

    @Test
    fun fogIsFogAndNothingFalls() {
        val s = scene(WeatherKind.FOG, cloud = 20)
        assertEquals(0.85f, s.fog, 1e-6f)
        assertEquals(0f, s.rain)
        assertEquals(0f, s.snow)
        assertEquals(0f, s.storm)
        // Whatever the gauge says, fog implies a low grey sky.
        assertEquals(0.75f, s.cloudCover, 1e-6f)
    }

    @Test
    fun aClearSkyIsEmptyOfEverythingButAHintOfCloud() {
        val s = scene(WeatherKind.CLEAR, cloud = 0)
        assertEquals(0.007f, s.cloudCover, 1e-4f)
        assertEquals(0f, s.rain)
        assertEquals(0f, s.snow)
        assertEquals(0f, s.fog)
        assertEquals(0f, s.storm)
        assertEquals(0f, s.thunder)
    }

    @Test
    fun overcastDarkensTheCloudALittle() {
        assertEquals(0.12f, scene(WeatherKind.OVERCAST, cloud = 100).storm, 1e-6f)
    }

    @Test
    fun withoutAMeasuredCoverTheKindAloneDecidesIt() {
        assertEquals(WeatherKind.CLEAR.impliedCloudCover, scene(WeatherKind.CLEAR).cloudCover, 1e-6f)
        assertEquals(WeatherKind.OVERCAST.impliedCloudCover, scene(WeatherKind.OVERCAST).cloudCover, 1e-6f)
    }

    @Test
    fun forSkyKindsTheMeasuredCoverCountsForTwoThirdsAndTheKindForOne() {
        // 0.8 * 0.65 + 0.5 * 0.35.
        assertEquals(0.695f, scene(WeatherKind.PARTLY_CLOUDY, cloud = 80).cloudCover, 1e-4f)
    }

    @Test
    fun forWeatherKindsTheGreaterOfMeasuredAndImpliedCoverWins() {
        assertEquals(1f, scene(WeatherKind.RAIN, cloud = 100).cloudCover)
        assertEquals(WeatherKind.RAIN.impliedCloudCover, scene(WeatherKind.RAIN, cloud = 10).cloudCover, 1e-6f)
    }

    @Test
    fun poorVisibilityAddsMurkToAClearSky() {
        assertEquals(0.45f, scene(WeatherKind.CLEAR, visibility = 600.0).fog, 1e-4f)
        assertEquals(0.25f, scene(WeatherKind.CLEAR, visibility = 3_000.0).fog, 1e-4f)
        assertEquals(0f, scene(WeatherKind.CLEAR, visibility = 6_000.0).fog)
        assertEquals(0f, scene(WeatherKind.CLEAR, visibility = 24_000.0).fog)
    }

    @Test
    fun everyKindMakesASceneWithinRange() {
        WeatherKind.entries.forEach { kind ->
            listOf<Int?>(null, 0, 50, 100).forEach { cloud ->
                val s = scene(kind, cloud = cloud, visibility = 1_000.0)
                listOf(s.cloudCover, s.storm, s.rain, s.snow, s.fog, s.thunder).forEach { assertTrue(it in 0f..1f, "$kind $cloud: $it") }
                assertTrue(s.wind in -1f..1f)
            }
        }
    }

    // ---- Wind ---------------------------------------------------------------------------------

    @Test
    fun aWesterlyWindCarriesThingsToTheRightAndAnEasterlyToTheLeft() {
        assertTrue(scene(WeatherKind.CLEAR, direction = 270).wind > 0f)
        assertTrue(scene(WeatherKind.CLEAR, direction = 90).wind < 0f)
        assertTrue(scene(WeatherKind.CLEAR, direction = 0).wind < 0f)
        assertTrue(scene(WeatherKind.CLEAR, direction = 180).wind > 0f)
        assertTrue(scene(WeatherKind.CLEAR, direction = 359).wind > 0f)
        assertTrue(scene(WeatherKind.CLEAR, direction = 360).wind < 0f)
        assertTrue(scene(WeatherKind.CLEAR, direction = -90).wind > 0f)
    }

    @Test
    fun theWindsStrengthIsItsSpeedOverFiftyFiveKilometresAnHourWithinLimits() {
        assertEquals(0.5f, scene(WeatherKind.CLEAR, wind = 27.5).wind, 1e-4f)
        assertEquals(1f, scene(WeatherKind.CLEAR, wind = 55.0).wind, 1e-4f)
        assertEquals(1f, scene(WeatherKind.CLEAR, wind = 150.0).wind, 1e-4f)
        // Never quite still: the sky always drifts a little.
        assertEquals(0.06f, scene(WeatherKind.CLEAR, wind = 0.0).wind, 1e-4f)
        assertEquals(-0.06f, scene(WeatherKind.CLEAR, wind = 0.0, direction = 90).wind, 1e-4f)
    }

    // ---- Constructors from forecasts ------------------------------------------------------------

    @Test
    fun aSceneFromTheCurrentConditionsUsesTheirOwnTimeByDefault() {
        val current = WeatherData.current(noon, weatherCode = 63)
        val expected = SkyScene.of(WeatherKind.RAIN, current.cloudCoverPercent, current.windKmh, current.windDirectionDeg, current.visibilityM, lat, lon, noon)
        assertEquals(expected, SkyScene.of(current, lat, lon))
    }

    @Test
    fun aSceneFromTheCurrentConditionsCanBeReadAtAnotherTime() {
        val current = WeatherData.current(noon)
        assertEquals(SkyScene.of(current, lat, lon, midnight).sunAltitude, scene(WeatherKind.OVERCAST, epoch = midnight).sunAltitude)
    }

    @Test
    fun aSceneFromAnHourIsTakenAtTheMiddleOfTheHour() {
        val hour = WeatherData.hour(noon - 1_800, weatherCode = 61)
        val expected = SkyScene.of(WeatherKind.LIGHT_RAIN, hour.cloudCoverPercent, hour.windKmh, hour.windDirectionDeg, hour.visibilityM, lat, lon, noon)
        assertEquals(expected, SkyScene.of(hour, lat, lon))
    }

    // ---- placeInSky ---------------------------------------------------------------------------

    @Test
    fun theSunAtDueSouthStandsRightOfCentre() {
        val (x, y) = SkyScene.placeInSky(altitudeDeg = 0.0, azimuthDeg = 180.0, latitude = 45.0)
        assertEquals(0.66f, x, 1e-5f)
        assertEquals(0.58f, y, 1e-5f)
    }

    @Test
    fun theSunRisesOnTheLeftAndSetsOnTheRight() {
        val rising = SkyScene.placeInSky(10.0, 90.0, 45.0).first
        val noon = SkyScene.placeInSky(60.0, 180.0, 45.0).first
        val setting = SkyScene.placeInSky(10.0, 270.0, 45.0).first
        assertTrue(rising < noon && noon < setting)
        assertEquals(0.28f, rising, 1e-5f)
        assertEquals(1.04f, setting, 1e-5f)
    }

    @Test
    fun higherInTheSkyIsHigherOnTheScreen() {
        val low = SkyScene.placeInSky(5.0, 180.0, 45.0).second
        val high = SkyScene.placeInSky(70.0, 180.0, 45.0).second
        assertTrue(high < low)
        assertEquals(0.08f, SkyScene.placeInSky(90.0, 180.0, 45.0).second, 1e-5f)
    }

    @Test
    fun belowTheHorizonItSinksButOnlyToEighteenDegrees() {
        val set = SkyScene.placeInSky(-10.0, 180.0, 45.0).second
        assertTrue(set > 0.58f)
        assertEquals(SkyScene.placeInSky(-18.0, 180.0, 45.0).second, SkyScene.placeInSky(-60.0, 180.0, 45.0).second)
    }

    @Test
    fun theHorizontalPositionIsKeptOnOrNearTheScreen() {
        assertEquals(1.2f, SkyScene.placeInSky(0.0, 359.9, 45.0).first, 1e-5f)
        assertTrue(SkyScene.placeInSky(0.0, 0.0, 45.0).first >= -0.15f)
    }

    @Test
    fun inTheSouthernHemisphereTheSkyIsMirrored() {
        // Facing the sun's side of the sky, the sun still rises on the left and sets on the right.
        listOf(0.0, 30.0, 60.0, 90.0).forEach { d ->
            val north = SkyScene.placeInSky(20.0, 180.0 - d, 45.0)
            val south = SkyScene.placeInSky(20.0, d, -35.0)
            assertEquals(north.first, south.first, 1e-5f, "east of the meridian by $d")
            val northWest = SkyScene.placeInSky(20.0, 180.0 + d, 45.0)
            val southWest = SkyScene.placeInSky(20.0, 360.0 - d, -35.0)
            assertEquals(northWest.first, southWest.first, 1e-5f, "west of the meridian by $d")
        }
    }

    @Test
    fun theSouthernSunAtDueNorthStandsWhereTheNorthernSunDoesAtDueSouth() {
        assertEquals(SkyScene.placeInSky(40.0, 180.0, 45.0), SkyScene.placeInSky(40.0, 0.0, -45.0))
    }

    @Test
    fun aSouthernSceneRisesOnTheLeftToo() {
        // Sydney, a morning in October: the sun is in the north-east.
        val morning = Instant.parse("2025-10-08T21:00:00Z").epochSeconds
        val s = SkyScene.of(WeatherKind.CLEAR, 0, 5.0, 270, null, -33.87, 151.21, morning)
        assertTrue(s.sunAltitude > 0f)
        assertTrue(s.sunX < 0.66f, "sunX ${s.sunX}")
    }

    // ---- SkyPalette ---------------------------------------------------------------------------

    private fun palette(altitude: Float, cover: Float = 0f, storm: Float = 0f, fog: Float = 0f, moonAltitude: Float = 30f) = SkyPalette.of(
        SkyScene(
            sunAltitude = altitude,
            sunX = 0.6f,
            sunY = 0.4f,
            moonAltitude = moonAltitude,
            moonX = 0.5f,
            moonY = 0.3f,
            moonCycle = 0.5f,
            cloudCover = cover,
            storm = storm,
            fog = fog,
        ),
    )

    private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    @Test
    fun starsAppearOnlyAtNight() {
        assertEquals(1f, palette(altitude = -30f).stars, 1e-6f)
        assertEquals(0f, palette(altitude = 40f).stars, 1e-6f)
        val dusk = palette(altitude = -8f).stars
        assertTrue(dusk > 0f && dusk < 1f, "dusk $dusk")
    }

    @Test
    fun starsFadeAsTheSunNearsTheHorizon() {
        val stars = listOf(-30f, -14f, -10f, -6f, -3f, 0f).map { palette(altitude = it).stars }
        assertEquals(stars.sortedDescending(), stars)
    }

    @Test
    fun starsAreGoneUnderOvercastAndFog() {
        assertEquals(0f, palette(altitude = -30f, cover = 1f).stars, 1e-6f)
        assertEquals(0f, palette(altitude = -30f, cover = 0.85f).stars, 1e-6f)
        assertEquals(1f, palette(altitude = -30f, cover = 0.25f).stars, 1e-6f)
        assertEquals(0f, palette(altitude = -30f, fog = 1f).stars, 1e-6f)
    }

    @Test
    fun theSunDiscShowsInAClearDaySky() {
        assertEquals(1f, palette(altitude = 40f).sunDisc, 1e-6f)
    }

    @Test
    fun theSunDiscIsHiddenUnderOvercastAndFogAndBelowTheHorizon() {
        assertEquals(0f, palette(altitude = 40f, cover = 0.9f).sunDisc, 1e-6f)
        assertEquals(0f, palette(altitude = 40f, cover = 1f).sunDisc, 1e-6f)
        assertEquals(0f, palette(altitude = 40f, fog = 1f).sunDisc, 1e-6f)
        assertEquals(0f, palette(altitude = -5f).sunDisc, 1e-6f)
    }

    @Test
    fun theSunDiscFadesAsCloudBuildsUp() {
        val discs = listOf(0f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f).map { palette(altitude = 40f, cover = it).sunDisc }
        assertEquals(discs.sortedDescending(), discs)
        assertTrue(discs[3] in 0.01f..0.99f)
    }

    @Test
    fun theSunsLightIsGoneBelowTheHorizon() {
        val below = palette(altitude = -10f).sunColor
        assertEquals(Color(0f, 0f, 0f), Color(below.red, below.green, below.blue))
    }

    @Test
    fun theMoonShowsAtNightAndNotInTheDay() {
        assertEquals(1f, palette(altitude = -30f, moonAltitude = 30f).moon, 1e-6f)
        assertEquals(0f, palette(altitude = 40f, moonAltitude = 30f).moon, 1e-6f)
        assertEquals(0f, palette(altitude = -30f, moonAltitude = -10f).moon, 1e-6f)
    }

    @Test
    fun cloudHidesMostOfTheMoon() {
        val clear = palette(altitude = -30f).moon
        val overcast = palette(altitude = -30f, cover = 1f).moon
        assertEquals(0.08f, overcast, 1e-4f)
        assertTrue(overcast < clear)
        assertEquals(0f, palette(altitude = -30f, fog = 1f).moon, 1e-6f)
    }

    @Test
    fun theDaySkyIsBrighterThanTheNightSky() {
        val day = palette(altitude = 40f)
        val night = palette(altitude = -30f)
        assertTrue(luminance(day.zenith) > luminance(night.zenith))
        assertTrue(luminance(day.horizon) > luminance(night.horizon))
        assertTrue(luminance(day.cloudLit) > luminance(night.cloudLit))
    }

    @Test
    fun aHeavyStormTakesTheLightOutOfTheSky() {
        val fair = palette(altitude = 40f)
        val storm = palette(altitude = 40f, cover = 1f, storm = 1f)
        assertTrue(luminance(storm.zenith) < luminance(fair.zenith))
        assertTrue(luminance(storm.horizon) < luminance(fair.horizon))
        assertTrue(luminance(storm.cloudLit) < luminance(fair.cloudLit))
        assertTrue(storm.glow.red <= fair.glow.red)
    }

    @Test
    fun everyColourAndAmountIsWithinRangeAcrossTheDayAndEveryWeather() {
        for (altitude in listOf(-60f, -30f, -18f, -12f, -6f, -2f, 0f, 2f, 8f, 20f, 50f, 90f)) {
            for (cover in listOf(0f, 0.4f, 0.7f, 1f)) {
                for (storm in listOf(0f, 0.5f, 1f)) {
                    for (fog in listOf(0f, 0.5f, 1f)) {
                        val p = palette(altitude, cover, storm, fog)
                        val label = "alt $altitude cover $cover storm $storm fog $fog"
                        listOf(p.zenith, p.horizon, p.glow, p.sunColor, p.cloudLit, p.cloudShade, p.precipLight).forEach { c ->
                            assertTrue(c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f, "$label: $c")
                        }
                        listOf(p.sunDisc, p.moon, p.stars).forEach { assertTrue(it in 0f..1f, "$label: $it") }
                    }
                }
            }
        }
    }

    @Test
    fun aSceneFromRealWeatherMakesAPaletteInRange() {
        WeatherKind.entries.forEach { kind ->
            listOf(noon, midnight).forEach { epoch ->
                val p = SkyPalette.of(scene(kind, cloud = 70, epoch = epoch))
                assertTrue(p.sunDisc in 0f..1f && p.moon in 0f..1f && p.stars in 0f..1f, "$kind at $epoch")
                assertTrue(p.zenith.red in 0f..1f && p.horizon.blue in 0f..1f)
            }
        }
    }
}
