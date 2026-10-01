package com.meticulouscreations.homesafe.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A notification's "Not a person" button, and its Undo (see [AlertNotificationPoster.showMarkedNotAPerson]).
 * Runs whether or not the app is open — usually it isn't — so the relay call rides on this
 * install's device secret ([PushedAlertActions]), not a session. `goAsync` buys the ten seconds a
 * background receiver gets; a relay that doesn't answer in time is reported as a failure, with a
 * button to try again.
 */
class NotAPersonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        val target = intent.getStringExtra(EXTRA_TARGET)?.let(MomentDeepLink::fromUri)?.takeIf { it.eventId.isNotBlank() } ?: return
        val marking = when (intent.action) {
            ACTION_MARK -> true
            ACTION_UNDO -> false
            else -> return
        }
        val app = context.applicationContext
        if (marking) AlertNotificationPoster.showMarkingNotAPerson(app, id, target)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val actions = BackgroundGraph.get(app).pushedAlertActions
                val result = withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    if (marking) actions.markNotAPerson(target.eventId) else actions.undoNotAPerson(target.eventId)
                } ?: Result.failure(IllegalStateException("The server didn't answer in time."))
                result
                    .onSuccess {
                        if (marking) AlertNotificationPoster.showMarkedNotAPerson(app, id, target) else AlertNotificationPoster.clearNotAPerson(app, id)
                    }
                    .onFailure { e ->
                        val reason = e.message ?: "The server couldn't be reached."
                        if (marking) AlertNotificationPoster.showNotAPersonFailed(app, id, target, reason) else AlertNotificationPoster.showUndoFailed(app, id, target, reason)
                    }
                Log.i(TAG, "${if (marking) "not a person" else "undo"} ${target.eventId} -> ${if (result.isSuccess) "done" else result.exceptionOrNull()?.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK = "com.meticulouscreations.homesafe.action.NOT_A_PERSON"
        const val ACTION_UNDO = "com.meticulouscreations.homesafe.action.UNDO_NOT_A_PERSON"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_TARGET = "target"
        private const val TAG = "HomeSafeNotAPerson"
        private const val RECEIVER_BUDGET_MS = 9_000L

        /**
         * The button's PendingIntent: explicit, so only this app's receiver answers it, and immutable.
         * One request code per notification and action, so Undo can't overwrite the mark's, nor one
         * alert's button another's.
         */
        fun intent(context: Context, action: String, notificationId: String, target: MomentDeepLink): PendingIntent {
            val intent = Intent(context, NotAPersonReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_TARGET, target.copy(tagCar = false).toUri())
            return PendingIntent.getBroadcast(
                context,
                "$notificationId:$action".hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
