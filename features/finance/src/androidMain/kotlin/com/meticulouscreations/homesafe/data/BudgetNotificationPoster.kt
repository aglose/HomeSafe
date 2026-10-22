package com.meticulouscreations.homesafe.data

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.meticulouscreations.homesafe.appicon.appLaunchIntent
import com.meticulouscreations.homesafe.finance.domain.BudgetAlert
import com.meticulouscreations.homesafe.finance.ui.FinanceFormat
import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import com.meticulouscreations.homesafe.navigation.FinanceDeepLink
import com.meticulouscreations.homesafe.shared.R
import com.meticulouscreations.homesafe.ui.notification.NotificationGlyph
import com.meticulouscreations.homesafe.ui.notification.drawableRes

/**
 * Posts the relay's budget alerts: the month's card spending near or past a limit, or into the
 * savings. They are about money, not about someone at the door, so they have a channel of their
 * own at an ordinary importance (no alarm category, no heads-up over a call), which is also the
 * one switch that silences them on this phone. A tap opens Finance on the Budget tab.
 *
 * Each line of each month is one notification ([BudgetAlert.id] is its tag), so the relay
 * telling a phone twice replaces rather than stacks.
 */
object BudgetNotificationPoster {
    /** "_v2" because the first "budget" channel sounded (see `createSilentChannel`). */
    const val CHANNEL_ID = "budget_v2"

    /** Its own id beside the detections' (`AlertNotificationPoster`), so a tag can never collide with one of theirs. */
    private const val NOTIFICATION_ID = 2

    private const val CLOSE_COLOR = 0xFFE39A1E.toInt()
    private const val OVER_COLOR = 0xFFE5484D.toInt()

    /** A group of their own under the wallet, so Android never bundles the month's spending in with the cameras' alerts or the weather. */
    private val group = NotificationGroupSummary("budget", CHANNEL_ID, R.string.notif_group_budget)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.retireChannels("budget")
        manager.createSilentChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_budget_name),
            context.getString(R.string.notif_channel_budget_description),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    /** The alert in this phone's language, as (title, body). */
    fun text(context: Context, alert: BudgetAlert): Pair<String, String> {
        val spent = FinanceFormat.money(alert.spent, 0)
        val limit = FinanceFormat.money(alert.limit, 0)
        val standing = if (alert.daysLeft <= 0) {
            context.getString(R.string.notif_budget_body_spent_last_day, spent, limit)
        } else {
            context.resources.getQuantityString(R.plurals.notif_budget_body_spent, alert.daysLeft, spent, limit, alert.daysLeft)
        }
        return when (alert.kind) {
            BudgetAlert.Kind.CLOSE -> context.getString(R.string.notif_budget_close_title) to standing
            BudgetAlert.Kind.OVER -> context.getString(R.string.notif_budget_over_title) to standing
            BudgetAlert.Kind.SAVINGS -> context.getString(R.string.notif_budget_savings_title) to context.getString(R.string.notif_budget_savings_body, spent, limit)
            BudgetAlert.Kind.PERSON_OVER -> context.getString(R.string.notif_budget_person_over_title, alert.person.orEmpty()) to standing
            BudgetAlert.Kind.FAMILY_OVER -> context.getString(R.string.notif_budget_family_over_title) to standing
        }
    }

    /**
     * Posts it under [id]. [kind] is which line was crossed, where this build could read that: it
     * picks the colour (amber for close to a limit, red for past one), and nothing else.
     */
    fun show(context: Context, id: String, title: String, body: String, kind: BudgetAlert.Kind? = null) {
        if (!canPost(context)) return
        ensureChannel(context)
        val color = if (kind == null || kind == BudgetAlert.Kind.CLOSE) CLOSE_COLOR else OVER_COLOR
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(NotificationGlyph.BUDGET.drawableRes)
            .setColor(color)
            .setTicker(title)
            .setGroup(group.group)
            .setDeleteIntent(group.removalIntent(context, id, NOTIFICATION_ID))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, id))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, NOTIFICATION_ID, notification)
        group.post(context, NotificationGlyph.BUDGET, color)
    }

    /** What a tap opens: the app's own activity, named explicitly, with `homesafe://finance?tab=budget`, which MainActivity hands to the shell. */
    private fun openIntent(context: Context, id: String): PendingIntent? {
        val launch = context.appLaunchIntent() ?: return null
        val intent = Intent(Intent.ACTION_VIEW, FinanceDeepLink(FinanceTab.BUDGET).toUri().toUri()).setComponent(launch.component)
        return PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}
