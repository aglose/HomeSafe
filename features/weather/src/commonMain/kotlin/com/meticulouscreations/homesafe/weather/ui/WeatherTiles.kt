package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.domain.AqiBand
import com.meticulouscreations.homesafe.weather.domain.Astronomy
import com.meticulouscreations.homesafe.weather.domain.MoonPhaseName
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.PressureTrend
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherStory
import com.meticulouscreations.homesafe.weather.ui.shader.MOON_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.weatherShaderOrNull
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_aqi_good
import homesafe.shared.generated.resources.weather_aqi_hazardous
import homesafe.shared.generated.resources.weather_aqi_moderate
import homesafe.shared.generated.resources.weather_aqi_sensitive
import homesafe.shared.generated.resources.weather_aqi_unhealthy
import homesafe.shared.generated.resources.weather_aqi_very_unhealthy
import homesafe.shared.generated.resources.weather_moon_first_quarter
import homesafe.shared.generated.resources.weather_moon_full
import homesafe.shared.generated.resources.weather_moon_last_quarter
import homesafe.shared.generated.resources.weather_moon_new
import homesafe.shared.generated.resources.weather_moon_waning_crescent
import homesafe.shared.generated.resources.weather_moon_waning_gibbous
import homesafe.shared.generated.resources.weather_moon_waxing_crescent
import homesafe.shared.generated.resources.weather_moon_waxing_gibbous
import homesafe.shared.generated.resources.weather_tile_air
import homesafe.shared.generated.resources.weather_tile_air_pm
import homesafe.shared.generated.resources.weather_tile_dew_point
import homesafe.shared.generated.resources.weather_tile_golden_hour
import homesafe.shared.generated.resources.weather_tile_gusts_from
import homesafe.shared.generated.resources.weather_tile_humidity
import homesafe.shared.generated.resources.weather_tile_humidity_dry
import homesafe.shared.generated.resources.weather_tile_humidity_muggy
import homesafe.shared.generated.resources.weather_tile_humidity_oppressive
import homesafe.shared.generated.resources.weather_tile_humidity_pleasant
import homesafe.shared.generated.resources.weather_tile_humidity_sticky
import homesafe.shared.generated.resources.weather_tile_moon
import homesafe.shared.generated.resources.weather_tile_moon_lit
import homesafe.shared.generated.resources.weather_tile_moon_next_full
import homesafe.shared.generated.resources.weather_tile_moon_next_new
import homesafe.shared.generated.resources.weather_tile_precipitation
import homesafe.shared.generated.resources.weather_tile_precipitation_none_ahead
import homesafe.shared.generated.resources.weather_tile_precipitation_today
import homesafe.shared.generated.resources.weather_tile_precipitation_tomorrow
import homesafe.shared.generated.resources.weather_tile_pressure
import homesafe.shared.generated.resources.weather_tile_pressure_falling
import homesafe.shared.generated.resources.weather_tile_pressure_rising
import homesafe.shared.generated.resources.weather_tile_pressure_steady
import homesafe.shared.generated.resources.weather_tile_sun
import homesafe.shared.generated.resources.weather_tile_sun_description
import homesafe.shared.generated.resources.weather_tile_sunrise
import homesafe.shared.generated.resources.weather_tile_sunset
import homesafe.shared.generated.resources.weather_tile_uv
import homesafe.shared.generated.resources.weather_tile_uv_extreme
import homesafe.shared.generated.resources.weather_tile_uv_high
import homesafe.shared.generated.resources.weather_tile_uv_low
import homesafe.shared.generated.resources.weather_tile_uv_moderate
import homesafe.shared.generated.resources.weather_tile_uv_peak
import homesafe.shared.generated.resources.weather_tile_uv_very_high
import homesafe.shared.generated.resources.weather_tile_visibility
import homesafe.shared.generated.resources.weather_tile_visibility_clear
import homesafe.shared.generated.resources.weather_tile_visibility_fog
import homesafe.shared.generated.resources.weather_tile_visibility_haze
import homesafe.shared.generated.resources.weather_tile_visibility_mist
import homesafe.shared.generated.resources.weather_tile_visibility_perfect
import homesafe.shared.generated.resources.weather_tile_wind
import homesafe.shared.generated.resources.weather_tile_wind_breezy
import homesafe.shared.generated.resources.weather_tile_wind_calm
import homesafe.shared.generated.resources.weather_tile_wind_gale
import homesafe.shared.generated.resources.weather_tile_wind_light
import homesafe.shared.generated.resources.weather_tile_wind_windy
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The frame every detail tile shares: its label, one big value (with [note], a word for it,
 * beside it), a line of plain words, and room for a drawing.
 */
