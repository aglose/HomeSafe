package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.PlaceWeather
import com.meticulouscreations.homesafe.weather.RadarLoad
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import com.meticulouscreations.homesafe.weather.domain.NearTerm
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherStory
import com.meticulouscreations.homesafe.weather.domain.WeatherTip
import com.meticulouscreations.homesafe.weather.ui.radar.RadarMap
import com.meticulouscreations.homesafe.weather.ui.radar.RadarMapState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.weather_alert_from
import homesafe.shared.generated.resources.weather_alert_until
import homesafe.shared.generated.resources.weather_attribution
import homesafe.shared.generated.resources.weather_feels_like
import homesafe.shared.generated.resources.weather_high_low
import homesafe.shared.generated.resources.weather_hourly_hint
import homesafe.shared.generated.resources.weather_hourly_title
import homesafe.shared.generated.resources.weather_loading
import homesafe.shared.generated.resources.weather_next_hours_dry
import homesafe.shared.generated.resources.weather_next_hours_title
import homesafe.shared.generated.resources.weather_outdoor_fair
import homesafe.shared.generated.resources.weather_outdoor_feels
import homesafe.shared.generated.resources.weather_outdoor_good
import homesafe.shared.generated.resources.weather_outdoor_great
import homesafe.shared.generated.resources.weather_outdoor_none
import homesafe.shared.generated.resources.weather_outdoor_title
import homesafe.shared.generated.resources.weather_radar_card_open
import homesafe.shared.generated.resources.weather_radar_card_title
import homesafe.shared.generated.resources.weather_scrub_at
import homesafe.shared.generated.resources.weather_scrub_at_day
import homesafe.shared.generated.resources.weather_stale
import homesafe.shared.generated.resources.weather_time_range
import homesafe.shared.generated.resources.weather_tip_coat
import homesafe.shared.generated.resources.weather_tip_ice
import homesafe.shared.generated.resources.weather_tip_layers
import homesafe.shared.generated.resources.weather_tip_sunscreen
import homesafe.shared.generated.resources.weather_tip_umbrella
import homesafe.shared.generated.resources.weather_tip_wind
import homesafe.shared.generated.resources.weather_updated
import org.jetbrains.compose.resources.stringResource

/** The widest the weather screens' column of cards goes: a phone's width, kept on a tablet or a desktop window. */
internal val WeatherContentWidth = 620.dp

/**
 * The Today tab for one place: the temperature and what the day is doing, written straight on
 * the sky, then the cards — any warning in force, the next two hours when rain is near, the next
 * day hour by hour, when to be outside, a look at the radar, and the details.
 *
 * [scrubbed] is the hour a finger is holding on the hourly strip, when one is: the top of the
 * screen then speaks for that hour, as the sky behind it does.
 */
