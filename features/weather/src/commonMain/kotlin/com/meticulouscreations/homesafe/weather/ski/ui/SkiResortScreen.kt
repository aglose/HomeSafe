package com.meticulouscreations.homesafe.weather.ski.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import com.meticulouscreations.homesafe.weather.ski.SkiUiState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiCost
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDriveSource
import com.meticulouscreations.homesafe.weather.ski.domain.SkiEntry
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecastDay
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecastHour
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlanner
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortFacts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ui.PillButton
import com.meticulouscreations.homesafe.weather.ui.WeatherCard
import com.meticulouscreations.homesafe.weather.ui.WeatherGlyph
import com.meticulouscreations.homesafe.weather.ui.WeatherTheme
import com.meticulouscreations.homesafe.weather.ui.cardWidth
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_attribution
import homesafe.shared.generated.resources.ski_cams_title
import homesafe.shared.generated.resources.ski_chain_at
import homesafe.shared.generated.resources.ski_chains_none
import homesafe.shared.generated.resources.ski_chains_unavailable
import homesafe.shared.generated.resources.ski_chains_unknown
import homesafe.shared.generated.resources.ski_conditions
import homesafe.shared.generated.resources.ski_day_title
import homesafe.shared.generated.resources.ski_depth_base
import homesafe.shared.generated.resources.ski_depth_middle
import homesafe.shared.generated.resources.ski_depth_summit
import homesafe.shared.generated.resources.ski_directions_button
import homesafe.shared.generated.resources.ski_drive_back
import homesafe.shared.generated.resources.ski_drive_free_flow
import homesafe.shared.generated.resources.ski_drive_from_city
import homesafe.shared.generated.resources.ski_drive_now
import homesafe.shared.generated.resources.ski_drive_out
import homesafe.shared.generated.resources.ski_drive_summary
import homesafe.shared.generated.resources.ski_drive_title
import homesafe.shared.generated.resources.ski_follow
import homesafe.shared.generated.resources.ski_following
import homesafe.shared.generated.resources.ski_forecast_title
import homesafe.shared.generated.resources.ski_freezing_level
import homesafe.shared.generated.resources.ski_gust_label
import homesafe.shared.generated.resources.ski_high_low_base
import homesafe.shared.generated.resources.ski_high_low_plain
import homesafe.shared.generated.resources.ski_high_low_summit
import homesafe.shared.generated.resources.ski_hours_title
import homesafe.shared.generated.resources.ski_last24
import homesafe.shared.generated.resources.ski_last48
import homesafe.shared.generated.resources.ski_last7
import homesafe.shared.generated.resources.ski_last72
import homesafe.shared.generated.resources.ski_lifts_open
import homesafe.shared.generated.resources.ski_loading
import homesafe.shared.generated.resources.ski_model_range
import homesafe.shared.generated.resources.ski_mountain_acres
import homesafe.shared.generated.resources.ski_mountain_base
import homesafe.shared.generated.resources.ski_mountain_summit
import homesafe.shared.generated.resources.ski_mountain_title
import homesafe.shared.generated.resources.ski_mountain_vertical
import homesafe.shared.generated.resources.ski_no_report
import homesafe.shared.generated.resources.ski_no_start
import homesafe.shared.generated.resources.ski_no_ticket_price
import homesafe.shared.generated.resources.ski_own_price_hint
import homesafe.shared.generated.resources.ski_own_price_label
import homesafe.shared.generated.resources.ski_pass_covers
import homesafe.shared.generated.resources.ski_placeholder
import homesafe.shared.generated.resources.ski_rain_at_base
import homesafe.shared.generated.resources.ski_report_title
import homesafe.shared.generated.resources.ski_reported_at
import homesafe.shared.generated.resources.ski_runs_label
import homesafe.shared.generated.resources.ski_runs_open
import homesafe.shared.generated.resources.ski_season_total
import homesafe.shared.generated.resources.ski_source_listing
import homesafe.shared.generated.resources.ski_source_prices
import homesafe.shared.generated.resources.ski_source_resort
import homesafe.shared.generated.resources.ski_ticket_adult
import homesafe.shared.generated.resources.ski_ticket_adult_from
import homesafe.shared.generated.resources.ski_ticket_adult_online
import homesafe.shared.generated.resources.ski_ticket_adult_window
import homesafe.shared.generated.resources.ski_ticket_buy
import homesafe.shared.generated.resources.ski_ticket_child
import homesafe.shared.generated.resources.ski_ticket_child_online
import homesafe.shared.generated.resources.ski_ticket_child_short
import homesafe.shared.generated.resources.ski_ticket_child_window
import homesafe.shared.generated.resources.ski_ticket_kids_left_out
import homesafe.shared.generated.resources.ski_ticket_note
import homesafe.shared.generated.resources.ski_ticket_party
import homesafe.shared.generated.resources.ski_ticket_party_dated
import homesafe.shared.generated.resources.ski_ticket_party_first_day
import homesafe.shared.generated.resources.ski_ticket_party_from
import homesafe.shared.generated.resources.ski_ticket_party_own
import homesafe.shared.generated.resources.ski_ticket_party_table
import homesafe.shared.generated.resources.ski_ticket_party_window
import homesafe.shared.generated.resources.ski_ticket_season
import homesafe.shared.generated.resources.ski_tickets_title
import homesafe.shared.generated.resources.ski_units_feet
import homesafe.shared.generated.resources.ski_units_meters
import homesafe.shared.generated.resources.ski_visibility_low
import homesafe.shared.generated.resources.ski_website_button
import io.ktor.http.encodeURLParameter
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * One resort in full: what it reports, what's coming and how sure anyone is, what the day being
 * planned for looks like hour by hour, the road there with its chain controls, what tickets come
 * to, the cameras, and the mountain's own numbers. Each card shows up as its data arrives.
 */
