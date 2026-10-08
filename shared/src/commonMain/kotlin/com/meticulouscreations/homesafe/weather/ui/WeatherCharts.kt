package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.domain.DayForecast
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherStory
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_hour_description
import homesafe.shared.generated.resources.weather_minutes_short
import homesafe.shared.generated.resources.weather_now
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.sqrt

// ---- The next day, hour by hour --------------------------------------------------------------

private val HourColumn = 58.dp

/** Above the curve: the hour, the sky, the chance of rain. */
private val HourTop = 66.dp

/** The band the temperature curve moves in. */
private val HourCurve = 72.dp

/** Under the curve: how much falls. */
private val HourBars = 22.dp
private const val HOURS_SHOWN = 26

/**
 * The next day and a bit as one strip to scroll along: each hour's sky and chance of rain, the
 * temperature as a curve that takes each hour's own colour, the amount that falls as bars along
 * the foot, and the night hours shaded.
 *
 * Hold a finger down and slide: the strip stops scrolling and [onScrub] is told the hour under
 * the finger (null when it lifts), which the app uses to turn the whole sky to that hour.
 */
@Composable
internal fun HourlyStrip(report: WeatherReport, nowEpochSeconds: Long, onScrub: (Long?) -> Unit, modifier: Modifier = Modifier) {
    val hours = remember(report, nowEpochSeconds) { report.hoursFrom(nowEpochSeconds).take(HOURS_SHOWN) }
    if (hours.size < 2) return
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = WeatherTheme.units
    val haptics = LocalHapticFeedback.current
    val scrub by rememberUpdatedState(onScrub)
    var scrubbed by remember { mutableIntStateOf(-1) }
    val low = remember(hours) { hours.minOf { it.temperatureC } }
    val high = remember(hours) { hours.maxOf { it.temperatureC } }
    val wettest = remember(hours) { max(1.5, hours.maxOf { it.precipitationMm }) }
    val nowLabel = stringResource(Res.string.weather_now)

    // Where an hour's temperature sits in the curve's band, 0 the foot and 1 the top.
    fun rise(hour: HourForecast): Float = if (high - low < 0.5) 0.5f else ((hour.temperatureC - low) / (high - low)).toFloat()

    Row(
        modifier
            .testTag("weather_hourly")
            .horizontalScroll(rememberScrollState())
            .pointerInput(hours) {
                fun indexAt(x: Float) = (x / HourColumn.toPx()).toInt().coerceIn(0, hours.lastIndex)
                fun moveTo(x: Float) {
                    val index = indexAt(x)
                    if (index != scrubbed) {
                        scrubbed = index
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        scrub(hours[index].epochSeconds)
                    }
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { moveTo(it.x) },
                    onDragEnd = {
                        scrubbed = -1
                        scrub(null)
                    },
                    onDragCancel = {
                        scrubbed = -1
                        scrub(null)
                    },
                ) { change, _ ->
                    change.consume()
                    moveTo(change.position.x)
                }
            }
            .drawBehind {
                val column = HourColumn.toPx()
                val curveTop = HourTop.toPx()
                val curveHeight = HourCurve.toPx()
                // Night, shaded.
                hours.forEachIndexed { i, hour ->
                    if (!hour.isDay) drawRect(Color(0x10000000), Offset(i * column, 0f), Size(column, size.height))
                }
                if (scrubbed >= 0) {
                    drawRoundRect(Color(0x24FFFFFF), Offset(scrubbed * column + 2.dp.toPx(), 0f), Size(column - 4.dp.toPx(), size.height), CornerRadius(12.dp.toPx()))
                }
                // What falls, along the foot: taller for more, fainter for less likely.
                hours.forEachIndexed { i, hour ->
                    if (hour.precipitationMm >= 0.05) {
                        val barHeight = (sqrt(hour.precipitationMm / wettest).toFloat() * (HourBars.toPx() - 4.dp.toPx())).coerceAtLeast(2.dp.toPx())
                        val alpha = 0.35f + 0.65f * ((hour.precipitationProbability ?: 70) / 100f)
                        drawRoundRect(
                            (if (hour.kind.isSnow) colors.snow else colors.rain).copy(alpha = alpha),
                            Offset(i * column + column * 0.3f, size.height - barHeight),
                            Size(column * 0.4f, barHeight),
                            CornerRadius(3.dp.toPx()),
                        )
                    }
                }
                val points = hours.mapIndexed { i, hour -> Offset((i + 0.5f) * column, curveTop + curvePointY(rise(hour), curveHeight)) }
                val line = smoothPath(points)
                // The wash under the curve runs out to both edges of the strip, level from the first and last points.
                val fill = Path().apply {
                    addPath(line)
                    lineTo(size.width, points.last().y)
                    lineTo(size.width, curveTop + curveHeight)
                    lineTo(0f, curveTop + curveHeight)
                    lineTo(0f, points.first().y)
                    close()
                }
                val stops = hours.mapIndexed { i, hour -> (i + 0.5f) / hours.size to colors.temperature(hour.temperatureC) }.toTypedArray()
                drawPath(fill, Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color.Transparent), startY = curveTop, endY = curveTop + curveHeight))
                drawPath(line, Brush.horizontalGradient(*stops, startX = 0f, endX = size.width), style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(Color.White, 4.5.dp.toPx(), points.first())
                drawCircle(colors.temperature(hours.first().temperatureC), 2.6.dp.toPx(), points.first())
            },
    ) {
        hours.forEachIndexed { i, hour ->
            val time = if (i == 0) nowLabel else WeatherFormat.hour(hour.epochSeconds, report.utcOffsetSeconds).resolve()
            val temperature = WeatherFormat.degrees(hour.temperatureC, units)
            val chance = hour.precipitationProbability?.takeIf { (it >= 20 && hour.kind.isPrecipitation) || it >= 35 }
            val description = stringResource(Res.string.weather_hour_description, time, temperature, stringResource(hour.kind.label(hour.isDay)))
            Column(
                Modifier.width(HourColumn).height(HourTop + HourCurve + HourBars).semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(2.dp))
                Text(time, style = type.label, color = if (i == 0) colors.onSky else colors.onSkyMuted, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                WeatherGlyph(hour.kind, hour.isDay, Modifier.size(26.dp))
                Text(chance?.let { WeatherFormat.percent(it) } ?: "", style = type.micro.copy(letterSpacing = type.label.letterSpacing), color = colors.rain, modifier = Modifier.height(15.dp))
                Box(Modifier.height(HourCurve).fillMaxWidth()) {
                    // Sits just above its point on the curve.
                    Text(
                        temperature,
                        style = type.bodyStrong,
                        color = colors.onSky,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = curvePointDp(rise(hour)) - 26.dp),
                    )
                }
            }
        }
    }
}

