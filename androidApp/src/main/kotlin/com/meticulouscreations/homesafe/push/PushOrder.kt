package com.meticulouscreations.homesafe.push

import android.content.Context
import android.util.Log

/**
 * Keeps pushes for one notification in the order the relay sent them. The relay sends what
 * alerts at high priority and what only updates a notification (`silent=1`) at normal priority,
 * and Android may hold the second kind back while the phone sleeps; so an update can arrive
 * after a newer, louder push with the same `notif_id` ("Kia has moved" after "Move Kia now"),
 * and showing it would put the old words back. Every push says when it was sent (`sent_at`);
 * the newest shown for each notification is remembered here, and anything older is dropped.
 */
internal object PushOrder {
    private const val TAG = "HomeSafePushOrder"
    private const val PREFS = "homesafe_push_order"

    /** How many notifications are remembered; past that, the ones heard from longest ago go. */
    private const val KEPT = 200

    /**
     * True when a push sent later than [sentAtMillis] has already been shown for [notificationId].
     * Otherwise this one is remembered as its newest. A push without either (an older relay, a
     * push that stands alone) is never stale.
     */
    @Synchronized
    fun isStale(context: Context, notificationId: String?, sentAtMillis: Long?): Boolean {
        if (notificationId.isNullOrEmpty() || sentAtMillis == null) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sentAtMillis < prefs.getLong(notificationId, Long.MIN_VALUE)) {
            Log.i(TAG, "dropped a push for $notificationId that a newer one overtook")
            return true
        }
        val edit = prefs.edit().putLong(notificationId, sentAtMillis)
        val all = prefs.all
        if (all.size >= KEPT) {
            all.entries.filter { it.key != notificationId }.sortedBy { it.value as? Long ?: Long.MIN_VALUE }.take(all.size - KEPT + 1).forEach { edit.remove(it.key) }
        }
        edit.apply()
        return false
    }
}
