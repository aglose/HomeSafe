package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.PlaceWeather
import com.meticulouscreations.homesafe.weather.domain.DayForecast
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherStory
import com.meticulouscreations.homesafe.weather.ui.sky.SkyFreeze
import com.meticulouscreations.homesafe.weather.ui.sky.SkyScene
import com.meticulouscreations.homesafe.weather.ui.sky.WeatherSky
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_day_collapsed
import homesafe.shared.generated.resources.weather_day_date
import homesafe.shared.generated.resources.weather_day_description
import homesafe.shared.generated.resources.weather_day_expanded
import homesafe.shared.generated.resources.weather_forecast_days_title
import homesafe.shared.generated.resources.weather_forecast_less_certain
import homesafe.shared.generated.resources.weather_forecast_trend_description
import homesafe.shared.generated.resources.weather_forecast_trend_title
import homesafe.shared.generated.resources.weather_stat_amount_chance
import homesafe.shared.generated.resources.weather_stat_feels
import homesafe.shared.generated.resources.weather_stat_high_low
import homesafe.shared.generated.resources.weather_stat_precipitation
import homesafe.shared.generated.resources.weather_stat_sunrise
import homesafe.shared.generated.resources.weather_stat_sunset
import homesafe.shared.generated.resources.weather_stat_uv
import homesafe.shared.generated.resources.weather_stat_wind
import homesafe.shared.generated.resources.weather_stat_wind_gusts
import homesafe.shared.generated.resources.weather_today
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** From this day on (counting today as the first) a forecast is more a likelihood than a promise, and is drawn that way. */
private const val LESS_CERTAIN_FROM = 7

/**
 * The Forecast tab for one place: the days ahead as one picture (highs and lows as two lines,
 * what falls as bars under them), then the list — each day's sky, chance of rain and range
 * against the week's, opening in place into that day's own sky, its hours, and its numbers.
 */
@Composable
internal fun ForecastScreen(
    entry: PlaceWeather,
    nowEpochSeconds: Long,
    listState: LazyListState,
    padding: PaddingValues,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = entry.report
    val days = remember(report, nowEpochSeconds) { report?.daysFromToday(nowEpochSeconds).orEmpty() }
    if (report == null || days.isEmpty()) {
        WaitingForForecast(entry, padding, onRetry, modifier)
        return
    }
    var opened by rememberSaveable(entry.place.id) { mutableLongStateOf(-1L) }
    val weekLow = remember(days) { days.minOf { it.lowC } }
    val weekHigh = remember(days) { days.maxOf { it.highC } }
    LazyColumn(
        modifier.fillMaxSize().testTag("weather_forecast"),
        state = listState,
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "trend", contentType = "card") {
            WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.weather_forecast_trend_title), icon = Icons.AutoMirrored.Filled.ShowChart) {
                WeekTrendChart(report, nowEpochSeconds)
            }
        }
        item(key = "days", contentType = "card") {
            WeatherCard(
                Modifier.cardWidth(),
                title = pluralStringResource(Res.plurals.weather_forecast_days_title, days.size, days.size),
                icon = Icons.Filled.CalendarMonth,
                padding = 0.dp,
            ) {
                days.forEachIndexed { i, day ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(WeatherTheme.colors.hairline))
                    DayRow(
                        report = report,
                        place = entry.place,
                        day = day,
                        index = i,
                        weekLowC = weekLow,
                        weekHighC = weekHigh,
                        currentC = if (i == 0) report.current.temperatureC else null,
                        expanded = opened == day.epochSeconds,
                        onToggle = { opened = if (opened == day.epochSeconds) -1L else day.epochSeconds },
                    )
                }
                if (days.size > LESS_CERTAIN_FROM) {
                    Text(
                        stringResource(Res.string.weather_forecast_less_certain),
                        style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing),
                        color = WeatherTheme.colors.onSkyFaint,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** One day of the list, and under it (when [expanded]) the day in full. */
@Composable
private fun DayRow(
    report: WeatherReport,
    place: Place,
    day: DayForecast,
    index: Int,
    weekLowC: Double,
    weekHighC: Double,
    currentC: Double?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = WeatherTheme.units
    val offset = report.utcOffsetSeconds
    val name = if (index == 0) stringResource(Res.string.weather_today) else stringResource(WeatherFormat.weekday(day.epochSeconds + 12 * 3600, offset))
    val kind = stringResource(day.kind.label())
    val high = WeatherFormat.degrees(day.highC, units)
    val low = WeatherFormat.degrees(day.lowC, units)
    val chance = day.precipitationProbability?.takeIf { it >= 25 && (day.kind.isPrecipitation || it >= 45) }
    val description = stringResource(Res.string.weather_day_description, name, kind, high, low)
    val state = stringResource(if (expanded) Res.string.weather_day_expanded else Res.string.weather_day_collapsed)
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "dayChevron")
    // A day a week off is drawn fainter: the model's reach, not a promise.
    val certainty = if (index >= LESS_CERTAIN_FROM) 0.62f else 1f
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    stateDescription = state
                }
                .testTag("weather_day_$index")
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = type.bodyStrong, color = colors.onSky, modifier = Modifier.width(54.dp), maxLines = 1)
            WeatherGlyph(day.kind, isDay = true, Modifier.size(26.dp))
            Text(
                chance?.let { WeatherFormat.percent(it) } ?: "",
                style = type.label,
                color = colors.rain,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(44.dp),
            )
            Text(low, style = type.bodyStrong, color = colors.onSkyMuted, textAlign = TextAlign.End, modifier = Modifier.width(38.dp))
            TemperatureRangeBar(day.lowC, day.highC, weekLowC, weekHighC, Modifier.weight(1f).padding(horizontal = 10.dp).graphicsLayer { alpha = certainty }, currentC)
            Text(high, style = type.bodyStrong, color = colors.onSky, textAlign = TextAlign.End, modifier = Modifier.width(38.dp))
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = colors.onSkyFaint, modifier = Modifier.padding(start = 6.dp).size(18.dp).graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            DayDetail(report, place, day, index, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
        }
    }
}

