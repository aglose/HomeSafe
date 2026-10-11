package com.meticulouscreations.homesafe.weather.ui.notice

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.createFontFamilyResolver
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeChart
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The notification's pictures are painted with no composition, in a background job: here, as
 * there, straight to a bitmap. That it paints at all, for every kind and for forecasts with
 * holes in them, is what these hold to; how it looks is the previews' to show.
 */
class WeatherNoticePainterTest {
    private val fonts = createFontFamilyResolver()
    private val start = 1_759_993_200L

    private fun soon(count: Int = 9) = WeatherNoticeChart.Soon(List(count) { PrecipSlice(start + it * 900L, if (it >= 2) 0.5 else 0.0) }, start + 1800, emptyList())

    private fun day(count: Int = 24) = WeatherNoticeChart.Day(
        List(count) { HourForecast(epochSeconds = start + it * 3600L, temperatureC = 10.0 + it % 12, feelsLikeC = 10.0, precipitationMm = if (it in 14..16) 1.0 else 0.0, weatherCode = 61) },
        nowEpochSeconds = start + 7 * 3600,
        ticks = emptyList(),
    )

    private fun ImageBitmap.pixel(x: Int, y: Int) = toPixelMap()[x, y]

    @Test
    fun everyKindOfNoticePaintsABadgeWithItsCornersLeftClear() {
        WeatherNoticeIcon.entries.forEach { icon ->
            val badge = WeatherNoticePainter.badge(icon, 96)
            assertEquals(96 to 96, badge.width to badge.height)
            assertEquals(0f, badge.pixel(0, 0).alpha, "$icon: the tile is a rounded square")
            assertEquals(1f, badge.pixel(48, 20).alpha, "$icon: and solid inside")
        }
    }

    @Test
    fun everyKindOfNoticePaintsACardThatCoversItsWholeBitmap() {
        WeatherNoticeIcon.entries.forEach { icon ->
            val card = WeatherNoticePainter.card(WeatherNoticeArt(icon, caption = "Portland", headline = "3:30 PM", chart = soon(), ticks = listOf("Now", "3:30 PM", "4 PM", "4:30 PM", "5 PM")), 720, 400, fonts)
            assertEquals(720 to 400, card.width to card.height)
            listOf(0 to 0, 719 to 0, 0 to 399, 719 to 399).forEach { (x, y) -> assertEquals(1f, card.pixel(x, y).alpha, "$icon at $x,$y") }
        }
    }

    @Test
    fun aChartIsDrawnWhereThereIsOne() {
        val words = WeatherNoticeArt(WeatherNoticeIcon.RAIN, headline = "3:30 PM")
        val bare = WeatherNoticePainter.card(words, 720, 400, fonts)
        val charted = WeatherNoticePainter.card(words.copy(chart = soon()), 720, 400, fonts)
        // Low on the card, in the last of the wet bars: rain's blue where there was only sky.
        assertNotEquals(bare.pixel(680, 330), charted.pixel(680, 330))
    }

    @Test
    fun aForecastWithHolesInItStillPaints() {
        val art = WeatherNoticeArt(WeatherNoticeIcon.STORM, facts = List(8) { WeatherNoticeArt.Fact(WeatherNoticeIcon.WIND, "45 mph") })
        listOf(soon(0), soon(1), soon(4), day(1), day(2), day(13), day(25)).forEach { chart ->
            assertNotNull(WeatherNoticePainter.card(art.copy(chart = chart, ticks = listOf("a very long tick that will not fit under anything", "b")), 720, 400, fonts))
        }
        // Too small for anything but the tile: nothing is drawn outside it, and nothing throws.
        assertNotNull(WeatherNoticePainter.card(art.copy(chart = day(), caption = "x", headline = "y"), 64, 32, fonts))
        assertNotNull(WeatherNoticePainter.card(art, 1, 1, fonts))
    }

    @Test
    fun aNoticeWithNoChartGetsAStripAndOneWithAChartACard() {
        val words = WeatherNoticeArt(WeatherNoticeIcon.TORNADO, headline = "Until Thu 3:45 PM")
        assertEquals(216, WeatherNoticePainter.cardHeight(words, 1080))
        assertEquals(600, WeatherNoticePainter.cardHeight(words.copy(chart = day()), 1080))
        assertEquals(720, WeatherNoticePainter.cardHeight(words.copy(chart = day()), 1080, chartHeightDp = 240))
    }

    @Test
    fun theThumbnailIsCutFromTheTileAndTheTileIsSquare() {
        val bounds = WeatherNoticePainter.tileBounds(1080, 720)
        assertTrue(bounds.left > 0f && bounds.top > 0f && bounds.right < 0.25f && bounds.bottom < 0.3f, "$bounds")
        assertEquals(bounds.width * 1080, bounds.height * 720, 0.01f)
        // The middle of it is the tile's own colour, not the sky's.
        val card = WeatherNoticePainter.card(WeatherNoticeArt(WeatherNoticeIcon.RAIN), 1080, 720, fonts)
        val inTile = card.pixel((bounds.center.x * 1080).toInt(), (bounds.top * 720).toInt() + 12)
        val inSky = card.pixel(1000, 60)
        assertNotEquals(inTile, inSky)
    }

    @Test
    fun aCardEncodesAsAPngForThePlatformThatWantsAFile() {
        val png = assertNotNull(WeatherNoticePainter.card(WeatherNoticeArt(WeatherNoticeIcon.SUN, chart = day()), 360, 200, fonts).toPng())
        // A PNG's signature.
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), png.take(4).map { it.toInt() and 0xFF })
    }
}
