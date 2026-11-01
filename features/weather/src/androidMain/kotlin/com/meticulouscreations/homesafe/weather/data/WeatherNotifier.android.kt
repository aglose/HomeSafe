package com.meticulouscreations.homesafe.weather.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.appicon.appLaunchIntent
import com.meticulouscreations.homesafe.data.BackgroundGraph
import com.meticulouscreations.homesafe.data.NotificationGroupSummary
import com.meticulouscreations.homesafe.data.createSilentChannel
import com.meticulouscreations.homesafe.data.retireChannels
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.shared.R
import com.meticulouscreations.homesafe.ui.notification.drawableRes
import com.meticulouscreations.homesafe.weather.domain.ReminderResult
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticePlanner
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import com.meticulouscreations.homesafe.weather.ui.notice.WeatherNoticePainter
import com.meticulouscreations.homesafe.weather.ui.notice.look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "HomeSafeWeather"

/** The everyday channel: rain on the way, the morning's and evening's outlook. "_v2" because the first "weather" channel sounded (see `createSilentChannel`). */
internal const val CHANNEL_ID = "weather_v2"

/** Rain about to start, on a channel of its own that comes up over whatever is on the screen: the whole point of it is being seen in time. */
internal const val SOON_CHANNEL_ID = "weather_soon"

/** Government warnings, on a channel of their own so they can be let through when the rest are silenced. */
internal const val SEVERE_CHANNEL_ID = "weather_severe_v2"

/** The channels [CHANNEL_ID] and [SEVERE_CHANNEL_ID] replace. */
private val RETIRED_CHANNEL_IDS = arrayOf("weather", "weather_severe")

/**
 * One group for the weather's notifications, so Android never bundles a shower in with the
 * cameras' alerts. Its header wears the newest one's icon and colour.
 */
private val group = NotificationGroupSummary("weather", CHANNEL_ID, R.string.notif_group_weather)

/** The id beside each notification's tag ([WeatherNotification.id]). */
private const val NOTIFICATION_ID = 2

/** The widest a card is painted: a phone's screen across. Wider than this is only a larger bitmap to hand the system. */
internal const val MAX_CARD_WIDTH_PX = 1080

/** Android's large icon, in dp. */
private const val BADGE_DP = 48

/**
 * However late it is posted, a notification about the forecast stays up at least this long: long
 * enough to be read. Not a government warning, which must not outlast the warning itself.
 */
private const val MIN_SHOWN_MS = 10 * 60_000L

/** The shortest time Android can be asked to keep one: a timeout has to be more than nothing. */
private const val MIN_TIMEOUT_MS = 1_000L

/**
 * Posts weather notifications on their own two channels. Each is tagged with its
 * [WeatherNotification.id], so a second post about the same thing replaces the first.
 *
 * What one looks like: the status-bar icon and the accent are its kind's (a cloud with rain in
 * blue, a bolt in violet, a funnel in red: see `WeatherNoticeLook`), the same glyph on its
 * coloured tile is the large icon, the place is the line beside the app's name, and opened out
 * it shows the forecast behind it as a picture (the next two hours of rain, the day by the hour)
 * or, for a government warning, the agency's words (an excerpt where it ran long). Its time is when what it tells of
 * happens, so a shower reads "in 25 min" and counts down by itself, and it takes itself down
 * once that is over (a warning at the moment it lapses; the rest no sooner than ten minutes after
 * they went up). A tap opens the weather app on what it was about; "Radar" opens the radar. A
 * shower about to start also comes up over the screen, and has a button to be reminded nearer the time.
 */
private class AndroidWeatherNotifier(private val context: Context) : WeatherNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override val isSupported = true

    override suspend fun isAllowed(): Boolean = allowed()

    private fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled()

    override suspend fun notify(notification: WeatherNotification): Boolean = withContext(Dispatchers.Default) {
        if (!allowed()) return@withContext false
        ensureChannels()
        val posted = buildWeatherNotification(context, notification, System.currentTimeMillis())
        runCatching {
            manager.notify(notification.id, NOTIFICATION_ID, posted)
            val look = notification.art.icon.look
            group.post(context, look.glyph, look.tintArgb, posted.timeoutAfter.takeIf { it > 0 }, posted.channelId)
        }
            .onFailure { Log.w(TAG, "couldn't post ${notification.id}: ${it.message}") }
            .isSuccess
    }

    private fun ensureChannels() {
        manager.retireChannels(*RETIRED_CHANNEL_IDS)
        manager.createSilentChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_weather_name),
            context.getString(R.string.notif_channel_weather_description),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createSilentChannel(
            SOON_CHANNEL_ID,
            context.getString(R.string.notif_channel_weather_soon_name),
            context.getString(R.string.notif_channel_weather_soon_description),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createSilentChannel(
            SEVERE_CHANNEL_ID,
            context.getString(R.string.notif_channel_weather_severe_name),
            context.getString(R.string.notif_channel_weather_severe_description),
            NotificationManager.IMPORTANCE_HIGH,
        )
    }
}

