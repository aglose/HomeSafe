package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.ui.notification.drawGlyph
import com.meticulouscreations.homesafe.weather.domain.DayForecast
import com.meticulouscreations.homesafe.weather.domain.DayOutlook
import com.meticulouscreations.homesafe.weather.domain.OutlookKind
import com.meticulouscreations.homesafe.weather.domain.Outlooks
import com.meticulouscreations.homesafe.weather.domain.Precipitation
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherStory
import com.meticulouscreations.homesafe.weather.ui.notice.look
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_outlook_cold
import homesafe.shared.generated.resources.weather_outlook_dry
import homesafe.shared.generated.resources.weather_outlook_heat
import homesafe.shared.generated.resources.weather_outlook_mix
import homesafe.shared.generated.resources.weather_outlook_next_mix
import homesafe.shared.generated.resources.weather_outlook_next_rain
import homesafe.shared.generated.resources.weather_outlook_next_snow
import homesafe.shared.generated.resources.weather_outlook_next_storm
import homesafe.shared.generated.resources.weather_outlook_rain
import homesafe.shared.generated.resources.weather_outlook_snow
import homesafe.shared.generated.resources.weather_outlook_storm
import homesafe.shared.generated.resources.weather_outlook_tomorrow
import homesafe.shared.generated.resources.weather_outlook_wind
import homesafe.shared.generated.resources.weather_stat_amount_chance
import homesafe.shared.generated.resources.weather_today
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What sits on a coloured tile or mark: nearly black, readable on every colour one comes in. */
internal val OnWeatherColor = Color(0xFF06121F)

/** The colour a day of [kind] is lit in: blue for rain, white for snow, violet for storms, amber for what the air is doing. */
@Composable
private fun outlookColor(kind: OutlookKind): Color? {
    val colors = WeatherTheme.colors
    return when (kind) {
        OutlookKind.DRY -> null
        OutlookKind.RAIN -> colors.rain
        OutlookKind.SNOW -> colors.snow
        OutlookKind.MIX -> colors.accent
        OutlookKind.STORM -> colors.violet
        OutlookKind.WIND, OutlookKind.HEAT, OutlookKind.COLD -> colors.watch
    }
}

private val OutlookKind.word: StringResource
    get() = when (this) {
        OutlookKind.DRY -> Res.string.weather_outlook_dry
        OutlookKind.RAIN -> Res.string.weather_outlook_rain
        OutlookKind.SNOW -> Res.string.weather_outlook_snow
        OutlookKind.MIX -> Res.string.weather_outlook_mix
        OutlookKind.STORM -> Res.string.weather_outlook_storm
        OutlookKind.WIND -> Res.string.weather_outlook_wind
        OutlookKind.HEAT -> Res.string.weather_outlook_heat
        OutlookKind.COLD -> Res.string.weather_outlook_cold
    }

private val OutlookKind.icon: WeatherNoticeIcon?
    get() = when (this) {
        OutlookKind.DRY -> null
        OutlookKind.RAIN -> WeatherNoticeIcon.RAIN
        OutlookKind.SNOW -> WeatherNoticeIcon.SNOW
        OutlookKind.MIX -> WeatherNoticeIcon.MIX
        OutlookKind.STORM -> WeatherNoticeIcon.STORM
        OutlookKind.WIND -> WeatherNoticeIcon.WIND
        OutlookKind.HEAT -> WeatherNoticeIcon.HEAT
        OutlookKind.COLD -> WeatherNoticeIcon.COLD
    }

/**
 * Today and tomorrow side by side, each a word you can read from across the room: a day with
 * weather worth planning around is a solid tile of its colour, a dry one stays dark glass. [sky]
 * and [skyTomorrow] are the days' own skies, which a dry tile wears as its glyph and names.
 */
@Composable
internal fun OutlookTiles(outlooks: Outlooks, sky: WeatherKind, skyTomorrow: WeatherKind?, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = OutlookTileHeight).height(IntrinsicSize.Min).testTag("weather_outlook"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlookTile(stringResource(Res.string.weather_today), outlooks.today, sky, Modifier.weight(1f).fillMaxHeight().testTag("weather_outlook_today"))
        if (outlooks.tomorrow != null) {
            OutlookTile(stringResource(Res.string.weather_outlook_tomorrow), outlooks.tomorrow, skyTomorrow ?: sky, Modifier.weight(1f).fillMaxHeight().testTag("weather_outlook_tomorrow"))
        } else {
            // The only tile keeps its half of the row.
            Spacer(Modifier.weight(1f))
        }
    }
}

private val OutlookTileHeight = 212.dp

