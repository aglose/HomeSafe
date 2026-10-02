package com.meticulouscreations.homesafe.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Draws an [AlertNotification] in Android's shade, for both the in-app alerts
 * (AlertNotifier.android.kt) and the relay's pushes (HomeSafeMessagingService), so the two look
 * and behave alike: one notification per detection, updated in place as its picture and clip
 * arrive, sounding only for the first post, and opening the detection full screen when tapped.
 *
 * The clip: Android can't play video or an animated image in a notification — the shade shows
 * a still Bitmap — so the preview GIF is shown by re-posting the notification through a handful
 * of its frames, [PASSES] times, then leaving it on the middle frame. Android sheds a package's
 * notification updates beyond about five a second, so [FRAME_MS] stays under that and only one
 * notification animates at a time.
 */
object AlertNotificationPoster {
    /** Shared with the relay (`send_push`'s old `channel_id`) and the user's channel settings. */
    const val CHANNEL_ID = "detections"

    /** The loud channel for people seen while nobody is home (docs/away-mode.md). */
    const val AWAY_CHANNEL_ID = "away_alerts"

    /** The quiet channel for the relay's summaries of what the cameras saw while someone was home. */
    const val SUMMARY_CHANNEL_ID = "summaries"

    private const val NOTIFICATION_ID = 1
    private const val FRAME_COUNT = 10
    private const val FRAME_MS = 300L
    private const val PASSES = 3
    private const val MAX_REMEMBERED = 64

    /** A post this soon after the first may not be in the shade's active list yet, so its absence proves nothing. */
    private const val POST_SETTLE_MS = 2_000L

    /** How long a "Not a person" confirmation, and its Undo, stays in the shade. */
    private const val CONFIRM_TIMEOUT_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = HashMap<String, Job>()
    private val animating = Mutex()

