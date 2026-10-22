package com.meticulouscreations.homesafe.data

import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * HomeSafe's notifications never make a sound: a camera, a door or the weather lights the screen
 * (and an Away alert buzzes), but the phone stays quiet. Someone who wants one to ring can still
 * pick a sound for its channel in Android's settings.
 *
 * Android fixes a channel's sound the first time the app creates it, and creating it again under
 * the same id changes only its name and description. So the channels that used to sound (the
 * Away alerts rang the phone's alarm) are deleted with [retireChannels] and come back under new
 * ids, created by [createSilentChannel].
 */
fun NotificationManager.createSilentChannel(id: String, name: String, description: String, importance: Int, vibrate: Boolean = false) {
    createNotificationChannel(
        NotificationChannel(id, name, importance).apply {
            this.description = description
            setSound(null, null)
            enableVibration(vibrate)
        },
    )
}

/** Deletes the channels under [ids], which this app no longer posts on; one already gone is skipped. */
fun NotificationManager.retireChannels(vararg ids: String) {
    for (id in ids) if (getNotificationChannel(id) != null) deleteNotificationChannel(id)
}