@Composable
private fun DetailTile(
    title: String,
    icon: ImageVector,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    visual: (@Composable () -> Unit)? = null,
) {
    WeatherCard(modifier.height(TileHeight), title = title, icon = icon) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = WeatherTheme.type.value, color = WeatherTheme.colors.onSky, maxLines = 1)
            if (note != null) {
                Spacer(Modifier.width(8.dp))
                Text(note, style = WeatherTheme.type.bodyStrong, color = WeatherTheme.colors.onSkyMuted, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        if (visual != null) {
            visual()
            Spacer(Modifier.height(8.dp))
        }
        Text(caption, style = WeatherTheme.type.label, color = WeatherTheme.colors.onSkyMuted, maxLines = 2)
    }
}

internal val TileHeight = 168.dp

// ---- UV ----------------------------------------------------------------------------------------

/** The UV index now, in a word, on the scale's own colours, with when it peaks today. */
@Composable
internal fun UvTile(report: WeatherReport, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val now = report.current.uvIndex ?: report.hoursFrom(nowEpochSeconds).firstOrNull()?.uvIndex ?: 0.0
    val today = report.today(nowEpochSeconds)
    val peak = remember(report, today) { today?.let { day -> report.hoursOf(day).maxByOrNull { it.uvIndex ?: 0.0 } } }
    val word = stringResource(
        when {
            now < 3 -> Res.string.weather_tile_uv_low
            now < 6 -> Res.string.weather_tile_uv_moderate
            now < 8 -> Res.string.weather_tile_uv_high
            now < 11 -> Res.string.weather_tile_uv_very_high
            else -> Res.string.weather_tile_uv_extreme
        },
    )
    val caption = peak?.takeIf { (it.uvIndex ?: 0.0) >= 3 }?.let {
        stringResource(Res.string.weather_tile_uv_peak, (it.uvIndex ?: 0.0).roundToInt(), WeatherFormat.hour(it.epochSeconds, report.offsetAt(it.epochSeconds)).resolve())
    } ?: word
    DetailTile(stringResource(Res.string.weather_tile_uv), Icons.Filled.WbSunny, now.roundToInt().toString(), caption, modifier, note = word) {
        ScaleBar((now / 11.0).toFloat(), UV_SCALE)
    }
}

private val UV_SCALE = listOf(Color(0xFF6BD66B), Color(0xFFF5D93A), Color(0xFFF59A2E), Color(0xFFE5483D), Color(0xFFB45CE6))

/** A scale as a bar of its own colours with a dot at [fraction] along it. */
@Composable
private fun ScaleBar(fraction: Float, scale: List<Color>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        drawRoundRect(Brush.horizontalGradient(scale), size = size, cornerRadius = CornerRadius(size.height / 2))
        if (size.width <= size.height * 2) return@Canvas
        val x = (fraction.coerceIn(0f, 1f) * size.width).coerceIn(size.height / 2, size.width - size.height / 2)
        drawCircle(Color(0x99000000), size.height * 0.95f, Offset(x, size.height / 2))
        drawCircle(Color.White, size.height * 0.62f, Offset(x, size.height / 2))
    }
}

// ---- Wind --------------------------------------------------------------------------------------

/**
 * The wind as a compass: an arrow flying the way the wind blows, the speed in the middle, and
 * under it the gusts and where it comes from.
 */