@Composable
internal fun SkiResortScreen(
    ref: SkiResortRef,
    state: SkiUiState,
    actions: SkiActions,
    pictures: ImageLoader?,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    LaunchedEffect(ref.slug) { actions.onOpenResort(ref) }
    LaunchedEffect(ref.slug, state.tripEpochDay) { actions.onPlanTrip(ref) }
    val entry = state.entries[ref.slug] ?: SkiEntry(ref)
    val report = entry.latest
    val following = state.plan.follows(ref.slug)
    val view = remember(entry, state.plan, state.tripEpochDay, state.todayEpochDay, state.nowEpochSeconds, units) { SkiPlanner.view(entry, state.plan, state.tripEpochDay, state.todayEpochDay, state.nowEpochSeconds, units) }
    val strip = remember(entry, state.todayEpochDay) { SkiPlanner.dayStrip(listOf(entry), state.todayEpochDay, SKI_DAYS_AHEAD) }
    LazyColumn(
        modifier.fillMaxSize().background(colors.surface).testTag("ski_resort_page"),
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "head") {
            Column(Modifier.cardWidth().padding(horizontal = 8.dp)) {
                Text(ref.name, style = WeatherTheme.type.title.copy(shadow = null), color = colors.onSky)
                entry.page?.facts?.city?.let { Text(it, style = WeatherTheme.type.label, color = colors.onSkyMuted) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (report != null) SkiPill(report.stateText(state.todayEpochDay, state.nowEpochSeconds).resolve(), report.stateColor(state.nowEpochSeconds))
                    Spacer(Modifier.weight(1f))
                    PillButton(
                        stringResource(if (following) Res.string.ski_following else Res.string.ski_follow),
                        { actions.onFollow(ref, !following) },
                        Modifier.testTag("ski_follow"),
                        icon = if (following) Icons.Filled.Star else Icons.Filled.StarBorder,
                        filled = !following,
                    )
                }
                entry.error?.takeIf { report == null }?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(error.resolve(), style = WeatherTheme.type.body, color = colors.watch)
                }
                if (entry.loading && report == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.ski_loading), style = WeatherTheme.type.body, color = colors.onSkyMuted)
                }
            }
        }
        if (entry.alerts.isNotEmpty()) {
            items(entry.alerts, key = { "alert_${it.id}" }) { alert ->
                Column(
                    Modifier.cardWidth().clip(RoundedCornerShape(18.dp)).background(colors.watch.copy(alpha = 0.14f)).border(1.dp, colors.watch.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.watch, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(alert.event, style = WeatherTheme.type.bodyStrong, color = colors.onSky)
                    }
                    if (alert.headline.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(alert.headline, style = WeatherTheme.type.label, color = colors.onSkyMuted)
                    }
                }
            }
        }
        item(key = "day", contentType = "day") {
            WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.ski_day_title), icon = Icons.Filled.CalendarMonth, padding = 0.dp) {
                SkiDayStrip(strip, state.tripEpochDay, actions.onPickDay)
                DaySummary(view.day, view.notes.map { it.text.resolve() to it.tone.color() }, entry.forecast, state.tripEpochDay)
            }
        }
        report?.let { item(key = "report", contentType = "report") { ReportCard(it, state.nowEpochSeconds, Modifier.cardWidth()) } }
        entry.forecast?.let { forecast ->
            val hours = forecast.skiingHours(state.tripEpochDay)
            if (hours.isNotEmpty()) item(key = "hours", contentType = "hours") { HoursCard(hours, forecast, Modifier.cardWidth()) }
            item(key = "forecast", contentType = "forecast") { ForecastCard(forecast, state.todayEpochDay, state.tripEpochDay, Modifier.cardWidth()) }
        }
        item(key = "drive", contentType = "drive") { DriveCard(entry, state.hasStart, state.startCity, Modifier.cardWidth()) }
        item(key = "tickets", contentType = "tickets") { TicketsCard(entry, view.cost, actions, state.plan.ownPrices[ref.slug], state.tripEpochDay, Modifier.cardWidth()) }
        val webcams = entry.page?.webcams.orEmpty()
        if (webcams.isNotEmpty() && pictures != null) {
            item(key = "cams", contentType = "cams") { WebcamsCard(entry, pictures, Modifier.cardWidth()) }
        }
        entry.page?.facts?.let { facts -> item(key = "mountain", contentType = "mountain") { MountainCard(facts, Modifier.cardWidth()) } }
        item(key = "footer") {
            Text(stringResource(Res.string.ski_attribution), style = WeatherTheme.type.label, color = colors.onSkyFaint, modifier = Modifier.cardWidth().padding(horizontal = 8.dp, vertical = 8.dp))
        }
    }
}