@Composable
internal fun TodayScreen(
    entry: PlaceWeather,
    nowEpochSeconds: Long,
    scrubbed: HourForecast?,
    radar: RadarLoad,
    tiles: MapTileSource,
    listState: LazyListState,
    padding: PaddingValues,
    onScrub: (Long?) -> Unit,
    onOpenAlert: (String) -> Unit,
    onOpenRadar: () -> Unit,
    onOpenForecast: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val report = entry.report
    if (report == null) {
        WaitingForForecast(entry, padding, onRetry, modifier)
        return
    }
    val near = remember(report, nowEpochSeconds) { WeatherStory.nearTerm(report, nowEpochSeconds) }
    val showNextHours = near != NearTerm.Dry || remember(report, nowEpochSeconds) { report.slicesFrom(nowEpochSeconds).take(9).any { it.isWet } }
    val units = WeatherTheme.units
    val outlooks = remember(report, units, nowEpochSeconds) { WeatherStory.outlooks(report, units, nowEpochSeconds) }
    val showTiles = outlooks?.hasNews == true
    val nextWet = remember(report, nowEpochSeconds) { WeatherStory.nextWetDay(report, nowEpochSeconds) }
    LazyColumn(
        modifier.fillMaxSize().testTag("weather_today"),
        state = listState,
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero", contentType = "hero") {
            // With the tiles on screen, the day's one sentence would only say what they say.
            Hero(report, nowEpochSeconds, scrubbed, Modifier.widthIn(max = WeatherContentWidth).fillMaxWidth().padding(horizontal = 24.dp), showHeadline = !showTiles)
        }
        if (entry.error != null) {
            item(key = "stale") {
                StaleNotice(
                    stringResource(Res.string.weather_stale, WeatherFormat.clock(report.fetchedAtEpochSeconds, report.offsetAt(report.fetchedAtEpochSeconds)).resolve()),
                    onRetry,
                    Modifier.cardWidth(),
                )
            }
        }
        items(report.alerts.size, key = { "alert-${report.alerts[it].id}" }, contentType = { "alert" }) { i ->
            val alert = report.alerts[i]
            AlertBanner(alert, report::offsetAt, nowEpochSeconds, onOpenAlert, Modifier.cardWidth())
        }
        if (showTiles) {
            item(key = "outlook", contentType = "outlook") {
                OutlookTiles(outlooks, report.current.kind, report.tomorrow(nowEpochSeconds)?.kind, Modifier.cardWidth())
            }
        }
        if (nextWet != null) {
            item(key = "next-wet", contentType = "next-wet") { NextWetDayRow(report, nextWet, onOpenForecast, Modifier.cardWidth()) }
        }
        if (showNextHours) {
            item(key = "next", contentType = "card") {
                WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.weather_next_hours_title), icon = Icons.Filled.WaterDrop) {
                    Text(
                        WeatherStory.nearTermPhrase(near, nowEpochSeconds)?.resolve() ?: stringResource(Res.string.weather_next_hours_dry),
                        style = WeatherTheme.type.bodyStrong,
                        color = WeatherTheme.colors.onSky,
                    )
                    Spacer(Modifier.height(12.dp))
                    NextHoursChart(report, nowEpochSeconds)
                }
            }
        }
        item(key = "hourly", contentType = "card") {
            WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.weather_hourly_title), icon = Icons.Filled.Schedule, padding = 0.dp) {
                HourlyStrip(report, nowEpochSeconds, onScrub)
                Text(
                    stringResource(Res.string.weather_hourly_hint),
                    style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing),
                    color = WeatherTheme.colors.onSkyFaint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        item(key = "outdoor", contentType = "card") { OutdoorCard(report, nowEpochSeconds, Modifier.cardWidth()) }
        item(key = "radar", contentType = "card") { RadarCard(entry.place, radar, tiles, animated, onOpenRadar, Modifier.cardWidth()) }
        item(key = "details", contentType = "details") { DetailTiles(report, entry.place, nowEpochSeconds, Modifier.cardWidth()) }
        item(key = "footer", contentType = "footer") {
            Column(Modifier.cardWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(
                    stringResource(Res.string.weather_updated, WeatherFormat.clock(report.fetchedAtEpochSeconds, report.offsetAt(report.fetchedAtEpochSeconds)).resolve()),
                    style = WeatherTheme.type.label,
                    color = WeatherTheme.colors.onSkyMuted,
                )
                Text(stringResource(Res.string.weather_attribution), style = WeatherTheme.type.label, color = WeatherTheme.colors.onSkyFaint)
            }
        }
    }
}

/** A card's place in the column: full width on a phone, a phone's width on anything wider, with the screen's margin. */
internal fun Modifier.cardWidth(): Modifier = widthIn(max = WeatherContentWidth).fillMaxWidth().padding(horizontal = 14.dp)

