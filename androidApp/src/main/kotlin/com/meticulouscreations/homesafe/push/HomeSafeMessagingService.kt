package com.meticulouscreations.homesafe.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.meticulouscreations.homesafe.data.AlertNotificationPoster
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import kotlinx.coroutines.runBlocking

/**
 * Shows a push from the HomeSafe relay, and hears about token rotation.
 *
 * The relay sends Android data-only messages (see `push_message` in relay/relay.py), so this runs
 * for every push, foreground or background — Android never draws one itself. It posts the text
 * at once, then leaves the picture and the clip to [AlertMediaWorker]: those take up to a minute
 * to arrive, longer than this callback may run, and a process with nothing running is frozen.
 */
class HomeSafeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(token, applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: message.notification?.title ?: return
        val body = message.data["body"] ?: message.notification?.body ?: ""
        val target = MomentDeepLink.from { message.data[it] }
        val text = AlertNotification(
            // The relay pushes review items; the review id is also what it tags the push with.
            id = message.data["review_id"] ?: message.messageId ?: title,
            title = title,
            body = body,
            target = target,
            // The relay marks escalated pushes with away=1 (see docs/away-mode.md): nobody home, person seen.
            urgent = message.data["away"] == "1",
        )
        AlertNotificationPoster.ensureChannels(this)
        // Already off the main thread: Firebase calls this on its own worker.
        runBlocking { AlertNotificationPoster.show(applicationContext, text) }

        val eventId = message.data["event_id"]
        if (target != null && !eventId.isNullOrBlank()) AlertMediaWorker.enqueue(applicationContext, text, eventId, target.startEpochSeconds)
    }
}