@Composable
internal fun WindTile(report: WeatherReport, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val current = report.current
    val word = stringResource(
        when {
            current.windKmh < 2 -> Res.string.weather_tile_wind_calm
            current.windKmh < 15 -> Res.string.weather_tile_wind_light
            current.windKmh < 32 -> Res.string.weather_tile_wind_breezy
            current.windKmh < 55 -> Res.string.weather_tile_wind_windy
            else -> Res.string.weather_tile_wind_gale
        },
    )
    val caption = stringResource(Res.string.weather_tile_gusts_from, WeatherFormat.speed(current.gustKmh, units).resolve(), stringResource(WeatherFormat.compass(current.windDirectionDeg)))
    WeatherCard(modifier.height(TileHeight), title = stringResource(Res.string.weather_tile_wind), icon = Icons.Filled.Air) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(WeatherFormat.speed(current.windKmh, units).resolve(), style = WeatherTheme.type.value, color = colors.onSky, maxLines = 1)
                Text(word, style = WeatherTheme.type.label, color = colors.onSkyMuted)
            }
            Canvas(Modifier.size(64.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2 - 2.dp.toPx()
                drawCircle(colors.hairline, radius, center, style = Stroke(1.5.dp.toPx()))
                for (i in 0 until 12) {
                    val a = i * PI / 6
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    val major = i % 3 == 0
                    drawLine(if (major) colors.onSkyMuted else colors.onSkyFaint, center + dir * (radius - (if (major) 7 else 4).dp.toPx()), center + dir * radius, (if (major) 2f else 1f).dp.toPx(), StrokeCap.Round)
                }
                // The arrow points where the wind is going: the far side from where it comes.
                rotate(current.windDirectionDeg + 180f, center) {
                    val tip = Offset(center.x, center.y - radius * 0.62f)
                    val tail = Offset(center.x, center.y + radius * 0.62f)
                    drawLine(colors.accent, tail, tip, 2.5.dp.toPx(), StrokeCap.Round)
                    val head = Path().apply {
                        moveTo(tip.x, tip.y - 5.dp.toPx())
                        lineTo(tip.x - 6.dp.toPx(), tip.y + 6.dp.toPx())
                        lineTo(tip.x + 6.dp.toPx(), tip.y + 6.dp.toPx())
                        close()
                    }
                    drawPath(head, colors.accent)
                    drawCircle(colors.accent, 3.dp.toPx(), tail)
                }
            }
        }
        Text(caption, style = WeatherTheme.type.label, color = colors.onSkyMuted, maxLines = 2)
    }
}

// ---- Humidity ----------------------------------------------------------------------------------

/** Humidity, led by how the air feels (which is the dew point's doing, not the percentage's). */
@Composable
internal fun HumidityTile(report: WeatherReport, modifier: Modifier = Modifier) {
    val units = WeatherTheme.units
    val current = report.current
    val dewPoint = current.dewPointC
    val feel = dewPoint?.let {
        stringResource(
            when {
                it < 8 -> Res.string.weather_tile_humidity_dry
                it < 15 -> Res.string.weather_tile_humidity_pleasant
                it < 18 -> Res.string.weather_tile_humidity_sticky
                it < 22 -> Res.string.weather_tile_humidity_muggy
                else -> Res.string.weather_tile_humidity_oppressive
            },
        )
    }
    val caption = if (dewPoint != null && feel != null) stringResource(Res.string.weather_tile_dew_point, WeatherFormat.degrees(dewPoint, units), feel) else ""
    DetailTile(stringResource(Res.string.weather_tile_humidity), Icons.Filled.WaterDrop, WeatherFormat.percent(current.humidityPercent), caption, modifier) {
        ScaleBar(current.humidityPercent / 100f, listOf(Color(0xFFE8D9A8), Color(0xFF8FD0FF), Color(0xFF3F7DE0)))
    }
}

// ---- Pressure ----------------------------------------------------------------------------------