@Composable
private fun OutlookTile(name: String, outlook: DayOutlook, sky: WeatherKind, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val lit = outlookColor(outlook.kind)
    val ink = if (lit != null) OnWeatherColor else colors.onSky
    val soft = if (lit != null) OnWeatherColor else colors.onSkyMuted
    Column(
        modifier
            .clip(WeatherCardShape)
            .background(lit ?: colors.card)
            .then(if (lit != null) Modifier else Modifier.border(1.dp, colors.cardBorder, WeatherCardShape))
            .semantics(mergeDescendants = true) {}
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(name.uppercase(), style = type.micro, color = soft, modifier = Modifier.weight(1f), maxLines = 1)
            val icon = outlook.kind.icon
            if (icon != null) {
                Canvas(Modifier.size(40.dp)) { drawGlyph(icon.look.glyph, Offset.Zero, size.minDimension, ink) }
            } else {
                WeatherGlyph(sky, isDay = true, Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
        Text(stringResource(outlook.kind.word), style = type.heroSmall, color = ink)
        Text(outlook.timing.resolve(), style = type.headline.copy(shadow = null), color = ink)
        // How soon it starts; or, on a day with nothing falling to time, its sky.
        val note = outlook.note?.resolve() ?: if (outlook.kind == OutlookKind.DRY || outlook.kind in AIR_KINDS) stringResource(sky.label()) else null
        if (note != null) Text(note, style = type.label, color = soft)
        if (outlook.marks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                outlook.marks.forEach { mark ->
                    Box(Modifier.clip(CircleShape).background(if (lit != null) OnWeatherColor.copy(alpha = 0.14f) else colors.hairline).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(mark.resolve(), style = type.label, color = ink, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Kinds with no rain to time, whose tile names the day's sky as well. */
private val AIR_KINDS = setOf(OutlookKind.WIND, OutlookKind.HEAT, OutlookKind.COLD)

/** The first wet day after tomorrow, under the tiles: a tap opens the forecast it is part of. */
@Composable
internal fun NextWetDayRow(report: WeatherReport, day: DayForecast, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = WeatherTheme.units
    val weekday = stringResource(WeatherFormat.weekday(day.epochSeconds + 12 * 3600, report.utcOffsetSeconds))
    val title = stringResource(
        when {
            day.isSnowy -> Res.string.weather_outlook_next_snow
            day.kind.isStorm -> Res.string.weather_outlook_next_storm
            day.kind.precipitation == Precipitation.MIX -> Res.string.weather_outlook_next_mix
            else -> Res.string.weather_outlook_next_rain
        },
        weekday,
    )
    val amount = (if (day.isSnowy) WeatherFormat.snow(day.snowfallCm, units) else WeatherFormat.precipitation(day.precipitationMm, units)).resolve()
    val numbers = day.precipitationProbability?.let { stringResource(Res.string.weather_stat_amount_chance, amount, WeatherFormat.percent(it)) } ?: amount
    Row(
        modifier
            .clip(WeatherCardShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, WeatherCardShape)
            .clickable(role = Role.Button, onClick = onOpen)
            .handCursor()
            .testTag("weather_next_wet_day")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WeatherGlyph(day.kind, isDay = true, Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = type.bodyStrong, color = colors.onSky, modifier = Modifier.weight(1f))
        Text(numbers, style = type.label, color = colors.onSkyMuted, maxLines = 1)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSkyMuted, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

/** The tiles and the row under them for [report], as the Today tab lays them out. */
@Composable
private fun OutlookPreviewBody(report: WeatherReport) {
    val now = WeatherFixtures.NOW
    val outlooks = WeatherStory.outlooks(report, WeatherTheme.units, now) ?: return
    Column(
        Modifier.background(Brush.verticalGradient(listOf(Color(0xFF4A8AC8), Color(0xFF7DB4E6)))).padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (outlooks.hasNews) OutlookTiles(outlooks, report.current.kind, report.tomorrow(now)?.kind, Modifier.cardWidth())
        WeatherStory.nextWetDay(report, now)?.let { NextWetDayRow(report, it, onOpen = {}, modifier = Modifier.cardWidth()) }
    }
}

// Rain this evening, tomorrow's morning shower too.
@Preview(widthDp = 412)
@Composable
private fun OutlookRainPreview() {
    OutlookPreviewBody(WeatherFixtures.reportAt(WeatherFixtures.NOW))
}

// Dry today, snow tomorrow.
@Preview(widthDp = 412)
@Composable
private fun OutlookSnowTomorrowPreview() {
    OutlookPreviewBody(WeatherFixtures.reportAt(WeatherFixtures.NOW, shift = 8))
}

// A storm today, then dry.
@Preview(widthDp = 412)
@Composable
private fun OutlookStormPreview() {
    OutlookPreviewBody(WeatherFixtures.reportAt(WeatherFixtures.NOW, shift = 5))
}

// Two dry days: no tiles, only when the next rain is.
@Preview(widthDp = 412)
@Composable
private fun OutlookDryPreview() {
    OutlookPreviewBody(WeatherFixtures.reportAt(WeatherFixtures.NOW, shift = 2))
}