/** The top of the Today tab: the temperature, the sky in a word, the high and low, the day's one sentence, and what to bring. */
@Composable
private fun Hero(report: WeatherReport, nowEpochSeconds: Long, scrubbed: HourForecast?, modifier: Modifier = Modifier, showHeadline: Boolean = true) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = WeatherTheme.units
    val current = report.current
    val today = report.today(nowEpochSeconds)
    val headline = remember(report, units, nowEpochSeconds) { WeatherStory.headline(report, units, nowEpochSeconds) }
    val tips = remember(report, nowEpochSeconds) { WeatherStory.tips(report, nowEpochSeconds) }
    Column(modifier.testTag("weather_hero")) {
        Spacer(Modifier.height(4.dp))
        AnimatedContent(scrubbed, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "hero") { hour ->
            Column {
                Text(WeatherFormat.degrees(hour?.temperatureC ?: current.temperatureC, units), style = type.hero, color = colors.onSky)
                Text(stringResource((hour?.kind ?: current.kind).label(hour?.isDay ?: current.isDay)), style = type.title, color = colors.onSky)
                Spacer(Modifier.height(2.dp))
                if (hour != null) {
                    // The sky and these words are showing another hour: say which.
                    val at = WeatherFormat.hour(hour.epochSeconds, report.offsetAt(hour.epochSeconds)).resolve()
                    val text = if (WeatherFormat.sameDay(hour.epochSeconds, nowEpochSeconds, report.utcOffsetSeconds)) {
                        stringResource(Res.string.weather_scrub_at, at)
                    } else {
                        stringResource(Res.string.weather_scrub_at_day, stringResource(WeatherFormat.weekdayLong(hour.epochSeconds, report.utcOffsetSeconds)), at)
                    }
                    Text(text, style = type.headline, color = colors.sun)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (today != null) {
                            Text(stringResource(Res.string.weather_high_low, WeatherFormat.degrees(today.highC, units), WeatherFormat.degrees(today.lowC, units)), style = type.headline, color = colors.onSkyMuted)
                        }
                        if (WeatherStory.feelsDifferent(current)) {
                            Text(stringResource(Res.string.weather_feels_like, WeatherFormat.degrees(current.feelsLikeC, units)), style = type.headline, color = colors.onSkyMuted)
                        }
                    }
                }
            }
        }
        if (showHeadline) {
            Spacer(Modifier.height(18.dp))
            Text(headline.resolve(), style = type.headline, color = colors.onSky, modifier = Modifier.testTag("weather_headline"))
        }
        if (tips.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tips.forEach { TipChip(it) } }
        }
        Spacer(Modifier.height(if (showHeadline) 28.dp else 16.dp))
    }
}

@Composable
private fun TipChip(tip: WeatherTip) {
    val colors = WeatherTheme.colors
    val (icon, label) = when (tip) {
        WeatherTip.UMBRELLA -> Icons.Filled.Umbrella to Res.string.weather_tip_umbrella
        WeatherTip.SUNSCREEN -> Icons.Filled.WbSunny to Res.string.weather_tip_sunscreen
        WeatherTip.LAYERS -> Icons.Filled.Layers to Res.string.weather_tip_layers
        WeatherTip.COAT -> Icons.Filled.Checkroom to Res.string.weather_tip_coat
        WeatherTip.ICE -> Icons.Filled.AcUnit to Res.string.weather_tip_ice
        WeatherTip.WIND -> Icons.Filled.Air to Res.string.weather_tip_wind
    }
    Row(
        Modifier.clip(CircleShape).background(colors.card).border(1.dp, colors.cardBorder, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSky, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), style = WeatherTheme.type.label, color = colors.onSky, maxLines = 1)
    }
}

/**
 * A government warning: its name, from when (only while that is still ahead, so a warning for
 * tomorrow doesn't read as in force now), until when, and a way into the whole of it. [offsetAt]
 * reads the place's clock at a moment.
 */
