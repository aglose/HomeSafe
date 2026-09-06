package com.meticulouscreations.homesafe.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.meticulouscreations.homesafe.shared.R

/**
 * Shows a push from the HomeSafe relay. When the app is in the background Android displays the
 * notification itself (the relay names the same "detections" channel the in-app alerts use); this
 * service handles the foreground case and token rotation.
 */
class HomeSafeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(token, applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detections", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Someone or something in a zone you asked about"
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
        // The relay marks escalated pushes with away=1 (see docs/away-mode.md): nobody home, person seen.
        val channelId = if (message.data["away"] == "1") AWAY_CHANNEL_ID else CHANNEL_ID
        val openApp = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val built = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_detection)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        val tag = message.data["review_id"] ?: message.messageId ?: title
        manager.notify(tag.hashCode(), built)
    }

    private companion object {
        /** Shared with AlertNotifier.android.kt so the user sees one channel, not two. */
        const val CHANNEL_ID = "detections"
        /** Also in AlertNotifier.android.kt and the relay's send_push: the loud away-mode channel. */
        const val AWAY_CHANNEL_ID = "away_alerts"
    }
}