    /** When each recent detection was first posted: its notification's time, and cover for the moment before the shade lists it. */
    private val firstPosted = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_REMEMBERED
    }

    /**
     * Alerts taken down by "Not a person": a picture or clip still on its way to one (the media
     * worker calls [show] directly, and may be mid-download) is dropped rather than put the alert
     * back. A new alert under the same id — the visit brought someone else — lifts it.
     */
    private val markedNotAPerson = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean = super.add(element).also { if (size > MAX_REMEMBERED) remove(first()) }
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_detections_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_detections_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(AWAY_CHANNEL_ID, context.getString(R.string.notif_channel_away_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_away_description)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SUMMARY_CHANNEL_ID, context.getString(R.string.notif_channel_summaries_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_summaries_description)
            },
        )
    }

    /** Fire-and-forget [show], for callers that aren't suspending. A newer post of the same detection replaces one still animating. */
    fun post(context: Context, notification: AlertNotification) {
        val app = context.applicationContext
        synchronized(running) {
            running.remove(notification.id)?.cancel()
            running[notification.id] = scope.launch { show(app, notification) }
        }
    }

    /**
     * Posts [notification], or updates the one already up for its id; returns once any clip has
     * finished playing. A post with a picture or clip is always an update — the text goes up
     * first — so if its notification isn't in the shade any more (swiped away, or tapped) it does
     * nothing: a picture arriving late mustn't bring it back. That's read from the shade itself,
     * because a push's media is added by a worker that may be in a fresh process.
     *
     * A text post is a new alert, possibly one more of a visit whose notification has [AlertNotification.id]
     * already. A [AlertNotification.silent] one replaces it (or puts it back, if swiped away)
     * without a sound; one that isn't silent brought something new to the visit, so the old
     * notification is taken down first and the new one sounds like a first post.
     */
    suspend fun show(context: Context, notification: AlertNotification): Unit = withContext(Dispatchers.Default) {
        if (!canPost(context)) return@withContext
        val now = System.currentTimeMillis()
        val remembered = synchronized(firstPosted) { firstPosted[notification.id] }
        val isUpdate = notification.thumbnail != null || notification.animation != null
        val dropped = synchronized(markedNotAPerson) {
            if (!isUpdate) markedNotAPerson.remove(notification.id)
            isUpdate && notification.id in markedNotAPerson
        }
        if (dropped) return@withContext
        val postedAt = if (!isUpdate) {
            // A visit keeps the time it began; each summary replaces the last under the one id, but is news of its own time.
            if (notification.summary) now else remembered ?: now
        } else {
            val showing = active(context, notification.id)
            when {
                showing != null -> showing.notification.`when`
                remembered != null && now - remembered <= POST_SETTLE_MS -> remembered
                else -> return@withContext
            }
        }
        synchronized(firstPosted) { firstPosted[notification.id] = postedAt }
        if (!isUpdate && !notification.silent && active(context, notification.id) != null) cancel(context, notification.id)

        val thumbnail = notification.thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val frames = notification.animation?.let { gifFrames(it) }.orEmpty()
        if (frames.size < 2) {
            if (isUpdate && isMarkedNotAPerson(notification.id)) return@withContext // marked while its picture decoded
            notify(context, notification.id, build(context, notification, postedAt, picture = frames.firstOrNull() ?: thumbnail, largeIcon = thumbnail))
            return@withContext
        }
        animating.withLock {
            repeat(PASSES) {
                for (frame in frames) {
                    if (!isShowing(context, notification.id) || isMarkedNotAPerson(notification.id)) return@withLock
                    notify(context, notification.id, build(context, notification, postedAt, picture = frame, largeIcon = thumbnail))
                    delay(FRAME_MS)
                }
            }
            if (isMarkedNotAPerson(notification.id)) return@withLock
            notify(context, notification.id, build(context, notification, postedAt, picture = frames[frames.size / 2], largeIcon = thumbnail))
        }
    }

    private fun build(context: Context, notification: AlertNotification, postedAt: Long, picture: Bitmap?, largeIcon: Bitmap?): Notification =
        if (notification.summary) buildSummary(context, notification, postedAt) else buildAlert(context, notification, postedAt, picture, largeIcon)

    private fun buildAlert(context: Context, notification: AlertNotification, postedAt: Long, picture: Bitmap?, largeIcon: Bitmap?): Notification =
        NotificationCompat.Builder(context, if (notification.urgent) AWAY_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_detection)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // Every stage after the first is an update: no second sound, no second buzz.
            .setOnlyAlertOnce(true)
            .setSilent(notification.silent)
            .setWhen(postedAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, notification))
            .apply {
                tagCarIntent(context, notification)?.let { addAction(R.drawable.ic_notification_detection, context.getString(R.string.notif_action_tag_car), it) }
                notAPersonIntent(context, notification)?.let { addAction(R.drawable.ic_notification_detection, context.getString(R.string.notif_action_not_a_person), it) }
                if (largeIcon != null) setLargeIcon(largeIcon)
                if (picture != null) {
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
                }
            }
            .build()

    /**
     * The relay's summary: several cameras' worth of text, so [NotificationCompat.BigTextStyle]
     * shows all of it when expanded. Low priority on its own low channel — no sound, no heads-up —
     * and never a picture: it's about no one detection. A tap opens the app as it was.
     */
    private fun buildSummary(context: Context, notification: AlertNotification, postedAt: Long): Notification =
        NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_detection)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(postedAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, notification))
            .build()

    /**
     * What a tap opens: the app's own activity — named explicitly, so no other app can answer it —
     * with the detection as a `homesafe://moment` URI, which MainActivity hands to the shell. The
     * URI also keeps each notification's PendingIntent distinct, so a later alert can't overwrite
     * an earlier one's destination.
     */
    private fun openIntent(context: Context, notification: AlertNotification): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val intent = notification.target?.let { target ->
            Intent(Intent.ACTION_VIEW, target.toUri().toUri()).setComponent(launch.component)
        } ?: launch
        return PendingIntent.getActivity(context, notification.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The "Tag car" button's destination: the same detection, with the car picker opened on
     * arrival. Null unless the notification offers it. A request code of its own, so it can't
     * replace the tap's PendingIntent (both carry the same activity and flags).
     */
    private fun tagCarIntent(context: Context, notification: AlertNotification): PendingIntent? {
        val target = notification.target?.takeIf { notification.offerCarTag } ?: return null
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val intent = Intent(Intent.ACTION_VIEW, target.copy(tagCar = true).toUri().toUri()).setComponent(launch.component)
        return PendingIntent.getActivity(context, "${notification.id}:tag-car".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The "Not a person" button: marks the detection in the background ([NotAPersonReceiver]), no
     * app opened. Null unless the notification offers it. A later alert of the same visit replaces
     * this PendingIntent's extras (same request code), so the button always speaks for the alert
     * the notification shows.
     */
    private fun notAPersonIntent(context: Context, notification: AlertNotification): PendingIntent? {
        val target = notification.target?.takeIf { notification.offerNotAPerson && it.eventId.isNotBlank() } ?: return null
        return NotAPersonReceiver.intent(context, NotAPersonReceiver.ACTION_MARK, notification.id, target)
    }

    /*
     * What a "Not a person" press shows. The alert itself goes at once: it said someone was there,
     * and nobody was. Its place is taken by a quiet notification of its own (tag [confirmTag]), so a
     * picture or clip still on its way to the alert finds it gone and stays away (see [show]). That
     * one says how it went and offers Undo, or, if the relay couldn't be reached, Try again; it
     * clears itself after [CONFIRM_TIMEOUT_MS].
     */

    /** The alert [id] is being marked: down it comes, and "Marking…" stands in until the relay answers. */
    fun showMarkingNotAPerson(context: Context, id: String, target: MomentDeepLink) {
        synchronized(markedNotAPerson) { markedNotAPerson.add(id) }
        // Forgotten too, so the moments-after-posting allowance in [show] can't let a picture back in.
        synchronized(firstPosted) { firstPosted.remove(id) }
        synchronized(running) { running.remove(id)?.cancel() }
        cancel(context, id)
        confirm(context, id, target, context.getString(R.string.notif_marking_not_a_person_title), cameraDisplayName(target.cameraName), action = null)
    }

    /** The relay has the mark: this spot won't alert again, and Undo takes it back. */
    fun showMarkedNotAPerson(context: Context, id: String, target: MomentDeepLink) =
        confirm(
            context = context,
            id = id,
            target = target,
            title = context.getString(R.string.notif_marked_not_a_person_title),
            body = context.getString(R.string.notif_marked_not_a_person_body, cameraDisplayName(target.cameraName)),
            action = context.getString(R.string.notif_action_undo) to NotAPersonReceiver.ACTION_UNDO,
        )

    /** The mark didn't reach the relay; [reason] says why, and the button tries again. */
    fun showNotAPersonFailed(context: Context, id: String, target: MomentDeepLink, reason: String) =
        confirm(
            context,
            id,
            target,
            context.getString(R.string.notif_mark_failed_title),
            reason,
            action = context.getString(R.string.notif_action_try_again) to NotAPersonReceiver.ACTION_MARK,
        )

    /** Undo landed: nothing left to say. */
    fun clearNotAPerson(context: Context, id: String) = cancel(context, confirmTag(id))

    /** Undo didn't reach the relay: the mark stands, and the button is still there. */
    fun showUndoFailed(context: Context, id: String, target: MomentDeepLink, reason: String) =
        confirm(context, id, target, context.getString(R.string.notif_undo_failed_title), reason, action = context.getString(R.string.notif_action_undo) to NotAPersonReceiver.ACTION_UNDO)

    private fun confirm(context: Context, id: String, target: MomentDeepLink, title: String, body: String, action: Pair<String, String>?) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_detection)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(CONFIRM_TIMEOUT_MS)
            .setContentIntent(openIntent(context, AlertNotification(id = confirmTag(id), title = title, body = body, target = target)))
            .apply {
                action?.let { (label, act) -> addAction(R.drawable.ic_notification_detection, label, NotAPersonReceiver.intent(context, act, id, target)) }
            }
            .build()
        notify(context, confirmTag(id), notification)
    }

    private fun isMarkedNotAPerson(id: String) = synchronized(markedNotAPerson) { id in markedNotAPerson }

    private fun confirmTag(id: String) = "$id:not-a-person"

    /** Tagged by the detection's id, so each detection has its own notification and every stage replaces the last. */
    private fun notify(context: Context, id: String, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(id, NOTIFICATION_ID, notification)
    }

    private fun cancel(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
    }

    private fun active(context: Context, id: String): StatusBarNotification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.tag == id && it.id == NOTIFICATION_ID }

    private fun isShowing(context: Context, id: String): Boolean = active(context, id) != null

    private fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /**
     * [FRAME_COUNT] frames spread evenly through the GIF. [Movie] is deprecated, but it's the
     * platform's only GIF decoder that can seek to a moment: ImageDecoder's AnimatedImageDrawable
     * can only play, and a notification needs the frames as Bitmaps. Empty for anything that
     * isn't an animated GIF.
     */
    @Suppress("DEPRECATION")
    private fun gifFrames(bytes: ByteArray): List<Bitmap> {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val width = movie.width()
        val height = movie.height()
        val duration = movie.duration()
        if (width <= 0 || height <= 0 || duration <= 0) return emptyList()
        return List(FRAME_COUNT) { i ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frame ->
                movie.setTime(duration * i / FRAME_COUNT)
                movie.draw(Canvas(frame), 0f, 0f)
            }
        }
    }
}