/** The chosen day, in one look: the sky, the snow with how far the models spread, top and bottom, the wind. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaySummary(day: SkiForecastDay?, notes: List<Pair<String, Color>>, forecast: SkiForecast?, epochDay: Int) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    if (day == null) return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(day.kind, isDay = true, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    WeatherFormat.snow(day.snowCm, units).resolve(),
                    style = WeatherTheme.type.value,
                    color = colors.onSky,
                )
                if (day.snowHighCm - day.snowLowCm >= 1) {
                    Text(
                        stringResource(Res.string.ski_model_range, WeatherFormat.snow(day.snowLowCm, units).resolve(), WeatherFormat.snow(day.snowHighCm, units).resolve()),
                        style = WeatherTheme.type.label,
                        color = colors.onSkyFaint,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(Res.string.ski_high_low_summit, WeatherFormat.degrees(day.summitHighC, units), WeatherFormat.degrees(day.summitLowC, units)), style = WeatherTheme.type.label, color = colors.onSky)
                if (day.baseHighC != null && day.baseLowC != null) {
                    Text(stringResource(Res.string.ski_high_low_base, WeatherFormat.degrees(day.baseHighC, units), WeatherFormat.degrees(day.baseLowC, units)), style = WeatherTheme.type.label, color = colors.onSkyMuted)
                }
                Text(stringResource(Res.string.ski_gust_label, WeatherFormat.speed(day.gustMaxKmh, units).resolve()), style = WeatherTheme.type.label, color = colors.onSkyMuted)
            }
        }
        val freezing = forecast?.skiingHours(epochDay)?.mapNotNull { it.freezingLevelM }?.maxOrNull()
        if (freezing != null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.ski_freezing_level, heightText(freezing, units)), style = WeatherTheme.type.label, color = colors.onSkyMuted)
        }
        if ((day.baseRainMm ?: 0.0) >= 0.5) {
            Text(stringResource(Res.string.ski_rain_at_base, WeatherFormat.precipitation(day.baseRainMm ?: 0.0, units).resolve()), style = WeatherTheme.type.label, color = colors.watch)
        }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                notes.forEach { (text, color) -> SkiPill(text, color) }
            }
        }
    }
}

/** A height in the reader's units: "8,200 ft", "2,500 m". To the nearest hundred: a freezing level is no finer. */
@Composable
private fun heightText(meters: Double, units: WeatherUnits): String = if (units.measures == MeasureSystem.IMPERIAL) {
    stringResource(Res.string.ski_units_feet, thousands(((meters * 3.28084) / 100).roundToInt() * 100))
} else {
    stringResource(Res.string.ski_units_meters, thousands(((meters / 100).roundToInt() * 100)))
}

