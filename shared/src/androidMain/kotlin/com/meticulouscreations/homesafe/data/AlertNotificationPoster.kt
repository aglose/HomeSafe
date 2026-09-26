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
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
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

    private const val NOTIFICATION_ID = 1
    private const val FRAME_COUNT = 10
    private const val FRAME_MS = 300L
    private const val PASSES = 3
    private const val MAX_REMEMBERED = 64

    /** A post this soon after the first may not be in the shade's active list yet, so its absence proves nothing. */
    private const val POST_SETTLE_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = HashMap<String, Job>()
    private val animating = Mutex()

    /** When each recent detection was first posted: its notification's time, and cover for the moment before the shade lists it. */
    private val firstPosted = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_REMEMBERED
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detections", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A person, vehicle, or animal seen by one of your cameras"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(AWAY_CHANNEL_ID, "Away alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A person on any camera while nobody is home"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
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
        val postedAt = if (!isUpdate) {
            remembered ?: now
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
            notify(context, notification.id, build(context, notification, postedAt, picture = frames.firstOrNull() ?: thumbnail, largeIcon = thumbnail))
            return@withContext
        }
        animating.withLock {
            repeat(PASSES) {
                for (frame in frames) {
                    if (!isShowing(context, notification.id)) return@withLock
                    notify(context, notification.id, build(context, notification, postedAt, picture = frame, largeIcon = thumbnail))
                    delay(FRAME_MS)
                }
            }
            notify(context, notification.id, build(context, notification, postedAt, picture = frames[frames.size / 2], largeIcon = thumbnail))
        }
    }

    private fun build(context: Context, notification: AlertNotification, postedAt: Long, picture: Bitmap?, largeIcon: Bitmap?): Notification =
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
                tagCarIntent(context, notification)?.let { addAction(R.drawable.ic_notification_detection, "Tag car", it) }
                if (largeIcon != null) setLargeIcon(largeIcon)
                if (picture != null) {
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
                }
            }
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
