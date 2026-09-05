package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendTestNotificationUseCaseTest {

    private class FakeNotifier : AlertNotifier {
        val posted = mutableListOf<AlertNotification>()
        override val isSupported = true
        override suspend fun permissionStatus() = NotificationPermission.GRANTED
        override suspend fun requestPermission() = true
        override fun openSystemSettings() = Unit
        override fun notify(notification: AlertNotification) { posted += notification }
    }

    @Test
    fun postsOneTextOnlyNotificationStraightToTheNotifier() {
        val notifier = FakeNotifier()

        SendTestNotificationUseCase(notifier)()

        val posted = notifier.posted.single()
        assertEquals(SendTestNotificationUseCase.TEST_NOTIFICATION_ID, posted.id)
        assertTrue(posted.title.isNotBlank() && posted.body.isNotBlank())
        assertEquals(null, posted.thumbnail)
    }
}