/** Where on the curve's band a point at [rise] (0–1) is drawn, from the band's top: the top third is left for the labels. */
private fun curvePointY(rise: Float, height: Float): Float = height * (0.4f + 0.46f * (1f - rise))

private fun curvePointDp(rise: Float): Dp = HourCurve * (0.4f + 0.46f * (1f - rise))

/** A smooth line through [points]: each stretch a cubic whose handles lean on its neighbours (Catmull-Rom). */
internal fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    for (i in 0 until points.lastIndex) {
        val p0 = points[max(i - 1, 0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[minOf(i + 2, points.lastIndex)]
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f,
            p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f,
            p2.y - (p3.y - p1.y) / 6f,
            p2.x,
            p2.y,
        )
    }
}

// ---- The next two hours, by the quarter hour ---------------------------------------------------

/**
 * How hard it will be raining (or snowing) in each of the next eight quarter-hours, as bars. The
 * scale is a square root, so a drizzle is still a visible bar beside a downpour, and two faint
 * lines mark where "moderate" and "heavy" begin.
 */
@Composable
internal fun NextHoursChart(report: WeatherReport, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val slices = remember(report, nowEpochSeconds) { report.slicesFrom(nowEpochSeconds).take(9) }
    if (slices.isEmpty()) return
    val colors = WeatherTheme.colors
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val gap = 5.dp.toPx()
            val barWidth = (size.width - gap * (slices.size - 1)) / slices.size
            fun heightOf(ratePerHour: Double) = (sqrt((ratePerHour / FULL_SCALE_MM_PER_HOUR).coerceIn(0.0, 1.0)).toFloat() * size.height)
            listOf(2.5, 7.6).forEach { threshold ->
                val y = size.height - heightOf(threshold)
                drawLine(colors.hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
            }
            slices.forEachIndexed { i, slice ->
                val h = heightOf(slice.ratePerHourMm).coerceAtLeast(3.dp.toPx())
                val wet = slice.isWet
                drawRoundRect(
                    when {
                        !wet -> colors.hairline
                        slice.isSnow -> colors.snow
                        else -> colors.rain
                    },
                    Offset(i * (barWidth + gap), size.height - h),
                    Size(barWidth, h),
                    CornerRadius(4.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Res.string.weather_now), style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing), color = colors.onSkyFaint)
            listOf(30, 60, 90, 120).forEach { minutes ->
                Text(stringResource(Res.string.weather_minutes_short, minutes), style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing), color = colors.onSkyFaint)
            }
        }
    }
}

/** Violent rain: the top of the chart. */
private const val FULL_SCALE_MM_PER_HOUR = 16.0

// ---- A day's range, against the week's ---------------------------------------------------------

/**
 * One day's low-to-high as a bar placed along the span of the whole forecast ([weekLowC] to
 * [weekHighC]), coloured by the temperatures it covers, so the days can be read against each
 * other at a glance. Today's also carries a dot at [currentC].
 */