/** [notification] as Android shows it, at [nowMillis]: see [AndroidWeatherNotifier] for what each part is for. */
internal fun buildWeatherNotification(context: Context, notification: WeatherNotification, nowMillis: Long): Notification {
    val look = notification.art.icon.look
    // A picture that can't be painted must not cost the notification: it goes out as text.
    val badge = runCatching { badge(context, notification.art) }.onFailure { Log.w(TAG, "no badge for ${notification.id}: ${it.message}") }.getOrNull()
    val card = runCatching { card(context, notification.art) }.onFailure { Log.w(TAG, "no picture for ${notification.id}: ${it.message}") }.getOrNull()
    val channel = when {
        notification.urgent -> SEVERE_CHANNEL_ID
        notification.headsUp -> SOON_CHANNEL_ID
        else -> CHANNEL_ID
    }
    return NotificationCompat.Builder(context, channel)
        .setSmallIcon(look.glyph.drawableRes)
        .setColor(look.tintArgb)
        .setContentTitle(notification.title)
        .setContentText(notification.body)
        .setSubText(notification.place)
        .setTicker(notification.title)
        .setLargeIcon(badge)
        .setStyle(
            if (card != null) {
                // The picture when opened out; the tile steps aside for it.
                NotificationCompat.BigPictureStyle().bigPicture(card).bigLargeIcon(null as Bitmap?).setContentDescription(notification.body)
            } else {
                NotificationCompat.BigTextStyle().bigText(notification.detail ?: notification.body)
            },
        )
        // No category: a warning is loud on its own channel, but it is not an alarm, and
        // doesn't claim the pass through Do Not Disturb that one gets.
        .setPriority(if (notification.headsUp) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        // The weather is nobody's secret: shown in full on the lock screen.
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setGroup(group.group)
        .setDeleteIntent(group.removalIntent(context, notification.id, NOTIFICATION_ID))
        // When it happens, not when it was posted: Android writes a time still to come as "in 25 min".
        .setWhen(notification.eventEpochSeconds?.times(1000) ?: nowMillis)
        .setShowWhen(true)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openIntent(context, notification.link))
        .addAction(look.glyph.drawableRes, context.getString(R.string.notif_action_weather_radar), openIntent(context, notification.link.copy(view = WeatherDeepLink.View.RADAR, alertKey = null)))
        .apply {
            val at = notification.reminderEpochSeconds
            val label = notification.reminderLabel
            if (at != null && label != null) addAction(look.glyph.drawableRes, label, WeatherReminderReceiver.intent(context, notification.id, at))
        }
        // A warning is over when the agency says it is, to the second; the rest stay long enough to be read.
        .apply { notification.expiresEpochSeconds?.let { setTimeoutAfter((it * 1000 - nowMillis).coerceAtLeast(if (notification.urgent) MIN_TIMEOUT_MS else MIN_SHOWN_MS)) } }
        .build()
}

private fun badge(context: Context, art: WeatherNoticeArt): Bitmap =
    WeatherNoticePainter.badge(art.icon, (BADGE_DP * context.resources.displayMetrics.density).roundToInt()).asAndroidBitmap()

/** The card for a notice with a chart to show; one without opens out into its text instead. */
private fun card(context: Context, art: WeatherNoticeArt): Bitmap? {
    if (art.chart == null) return null
    val width = min(context.resources.displayMetrics.widthPixels, MAX_CARD_WIDTH_PX)
    return WeatherNoticePainter.card(art, width, WeatherNoticePainter.cardHeight(art, width), createFontFamilyResolver(context)).asAndroidBitmap()
}

