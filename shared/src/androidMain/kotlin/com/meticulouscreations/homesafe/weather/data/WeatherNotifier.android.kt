package com.meticulouscreations.homesafe.weather.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import java.util.concurrent.TimeUnit

private const val TAG = "HomeSafeWeather"

/** The everyday channel: rain on the way, the morning's and evening's outlook. */
private const val CHANNEL_ID = "weather"

/** Government warnings, on a channel of their own so they can be let through when the rest are silenced. */
private const val SEVERE_CHANNEL_ID = "weather_severe"

/**
 * Posts weather notifications on their own two channels. Each is tagged with its
 * [WeatherNotification.id], so a second post about the same thing replaces the first, and a tap
 * opens the weather app through `homesafe://weather`.
 */
private class AndroidWeatherNotifier(private val context: Context) : WeatherNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override val isSupported = true

    override fun notify(notification: WeatherNotification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannels()
        val posted = NotificationCompat.Builder(context, if (notification.urgent) SEVERE_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_weather)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setCategory(if (notification.urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (notification.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
            .build()
        runCatching { manager.notify(notification.id, NOTIFICATION_ID, posted) }
            .onFailure { Log.w(TAG, "couldn't post ${notification.id}: ${it.message}") }
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

    private fun openIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val intent = Intent(Intent.ACTION_VIEW, WeatherDeepLink.URI.toUri()).setComponent(launch.component)
        return PendingIntent.getActivity(context, WeatherDeepLink.URI.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val NOTIFICATION_ID = 2
    }
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