private fun thousands(value: Int): String {
    val text = value.toString()
    return if (text.length > 3) text.dropLast(3) + "," + text.takeLast(3) else text
}

/** What the resort itself reports, or that it hasn't, with its date. */
@Composable
private fun ReportCard(report: SkiReport, now: Long, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val uri = LocalUriHandler.current
    val placeholder = stringResource(Res.string.ski_placeholder)

    @Composable
    fun snow(cm: Double?) = cm?.let { WeatherFormat.snow(it, units).resolve() } ?: placeholder
    WeatherCard(modifier.testTag("ski_report"), title = stringResource(Res.string.ski_report_title), icon = Icons.Filled.AcUnit) {
        if (!report.isCurrent(now)) {
            Text(stringResource(Res.string.ski_no_report), style = WeatherTheme.type.body, color = colors.onSkyMuted)
        } else {
            // A resort's site gives some of these and not others: only what it gives is shown.
            val recent = listOfNotNull(
                report.last24Cm?.let { Res.string.ski_last24 to it },
                report.last48Cm?.let { Res.string.ski_last48 to it },
                report.last72Cm?.let { Res.string.ski_last72 to it },
                report.last7DaysCm?.let { Res.string.ski_last7 to it },
            ).take(3)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                recent.forEach { (label, cm) -> SkiStat(stringResource(label), snow(cm), Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkiStat(stringResource(Res.string.ski_season_total), snow(report.seasonCm), Modifier.weight(1f))
                SkiStat(stringResource(Res.string.ski_depth_base), snow(report.baseDepthCm), Modifier.weight(1f))
                SkiStat(stringResource(Res.string.ski_depth_summit), snow(report.summitDepthCm ?: report.middleDepthCm), Modifier.weight(1f))
            }
            report.conditions?.let { conditions ->
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.ski_conditions, conditions), style = WeatherTheme.type.label, color = colors.onSkyMuted)
            }
            val lifts = report.liftsOpen
            val liftsTotal = report.liftsTotal
            if (lifts != null && liftsTotal != null && liftsTotal > 0) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(Res.string.ski_lifts_open, lifts, liftsTotal), style = WeatherTheme.type.label, color = colors.onSkyMuted)
                Spacer(Modifier.height(4.dp))
                SkiProgress(lifts.toFloat() / liftsTotal)
            }
            val runs = report.runsOpen
            val runsTotal = report.runsTotal
            if (runs != null && runsTotal != null && runsTotal > 0) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.ski_runs_open, runs, runsTotal), style = WeatherTheme.type.label, color = colors.onSkyMuted)
                Spacer(Modifier.height(4.dp))
                SkiProgress(runs.toFloat() / runsTotal)
            }
            report.updatedAtEpochSeconds?.let { at ->
                val offset = localUtcOffsetSeconds(at.toDouble())
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(Res.string.ski_reported_at, WeatherFormat.date(at, offset).resolve(), WeatherFormat.clock(at, offset).resolve()),
                    style = WeatherTheme.type.label,
                    color = colors.onSkyFaint,
                )
            }
        }
        // Where these numbers are from, whichever it is: the resort's page, or a listing of it.
        Spacer(Modifier.height(6.dp))
        val page = report.resortPageUrl
        if (page != null) {
            Text(
                stringResource(Res.string.ski_source_resort, hostOf(page)),
                style = WeatherTheme.type.label,
                color = colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { uri.openUri(page) }.handCursor().testTag("ski_report_source"),
            )
        } else {
            Text(stringResource(Res.string.ski_source_listing), style = WeatherTheme.type.label, color = colors.onSkyFaint, modifier = Modifier.testTag("ski_report_source"))
        }
    }
}