/** The app's own activity, named explicitly, with [link] as its URI: also what keeps one notification's destination from overwriting another's. */
private fun openIntent(context: Context, link: WeatherDeepLink): PendingIntent? {
    val launch = context.appLaunchIntent() ?: return null
    val uri = link.toUri()
    val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).setComponent(launch.component)
    return PendingIntent.getActivity(context, uri.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

actual fun createWeatherNotifier(platformContext: PlatformContext): WeatherNotifier = AndroidWeatherNotifier(platformContext.context.applicationContext)

/**
 * The periodic look at the forecast, as WorkManager work: it survives the app being closed and
 * the phone restarting, waits for a network, and is run by the OS roughly every half hour (less
 * often when the phone is dozing). Half an hour is what "rain in the next hour or so" needs: a
 * shower is seen coming at least once before it arrives.
 */
class WeatherCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val posted = (BackgroundGraph.get(applicationContext) as WeatherBackgroundServices).weatherAlertCheck.run()
        Log.i(TAG, "background check: $posted posted")
        return Result.success()
    }
}

/**
 * One shower's reminder, as WorkManager work: from the "Remind me" button until its time comes. A
 * forecast that can't be had is tried again a few times. The work waits for a network, so it can
 * run late: from when that shower was due it has nothing to say, or it would tell of a later one.
 */
class WeatherReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (System.currentTimeMillis() / 1000 >= inputData.getLong(KEY_SHOWER_AT, Long.MAX_VALUE)) {
            Log.i(TAG, "reminder: too late for the shower it was about")
            return Result.success()
        }
        val result = (BackgroundGraph.get(applicationContext) as WeatherBackgroundServices).weatherAlertCheck.remind()
        Log.i(TAG, "reminder: $result (attempt ${runAttemptCount + 1})")
        return if (result == ReminderResult.TRY_AGAIN && runAttemptCount < REMINDER_RETRIES) Result.retry() else Result.success()
    }
}

/** Input to [WeatherReminderWorker]: when the shower it was asked for was due (epoch seconds). */
private const val KEY_SHOWER_AT = "shower_at"

/** How many times a reminder is tried again, a minute further apart each time: past that the rain has started. */
private const val REMINDER_RETRIES = 4

/**
 * A shower's "Remind me" button: asks for the notification again at the time the button said, and
 * once WorkManager has that safely stored, takes this one down. The reminder looks at the forecast
 * afresh, so it needs a network, and asking twice replaces the first.
 */
class WeatherReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        val tag = intent.getStringExtra(EXTRA_TAG)
        val delay = (at * 1000 - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<WeatherReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_SHOWER_AT to at + WeatherNoticePlanner.REMIND_BEFORE_SECONDS))
            .build()
        // Storing the request happens on another thread: the process is kept until it's done.
        val pending = goAsync()
        runCatching {
            val enqueued = WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request).result
            enqueued.addListener(
                {
                    runCatching {
                        enqueued.get()
                        context.getSystemService(NotificationManager::class.java).cancel(tag, NOTIFICATION_ID)
                        group.tidy(context, tag, NOTIFICATION_ID)
                    }.onFailure { Log.w(TAG, "couldn't schedule the reminder: ${it.message}") }
                    pending.finish()
                },
                ContextCompat.getMainExecutor(context),
            )
        }.onFailure {
            Log.w(TAG, "couldn't schedule the reminder: ${it.message}")
            pending.finish()
        }
    }

    companion object {
        private const val WORK_NAME = "weather-reminder"
        private const val EXTRA_AT = "at"
        private const val EXTRA_TAG = "tag"

        internal fun intent(context: Context, tag: String, atEpochSeconds: Long): PendingIntent {
            val intent = Intent(context, WeatherReminderReceiver::class.java).putExtra(EXTRA_AT, atEpochSeconds).putExtra(EXTRA_TAG, tag)
            return PendingIntent.getBroadcast(context, tag.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

private class AndroidWeatherCheckScheduler(private val context: Context) : WeatherCheckScheduler {
    override fun schedule(enabled: Boolean) {
        runCatching {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<WeatherCheckWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP: called on every launch, and the job already scheduled must not have its clock reset each time.
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "couldn't schedule the weather check: ${it.message}") }
    }

    private companion object {
        const val WORK_NAME = "weather-check"
    }
}

actual fun createWeatherCheckScheduler(platformContext: PlatformContext): WeatherCheckScheduler =
    AndroidWeatherCheckScheduler(platformContext.context.applicationContext)