/** A day opened up: its sky as a picture, what it's doing in a sentence, its hours, and its numbers. */
@Composable
private fun DayDetail(report: WeatherReport, place: Place, day: DayForecast, index: Int, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = WeatherTheme.units
    val offset = report.utcOffsetSeconds
    val hours = remember(report, day) { report.hoursOf(day) }
    val phrase = remember(hours, day) {
        WeatherStory.spanPhrase(WeatherStory.wetSpans(hours), day.epochSeconds, day.epochSeconds + WeatherReport.DAY_SECONDS, report::offsetAt)
    }
    // The day at one in the afternoon: its sky at its most itself.
    val scene = remember(day, place) {
        SkyScene.of(
            kind = day.kind,
            cloudCoverPercent = hours.getOrNull(13)?.cloudCoverPercent,
            windKmh = day.windMaxKmh,
            windDirectionDeg = day.windDirectionDeg,
            visibilityM = null,
            latitude = place.latitude,
            longitude = place.longitude,
            epochSeconds = day.epochSeconds + 13 * 3600,
        )
    }
    val midday = day.epochSeconds + 12 * 3600
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(16.dp))) {
            // Still: a picture of the day, and a dozen of these in a list are not a dozen animations.
            WeatherSky(scene, Modifier.fillMaxSize(), glass = false, freeze = SkyFreeze(time = 9f + index * 3.7f))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x73000000)))))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    stringResource(Res.string.weather_day_date, stringResource(WeatherFormat.weekdayLong(midday, offset)), WeatherFormat.date(midday, offset).resolve()),
                    style = type.label,
                    color = colors.onSkyMuted,
                )
                Text(phrase?.resolve() ?: stringResource(day.kind.label()), style = type.headline, color = colors.onSky)
            }
        }
        Spacer(Modifier.height(14.dp))
        DayHoursChart(report, day)
        Spacer(Modifier.height(14.dp))
        val amount = if (day.isSnowy) WeatherFormat.snow(day.snowfallCm, units) else WeatherFormat.precipitation(day.precipitationMm, units)
        val stats = buildList {
            add(
                Res.string.weather_stat_precipitation to (
                    day.precipitationProbability?.let { stringResource(Res.string.weather_stat_amount_chance, amount.resolve(), WeatherFormat.percent(it)) } ?: amount.resolve()
                    ),
            )
            add(Res.string.weather_stat_wind to stringResource(Res.string.weather_stat_wind_gusts, WeatherFormat.speed(day.windMaxKmh, units).resolve(), WeatherFormat.speed(day.gustMaxKmh, units).resolve()))
            // By the clock as it will stand that morning and evening, which may not be today's.
            day.sunriseEpochSeconds?.let { add(Res.string.weather_stat_sunrise to WeatherFormat.clock(it, report.offsetAt(it)).resolve()) }
            day.sunsetEpochSeconds?.let { add(Res.string.weather_stat_sunset to WeatherFormat.clock(it, report.offsetAt(it)).resolve()) }
            day.uvIndexMax?.let { add(Res.string.weather_stat_uv to it.roundToInt().toString()) }
            if (day.feelsHighC != null && day.feelsLowC != null) {
                add(Res.string.weather_stat_feels to stringResource(Res.string.weather_stat_high_low, WeatherFormat.degrees(day.feelsHighC, units), WeatherFormat.degrees(day.feelsLowC, units)))
            }
        }
        stats.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(label).uppercase(), style = type.micro, color = colors.onSkyFaint)
                        Text(value, style = type.bodyStrong, color = colors.onSky)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The days ahead at a glance: the highs as one line and the lows as another, each in the colours
 * of its temperatures, the band between them shaded, and each day's rain or snow as a bar at
 * the foot. The days' names run along the top.
 */
