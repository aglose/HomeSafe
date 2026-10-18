package com.meticulouscreations.homesafe.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.meticulouscreations.homesafe.ui.notification.NotificationGlyph
import com.meticulouscreations.homesafe.ui.notification.drawableRes
import kotlin.math.abs

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
 * Android doesn't take a summary down when the notifications under it time out, are dismissed one
 * by one, or are cancelled by the app, and one left on its own shows as an empty notification. So
 * each of the group's notifications carries [removalIntent] as its delete intent (Android sends it
 * on a dismissal and on a timeout), a poster that cancels one calls [tidy], and both reconcile the
 * summary with what is left under it: gone when nothing is, otherwise timing out with the last of
 * them, or not at all while one of them stays until it is dismissed.
 */
class NotificationGroupSummary(
    /** The group key its notifications are posted with ([NotificationCompat.Builder.setGroup]). */
    val group: String,
    /** The channel the summary is posted on unless [post] is given another. It never sounds itself. */
    private val channelId: String,
    /** The kind's name, shown beside the app's in the group's header. */
    @param:StringRes private val label: Int,
) {
    /**
     * Posts the summary, or updates it, in [glyph] and [color]: call it right after posting one of
     * the group's notifications, with that notification's [timeoutAfterMs] (null for none) and
     * [channel]: on the channel of the notification it heads, it is up wherever that one may be,
     * though another of the kind's channels is switched off.
     */
    fun post(context: Context, glyph: NotificationGlyph, color: Int, timeoutAfterMs: Long? = null, channel: String = channelId) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val name = context.getString(label)
        val now = System.currentTimeMillis()
        // The one just posted may not be in the shade's list yet, so its own timeout is counted as given.
        val others = children(manager, group)
        val timeout = when {
            timeoutAfterMs == null -> null
            others.isEmpty() -> timeoutAfterMs
            else -> latestTimeout(others, now)?.let { maxOf(it, timeoutAfterMs) }
        }
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
            .apply { timeout?.let { setTimeoutAfter(it.coerceAtLeast(MIN_TIMEOUT_MS)) } }
            .build()
        manager.notify(summaryTag(group), SUMMARY_ID, summary)
    }

    /** The group's notification under [tag] and [id] has just been cancelled by the app: reconcile the summary with what is left. */
    fun tidy(context: Context, tag: String?, id: Int) = reconcile(context, group, Removed(tag, id))

    /**
     * The delete intent for the group's notification under [tag] and [id]: explicit and immutable,
     * and one per notification (the data URI tells them apart), so one can't overwrite another's.
     */
    fun removalIntent(context: Context, tag: String?, id: Int): PendingIntent {
        val uri = Uri.Builder().scheme(URI_SCHEME).authority(group).appendPath(tag.orEmpty()).appendPath(id.toString()).build()
        val intent = Intent(ACTION_REMOVED, uri, context, NotificationGroupReceiver::class.java)
            .putExtra(EXTRA_GROUP, group)
            .putExtra(EXTRA_TAG, tag)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A notification on its way out, which the shade's list may still hold: cancelling is asynchronous. */
    internal data class Removed(val tag: String?, val id: Int)

    internal companion object {
        /** Tags keep the summaries apart from each other and from every poster's own ids. */
        private const val SUMMARY_ID = 0

        private const val MIN_TIMEOUT_MS = 1_000L

        /** Closer than this, a summary's deadline is left as it is rather than posted again. */
        private const val TIMEOUT_SLACK_MS = 1_000L

        const val ACTION_REMOVED = "com.meticulouscreations.homesafe.action.NOTIFICATION_REMOVED"
        const val EXTRA_GROUP = "group"
        const val EXTRA_TAG = "tag"
        const val EXTRA_ID = "id"
        private const val URI_SCHEME = "homesafe-notification"

        private fun summaryTag(group: String) = "group-summary:$group"

        private fun isSummary(sbn: StatusBarNotification) = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

        private fun children(manager: NotificationManager, group: String, removed: Removed? = null): List<StatusBarNotification> =
            manager.activeNotifications.filter {
                it.notification.group == group && !isSummary(it) && !(removed != null && it.tag == removed.tag && it.id == removed.id)
            }

        /** How long from [now] until the last of [children] times out; null when one of them stays until it is dismissed. */
        private fun latestTimeout(children: List<StatusBarNotification>, now: Long): Long? {
            if (children.isEmpty() || children.any { it.notification.timeoutAfter <= 0 }) return null
            return children.maxOf { it.postTime + it.notification.timeoutAfter - now }
        }

        /**
         * Takes [group]'s summary down if nothing but [removed] is left under it; otherwise gives
         * it the deadline of what is, posting it again only when that has changed.
         */
        fun reconcile(context: Context, group: String, removed: Removed?) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val summary = manager.activeNotifications.firstOrNull { it.tag == summaryTag(group) && it.id == SUMMARY_ID } ?: return
            val children = children(manager, group, removed)
            if (children.isEmpty()) {
                manager.cancel(summaryTag(group), SUMMARY_ID)
                return
            }
            val now = System.currentTimeMillis()
            val wanted = latestTimeout(children, now)
            val current = summary.notification.timeoutAfter.takeIf { it > 0 }?.let { summary.postTime + it - now }
            val unchanged = if (wanted == null || current == null) wanted == current else abs(wanted - current) < TIMEOUT_SLACK_MS
            if (unchanged) return
            val updated = Notification.Builder.recoverBuilder(context, summary.notification)
                // Nothing for "no timeout": a timeout of 0 is none.
                .setTimeoutAfter(wanted?.coerceAtLeast(MIN_TIMEOUT_MS) ?: 0)
                .build()
            manager.notify(summaryTag(group), SUMMARY_ID, updated)
        }
    }
}

/** Hears that one of a group's notifications was dismissed or timed out (see [NotificationGroupSummary]). */
class NotificationGroupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationGroupSummary.ACTION_REMOVED) return
        val group = intent.getStringExtra(NotificationGroupSummary.EXTRA_GROUP) ?: return
        val removed = NotificationGroupSummary.Removed(intent.getStringExtra(NotificationGroupSummary.EXTRA_TAG), intent.getIntExtra(NotificationGroupSummary.EXTRA_ID, 0))
        NotificationGroupSummary.reconcile(context.applicationContext, group, removed)
    }
}