/** The barometer and which way it is moving, which says more about the next few hours than its number does. */
@Composable
internal fun PressureTile(report: WeatherReport, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val pressure = report.current.pressureHpa ?: return
    val trend = remember(report, nowEpochSeconds) { WeatherStory.pressureTrend(report, nowEpochSeconds) }
    val (word, icon) = when (trend) {
        PressureTrend.RISING -> Res.string.weather_tile_pressure_rising to Icons.AutoMirrored.Filled.TrendingUp
        PressureTrend.FALLING -> Res.string.weather_tile_pressure_falling to Icons.AutoMirrored.Filled.TrendingDown
        else -> Res.string.weather_tile_pressure_steady to Icons.AutoMirrored.Filled.TrendingFlat
    }
    DetailTile(stringResource(Res.string.weather_tile_pressure), Icons.Filled.Speed, WeatherFormat.pressure(pressure, WeatherTheme.units).resolve(), stringResource(word), modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            // 980 to 1040 hPa: the span of ordinary weather, with fair at the high end.
            Box(Modifier.weight(1f)) { ScaleBar(((pressure - 980) / 60).toFloat(), listOf(Color(0xFF6E7BFF), Color(0xFF8FD0FF), Color(0xFFFFD84D))) }
        }
    }
}

// ---- Visibility --------------------------------------------------------------------------------

@Composable
internal fun VisibilityTile(report: WeatherReport, modifier: Modifier = Modifier) {
    val meters = report.current.visibilityM ?: return
    val word = stringResource(
        when {
            meters >= 16_000 -> Res.string.weather_tile_visibility_perfect
            meters >= 9_000 -> Res.string.weather_tile_visibility_clear
            meters >= 4_000 -> Res.string.weather_tile_visibility_haze
            meters >= 1_000 -> Res.string.weather_tile_visibility_mist
            else -> Res.string.weather_tile_visibility_fog
        },
    )
    DetailTile(stringResource(Res.string.weather_tile_visibility), Icons.Filled.Visibility, WeatherFormat.distance(meters, WeatherTheme.units).resolve(), word, modifier)
}

// ---- Air quality -------------------------------------------------------------------------------

/** The US air quality index, with its band in words and on the index's own colours. */
@Composable
internal fun AirQualityTile(report: WeatherReport, modifier: Modifier = Modifier) {
    val air = report.air ?: return
    val band = stringResource(air.band.label())
    val caption = air.pm25?.let { stringResource(Res.string.weather_tile_air_pm, WeatherFormat.decimal(it, 1)) } ?: band
    DetailTile(stringResource(Res.string.weather_tile_air), Icons.Filled.Grain, air.usAqi.toString(), caption, modifier, note = band) {
        ScaleBar(air.usAqi / 300f, AQI_SCALE)
    }
}

private val AQI_SCALE = listOf(Color(0xFF6BD66B), Color(0xFFF5D93A), Color(0xFFF59A2E), Color(0xFFE5483D), Color(0xFF9B59D0), Color(0xFF8A2A3A))

internal fun AqiBand.label(): StringResource = when (this) {
    AqiBand.GOOD -> Res.string.weather_aqi_good
    AqiBand.MODERATE -> Res.string.weather_aqi_moderate
    AqiBand.SENSITIVE -> Res.string.weather_aqi_sensitive
    AqiBand.UNHEALTHY -> Res.string.weather_aqi_unhealthy
    AqiBand.VERY_UNHEALTHY -> Res.string.weather_aqi_very_unhealthy
    AqiBand.HAZARDOUS -> Res.string.weather_aqi_hazardous
}

// ---- The sun -----------------------------------------------------------------------------------

/**
 * The sun's day as an arc: the path it takes over the horizon, how far along it is now, when it
 * rises and sets, and when the evening's golden hour starts.
 */