@Composable
private fun WeekTrendChart(report: WeatherReport, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val days = remember(report, nowEpochSeconds) { report.daysFromToday(nowEpochSeconds) }
    if (days.size < 2) return
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val offset = report.utcOffsetSeconds
    val low = remember(days) { days.minOf { it.lowC } }
    val high = remember(days) { days.maxOf { it.highC } }
    val description = stringResource(Res.string.weather_forecast_trend_description, WeatherFormat.degrees(days.maxOf { it.highC }, units), WeatherFormat.degrees(days.minOf { it.lowC }, units))
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, day ->
                Text(
                    stringResource(WeatherFormat.weekday(day.epochSeconds + 12 * 3600, offset)),
                    style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing),
                    // Today in the sun's colour; the weekend a little brighter than the working days.
                    color = when {
                        i == 0 -> colors.sun
                        WeatherFormat.isWeekend(day.epochSeconds + 12 * 3600, offset) -> colors.onSky
                        else -> colors.onSkyFaint
                    },
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(132.dp).semantics { contentDescription = description }) {
            val step = size.width / days.size
            val top = 10.dp.toPx()
            val bottom = size.height - 30.dp.toPx()
            val span = (high - low).coerceAtLeast(1.0)
            fun yOf(celsius: Double) = (bottom - (celsius - low) / span * (bottom - top)).toFloat()
            val highs = days.mapIndexed { i, day -> Offset((i + 0.5f) * step, yOf(day.highC)) }
            val lows = days.mapIndexed { i, day -> Offset((i + 0.5f) * step, yOf(day.lowC)) }
            val wettest = max(6.0, days.maxOf { max(it.precipitationMm, it.snowfallCm) })
            days.forEachIndexed { i, day ->
                val amount = max(day.precipitationMm, day.snowfallCm)
                if (amount >= 0.2) {
                    val h = (sqrt(amount / wettest).toFloat() * 24.dp.toPx()).coerceAtLeast(3.dp.toPx())
                    val alpha = 0.4f + 0.6f * ((day.precipitationProbability ?: 70) / 100f)
                    drawRoundRect((if (day.isSnowy) colors.snow else colors.rain).copy(alpha = alpha), Offset(i * step + step * 0.28f, size.height - h), Size(step * 0.44f, h), CornerRadius(3.dp.toPx()))
                }
            }
            val band = Path().apply {
                addPath(smoothPath(highs))
                lows.reversed().forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(band, Color(0x1FFFFFFF))
            val highStops = days.mapIndexed { i, day -> (i + 0.5f) / days.size to colors.temperature(day.highC) }.toTypedArray()
            val lowStops = days.mapIndexed { i, day -> (i + 0.5f) / days.size to colors.temperature(day.lowC) }.toTypedArray()
            drawPath(smoothPath(highs), Brush.horizontalGradient(*highStops, startX = 0f, endX = size.width), style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
            drawPath(smoothPath(lows), Brush.horizontalGradient(*lowStops, startX = 0f, endX = size.width), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round), alpha = 0.8f)
            highs.forEachIndexed { i, p -> drawCircle(colors.temperature(days[i].highC), 3.dp.toPx(), p) }
            lows.forEachIndexed { i, p -> drawCircle(colors.temperature(days[i].lowC), 2.4.dp.toPx(), p, alpha = 0.85f) }
        }
    }
}