@Composable
private fun AlertBanner(alert: WeatherAlert, offsetAt: (Long) -> Int, nowEpochSeconds: Long, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val tint = if (alert.isUrgent) colors.danger else colors.watch
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.26f))
            .background(colors.card)
            .border(1.dp, tint.copy(alpha = 0.7f), shape)
            .clickable(role = Role.Button) { onOpen(alert.id) }
            .testTag("weather_alert")
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(alert.event, style = WeatherTheme.type.bodyStrong, color = colors.onSky)
            alert.onsetEpochSeconds?.takeIf { it > nowEpochSeconds }?.let { onset ->
                Text(
                    stringResource(Res.string.weather_alert_from, stringResource(WeatherFormat.weekday(onset, offsetAt(onset))), WeatherFormat.clock(onset, offsetAt(onset)).resolve()),
                    style = WeatherTheme.type.label,
                    color = colors.onSky,
                )
            }
            alert.endsEpochSeconds?.let { ends ->
                Text(
                    stringResource(Res.string.weather_alert_until, stringResource(WeatherFormat.weekday(ends, offsetAt(ends))), WeatherFormat.clock(ends, offsetAt(ends)).resolve()),
                    style = WeatherTheme.type.label,
                    color = colors.onSkyMuted,
                )
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSkyMuted)
    }
}

