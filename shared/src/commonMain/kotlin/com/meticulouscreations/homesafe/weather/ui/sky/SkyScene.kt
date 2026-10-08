package com.meticulouscreations.homesafe.weather.ui.sky

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.meticulouscreations.homesafe.weather.domain.Astronomy
import com.meticulouscreations.homesafe.weather.domain.CurrentConditions
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.Precipitation
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import kotlin.math.PI
import kotlin.math.sin

/**
 * A sky to draw, as the handful of numbers the shaders run on. Everything is continuous (how
 * much cloud, how hard the rain) so one scene can be faded into another; the sun and moon are
 * placed where they really are for the place and the minute.
 */
@Immutable
data class SkyScene(
    /** Degrees above the horizon; negative once it has set. */
    val sunAltitude: Float,
    /** Where the sun is drawn, as fractions of the sky's width and height (y down). */
    val sunX: Float,
    val sunY: Float,
    val moonAltitude: Float,
    val moonX: Float,
    val moonY: Float,
    /** 0 new, 0.5 full, 1 new again. */
    val moonCycle: Float,
    /** 0 a clear sky, 1 a closed deck. */
    val cloudCover: Float,
    /** How dark and heavy the cloud is: 0 fair-weather white, 1 a thunderhead. */
    val storm: Float = 0f,
    val rain: Float = 0f,
    val snow: Float = 0f,
    val fog: Float = 0f,
    /** Above zero, lightning strikes now and then. */
    val thunder: Float = 0f,
    /** Which way and how hard the wind carries things across the screen: -1 hard left to 1 hard right. */
    val wind: Float = 0.15f,
) {
    companion object {
        fun of(current: CurrentConditions, latitude: Double, longitude: Double, epochSeconds: Long = current.epochSeconds): SkyScene = of(
            kind = current.kind,
            cloudCoverPercent = current.cloudCoverPercent,
            windKmh = current.windKmh,
            windDirectionDeg = current.windDirectionDeg,
            visibilityM = current.visibilityM,
            latitude = latitude,
            longitude = longitude,
            epochSeconds = epochSeconds,
        )

        fun of(hour: HourForecast, latitude: Double, longitude: Double, epochSeconds: Long = hour.epochSeconds + 1800): SkyScene = of(
            kind = hour.kind,
            cloudCoverPercent = hour.cloudCoverPercent,
            windKmh = hour.windKmh,
            windDirectionDeg = hour.windDirectionDeg,
            visibilityM = hour.visibilityM,
            latitude = latitude,
            longitude = longitude,
            epochSeconds = epochSeconds,
        )

        /** The sky for [kind] at a place and instant. [cloudCoverPercent] is the measured cover, or null to go by the kind alone. */
        fun of(
            kind: WeatherKind,
            cloudCoverPercent: Int?,
            windKmh: Double,
            windDirectionDeg: Int,
            visibilityM: Double?,
            latitude: Double,
            longitude: Double,
            epochSeconds: Long,
        ): SkyScene {
            val sun = Astronomy.sunPosition(epochSeconds, latitude, longitude)
            val moonPhase = Astronomy.moonPhase(epochSeconds)
            // Near enough for a picture: the moon trails the sun round the sky by its phase, so
            // it stands where the sun stood that fraction of a day ago.
            val moon = Astronomy.sunPosition(epochSeconds - (moonPhase.cycle * 86_400).toLong(), latitude, longitude)
            val (sunX, sunY) = placeInSky(sun.altitudeDeg, sun.azimuthDeg, latitude)
            val (moonX, moonY) = placeInSky(moon.altitudeDeg, moon.azimuthDeg, latitude)

            val measured = cloudCoverPercent?.let { it / 100f }
            val implied = kind.impliedCloudCover
            val cover = when {
                measured == null -> implied
                kind.isPrecipitation || kind == WeatherKind.OVERCAST || kind == WeatherKind.FOG -> maxOf(measured, implied)
                else -> measured * 0.65f + implied * 0.35f
            }
            val rain = when (kind.precipitation) {
                Precipitation.RAIN -> kind.intensity
                Precipitation.STORM -> 0.75f + 0.25f * kind.intensity
                Precipitation.MIX -> 0.5f * kind.intensity + 0.15f
                else -> 0f
            }
            val snow = when (kind.precipitation) {
                Precipitation.SNOW -> kind.intensity
                Precipitation.MIX -> 0.45f
                else -> 0f
            }
            val murk = visibilityM?.let { (1f - (it / 6_000.0).toFloat()).coerceIn(0f, 1f) * 0.5f } ?: 0f
            val fog = maxOf(if (kind == WeatherKind.FOG) 0.85f else 0f, murk, rain * 0.24f, snow * 0.34f)
            val storm = when {
                kind.isStorm -> 1f
                kind.precipitation == Precipitation.RAIN -> 0.3f + 0.45f * kind.intensity
                kind.isPrecipitation -> 0.2f + 0.2f * kind.intensity
                kind == WeatherKind.OVERCAST -> 0.12f
                else -> 0f
            }
            // The wind is named for where it comes from: a westerly carries things east, to the right.
            val toTheRight = ((windDirectionDeg % 360) + 360) % 360 in 180..359
            val strength = (windKmh / 55.0).toFloat().coerceIn(0.06f, 1f)
            return SkyScene(
                sunAltitude = sun.altitudeDeg.toFloat(),
                sunX = sunX,
                sunY = sunY,
                moonAltitude = moon.altitudeDeg.toFloat(),
                moonX = moonX,
                moonY = moonY,
                moonCycle = moonPhase.cycle.toFloat(),
                cloudCover = cover.coerceIn(0f, 1f),
                storm = storm,
                rain = rain,
                snow = snow,
                fog = fog.coerceIn(0f, 1f),
                thunder = if (kind.isStorm) 1f else 0f,
                wind = if (toTheRight) strength else -strength,
            )
        }

        /**
         * Where on the screen something at [altitudeDeg] and [azimuthDeg] goes. The screen looks
         * toward the sun's side of the sky (south from the northern hemisphere), so it rises on
         * the left and sets on the right; the arc is kept to the right of centre, clear of the
         * temperature that is written top left.
         */
        internal fun placeInSky(altitudeDeg: Double, azimuthDeg: Double, latitude: Double): Pair<Float, Float> {
            val fromMeridian = if (latitude >= 0) azimuthDeg - 180.0 else -(if (azimuthDeg > 180) azimuthDeg - 360.0 else azimuthDeg)
            val x = (0.66 + fromMeridian / 90.0 * 0.38).coerceIn(-0.15, 1.2)
            val y = 0.58 - sin(altitudeDeg.coerceIn(-18.0, 90.0) * PI / 180.0) * 0.5
            return x.toFloat() to y.toFloat()
        }
    }
}