/** "www.northstarcalifornia.com/plan…" as a person would say it. */
private fun hostOf(url: String): String = url.substringAfter("://").substringBefore("/").removePrefix("www.")

/** The hours on the snow, at the summit. */
@Composable
private fun HoursCard(hours: List<SkiForecastHour>, forecast: SkiForecast, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    WeatherCard(modifier.testTag("ski_hours"), title = stringResource(Res.string.ski_hours_title), icon = Icons.Filled.Schedule, padding = 0.dp) {
        LazyRow(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(hours, key = { it.epochSeconds }) { hour ->
                Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(WeatherFormat.hour(hour.epochSeconds, forecast.utcOffsetSeconds).resolve(), style = WeatherTheme.type.label, color = colors.onSkyMuted, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    WeatherGlyph(hour.kind, hour.isDay, Modifier.size(26.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(WeatherFormat.degrees(hour.temperatureC, units), style = WeatherTheme.type.bodyStrong, color = colors.onSky)
                    Text(
                        WeatherFormat.speed(hour.gustKmh, units).resolve(),
                        style = WeatherTheme.type.micro,
                        color = if (hour.gustKmh >= 60) colors.watch else colors.onSkyFaint,
                        maxLines = 1,
                    )
                    Text(
                        if (hour.snowCm >= 0.1) WeatherFormat.snow(hour.snowCm, units).resolve() else " ",
                        style = WeatherTheme.type.micro,
                        color = colors.snow,
                        maxLines = 1,
                    )
                    if ((hour.visibilityM ?: 99_999.0) < LOW_VISIBILITY_M) {
                        Text(stringResource(Res.string.ski_visibility_low), style = WeatherTheme.type.micro, color = colors.watch, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The ten days, a line each: the snow with its range, the sky, the temperatures at the top. */
@Composable
private fun ForecastCard(forecast: SkiForecast, today: Int, trip: Int, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val days = forecast.days.filter { forecast.epochDayOf(it.epochSeconds) >= today }
    WeatherCard(modifier.testTag("ski_forecast"), title = stringResource(Res.string.ski_forecast_title), icon = Icons.Filled.Landscape, padding = 0.dp) {
        days.forEach { day ->
            val epochDay = forecast.epochDayOf(day.epochSeconds)
            val lit = epochDay == trip
            Row(
                Modifier.fillMaxWidth().background(if (lit) colors.accent.copy(alpha = 0.14f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(WeatherFormat.weekday(epochDay.asInstant(), 0)), style = WeatherTheme.type.bodyStrong, color = colors.onSky, modifier = Modifier.width(44.dp))
                WeatherGlyph(day.kind, isDay = true, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(WeatherFormat.snow(day.snowCm, units).resolve(), style = WeatherTheme.type.bodyStrong, color = if (day.snowCm >= 0.5) colors.snow else colors.onSkyFaint, maxLines = 1)
                    if (day.snowHighCm - day.snowLowCm >= 1) {
                        Text(
                            stringResource(Res.string.ski_model_range, WeatherFormat.snow(day.snowLowCm, units).resolve(), WeatherFormat.snow(day.snowHighCm, units).resolve()),
                            style = WeatherTheme.type.micro,
                            color = colors.onSkyFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    stringResource(Res.string.ski_high_low_plain, WeatherFormat.degrees(day.summitHighC, units), WeatherFormat.degrees(day.summitLowC, units)),
                    style = WeatherTheme.type.label,
                    color = colors.onSkyMuted,
                )
            }
        }
    }
}

/** How long, how far, how it was worked out; the same road at the trip's own hours; what's in force on it. */
@Composable
private fun DriveCard(entry: SkiEntry, hasStart: Boolean, startCity: String?, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val uri = LocalUriHandler.current
    val drive = entry.drive
    WeatherCard(modifier.testTag("ski_drive"), title = stringResource(Res.string.ski_drive_title), icon = Icons.Filled.DirectionsCar) {
        when {
            !hasStart -> Text(stringResource(Res.string.ski_no_start), style = WeatherTheme.type.body, color = colors.onSkyMuted)

            drive == null -> Text(stringResource(Res.string.ski_placeholder), style = WeatherTheme.type.value, color = colors.onSkyFaint)

            else -> {
                Text(stringResource(Res.string.ski_drive_now), style = WeatherTheme.type.label, color = colors.onSkyFaint)
                Text(driveText(drive.seconds).resolve(), style = WeatherTheme.type.value, color = colors.onSky)
                Text(WeatherFormat.distance(drive.meters, units).resolve().let { stringResource(Res.string.ski_drive_summary, it) }, style = WeatherTheme.type.label, color = colors.onSkyMuted)
                startCity?.let { Text(stringResource(Res.string.ski_drive_from_city, it), style = WeatherTheme.type.label, color = colors.onSkyMuted) }
                if (drive.source == SkiDriveSource.FREE_FLOW) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(Res.string.ski_drive_free_flow), style = WeatherTheme.type.label, color = colors.onSkyFaint)
                }
                val out = entry.driveOut?.takeIf { it.source == SkiDriveSource.TRAFFIC }
                val back = entry.driveBack?.takeIf { it.source == SkiDriveSource.TRAFFIC }
                if (out != null || back != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        out?.let { SkiStat(stringResource(Res.string.ski_drive_out), driveText(it.seconds).resolve(), Modifier.weight(1f)) }
                        back?.let { SkiStat(stringResource(Res.string.ski_drive_back), driveText(it.seconds).resolve(), Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    entry.chains.isNotEmpty() -> entry.chains.sortedByDescending { it.level.ordinal }.take(MAX_CHAIN_LINES).forEach { control ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.watch, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(stringResource(Res.string.ski_chain_at, stringResource(control.level.labelRes()), control.route, control.place), style = WeatherTheme.type.label, color = colors.onSky)
                                if (control.description.isNotBlank()) Text(control.description, style = WeatherTheme.type.label, color = colors.onSkyMuted)
                            }
                        }
                    }

                    entry.chainsUnavailable -> Unit

                    inCalifornia(entry.page?.facts) -> Text(stringResource(Res.string.ski_chains_none), style = WeatherTheme.type.label, color = colors.good)

                    else -> Text(stringResource(Res.string.ski_chains_unknown), style = WeatherTheme.type.label, color = colors.onSkyFaint)
                }
                if (entry.chainsUnavailable) Text(stringResource(Res.string.ski_chains_unavailable), style = WeatherTheme.type.label, color = colors.watch, modifier = Modifier.testTag("ski_chains_unavailable"))
                entry.page?.facts?.let { facts ->
                    Spacer(Modifier.height(12.dp))
                    PillButton(
                        stringResource(Res.string.ski_directions_button),
                        { uri.openUri("https://www.google.com/maps/dir/?api=1&destination=${facts.latitude},${facts.longitude}") },
                        Modifier.testTag("ski_directions"),
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        filled = false,
                    )
                }
            }
        }
    }
}

/** Caltrans says what's in force in California and nobody here reads anywhere else: outside it, "no chains" isn't known. */
private fun inCalifornia(facts: SkiResortFacts?): Boolean =
    facts != null && facts.latitude in 32.5..42.0 && facts.longitude in -124.5..-117.0 && !(facts.latitude > 39.0 && facts.longitude > -120.0)

@Composable
private fun TicketsCard(entry: SkiEntry, cost: SkiCost, actions: SkiActions, ownPrice: Int?, epochDay: Int, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val uri = LocalUriHandler.current
    val currency = entry.price?.currency ?: "US$"
    val prices = entry.prices
    WeatherCard(modifier.testTag("ski_tickets"), title = stringResource(Res.string.ski_tickets_title), icon = Icons.Filled.ConfirmationNumber) {
        cost.coveredBy?.let { pass ->
            Text(stringResource(Res.string.ski_pass_covers, pass.label()), style = WeatherTheme.type.bodyStrong, color = colors.good)
            Spacer(Modifier.height(8.dp))
        }
        if (cost.coveredBy == null && cost.total != null) {
            Text(SkiPlanner.money(cost.total, currency), style = WeatherTheme.type.value, color = colors.onSky)
            val on = cost.onEpochDay
            Text(
                when {
                    cost.own -> stringResource(Res.string.ski_ticket_party_own)
                    cost.startsAt -> stringResource(Res.string.ski_ticket_party_from)
                    on != null && on != epochDay -> stringResource(Res.string.ski_ticket_party_first_day, WeatherFormat.date(on.asInstant(), 0).resolve())
                    on != null -> stringResource(Res.string.ski_ticket_party_dated, WeatherFormat.date(on.asInstant(), 0).resolve())
                    cost.source == SkiCost.Source.RESORT_TABLE -> stringResource(Res.string.ski_ticket_party_table)
                    else -> stringResource(Res.string.ski_ticket_party)
                },
                style = WeatherTheme.type.label,
                color = colors.onSkyMuted,
            )
            cost.windowTotal?.let { window ->
                Text(stringResource(Res.string.ski_ticket_party_window, SkiPlanner.money(window, currency)), style = WeatherTheme.type.label, color = colors.onSkyMuted)
            }
            if (cost.childrenLeftOut) Text(stringResource(Res.string.ski_ticket_kids_left_out), style = WeatherTheme.type.label, color = colors.watch)
            Spacer(Modifier.height(10.dp))
        }
        val dated = prices?.priceFor(epochDay)?.second
        when {
            dated != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    dated.adultOnline?.let { SkiStat(stringResource(Res.string.ski_ticket_adult_online), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                    dated.adultWindow?.let { SkiStat(stringResource(Res.string.ski_ticket_adult_window), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                }
                if (dated.childOnline != null || dated.childWindow != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        dated.childOnline?.let { SkiStat(stringResource(Res.string.ski_ticket_child_online), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                        dated.childWindow?.let { SkiStat(stringResource(Res.string.ski_ticket_child_window), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                    }
                }
            }

            !prices?.tiers.isNullOrEmpty() -> prices.tiers.forEach { tier ->
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(stringResource(tier.kind.labelRes()), style = WeatherTheme.type.label, color = colors.onSkyFaint)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        tier.price.adultOnline?.let { SkiStat(stringResource(if (tier.onlineIsLowest) Res.string.ski_ticket_adult_from else Res.string.ski_ticket_adult_online), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                        tier.price.adultWindow?.let { SkiStat(stringResource(Res.string.ski_ticket_adult_window), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                        tier.price.child?.let { SkiStat(stringResource(Res.string.ski_ticket_child_short), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                    }
                }
            }

            entry.price?.adultDay != null || entry.price?.childDay != null -> {
                val listed = entry.price
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listed.adultDay?.let { SkiStat(stringResource(Res.string.ski_ticket_adult), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                    listed.childDay?.let { SkiStat(stringResource(Res.string.ski_ticket_child), SkiPlanner.money(it, currency), Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.ski_ticket_note), style = WeatherTheme.type.label, color = colors.onSkyFaint)
            }

            cost.coveredBy == null -> Text(stringResource(Res.string.ski_no_ticket_price), style = WeatherTheme.type.body, color = colors.onSkyMuted)
        }
        if (prices != null && prices.pageUrl.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val checked = prices.fetchedAtEpochSeconds
            val offset = localUtcOffsetSeconds(checked.toDouble())
            Text(
                stringResource(Res.string.ski_source_prices, hostOf(prices.pageUrl), WeatherFormat.date(checked, offset).resolve()),
                style = WeatherTheme.type.label,
                color = colors.onSkyFaint,
                modifier = Modifier.testTag("ski_prices_source"),
            )
        }
        Spacer(Modifier.height(12.dp))
        OwnPriceField(ownPrice) { actions.onOwnPrice(entry.ref.slug, it) }
        // The resort's shop if the app knows it; then whatever link it gave; otherwise a search finds its page.
        val link = prices?.pageUrl?.takeIf { it.isNotEmpty() } ?: entry.price?.purchaseUrl ?: entry.page?.facts?.ticketsUrl ?: ("https://www.google.com/search?q=" + "${entry.ref.name} lift tickets".encodeURLParameter())
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(Res.string.ski_ticket_buy), { uri.openUri(link) }, Modifier.testTag("ski_buy"), icon = Icons.AutoMirrored.Filled.OpenInNew)
    }
}

/** A box for what the household really pays: most mountains sell by the date, and the listing can't know. */
@Composable
private fun OwnPriceField(price: Int?, onChange: (Int?) -> Unit) {
    val colors = WeatherTheme.colors
    var text by remember(price) { mutableStateOf(price?.toString().orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.ski_own_price_label), style = WeatherTheme.type.label, color = colors.onSkyMuted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.width(92.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceRaised).border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (text.isEmpty()) Text(stringResource(Res.string.ski_own_price_hint), style = WeatherTheme.type.body, color = colors.onSkyFaint)
            BasicTextField(
                value = text,
                onValueChange = { typed ->
                    val digits = typed.filter { it.isDigit() }.take(4)
                    text = digits
                    onChange(digits.toIntOrNull())
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = WeatherTheme.type.body.copy(color = colors.onSky),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().testTag("ski_own_price"),
            )
        }
    }
}

/** The mountain's own cameras, freshly taken when this page came up. */
@Composable
private fun WebcamsCard(entry: SkiEntry, pictures: ImageLoader, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val uri = LocalUriHandler.current
    WeatherCard(modifier.testTag("ski_cams"), title = stringResource(Res.string.ski_cams_title), icon = Icons.Filled.Videocam, padding = 0.dp) {
        LazyRow(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entry.page?.webcams.orEmpty(), key = { it.imageUrl }) { cam ->
                Column(Modifier.width(220.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Image) { uri.openUri(cam.imageUrl) }.handCursor()) {
                    AsyncImage(
                        model = cam.thumbnailUrl,
                        imageLoader = pictures,
                        contentDescription = cam.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(colors.surfaceRaised),
                    )
                    Spacer(Modifier.height(4.dp))
                    // In from the edge, clear of the corner the column is clipped to.
                    Text(cam.title, style = WeatherTheme.type.label, color = colors.onSkyMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MountainCard(facts: SkiResortFacts, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val uri = LocalUriHandler.current
    val placeholder = stringResource(Res.string.ski_placeholder)
    WeatherCard(modifier.testTag("ski_mountain"), title = stringResource(Res.string.ski_mountain_title), icon = Icons.Filled.Landscape) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkiStat(stringResource(Res.string.ski_mountain_summit), facts.summitElevationM?.let { heightText(it, units) } ?: placeholder, Modifier.weight(1f))
            SkiStat(stringResource(Res.string.ski_mountain_base), facts.baseElevationM?.let { heightText(it, units) } ?: placeholder, Modifier.weight(1f))
            SkiStat(stringResource(Res.string.ski_mountain_vertical), facts.verticalM?.let { heightText(it, units) } ?: placeholder, Modifier.weight(1f))
        }
        val parts = listOfNotNull(facts.runsBeginner, facts.runsIntermediate, facts.runsAdvanced, facts.runsExpert)
        if (parts.isNotEmpty() || facts.acres != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                facts.acres?.let { SkiStat(stringResource(Res.string.ski_mountain_acres), thousands(it), Modifier.weight(1f)) }
                if (parts.isNotEmpty()) SkiStat(stringResource(Res.string.ski_runs_label), parts.sum().toString(), Modifier.weight(1f))
                // Under the row above: its three columns.
                Spacer(Modifier.weight(if (parts.isNotEmpty() && facts.acres != null) 1f else 2f))
            }
        }
        facts.websiteUrl?.let { url ->
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(Res.string.ski_website_button), { uri.openUri(url) }, icon = Icons.AutoMirrored.Filled.OpenInNew, filled = false)
        }
    }
}

private const val MAX_CHAIN_LINES = 4

/** Under this the top of the mountain is in cloud: flat light at best. */
private const val LOW_VISIBILITY_M = 800.0