@Composable
internal fun TemperatureRangeBar(lowC: Double, highC: Double, weekLowC: Double, weekHighC: Double, modifier: Modifier = Modifier, currentC: Double? = null) {
    val colors = WeatherTheme.colors
    Canvas(modifier.height(6.dp)) {
        val span = (weekHighC - weekLowC).coerceAtLeast(1.0)
        fun xOf(celsius: Double) = (((celsius - weekLowC) / span).coerceIn(0.0, 1.0) * size.width).toFloat()
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(Color(0x33000000), size = size, cornerRadius = radius)
        val from = xOf(lowC)
        val to = max(xOf(highC), from + size.height)
        drawRoundRect(
            Brush.horizontalGradient(listOf(colors.temperature(lowC), colors.temperature((lowC + highC) / 2), colors.temperature(highC)), startX = from, endX = to),
            Offset(from, 0f),
            Size(to - from, size.height),
            radius,
        )
        // (Not on a bar too short to hold it: a window squeezed that narrow has other problems.)
        if (currentC != null && size.width > size.height * 2) {
            val x = xOf(currentC).coerceIn(size.height / 2, size.width - size.height / 2)
            drawCircle(Color(0x99000000), size.height * 0.95f, Offset(x, size.height / 2))
            drawCircle(Color.White, size.height * 0.62f, Offset(x, size.height / 2))
        }
    }
}

// ---- One day, hour by hour ---------------------------------------------------------------------

/**
 * A single day's twenty-four hours: the temperature as a curve in its own colours and what
 * falls as bars under it, with the clock along the foot.
 */
@Composable
internal fun DayHoursChart(report: WeatherReport, day: DayForecast, modifier: Modifier = Modifier) {
    val hours = remember(report, day) { report.hoursOf(day) }
    if (hours.size < 3) return
    val colors = WeatherTheme.colors
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(84.dp)) { drawDayHours(hours, colors) }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            // The marks fall at midnight, six, noon and six: each label sits at the start of its quarter.
            listOf(0, 6, 12, 18).forEach { hour ->
                val at = day.epochSeconds + hour * WeatherReport.HOUR_SECONDS
                Text(
                    WeatherFormat.hour(at, report.utcOffsetSeconds).resolve(),
                    style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing),
                    color = colors.onSkyFaint,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun DrawScope.drawDayHours(hours: List<HourForecast>, colors: WeatherPalette) {
    val low = hours.minOf { it.temperatureC }
    val high = hours.maxOf { it.temperatureC }
    val wettest = max(1.5, hours.maxOf { it.precipitationMm })
    val step = size.width / 24f
    val curveHeight = size.height * 0.62f
    for (quarter in 1..3) {
        val x = quarter * 6 * step
        drawLine(colors.hairline, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
    hours.forEachIndexed { i, hour ->
        if (hour.precipitationMm >= 0.05) {
            val h = (sqrt(hour.precipitationMm / wettest).toFloat() * size.height * 0.3f).coerceAtLeast(2.dp.toPx())
            val alpha = 0.35f + 0.65f * ((hour.precipitationProbability ?: 70) / 100f)
            drawRoundRect((if (hour.kind.isSnow) colors.snow else colors.rain).copy(alpha = alpha), Offset(i * step + step * 0.2f, size.height - h), Size(step * 0.6f, h), CornerRadius(2.dp.toPx()))
        }
    }
    val points = hours.mapIndexed { i, hour ->
        val rise = if (high - low < 0.5) 0.5f else ((hour.temperatureC - low) / (high - low)).toFloat()
        Offset((i + 0.5f) * step, 6.dp.toPx() + (curveHeight - 12.dp.toPx()) * (1f - rise))
    }
    val stops = hours.mapIndexed { i, hour -> (i + 0.5f) / 24f to colors.temperature(hour.temperatureC) }.toTypedArray()
    drawPath(smoothPath(points), Brush.horizontalGradient(*stops, startX = 0f, endX = size.width), style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
}

// ---- When to be outside ------------------------------------------------------------------------

/**
 * The rest of today (and tomorrow's daylight) as a strip of hours, each lit by how pleasant it
 * will be outside: bright for a good hour, dim for a poor one, dark for the night. The best
 * stretch is the one the card names.
 */
@Composable
internal fun ComfortStrip(report: WeatherReport, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val hours = remember(report, nowEpochSeconds) { report.hoursFrom(nowEpochSeconds).take(24) }
    if (hours.isEmpty()) return
    val colors = WeatherTheme.colors
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (hours.size - 1)) / hours.size
        hours.forEachIndexed { i, hour ->
            val score = WeatherStory.comfort(hour)
            val color = when {
                !hour.isDay -> Color(0x33000000)
                score >= 78 -> colors.good
                score >= 55 -> colors.sun
                else -> colors.onSkyFaint.copy(alpha = 0.3f)
            }
            drawRoundRect(color, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
        }
    }
}
