package com.meticulouscreations.homesafe.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.meticulouscreations.homesafe.ui.notification.NotificationGlyph
import com.meticulouscreations.homesafe.ui.notification.drawableRes

/**
 * One kind of the app's notifications (the cameras, the weather, the budget) kept together in
 * the shade under a header of its own: its icon, its colour, and its name beside the app's.
 *
 * Android 16 and later fold an app's notifications into one stack under the app's own icon when
 * they are left ungrouped, or put in a group with no summary: a shower, a car in the drive and
 * the month's spending then all look alike. A group with a summary is left alone, so every kind
 * posts [post] beside each of its notifications. Android can still fold three or more small
 * groups (one or two notifications each) together; three kinds rarely all have that few.
 *
 * Android doesn't take a summary down when its notifications time out or are taken down by the
 * app, and one left on its own shows as an empty notification, so a poster that cancels or times
 * out its notifications calls [tidy] afterwards, and [post] gives the summary the latest timeout
 * of the notifications under it.
 */
class NotificationGroupSummary(
    /** The group key its notifications are posted with ([NotificationCompat.Builder.setGroup]). */
    val group: String,
    /** The channel the summary is posted on unless [post] is given another. It never sounds itself. */
    private val channelId: String,
    /** The kind's name, shown beside the app's in the group's header. */
    @param:StringRes private val label: Int,
) {
    private val tag = "group-summary:$group"

    /**
     * Posts the summary, or updates it, in [glyph] and [color]: call it right after posting one of
     * the group's notifications, with that notification's [timeoutAfterMs] (null for none) and
     * [channel]: on the channel of the notification it heads, it is up wherever that one may be,
     * though another of the kind's channels is switched off.
     */
    fun post(context: Context, glyph: NotificationGlyph, color: Int, timeoutAfterMs: Long? = null, channel: String = channelId) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val name = context.getString(label)
        val summary = NotificationCompat.Builder(context, channel)
            .setSmallIcon(glyph.drawableRes)
            .setColor(color)
            .setContentTitle(name)
            .setSubText(name)
            .setGroup(group)
            .setGroupSummary(true)
            // Each notification sounds (or doesn't) on its own; the summary never does.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .apply { latestTimeout(manager, timeoutAfterMs)?.let { setTimeoutAfter(it) } }
            .build()
        manager.notify(tag, SUMMARY_ID, summary)
    }

    /** Takes the summary down if nothing is left under it. */
    fun tidy(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (children(manager).isEmpty()) manager.cancel(tag, SUMMARY_ID)
    }

    private fun children(manager: NotificationManager): List<StatusBarNotification> =
        manager.activeNotifications.filter { it.notification.group == group && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }

    /**
     * How long until the last of the group's notifications times out, the one just posted
     * ([newest], which may not be in the shade's list yet) among them; null when one of them stays
     * until it is dismissed.
     */
    private fun latestTimeout(manager: NotificationManager, newest: Long?): Long? {
        if (newest == null) return null
        val now = System.currentTimeMillis()
        val others = children(manager).map { if (it.notification.timeoutAfter > 0) it.postTime + it.notification.timeoutAfter - now else null }
        if (others.any { it == null }) return null
        return (others.filterNotNull() + newest).max().coerceAtLeast(MIN_TIMEOUT_MS)
    }

    private companion object {
        /** Tags keep the summaries apart from each other and from every poster's own ids. */
        const val SUMMARY_ID = 0

        const val MIN_TIMEOUT_MS = 1_000L
    }
}
