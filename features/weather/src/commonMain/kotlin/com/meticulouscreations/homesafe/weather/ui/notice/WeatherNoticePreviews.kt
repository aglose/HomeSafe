package com.meticulouscreations.homesafe.weather.ui.notice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.ui.notification.NotificationGlyph
import com.meticulouscreations.homesafe.ui.notification.drawGlyph
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeChart
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

// The names under the glyphs and the words on the cards are sample data: what a notification would be handed.

// Local midnight on Thursday 9 October 2025 in Portland.
private const val MIDNIGHT = 1_759_993_200L

@Composable
private fun NoticeCard(art: WeatherNoticeArt, heightDp: Int = WeatherNoticePainter.CARD_HEIGHT_DP) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.size(WeatherNoticePainter.CARD_WIDTH_DP.dp, heightDp.dp).clip(RoundedCornerShape(16.dp))) { drawNoticeCard(art, measurer) }
}

/** A shower: dry for [lead] quarter-hours, then [rates] in mm/h. */
private fun shower(lead: Int, vararg rates: Double, snow: Boolean = false): WeatherNoticeChart.Soon {
    val start = MIDNIGHT + 15 * 3600
    val slices = List(9) { i ->
        val rate = rates.getOrElse(i - lead) { 0.0 }.takeIf { i >= lead } ?: 0.0
        PrecipSlice(start + i * 900L, rate / 4, if (snow) rate / 4 else 0.0)
    }
    return WeatherNoticeChart.Soon(slices, start + lead * 900L, emptyList())
}

/** A day whose temperature runs from [lowC] before dawn to [highC] mid-afternoon, wet where [rain] gives millimetres for the hour. */
private fun day(lowC: Double, highC: Double, now: Long? = null, snow: Boolean = false, rain: (Int) -> Double = { 0.0 }): WeatherNoticeChart.Day {
    val hours = List(24) { h ->
        val warmth = (1 - cos((h - 4) / 24.0 * 2 * PI)) / 2
        val mm = max(0.0, rain(h))
        HourForecast(
            epochSeconds = MIDNIGHT + h * 3600L,
            temperatureC = lowC + (highC - lowC) * warmth,
            feelsLikeC = lowC + (highC - lowC) * warmth,
            precipitationMm = mm,
            snowfallCm = if (snow) mm else 0.0,
            precipitationProbability = if (mm > 0) 80 else 5,
            weatherCode = if (mm <= 0) {
                2
            } else if (snow) {
                73
            } else {
                63
            },
        )
    }
    return WeatherNoticeChart.Day(hours, now, emptyList())
}

private val HALF_HOURS = listOf("Now", "3:30 PM", "4 PM", "4:30 PM", "5 PM")
private val QUARTERS = listOf("12 AM", "6 AM", "12 PM", "6 PM")

@Preview
@Composable
private fun NoticeRainSoonPreview() {
    NoticeCard(
        WeatherNoticeArt(
            icon = WeatherNoticeIcon.RAIN,
            caption = "Portland",
            headline = "3:30 PM",
            facts = listOf(WeatherNoticeArt.Fact(WeatherNoticeIcon.RAIN, "45 min"), WeatherNoticeArt.Fact(null, "Heavy at times")),
            chart = shower(2, 1.2, 4.0, 9.5, 6.0, 2.2, 0.6),
            ticks = HALF_HOURS,
        ),
    )
}

@Preview
@Composable
private fun NoticeSnowSoonNightPreview() {
    NoticeCard(
        WeatherNoticeArt(
            icon = WeatherNoticeIcon.SNOW,
            isDay = false,
            headline = "3:45 PM",
            facts = listOf(WeatherNoticeArt.Fact(WeatherNoticeIcon.SNOW, "75 min")),
            chart = shower(3, 0.8, 1.6, 2.4, 2.8, 2.0, 1.2, snow = true),
            ticks = HALF_HOURS,
        ),
    )
}

