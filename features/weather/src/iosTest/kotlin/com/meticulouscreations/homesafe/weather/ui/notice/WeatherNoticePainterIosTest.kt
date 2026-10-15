package com.meticulouscreations.homesafe.weather.ui.notice

import androidx.compose.ui.text.font.createFontFamilyResolver
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeChart
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * iOS takes a weather notification's picture as a PNG file: painted here with iOS's own fonts
 * and no composition, as the background refresh paints it, and encoded. That it comes out a PNG
 * of the size asked for is all this holds to; `WeatherNoticePainterTest` (JVM) has the rest.
 */
class WeatherNoticePainterIosTest {
    private val start = 1_759_993_200L

    private fun signature(png: ByteArray) = png.take(4).map { it.toInt() and 0xFF }

    private val pngSignature = listOf(0x89, 'P'.code, 'N'.code, 'G'.code)

    @Test
    fun aRainCardPaintsAndEncodes() {
        val art = WeatherNoticeArt(
            icon = WeatherNoticeIcon.RAIN,
            caption = "Portland",
            headline = "3:30 PM",
            facts = listOf(WeatherNoticeArt.Fact(WeatherNoticeIcon.RAIN, "30 min")),
            chart = WeatherNoticeChart.Soon(List(9) { PrecipSlice(start + it * 900L, if (it >= 2) 0.5 else 0.0) }, start + 1_800, emptyList()),
            ticks = listOf("Now", "3:30 PM", "4 PM", "4:30 PM", "5 PM"),
        )
        val height = WeatherNoticePainter.cardHeight(art, 1080, chartHeightDp = 240)
        val card = WeatherNoticePainter.card(art, 1080, height, createFontFamilyResolver())
        assertEquals(1080 to 720, card.width to card.height)
        assertEquals(pngSignature, signature(assertNotNull(card.toPng())))
    }

    @Test
    fun aDayCardAndAWarningsStripPaintAndEncode() {
        val day = WeatherNoticeChart.Day(
            List(24) { HourForecast(epochSeconds = start + it * 3_600L, temperatureC = 10.0 + it % 12, feelsLikeC = 10.0, precipitationMm = if (it in 14..16) 1.0 else 0.0, weatherCode = 61) },
            nowEpochSeconds = start + 7 * 3_600,
            ticks = emptyList(),
        )
        val outlook = WeatherNoticeArt(WeatherNoticeIcon.STORM, caption = "Thursday", headline = "71° / 55°", chart = day, ticks = listOf("12 AM", "6 AM", "12 PM", "6 PM"))
        assertEquals(pngSignature, signature(assertNotNull(WeatherNoticePainter.card(outlook, 1080, 720, createFontFamilyResolver()).toPng())))

        val warning = WeatherNoticeArt(WeatherNoticeIcon.TORNADO, caption = "NWS Portland OR", headline = "Until Thu 3:45 PM")
        val strip = WeatherNoticePainter.card(warning, 1080, WeatherNoticePainter.cardHeight(warning, 1080), createFontFamilyResolver())
        assertEquals(1080 to 216, strip.width to strip.height)
        assertEquals(pngSignature, signature(assertNotNull(strip.toPng())))
    }
}