@Composable
internal fun SunTile(report: WeatherReport, place: Place, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val today = report.today(nowEpochSeconds) ?: return
    val sunrise = today.sunriseEpochSeconds ?: return
    val sunset = today.sunsetEpochSeconds ?: return
    val light = remember(today.epochSeconds, place.latitude, place.longitude) { Astronomy.dayLight(today.epochSeconds, place.latitude, place.longitude) }
    val rise = WeatherFormat.clock(sunrise, report.offsetAt(sunrise)).resolve()
    val set = WeatherFormat.clock(sunset, report.offsetAt(sunset)).resolve()
    val description = stringResource(Res.string.weather_tile_sun_description, rise, set)
    // How far through the daylight it is, running on below the horizon either side of it.
    val progress = ((nowEpochSeconds - sunrise).toFloat() / (sunset - sunrise).coerceAtLeast(1)).coerceIn(-0.25f, 1.25f)
    WeatherCard(modifier.height(TileHeight), title = stringResource(Res.string.weather_tile_sun), icon = Icons.Filled.WbTwilight) {
        Canvas(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = description }) {
            val horizon = size.height * 0.72f
            val left = size.width * 0.14f
            val right = size.width * 0.86f
            val span = right - left
            val peak = horizon - size.height * 0.62f

            // The sun's height along the arc: a half sine over the day, carried on below the horizon.
            fun point(t: Float) = Offset(left + span * t, horizon - sin(t * PI).toFloat() * (horizon - peak))
            val arc = Path().apply {
                val start = point(-0.2f)
                moveTo(start.x, start.y)
                var t = -0.2f
                while (t <= 1.2f) {
                    val p = point(t)
                    lineTo(p.x, p.y)
                    t += 0.02f
                }
            }
            drawPath(arc, colors.onSkyFaint, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))))
            if (progress > 0f) {
                val travelled = Path().apply {
                    moveTo(point(0f).x, point(0f).y)
                    var t = 0f
                    val end = progress.coerceAtMost(1f)
                    while (t < end) {
                        val p = point(t)
                        lineTo(p.x, p.y)
                        t += 0.02f
                    }
                    val p = point(end)
                    lineTo(p.x, p.y)
                }
                drawPath(travelled, Brush.horizontalGradient(listOf(Color(0xFFFF9A4D), colors.sun, Color(0xFFFF9A4D)), startX = left, endX = right), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            drawLine(colors.hairline, Offset(0f, horizon), Offset(size.width, horizon), 1.dp.toPx())
            val sun = point(progress)
            val up = progress in 0f..1f
            drawCircle(colors.sun.copy(alpha = if (up) 0.28f else 0.1f), 11.dp.toPx(), sun)
            drawCircle(if (up) colors.sun else colors.onSkyFaint, 5.5.dp.toPx(), sun)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SunTime(stringResource(Res.string.weather_tile_sunrise), rise)
            light.goldenEveningStart?.takeIf { it > nowEpochSeconds - 3600 }?.let { golden ->
                SunTime(stringResource(Res.string.weather_tile_golden_hour), WeatherFormat.clock(golden, report.offsetAt(golden)).resolve(), Alignment.CenterHorizontally)
            }
            SunTime(stringResource(Res.string.weather_tile_sunset), set, Alignment.End)
        }
    }
}

@Composable
private fun SunTime(label: String, time: String, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Text(label, style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing), color = WeatherTheme.colors.onSkyFaint)
        Text(time, style = WeatherTheme.type.label, color = WeatherTheme.colors.onSky)
    }
}

// ---- The moon ----------------------------------------------------------------------------------

