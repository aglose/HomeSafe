package com.meticulouscreations.homesafe.push

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.meticulouscreations.homesafe.data.AlertNotificationPoster
import com.meticulouscreations.homesafe.data.BackgroundGraph
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.navigation.MomentDeepLink

/**
 * Adds a pushed notification's picture, then its clip (see `PushedAlertMedia.addTo`). A worker
 * rather than a coroutine in the messaging service because the clip can't be asked for until
 * Frigate's preview window has passed — some 20 s after the detection, often after the service
 * has stopped and Android has frozen the process. Expedited, since a picture that turns up
 * minutes later is no use; out of expedited quota it runs as ordinary work instead.
 */
class AlertMediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val input = inputData
        val eventId = input.getString(KEY_EVENT_ID) ?: return Result.failure()
        val start = input.getDouble(KEY_START, 0.0)
        val text = AlertNotification(
            id = input.getString(KEY_ID) ?: return Result.failure(),
            title = input.getString(KEY_TITLE).orEmpty(),
            body = input.getString(KEY_BODY).orEmpty(),
            target = MomentDeepLink(eventId, input.getString(KEY_CAMERA).orEmpty(), start),
            urgent = input.getBoolean(KEY_URGENT, false),
        )
        BackgroundGraph.get(applicationContext).pushedAlertMedia.addTo(text, eventId, start) { stage ->
            AlertNotificationPoster.show(applicationContext, stage)
            Log.i(TAG, "alert ${text.id}: ${if (stage.animation != null) "clip" else "picture"} added")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "HomeSafeAlertMedia"
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_CAMERA = "camera"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_START = "start"
        private const val KEY_URGENT = "urgent"

        fun enqueue(context: Context, text: AlertNotification, eventId: String, startEpochSeconds: Double) {
            val input: Data = workDataOf(
                KEY_ID to text.id,
                KEY_TITLE to text.title,
                KEY_BODY to text.body,
                KEY_CAMERA to text.target?.cameraName,
                KEY_EVENT_ID to eventId,
                KEY_START to startEpochSeconds,
                KEY_URGENT to text.urgent,
            )
            val request = OneTimeWorkRequestBuilder<AlertMediaWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(input)
                .build()
            // A repeat push of the same alert restarts its media rather than running it twice.
            WorkManager.getInstance(context).enqueueUniqueWork("alert-media-${text.id}", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
