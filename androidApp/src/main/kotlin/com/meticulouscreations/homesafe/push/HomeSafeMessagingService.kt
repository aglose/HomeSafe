package com.meticulouscreations.homesafe.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.meticulouscreations.homesafe.data.AlertNotificationPoster
import com.meticulouscreations.homesafe.data.BudgetNotificationPoster
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.finance.domain.BudgetAlert
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import kotlinx.coroutines.runBlocking

/**
 * Shows a push from the HomeSafe relay, and hears about token rotation.
 *
 * The relay sends Android data-only messages (see `push_message` in relay/relay.py), so this runs
 * for every push, foreground or background — Android never draws one itself. It posts the text
 * at once, then leaves the picture and the clip to [AlertMediaWorker]: those take up to a minute
 * to arrive, longer than this callback may run, and a process with nothing running is frozen.
 * A summary (`summary=1`) is text only: it posts on the quiet "Summaries" channel and gets no media.
 * A budget alert (`budget=1`) is no detection at all: it goes to [BudgetNotificationPoster].
 */
class HomeSafeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(token, applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (BudgetAlert.isBudget { message.data[it] }) return showBudgetAlert(message)
        val title = message.data["title"] ?: message.notification?.title ?: return
        val body = message.data["body"] ?: message.notification?.body ?: ""
        val target = MomentDeepLink.from { message.data[it] }
        val text = AlertNotification(
            // The relay pushes review items folded into visits: every alert of one visit carries the
            // visit's notif_id, so they share one notification. An older relay sends only review_id.
            id = message.data["notif_id"] ?: message.data["review_id"] ?: message.messageId ?: title,
            title = title,
            body = body,
            target = target,
            // The relay marks escalated pushes with away=1 (see docs/away-mode.md): nobody home, person seen.
            urgent = message.data["away"] == "1",
            // More of the same visit: update its notification without a sound (see Visits in relay.py).
            silent = message.data["silent"] == "1",
            // The relay marks an alert about a car the classifier didn't name (car_unnamed=1), for a "Tag car" button.
            offerCarTag = message.data["car_unnamed"] == "1",
            // And one about a person nobody named (person_unnamed=1), for a "Not a person" button.
            offerNotAPerson = message.data["person_unnamed"] == "1",
            // The relay's periodic summary of what was seen while someone was home: quiet, all text, no media.
            summary = message.data["summary"] == "1",
        )
        AlertNotificationPoster.ensureChannels(this)
        // Already off the main thread: Firebase calls this on its own worker.
        runBlocking { AlertNotificationPoster.show(applicationContext, text) }

        // start_time is the visit's, for the tap; the clip's timing is this alert's own.
        val eventId = message.data["event_id"]
        val eventStart = message.data["event_start"]?.toDoubleOrNull()
        if (!text.summary && target != null && !eventId.isNullOrBlank()) AlertMediaWorker.enqueue(applicationContext, text, eventId, eventStart ?: target.startEpochSeconds)
    }

    /**
     * The month's spending crossed a line. Worded here, in the phone's language, from the amounts
     * the push carries; the relay's own English only when it says something this build can't
     * word (a line a newer relay knows about). Never the detections' channel, and never a clip.
     */
    private fun showBudgetAlert(message: RemoteMessage) {
        val alert = BudgetAlert.from { message.data[it] }
        val (title, body) = alert?.let { BudgetNotificationPoster.text(this, it) }
            ?: ((message.data["title"] ?: return) to message.data["body"].orEmpty())
        BudgetNotificationPoster.show(applicationContext, alert?.id ?: message.data[BudgetAlert.KEY_ID] ?: message.messageId ?: title, title, body)
    }
}
