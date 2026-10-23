package com.meticulouscreations.homesafe.data

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cameras' channels as a real Android keeps them: none of them makes a sound, and the ones that used to are gone. */
class AlertNotificationChannelsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun noChannelMakesASound() {
        AlertNotificationPoster.ensureChannels(context)
        for (id in listOf(AlertNotificationPoster.CHANNEL_ID, AlertNotificationPoster.AWAY_CHANNEL_ID, AlertNotificationPoster.DOORS_CHANNEL_ID, AlertNotificationPoster.SUMMARY_CHANNEL_ID)) {
            val channel = assertNotNull(manager.getNotificationChannel(id), id)
            assertNull(channel.sound, "$id has a sound")
        }
    }

    @Test
    fun anAwayAlertStillComesUpOverTheScreenAndBuzzes() {
        AlertNotificationPoster.ensureChannels(context)
        val away = assertNotNull(manager.getNotificationChannel(AlertNotificationPoster.AWAY_CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, away.importance)
        assertTrue(away.shouldVibrate())
    }

    @Test
    fun theChannelsThatRangAreDeleted() {
        // A phone updated from a build that made them: the Away one rang the alarm.
        manager.createNotificationChannel(NotificationChannel("away_alerts", "Away alerts", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("detections", "Detections", NotificationManager.IMPORTANCE_HIGH))
        AlertNotificationPoster.ensureChannels(context)
        val ids = manager.notificationChannels.map { it.id }
        assertFalse("away_alerts" in ids)
        assertFalse("detections" in ids)
    }
}