/**
 * The colours of a [SkyScene]: what the shaders are handed. Worked out here rather than in the
 * shader because it is art direction, a table of skies by the sun's height, and a table is
 * easier to tune and to test in Kotlin.
 */
@Immutable
data class SkyPalette(
    val zenith: Color,
    val horizon: Color,
    /** The colour pooled round the sun where it nears the horizon. */
    val glow: Color,
    val sunColor: Color,
    val cloudLit: Color,
    val cloudShade: Color,
    /** The light rain and snow are seen by. */
    val precipLight: Color,
    /** How much of the sun's disc shows, 0–1; cloud and fog take it away before they take its light. */
    val sunDisc: Float,
    val moon: Float,
    val stars: Float,
) {
    companion object {
        fun of(scene: SkyScene): SkyPalette {
            val altitude = scene.sunAltitude
            val key = keyAt(altitude)
            val daylight = smooth(-8f, 10f, altitude)
            // A heavy sky takes the colour out of the air as well as covering it.
            val grey = maxOf(smooth(0.5f, 1f, scene.cloudCover), scene.fog * 0.9f)
            val overcastZenith = mix(Color(0xFF090A0E), Color(0xFF56636F), daylight)
            val overcastHorizon = mix(Color(0xFF14161C), Color(0xFFA5ADB4), daylight)
            val stormZenith = mix(Color(0xFF050608), Color(0xFF262E38), daylight)
            val stormHorizon = mix(Color(0xFF0D0F13), Color(0xFF57626C), daylight)
            val zenith = mix(mix(key.zenith, overcastZenith, grey), stormZenith, grey * scene.storm * 0.85f)
            val horizon = mix(mix(key.horizon, overcastHorizon, grey), stormHorizon, grey * scene.storm * 0.85f)

            val warm = 1f - smooth(1f, 16f, altitude)
            val dayLit = mix(Color.White, Color(0xFFFFC29A), warm * 0.8f)
            val lit = scale(mix(Color(0xFF242A3A), dayLit, daylight), 1f - 0.3f * scene.storm)
            val fairShade = mix(mix(Color(0xFF8B9BB4), Color(0xFF6A5A7C), warm), Color(0xFF6B7480), grey)
            val shade = mix(Color(0xFF0A0C13), mix(fairShade, Color(0xFF2B3037), scene.storm * 0.8f), daylight)

            return SkyPalette(
                zenith = zenith,
                horizon = horizon,
                glow = scale(key.glow, (1f - 0.85f * grey)),
                sunColor = scale(key.sun, (1f - 0.55f * grey) * smooth(-4f, 0f, altitude)),
                cloudLit = lit,
                cloudShade = shade,
                precipLight = mix(Color(0xFF6C7890), Color(0xFFDDE6F0), daylight),
                sunDisc = smooth(-1.6f, 0.6f, altitude) * (1f - smooth(0.5f, 0.9f, scene.cloudCover)) * (1f - scene.fog),
                moon = smooth(-2f, 5f, scene.moonAltitude) * (1f - smooth(-7f, 1f, altitude)) * (1f - 0.92f * smooth(0.55f, 0.95f, scene.cloudCover)) * (1f - scene.fog),
                stars = (1f - smooth(-13f, -4f, altitude)) * (1f - smooth(0.25f, 0.85f, scene.cloudCover)) * (1f - scene.fog),
            )
        }

        private class Key(val altitude: Float, val zenith: Color, val horizon: Color, val glow: Color, val sun: Color)

        /** Clear skies by the sun's height: deep night, the blue hour, the last light, the golden hour, and day. */
        private val KEYS = listOf(
            Key(-18f, Color(0xFF02030A), Color(0xFF080C1B), Color(0xFF000000), Color(0xFF000000)),
            Key(-12f, Color(0xFF040716), Color(0xFF0E1530), Color(0xFF05050E), Color(0xFF000000)),
            Key(-6f, Color(0xFF0A1236), Color(0xFF41386A), Color(0xFF8C4030), Color(0xFF000000)),
            Key(-2f, Color(0xFF17265E), Color(0xFFC97A6E), Color(0xFFF2742E), Color(0xFFFF7A2A)),
            Key(2f, Color(0xFF28478F), Color(0xFFF2A873), Color(0xFFE67F33), Color(0xFFFF9A4D)),
            Key(8f, Color(0xFF2B62B8), Color(0xFFD9CDB8), Color(0xFF99703F), Color(0xFFFFD199)),
            // By day the foot of the sky is kept a real blue, not the white of a true horizon: this
            // is a sky looked up into, and white lettering has to be read against all of it.
            Key(20f, Color(0xFF1B62CC), Color(0xFF72B2EE), Color(0xFF33362E), Color(0xFFFFF2D9)),
            Key(50f, Color(0xFF155AC6), Color(0xFF63A8EC), Color(0xFF262B2B), Color(0xFFFFFAED)),
        )

        private fun keyAt(altitude: Float): Key {
            val a = altitude.coerceIn(KEYS.first().altitude, KEYS.last().altitude)
            val i = KEYS.indexOfLast { it.altitude <= a }.coerceIn(0, KEYS.lastIndex - 1)
            val from = KEYS[i]
            val to = KEYS[i + 1]
            val t = (a - from.altitude) / (to.altitude - from.altitude)
            return Key(a, mix(from.zenith, to.zenith, t), mix(from.horizon, to.horizon, t), mix(from.glow, to.glow, t), mix(from.sun, to.sun, t))
        }

        private fun smooth(from: Float, to: Float, value: Float): Float {
            val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun mix(a: Color, b: Color, t: Float): Color {
            val k = t.coerceIn(0f, 1f)
            return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k)
        }

        private fun scale(c: Color, k: Float): Color = Color(c.red * k.coerceIn(0f, 1f), c.green * k.coerceIn(0f, 1f), c.blue * k.coerceIn(0f, 1f))
    }
}
