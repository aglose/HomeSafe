package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.shared.R
import kotlinx.coroutines.CompletableDeferred

private const val CHANNEL_ID = "detections"
private const val PREFS_NAME = "homesafe_notifications"
private const val PREF_ASKED = "asked_for_permission"

/**
 * Posts through a single high-importance "Detections" channel, so the user can silence or
 * restyle these alerts from the OS side without touching the app. minSdk is 33, so the
 * runtime `POST_NOTIFICATIONS` permission always applies: nothing shows until it's granted.
 */
private class AndroidAlertNotifier(private val activity: FragmentActivity) : AlertNotifier {
    private val context: Context = activity.applicationContext
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var pendingRequest: CompletableDeferred<Boolean>? = null

    // Registered on the registry directly (not through the LifecycleOwner overload), which is
    // allowed after the activity is started — the graph is built in onCreate, but late enough
    // that the lifecycle-bound register() would refuse.
    private val permissionLauncher = activity.activityResultRegistry.register(
        "homesafe.post_notifications",
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingRequest?.complete(granted)
        pendingRequest = null
    }

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detections", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A person, vehicle, or animal seen by one of your cameras"
            },
        )
    }

    override val isSupported = true

    override suspend fun permissionStatus(): NotificationPermission = when {
        isGranted() -> NotificationPermission.GRANTED
        // After one refusal Android still shows the prompt (with a rationale hint); after the
        // second it auto-denies silently, which is the state only the settings screen can fix.
        prefs.getBoolean(PREF_ASKED, false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS) ->
            NotificationPermission.DENIED
        else -> NotificationPermission.NOT_DETERMINED
    }

    override suspend fun requestPermission(): Boolean {
        if (isGranted()) return true
        pendingRequest?.let { return it.await() }
        val request = CompletableDeferred<Boolean>()
        pendingRequest = request
        prefs.edit().putBoolean(PREF_ASKED, true).apply()
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return request.await()
    }

    override fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    override fun notify(notification: AlertNotification) {
        if (!isGranted()) return
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val thumbnail = notification.thumbnail?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_detection)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .apply {
                if (thumbnail != null) {
                    setLargeIcon(thumbnail)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumbnail).bigLargeIcon(null as android.graphics.Bitmap?))
                }
            }
            .build()
        notificationManager.notify(notification.id.hashCode(), built)
    }

    private fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notificationManager.areNotificationsEnabled()
}

actual fun createAlertNotifier(platformContext: PlatformContext): AlertNotifier =
    AndroidAlertNotifier(platformContext.context as FragmentActivity)
