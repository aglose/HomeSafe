package com.meticulouscreations.homesafe.weather.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.data.BackgroundGraph
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.shared.R
import com.meticulouscreations.homesafe.ui.notification.drawableRes
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
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

/** The everyday channel: rain on the way, the morning's and evening's outlook. */
internal const val CHANNEL_ID = "weather"

/** Government warnings, on a channel of their own so they can be let through when the rest are silenced. */
internal const val SEVERE_CHANNEL_ID = "weather_severe"

/** One group for the weather's notifications, so Android never bundles a shower in with the cameras' alerts. */
private const val GROUP = "weather"

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
 * they went up). A tap opens the weather app on what it was about; "Radar" opens the radar.
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
        runCatching { manager.notify(notification.id, NOTIFICATION_ID, posted) }
            .onFailure { Log.w(TAG, "couldn't post ${notification.id}: ${it.message}") }
            .isSuccess
    }

    private fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_weather_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_weather_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SEVERE_CHANNEL_ID, context.getString(R.string.notif_channel_weather_severe_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_weather_severe_description)
            },
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 2
    }
}

/** [notification] as Android shows it, at [nowMillis]: see [AndroidWeatherNotifier] for what each part is for. */
internal fun buildWeatherNotification(context: Context, notification: WeatherNotification, nowMillis: Long): Notification {
    val look = notification.art.icon.look
    // A picture that can't be painted must not cost the notification: it goes out as text.
    val badge = runCatching { badge(context, notification.art) }.onFailure { Log.w(TAG, "no badge for ${notification.id}: ${it.message}") }.getOrNull()
    val card = runCatching { card(context, notification.art) }.onFailure { Log.w(TAG, "no picture for ${notification.id}: ${it.message}") }.getOrNull()
    return NotificationCompat.Builder(context, if (notification.urgent) SEVERE_CHANNEL_ID else CHANNEL_ID)
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
        .setPriority(if (notification.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        // The weather is nobody's secret: shown in full on the lock screen.
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setGroup(GROUP)
        // When it happens, not when it was posted: Android writes a time still to come as "in 25 min".
        .setWhen(notification.eventEpochSeconds?.times(1000) ?: nowMillis)
        .setShowWhen(true)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openIntent(context, notification.link))
        .addAction(look.glyph.drawableRes, context.getString(R.string.notif_action_weather_radar), openIntent(context, notification.link.copy(view = WeatherDeepLink.View.RADAR, alertKey = null)))
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
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
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
        val posted = BackgroundGraph.get(applicationContext).weatherAlertCheck.run()
        Log.i(TAG, "background check: $posted posted")
        return Result.success()
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
