package com.meticulouscreations.homesafe.weather.data

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.test.platform.app.InstrumentationRegistry
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.shared.R
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeChart
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.ui.notice.WeatherNoticePainter
import com.meticulouscreations.homesafe.weather.ui.notice.look
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A weather notification as Android is handed it, built on a real Android: its pictures are
 * painted there with the platform's own bitmaps and fonts and no composition, which only a
 * device can show to work. Built, not posted, so the test needs no notification permission.
 */
class WeatherNotificationBuildTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_760_000_000_000L
    private val nowSeconds = now / 1000

    private val rain = WeatherNotification(
        id = "soon:1",
        title = "Rain at 3:30 PM",
        body = "For about 30 min, around 0.04 in",
        art = WeatherNoticeArt(
            icon = WeatherNoticeIcon.RAIN,
            headline = "3:30 PM",
            facts = listOf(WeatherNoticeArt.Fact(WeatherNoticeIcon.RAIN, "30 min")),
            chart = WeatherNoticeChart.Soon(List(9) { PrecipSlice(nowSeconds + it * 900L, if (it in 2..3) 0.5 else 0.0) }, nowSeconds + 1_800, emptyList()),
            ticks = listOf("Now", "3:30 PM", "4 PM", "4:30 PM", "5 PM"),
        ),
        place = "Portland",
        eventEpochSeconds = nowSeconds + 1_500,
        expiresEpochSeconds = nowSeconds + 3_600,
        link = WeatherDeepLink(placeId = "current"),
    )

    private val tornado = WeatherNotification(
        id = "alert:KPQR.TO.W.0012",
        title = "Tornado Warning",
        body = "Tornado Warning issued October 9 at 3:02PM PDT",
        art = WeatherNoticeArt(icon = WeatherNoticeIcon.TORNADO, caption = "NWS Portland OR", headline = "Until Thu 3:45 PM"),
        urgent = true,
        detail = "Tornado Warning issued October 9 at 3:02PM PDT\n\nTAKE COVER NOW!",
        link = WeatherDeepLink(alertKey = "KPQR.TO.W.0012"),
    )

    private fun build(notification: WeatherNotification) = buildWeatherNotification(context, notification, now)

    private fun Notification.picture(): Bitmap? =
        extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            ?: extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)?.loadDrawable(context)?.let { drawable ->
                Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            }

    @Test
    fun rainOnTheWayWearsItsCloudItsColourAndItsPlace() {
        val built = build(rain)
        assertEquals(R.drawable.ic_notification_weather_rain, built.smallIcon.resId)
        assertEquals(WeatherNoticeIcon.RAIN.look.tintArgb, built.color)
        assertEquals(CHANNEL_ID, built.channelId)
        // Its own group, never Android's bundle of the app's notifications under the app icon.
        assertEquals("weather", built.group)
        assertEquals("Rain at 3:30 PM", built.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("For about 30 min, around 0.04 in", built.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Portland", built.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PUBLIC, built.visibility)
        assertNotNull(built.getLargeIcon(), "the glyph on its tile")
    }

    @Test
    fun rainOnTheWayOpensOutIntoThePictureOfTheNextTwoHours() {
        val picture = assertNotNull(build(rain).picture())
        val width = min(context.resources.displayMetrics.widthPixels, MAX_CARD_WIDTH_PX)
        assertEquals(width, picture.width)
        assertEquals(WeatherNoticePainter.cardHeight(rain.art, width), picture.height, "the card's own shape")
    }

    @Test
    fun itsTimeIsWhenTheRainStartsAndItTakesItselfDownWhenTheRainIsOver() {
        val built = build(rain)
        assertEquals((nowSeconds + 1_500) * 1000, built.`when`)
        assertEquals(3_600_000L, built.timeoutAfter)
        // Posted late, with the shower all but over: still up long enough to be read.
        assertEquals(600_000L, build(rain.copy(expiresEpochSeconds = nowSeconds + 5)).timeoutAfter)
        assertEquals(0L, build(tornado).timeoutAfter, "a warning with no end is the reader's to dismiss")
        // A warning is the exception to "long enough to be read": it must not outlast the warning.
        assertEquals(5_000L, build(tornado.copy(expiresEpochSeconds = nowSeconds + 5)).timeoutAfter)
        assertEquals(1_800_000L, build(tornado.copy(expiresEpochSeconds = nowSeconds + 1_800)).timeoutAfter)
        assertEquals(now, build(tornado).`when`)
    }

    @Test
    fun itOffersTheRadar() {
        assertEquals(listOf("Radar"), build(rain).actions.map { it.title.toString() })
    }

    @Test
    fun rainAboutToStartComesUpOverTheScreenAndCanBePutOff() {
        val built = build(rain.copy(headsUp = true, reminderEpochSeconds = nowSeconds + 900, reminderLabel = "Remind me at 3:15 PM"))
        assertEquals(SOON_CHANNEL_ID, built.channelId)
        assertEquals(listOf("Radar", "Remind me at 3:15 PM"), built.actions.map { it.title.toString() })
    }

    @Test
    fun aWarningGoesOutOnTheSevereChannelAndOpensOutIntoTheAgencysWords() {
        val built = build(tornado)
        assertEquals(SEVERE_CHANNEL_ID, built.channelId)
        assertEquals(R.drawable.ic_notification_weather_tornado, built.smallIcon.resId)
        assertEquals("Tornado Warning issued October 9 at 3:02PM PDT\n\nTAKE COVER NOW!", built.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertNull(built.picture(), "no chart: its words are what matters")
        assertNotNull(built.getLargeIcon())
    }

    @Test
    fun aDayAheadPaintsItsHoursToo() {
        val day = WeatherNoticeChart.Day(
            List(24) { HourForecast(epochSeconds = nowSeconds + it * 3_600L, temperatureC = 10.0 + it % 12, feelsLikeC = 10.0, precipitationMm = if (it in 14..16) 1.0 else 0.0, weatherCode = 61) },
            nowEpochSeconds = nowSeconds + 7 * 3_600,
            ticks = emptyList(),
        )
        val built = build(rain.copy(art = WeatherNoticeArt(WeatherNoticeIcon.STORM, caption = "Thursday", headline = "71° / 55°", chart = day, ticks = listOf("12 AM", "6 AM", "12 PM", "6 PM"))))
        assertNotNull(built.picture())
        assertEquals(R.drawable.ic_notification_weather_storm, built.smallIcon.resId)
    }

    @Test
    fun everyKindOfNoticeBuildsWithAnIconOfItsOwn() {
        val icons = WeatherNoticeIcon.entries.map { icon -> build(rain.copy(art = rain.art.copy(icon = icon))).smallIcon.resId }
        icons.forEach { assertNotEquals(0, it) }
        assertEquals(icons.size, icons.toSet().size)
    }
}