/** Tonight's moon, drawn by a shader as the lit sphere it is, with its phase, how much is lit, and the next full or new one. */
@Composable
internal fun MoonTile(report: WeatherReport, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val phase = remember(nowEpochSeconds / 3600) { Astronomy.moonPhase(nowEpochSeconds) }
    val name = stringResource(phase.name.label())
    val next = remember(nowEpochSeconds / 3600) {
        // Whichever is nearer: the next full moon while it waxes, the next new one while it wanes.
        val full = phase.cycle < 0.5
        full to Astronomy.nextMoon(nowEpochSeconds, full)
    }
    val date = WeatherFormat.date(next.second, report.offsetAt(next.second)).resolve()
    val caption = stringResource(if (next.first) Res.string.weather_tile_moon_next_full else Res.string.weather_tile_moon_next_new, date)
    WeatherCard(modifier.height(TileHeight), title = stringResource(Res.string.weather_tile_moon), icon = Icons.Filled.NightsStay) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = WeatherTheme.type.bodyStrong, color = colors.onSky, maxLines = 2)
                Text(stringResource(Res.string.weather_tile_moon_lit, (phase.illumination * 100).roundToInt()), style = WeatherTheme.type.label, color = colors.onSkyMuted)
            }
            MoonDisc(phase.cycle.toFloat(), Modifier.size(64.dp))
        }
        Text(caption, style = WeatherTheme.type.label, color = colors.onSkyMuted, maxLines = 2)
    }
}

/** The moon at [cycle] (0 new, 0.5 full), by [MOON_SHADER]; a plain disc with a shadow slid over it where shaders can't be had. */
@Composable
internal fun MoonDisc(cycle: Float, modifier: Modifier = Modifier) {
    val shaded = LocalWeatherShaders.current
    val shader = remember(shaded) { if (shaded) weatherShaderOrNull(MOON_SHADER) else null }
    Canvas(modifier) {
        if (shader != null) {
            shader.setUniform("size", size.width, size.height)
            shader.setUniform("cycle", cycle)
            drawRect(shader.brush())
        } else {
            val radius = size.minDimension * 0.43f
            drawCircle(Color(0xFFE9E6DC), radius)
            val shadow = Path().apply { addOval(Rect(Offset(center.x + (cycle - 0.5f) * 4f * radius, center.y), radius)) }
            drawPath(Path.combine(PathOperation.Intersect, shadow, Path().apply { addOval(Rect(center, radius)) }), Color(0xE6101622))
        }
    }
}

internal fun MoonPhaseName.label(): StringResource = when (this) {
    MoonPhaseName.NEW -> Res.string.weather_moon_new
    MoonPhaseName.WAXING_CRESCENT -> Res.string.weather_moon_waxing_crescent
    MoonPhaseName.FIRST_QUARTER -> Res.string.weather_moon_first_quarter
    MoonPhaseName.WAXING_GIBBOUS -> Res.string.weather_moon_waxing_gibbous
    MoonPhaseName.FULL -> Res.string.weather_moon_full
    MoonPhaseName.WANING_GIBBOUS -> Res.string.weather_moon_waning_gibbous
    MoonPhaseName.LAST_QUARTER -> Res.string.weather_moon_last_quarter
    MoonPhaseName.WANING_CRESCENT -> Res.string.weather_moon_waning_crescent
}

// ---- Precipitation -----------------------------------------------------------------------------

/** What has fallen and will fall today, and what tomorrow brings. */
@Composable
internal fun PrecipitationTile(report: WeatherReport, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val units = WeatherTheme.units
    val today = report.today(nowEpochSeconds) ?: return
    val tomorrow = report.tomorrow(nowEpochSeconds)
    fun amount(snowCm: Double, mm: Double, snowy: Boolean): UiText = if (snowy) WeatherFormat.snow(snowCm, units) else WeatherFormat.precipitation(mm, units)
    val caption = when {
        tomorrow != null && tomorrow.isWet ->
            stringResource(Res.string.weather_tile_precipitation_tomorrow, amount(tomorrow.snowfallCm, tomorrow.precipitationMm, tomorrow.isSnowy).resolve())

        else -> stringResource(Res.string.weather_tile_precipitation_none_ahead)
    }
    DetailTile(
        stringResource(Res.string.weather_tile_precipitation),
        Icons.Filled.WaterDrop,
        amount(today.snowfallCm, today.precipitationMm, today.isSnowy).resolve(),
        caption,
        modifier,
    ) {
        Text(stringResource(Res.string.weather_tile_precipitation_today), style = WeatherTheme.type.label, color = WeatherTheme.colors.onSkyMuted)
    }
}
