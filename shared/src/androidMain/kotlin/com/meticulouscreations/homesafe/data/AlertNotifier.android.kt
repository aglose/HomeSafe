package com.meticulouscreations.homesafe.data

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import kotlinx.coroutines.CompletableDeferred

private const val PREFS_NAME = "homesafe_notifications"
private const val PREF_ASKED = "asked_for_permission"

/**
 * Posts through a single high-importance "Detections" channel, so the user can silence or
 * restyle these alerts from the OS side without touching the app. minSdk is 33, so the
 * runtime `POST_NOTIFICATIONS` permission always applies: nothing shows until it's granted.
 * The drawing itself — stages, clip, tap target — is [AlertNotificationPoster]'s, shared with pushes.
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
        AlertNotificationPoster.ensureChannels(context)
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
        AlertNotificationPoster.post(context, notification)
    }

    private fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notificationManager.areNotificationsEnabled()
}

/**
 * For a graph built with no Activity — a geofence or boot receiver waking the app to report
 * presence. Nothing there posts in-app alerts (the poller isn't started), and there's no UI to
 * ask permission from, so this answers like desktop does.
 */
private class HeadlessAlertNotifier : AlertNotifier {
    override val isSupported = false
    override suspend fun permissionStatus() = NotificationPermission.DENIED
    override suspend fun requestPermission() = false
    override fun openSystemSettings() = Unit
    override fun notify(notification: AlertNotification) = Unit
}

actual fun createAlertNotifier(platformContext: PlatformContext): AlertNotifier =
    (platformContext.context as? FragmentActivity)?.let(::AndroidAlertNotifier) ?: HeadlessAlertNotifier()