/** The forecast on screen is an old one because the last fetch failed. */
@Composable
private fun StaleNotice(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    Row(
        modifier.clip(CircleShape).background(colors.card).border(1.dp, colors.watch.copy(alpha = 0.6f), CircleShape).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = WeatherTheme.type.label, color = colors.onSky, modifier = Modifier.weight(1f))
        Text(
            stringResource(Res.string.common_retry),
            style = WeatherTheme.type.bodyStrong,
            color = colors.accent,
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** When it will be best to be outside, and the day's hours lit by how good each is. */
@Composable
private fun OutdoorCard(report: WeatherReport, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val window = remember(report, nowEpochSeconds) { WeatherStory.outdoorWindow(report, nowEpochSeconds) }
    WeatherCard(modifier, title = stringResource(Res.string.weather_outdoor_title), icon = Icons.AutoMirrored.Filled.DirectionsWalk) {
        if (window == null) {
            Text(stringResource(Res.string.weather_outdoor_none), style = WeatherTheme.type.bodyStrong, color = colors.onSky)
        } else {
            val offset = report.utcOffsetSeconds
            val day = if (WeatherFormat.sameDay(window.startEpochSeconds, nowEpochSeconds, offset)) null else stringResource(WeatherFormat.weekday(window.startEpochSeconds, offset))
            val range = stringResource(
                Res.string.weather_time_range,
                WeatherFormat.hour(window.startEpochSeconds, report.offsetAt(window.startEpochSeconds)).resolve(),
                WeatherFormat.hour(window.endEpochSeconds, report.offsetAt(window.endEpochSeconds)).resolve(),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(range, style = WeatherTheme.type.value, color = colors.onSky)
                if (day != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(day, style = WeatherTheme.type.bodyStrong, color = colors.onSkyMuted, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            val word = stringResource(
                when {
                    window.score >= 82 -> Res.string.weather_outdoor_great
                    window.score >= 68 -> Res.string.weather_outdoor_good
                    else -> Res.string.weather_outdoor_fair
                },
            )
            Text(stringResource(Res.string.weather_outdoor_feels, word, WeatherFormat.degrees(window.temperatureC, units)), style = WeatherTheme.type.label, color = colors.onSkyMuted)
        }
        Spacer(Modifier.height(12.dp))
        ComfortStrip(report, nowEpochSeconds)
    }
}

/** A window onto the radar: the newest frame over the place, still. A tap opens the radar itself. */
@Composable
private fun RadarCard(place: Place, radar: RadarLoad, tiles: MapTileSource, animated: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val map = remember(place.id, place.latitude, place.longitude) { RadarMapState(MapCamera.at(place.latitude, place.longitude, 6.4)) }
    val timeline = radar.timeline
    val openLabel = stringResource(Res.string.weather_radar_card_open)
    Box(
        modifier
            .height(190.dp)
            .clip(WeatherCardShape)
            .border(1.dp, colors.cardBorder, WeatherCardShape)
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
            .testTag("weather_radar_card"),
    ) {
        RadarMap(
            state = map,
            timeline = timeline,
            position = { (timeline?.latestObserved ?: 0).toFloat() },
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            markerLatitude = place.latitude,
            markerLongitude = place.longitude,
            interactive = false,
            wholeLoop = false,
            animated = animated,
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape).background(colors.cardSolid).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardLabel(stringResource(Res.string.weather_radar_card_title), icon = Icons.Filled.Radar)
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = colors.onSky,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape).background(colors.cardSolid).padding(4.dp),
        )
    }
}

/** The details, two to a row: whichever of them this forecast has the numbers for. */
@Composable
private fun DetailTiles(report: WeatherReport, place: Place, nowEpochSeconds: Long, modifier: Modifier = Modifier) {
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        add { UvTile(report, nowEpochSeconds, it) }
        add { WindTile(report, it) }
        if (report.today(nowEpochSeconds)?.sunriseEpochSeconds != null) add { SunTile(report, place, nowEpochSeconds, it) }
        add { MoonTile(report, nowEpochSeconds, it) }
        add { HumidityTile(report, it) }
        if (report.current.pressureHpa != null) add { PressureTile(report, nowEpochSeconds, it) }
        if (report.today(nowEpochSeconds) != null) add { PrecipitationTile(report, nowEpochSeconds, it) }
        if (report.air != null) add { AirQualityTile(report, it) }
        if (report.current.visibilityM != null) add { VisibilityTile(report, it) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { tile -> tile(Modifier.weight(1f)) }
                // An odd one out keeps its half of the row.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** No forecast yet for this place: still on its way, or the fetch failed and there is nothing older to show. */
@Composable
internal fun WaitingForForecast(entry: PlaceWeather, padding: PaddingValues, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    Column(modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val error = entry.error
        if (error != null) {
            Text(error.resolve(), style = WeatherTheme.type.headline, color = colors.onSky)
            Spacer(Modifier.height(16.dp))
            PillButton(stringResource(Res.string.common_retry), onRetry)
        } else {
            Text(stringResource(Res.string.weather_loading), style = WeatherTheme.type.headline, color = colors.onSkyMuted)
        }
    }
}

/** The weather screens' button: a pill of the accent colour, with [icon] before its [label] when given. */
@Composable
internal fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, filled: Boolean = true) {
    val colors = WeatherTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(if (filled) colors.accent else colors.card)
            .then(if (filled) Modifier else Modifier.border(1.dp, colors.cardBorder, CircleShape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (filled) Color(0xFF06121F) else colors.onSky
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = WeatherTheme.type.bodyStrong, color = content, maxLines = 1)
    }
}

// Read this afternoon: a warning that starts tomorrow says when, and a finger held on tomorrow
// morning in the strip (which marks the new day with its name) says which day the sky is showing.
@Preview(widthDp = 412)
@Composable
private fun WeatherTodayAheadPreview() {
    val report = WeatherFixtures.report
    val tomorrowMorning = WeatherFixtures.TODAY + 33 * 3_600L
    val alert = WeatherFixtures.windAdvisory.copy(onsetEpochSeconds = WeatherFixtures.TODAY + 30 * 3_600L, endsEpochSeconds = WeatherFixtures.TODAY + 42 * 3_600L)
    Column(
        Modifier.background(Brush.verticalGradient(listOf(Color(0xFF4A6A8C), Color(0xFF1B2638)))).padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hero(report, WeatherFixtures.NOW, report.hourly.first { it.epochSeconds == tomorrowMorning }, Modifier.padding(horizontal = 24.dp))
        AlertBanner(alert, report::offsetAt, WeatherFixtures.NOW, onOpen = {}, modifier = Modifier.cardWidth())
        WeatherCard(Modifier.cardWidth(), padding = 0.dp) {
            HourlyStrip(report, WeatherFixtures.NOW, onScrub = {})
        }
    }
}
