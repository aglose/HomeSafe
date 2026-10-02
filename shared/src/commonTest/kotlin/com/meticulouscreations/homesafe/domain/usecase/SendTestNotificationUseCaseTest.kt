package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.text.KeyedTextLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SendTestNotificationUseCaseTest {

    private class FakeNotifier : AlertNotifier {
        val posted = mutableListOf<AlertNotification>()
        override val isSupported = true
        override suspend fun permissionStatus() = NotificationPermission.GRANTED
        override suspend fun requestPermission() = true
        override fun openSystemSettings() = Unit
        override fun notify(notification: AlertNotification) {
            posted += notification
        }
    }

    @Test
    fun postsOneTextOnlyNotificationStraightToTheNotifier() = runTest {
        val notifier = FakeNotifier()

        SendTestNotificationUseCase(notifier, KeyedTextLoader)()

        val posted = notifier.posted.single()
        assertEquals(SendTestNotificationUseCase.TEST_NOTIFICATION_ID, posted.id)
        assertEquals("settings_test_notification_title", posted.title)
        assertEquals("settings_test_notification_body", posted.body)
        assertEquals(null, posted.thumbnail)
    }
}
