package com.meticulouscreations.homesafe.push

import android.text.format.DateFormat
import android.text.format.DateUtils
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.meticulouscreations.homesafe.data.AlertNotificationPoster
import com.meticulouscreations.homesafe.data.BudgetNotificationPoster
import com.meticulouscreations.homesafe.data.NotificationMuteStore
import com.meticulouscreations.homesafe.domain.model.DoorAlert
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.NestOutageAlert
import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.finance.domain.BudgetAlert
import com.meticulouscreations.homesafe.navigation.DoorDeepLink
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.shared.R
import kotlinx.coroutines.runBlocking
import java.util.Date

/**
 * Shows a push from the HomeSafe relay, and hears about token rotation.
 *
 * The relay sends Android data-only messages (see `push_message` in relay/relay.py), so this runs
 * for every push, foreground or background — Android never draws one itself. It posts the text
 * at once, then leaves the picture and the clip to [AlertMediaWorker]: those take up to a minute
 * to arrive, longer than this callback may run, and a process with nothing running is frozen.
 * A summary (`summary=1`) is text only: it posts on the quiet "Summaries" channel and gets no media.
 * A budget alert (`budget=1`) is no detection at all: it goes to [BudgetNotificationPoster].
 * A door opening (`door=<id>`) is worded here and posted like an alert, with no picture to fetch.
 * So is a Nest camera Google stopped streaming, and its return (`system=nest`), and a household
 * car parked on the street that must move (`parking=1`), which goes on its own Parking channel.
 * With every notification muted (Settings → Notifications) nothing is shown at all.
 */
class HomeSafeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(token, applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Muted, the relay is told to push nothing here; this covers what was already on its way.
        if (NotificationMuteStore.isMuted(applicationContext)) return
        if (BudgetAlert.isBudget { message.data[it] }) return showBudgetAlert(message)
        if (DoorAlert.isDoor { message.data[it] }) return showDoorAlert(message)
        if (NestOutageAlert.isNestOutage { message.data[it] }) return showNestOutageAlert(message)
        if (ParkingAlert.isParking { message.data[it] }) return showParkingAlert(message)
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
            // What the push lets on about its subject: the two offers above, and a household car's own
            // notification (`notif_id=car-<name>`, see `tell_car` in relay.py). Anything else wears the camera.
            subject = when {
                message.data["car_unnamed"] == "1" || message.data["notif_id"]?.startsWith("car-") == true -> MomentCategory.VEHICLES
                message.data["person_unnamed"] == "1" -> MomentCategory.PEOPLE
                else -> MomentCategory.ALL
            },
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
        BudgetNotificationPoster.show(applicationContext, alert?.id ?: message.data[BudgetAlert.KEY_ID] ?: message.messageId ?: title, title, body, alert?.kind)
    }

    /**
     * A door opened (see docs/doors.md). Worded here from the door's name; the relay's English
     * only for an event this build doesn't know. It wears the door in the status bar, and a tap
     * opens the Doors page (`homesafe://doors`).
     */
    private fun showDoorAlert(message: RemoteMessage) {
        val alert = DoorAlert.from { message.data[it] }
        val title = alert?.let { getString(R.string.notif_door_opened_title, it.doorName) }
            ?: message.data["title"] ?: return
        val body = alert?.let { getString(if (it.away) R.string.notif_door_away_body else R.string.notif_door_body) } ?: message.data["body"].orEmpty()
        val notification = AlertNotification(
            id = alert?.id ?: message.data[DoorAlert.KEY_ID] ?: message.messageId ?: title,
            title = title,
            body = body,
            urgent = message.data["away"] == "1",
            silent = message.data["silent"] == "1",
            door = DoorDeepLink(alert?.doorId ?: message.data[DoorAlert.KEY_DOOR]),
        )
        AlertNotificationPoster.ensureChannels(this)
        runBlocking { AlertNotificationPoster.show(applicationContext, notification) }
    }

    /**
     * Google stopped streaming a Nest camera for an hour, or started again (`nest_outage_push` in
     * relay.py). Worded here from the camera's name and the local time the outage began; the
     * relay's English only for an event or reason this build doesn't know. The return shares the
     * outage's id, so it replaces it.
     */
    private fun showNestOutageAlert(message: RemoteMessage) {
        val alert = NestOutageAlert.from { message.data[it] }
        val title = alert?.let {
            getString(if (it.reason == null) R.string.notif_nest_streaming_title else R.string.notif_nest_unavailable_title, it.cameraName)
        } ?: message.data["title"] ?: return
        val body = alert?.let { nestOutageBody(it) } ?: message.data["body"].orEmpty()
        val notification = AlertNotification(
            id = alert?.id ?: message.data[NestOutageAlert.KEY_ID] ?: message.messageId ?: title,
            title = title,
            body = body,
        )
        AlertNotificationPoster.ensureChannels(this)
        runBlocking { AlertNotificationPoster.show(applicationContext, notification) }
    }

    /**
     * A household car parked on the street must move, or has (docs/parking.md). Worded here from the
     * car's name, the street and the deadline, on this phone's clock; the relay's English only for
     * an event this build doesn't know. It wears the car, on the Parking channel.
     */
    private fun showParkingAlert(message: RemoteMessage) {
        val alert = ParkingAlert.from { message.data[it] }
        val (title, body) = alert?.let { parkingText(it) }
            ?: ((message.data["title"] ?: return) to message.data["body"].orEmpty())
        val notification = AlertNotification(
            id = alert?.id ?: message.data[ParkingAlert.KEY_ID] ?: message.messageId ?: title,
            title = title,
            body = body,
            silent = message.data["silent"] == "1",
            parking = true,
        )
        AlertNotificationPoster.ensureChannels(this)
        runBlocking { AlertNotificationPoster.show(applicationContext, notification) }
    }

    private fun parkingText(alert: ParkingAlert): Pair<String, String> {
        if (alert.event == ParkingAlert.Event.MOVED) {
            val body = if (alert.street.isBlank()) getString(R.string.notif_parking_moved_body_no_street) else getString(R.string.notif_parking_moved_body, alert.street)
            return getString(R.string.notif_parking_moved_title, alert.carName) to body
        }
        val title = when (alert.event) {
            ParkingAlert.Event.NOW -> getString(R.string.notif_parking_now_title, alert.carName)
            else -> getString(R.string.notif_parking_due_title, alert.carName, parkingTime(alert.byEpochSeconds ?: 0))
        }
        val place = when (alert.side) {
            ParkingAlert.Side.NORTH -> getString(R.string.notif_parking_place_north, alert.street)
            ParkingAlert.Side.EAST -> getString(R.string.notif_parking_place_east, alert.street)
            ParkingAlert.Side.SOUTH -> getString(R.string.notif_parking_place_south, alert.street)
            ParkingAlert.Side.WEST -> getString(R.string.notif_parking_place_west, alert.street)
            null -> alert.street
        }
        val limit = alert.limitMinutes
        val from = alert.fromEpochSeconds
        val until = alert.untilEpochSeconds
        val reason = when {
            alert.note != null -> alert.note
            limit != null && limit % 60 == 0 -> resources.getQuantityString(R.plurals.notif_parking_limit_hours, limit / 60, limit / 60)
            limit != null -> resources.getQuantityString(R.plurals.notif_parking_limit_minutes, limit, limit)
            from != null && until != null -> getString(R.string.notif_parking_no_parking, clockTime(from), clockTime(until))
            else -> null
        }
        return title to (reason?.let { getString(R.string.notif_parking_body, place, it) } ?: place)
    }

    /** "8:00 AM" today, "Thu 8:00 AM" another day: this phone's clock, in its language. */
    private fun parkingTime(epochSeconds: Long): String {
        val millis = epochSeconds * 1000
        val day = if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
        return DateUtils.formatDateTime(this, millis, DateUtils.FORMAT_SHOW_TIME or day)
    }

    private fun clockTime(epochSeconds: Long): String = DateFormat.getTimeFormat(this).format(Date(epochSeconds * 1000))

    private fun nestOutageBody(alert: NestOutageAlert): String {
        val since = alert.sinceEpochSeconds?.let { DateFormat.getTimeFormat(this).format(Date(it * 1000)) }
        return when (alert.reason) {
            null -> getString(R.string.notif_nest_streaming_body)
            NestOutageAlert.Reason.REFUSED -> since?.let { getString(R.string.notif_nest_refused_body, it) } ?: getString(R.string.notif_nest_refused_body_no_time)
            NestOutageAlert.Reason.UNCONFIRMED -> since?.let { getString(R.string.notif_nest_unconfirmed_body, it) } ?: getString(R.string.notif_nest_unconfirmed_body_no_time)
        }
    }
}
