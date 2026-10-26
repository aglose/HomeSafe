package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
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
import homesafe.shared.generated.resources.weather_forecast_trend_title
import homesafe.shared.generated.resources.weather_stat_amount_chance
import homesafe.shared.generated.resources.weather_stat_daylight
import homesafe.shared.generated.resources.weather_stat_daylight_value
import homesafe.shared.generated.resources.weather_stat_feels
import homesafe.shared.generated.resources.weather_stat_high_low
import homesafe.shared.generated.resources.weather_stat_hours_value
import homesafe.shared.generated.resources.weather_stat_precipitation
import homesafe.shared.generated.resources.weather_stat_sunrise
import homesafe.shared.generated.resources.weather_stat_sunset
import homesafe.shared.generated.resources.weather_stat_uv
import homesafe.shared.generated.resources.weather_stat_wet_hours
import homesafe.shared.generated.resources.weather_stat_wind
import homesafe.shared.generated.resources.weather_stat_wind_from_gusts
import homesafe.shared.generated.resources.weather_stat_wind_gusts
import homesafe.shared.generated.resources.weather_today
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** From this day on (counting today as the first) a forecast is more a likelihood than a promise, and is drawn that way. */
internal const val LESS_CERTAIN_FROM = 7

/**
 * The Forecast tab for one place: the days ahead as a strip to scroll along (each day's sky, its
 * high and low on the week's two curves, what falls and how hard it blows), then the list — each
 * day's sky, chance of rain and range against the week's, opening in place into that day's own
 * sky, its hours, and its numbers. A day picked in the strip is the day opened in the list.
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
    val scope = rememberCoroutineScope()
    val places = remember { DayPlaces() }
    // Opens the day at [index], or shuts it if it is the one open. A tap on the day's own row also sees
    // that what opened can be read; a tap in the strip leaves the list where it is, the strip under the finger.
    val toggle: (Int, Boolean) -> Unit = { index, follow ->
        val day = days[index].epochSeconds
        places.following?.cancel()
        if (opened == day) {
            opened = -1L
        } else {
            opened = day
            if (follow) places.following = scope.launch { listState.follow(places, index) }
        }
    }
    LazyColumn(
        modifier.fillMaxSize().testTag("weather_forecast").onPlaced { places.list = it },
        state = listState,
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "trend", contentType = "card") {
            WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.weather_forecast_trend_title), icon = Icons.AutoMirrored.Filled.ShowChart, padding = 0.dp) {
                DayTimeline(report, nowEpochSeconds, selected = days.indexOfFirst { it.epochSeconds == opened }, onSelect = { toggle(it, false) })
            }
        }
        item(key = DAYS_KEY, contentType = "card") {
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
                        onToggle = { toggle(i, true) },
                        modifier = Modifier.onPlaced { places.rows[i] = it },
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

private const val DAYS_KEY = "days"

/**
 * Where the list and each of its day rows were last placed, and the scroll that is following a
 * day open. Plain fields: written as things are laid out, read on a tap, drawn from by nothing.
 */
private class DayPlaces {
    var list: LayoutCoordinates? = null
    val rows = mutableMapOf<Int, LayoutCoordinates>()
    var following: Job? = null
}

/**
 * Keeps the day row at [index] in the part of the list that nothing lies over while its detail
 * opens under it: each frame, a share of the way to where it should be, so the scroll follows
 * the opening and comes to rest with it. A row taller than the window is shown from its top. A
 * finger on the list takes over, as it does from any scroll the app starts.
 */
private suspend fun LazyListState.follow(places: DayPlaces, index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.key == DAYS_KEY }) animateScrollToItem(layoutInfo.totalItemsCount - 1)
    var height = -1
    var still = 0
    var frames = 0
    while (still < FOLLOW_STILL_FRAMES && frames++ < FOLLOW_FRAMES_AT_MOST) {
        withFrameNanos { }
        val list = places.list?.takeIf { it.isAttached } ?: return
        val row = places.rows[index]?.takeIf { it.isAttached } ?: return
        val top = list.localPositionOf(row, Offset.Zero).y
        val from = layoutInfo.beforeContentPadding.toFloat()
        val to = (list.size.height - layoutInfo.afterContentPadding).toFloat()
        val away = when {
            top < from -> top - from
            top + row.size.height > to -> min(top + row.size.height - to, top - from)
            else -> 0f
        }
        val step = if (abs(away) < 2f) away else away * FOLLOW_SHARE
        val moved = if (step == 0f) 0f else scrollBy(step)
        // Done once the row has stopped growing and there is nowhere further to take it.
        if (row.size.height == height && abs(moved) < 0.5f) still++ else still = 0
        height = row.size.height
    }
}

private const val FOLLOW_SHARE = 0.28f
private const val FOLLOW_STILL_FRAMES = 6
private const val FOLLOW_FRAMES_AT_MOST = 180

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
            // How many of its hours it rains or snows: worth saying once it is an hour or more.
            val wetHours = day.precipitationHours.roundToInt()
            if (wetHours >= 1) add(Res.string.weather_stat_wet_hours to pluralStringResource(Res.plurals.weather_stat_hours_value, wetHours, wetHours))
            val wind = WeatherFormat.speed(day.windMaxKmh, units).resolve()
            val gusts = WeatherFormat.speed(day.gustMaxKmh, units).resolve()
            // Where it blows from, unless there is next to none of it to come from anywhere.
            val windValue = if (WeatherFormat.speedValue(day.windMaxKmh, units) >= 1) {
                stringResource(Res.string.weather_stat_wind_from_gusts, wind, stringResource(WeatherFormat.compass(day.windDirectionDeg)), gusts)
            } else {
                stringResource(Res.string.weather_stat_wind_gusts, wind, gusts)
            }
            add(Res.string.weather_stat_wind to windValue)
            // By the clock as it will stand that morning and evening, which may not be today's.
            day.sunriseEpochSeconds?.let { add(Res.string.weather_stat_sunrise to WeatherFormat.clock(it, report.offsetAt(it)).resolve()) }
            day.sunsetEpochSeconds?.let { add(Res.string.weather_stat_sunset to WeatherFormat.clock(it, report.offsetAt(it)).resolve()) }
            day.daylightSeconds?.takeIf { it > 0 }?.let { seconds ->
                val minutes = ((seconds + 30) / 60).toInt()
                add(Res.string.weather_stat_daylight to stringResource(Res.string.weather_stat_daylight_value, minutes / 60, minutes % 60))
            }
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