@Preview
@Composable
private fun NoticeStormTodayPreview() {
    NoticeCard(
        WeatherNoticeArt(
            icon = WeatherNoticeIcon.STORM,
            caption = "Thursday",
            headline = "71° / 55°",
            facts = listOf(
                WeatherNoticeArt.Fact(WeatherNoticeIcon.STORM, "0.8 in"),
                WeatherNoticeArt.Fact(WeatherNoticeIcon.WIND, "45 mph"),
                WeatherNoticeArt.Fact(WeatherNoticeIcon.SUN, "UV 8"),
            ),
            chart = day(13.0, 22.0, now = MIDNIGHT + 7 * 3600 + 1800) { h -> if (h in 14..18) 6.0 - (h - 16) * (h - 16) * 1.2 else 0.0 },
            ticks = QUARTERS,
        ),
    )
}

@Preview
@Composable
private fun NoticeColderTomorrowPreview() {
    NoticeCard(
        WeatherNoticeArt(
            icon = WeatherNoticeIcon.COLDER,
            caption = "Friday",
            headline = "41° / 28°",
            facts = listOf(WeatherNoticeArt.Fact(WeatherNoticeIcon.FREEZE, "28°"), WeatherNoticeArt.Fact(WeatherNoticeIcon.COLDER, "16°")),
            chart = day(-2.5, 5.0),
            ticks = QUARTERS,
        ),
    )
}

@Preview
@Composable
private fun NoticeHeatTodayTallPreview() {
    // As iOS shows one: the same card with more room for the chart.
    NoticeCard(
        WeatherNoticeArt(
            icon = WeatherNoticeIcon.HEAT,
            caption = "Thursday",
            headline = "104° / 78°",
            facts = listOf(WeatherNoticeArt.Fact(WeatherNoticeIcon.HEAT, "109°"), WeatherNoticeArt.Fact(WeatherNoticeIcon.AIR, "AQI 160"), WeatherNoticeArt.Fact(WeatherNoticeIcon.SUN, "UV 10")),
            chart = day(25.5, 40.0, now = MIDNIGHT + 8 * 3600),
            ticks = QUARTERS,
        ),
        heightDp = 240,
    )
}

@Preview
@Composable
private fun NoticeTornadoWarningPreview() {
    NoticeCard(
        WeatherNoticeArt(icon = WeatherNoticeIcon.TORNADO, caption = "NWS Portland OR", headline = "Until Thu 3:45 PM"),
        heightDp = WeatherNoticePainter.STRIP_HEIGHT_DP,
    )
}

/** Every weather notice's tile, as the large icon wears it. */
@OptIn(ExperimentalLayoutApi::class)
@Preview(widthDp = 400)
@Composable
private fun NoticeTilesPreview() {
    FlowRow(Modifier.background(Color(0xFF12161D)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WeatherNoticeIcon.entries.forEach { icon ->
            Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(48.dp)) { drawNoticeTile(icon.look, Offset.Zero, size.width) }
                Text(icon.name.lowercase(), color = Color(0xB8FFFFFF), fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

/** Every notification glyph in one colour, as the status bar shows it: large to check the drawing, small to check it still reads. */
@OptIn(ExperimentalLayoutApi::class)
// Tall enough for every glyph: a FlowRow leaves out whatever falls below the bottom.
@Preview(widthDp = 400, heightDp = 560)
@Composable
private fun NotificationGlyphsPreview() {
    FlowRow(Modifier.background(Color(0xFF12161D)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NotificationGlyph.entries.forEach { glyph ->
            Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(44.dp)) { drawGlyph(glyph, Offset.Zero, size.width, Color.White) }
                Box(Modifier.padding(top = 4.dp)) { Canvas(Modifier.size(18.dp)) { drawGlyph(glyph, Offset.Zero, size.width, Color.White) } }
                Text(glyph.name.lowercase(), color = Color(0xB8FFFFFF), fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}
