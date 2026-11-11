package com.meticulouscreations.homesafe.push

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
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
import com.meticulouscreations.homesafe.data.NotificationMuteStore
import com.meticulouscreations.homesafe.data.PushedAlertMedia
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import java.util.concurrent.TimeUnit

/**
 * Adds a pushed notification's picture, then its clip (see `PushedAlertMedia`), as two runs of
 * this worker. The clip can't be asked for until Frigate's preview window has passed, some 20 s
 * after the detection, and may take a minute more to appear: waiting that out inside one run
 * kept the phone awake for it, dozens of times a day. So the first run fetches the picture and
 * is over in a second or two, and schedules the second for when the clip is due; a clip that
 * isn't there yet is asked for again after WorkManager's backoff, with nothing held in between.
 *
 * The picture's run is expedited, since a picture that turns up minutes later is no use; out of
 * expedited quota it runs as ordinary work instead. The clip's can't be (delayed work never is),
 * so on a phone that is idle it arrives when Android next lets work run.
 */
class AlertMediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Muted since the push came: its notification gets nothing more.
        if (NotificationMuteStore.isMuted(applicationContext)) return Result.success()
        val input = inputData
        val eventId = input.getString(KEY_EVENT_ID) ?: return Result.failure()
        val start = input.getDouble(KEY_START, 0.0)
        val text = AlertNotification(
            id = input.getString(KEY_ID) ?: return Result.failure(),
            title = input.getString(KEY_TITLE).orEmpty(),
            body = input.getString(KEY_BODY).orEmpty(),
            target = MomentDeepLink(eventId, input.getString(KEY_CAMERA).orEmpty(), input.getDouble(KEY_TARGET_START, start)),
            urgent = input.getBoolean(KEY_URGENT, false),
            silent = input.getBoolean(KEY_SILENT, false),
            offerCarTag = input.getBoolean(KEY_OFFER_CAR_TAG, false),
            offerNotAPerson = input.getBoolean(KEY_OFFER_NOT_A_PERSON, false),
            subject = MomentCategory.entries.firstOrNull { it.name == input.getString(KEY_SUBJECT) } ?: MomentCategory.ALL,
        )
        val media = BackgroundGraph.get(applicationContext).pushedAlertMedia
        return if (input.getBoolean(KEY_CLIP, false)) addClip(media, text, eventId) else addPicture(media, text, eventId, start)
    }

    private suspend fun addPicture(media: PushedAlertMedia, text: AlertNotification, eventId: String, start: Double): Result {
        val picture = media.picture(eventId)
        // Replaced by a later alert of the same visit while the picture was on its way (a cancelled request reads as no
        // picture): that alert's run posts its own and schedules its own clip, and this one must not put its clip in the way.
        if (isStopped) return Result.success()
        // Muted while the picture was on its way: nothing more comes, the clip included.
        if (muted()) return Result.success()
        if (picture != null) {
            AlertNotificationPoster.show(applicationContext, text.copy(thumbnail = picture))
            Log.i(TAG, "alert ${text.id}: picture added")
        }
        // With or without its picture: the clip's run reads the picture again, and a text-only notification still gets its clip.
        val request = OneTimeWorkRequestBuilder<AlertMediaWorker>()
            .setInitialDelay(media.clipWaitMs(start), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, CLIP_RETRY_STEP_MS, TimeUnit.MILLISECONDS)
            .setConstraints(CONNECTED)
            .setInputData(Data.Builder().putAll(inputData).putBoolean(KEY_CLIP, true).build())
            .build()
        // A later alert of the same visit replaces this one's clip with its own, as it does the picture.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(clipWorkName(text.id), ExistingWorkPolicy.REPLACE, request)
        return Result.success()
    }

    private fun muted() = NotificationMuteStore.isMuted(applicationContext)

    /** One ask per run. Not there yet: again after 10 s, 20 s and 30 s (Android may stretch those), which is as long as it usually takes. */
    private suspend fun addClip(media: PushedAlertMedia, text: AlertNotification, eventId: String): Result {
        val clip = media.clip(eventId) ?: return if (runAttemptCount < CLIP_RETRIES) Result.retry() else Result.success()
        // The picture is the notification's icon beside the clip; a few kilobytes, read again rather than kept between runs.
        val picture = media.picture(eventId)
        // Replaced, or muted, while the two were on their way: the clip doesn't come.
        if (isStopped || muted()) return Result.success()
        AlertNotificationPoster.show(applicationContext, text.copy(thumbnail = picture, animation = clip))
        Log.i(TAG, "alert ${text.id}: clip added")
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
        private const val KEY_SILENT = "silent"
        private const val KEY_OFFER_CAR_TAG = "offer_car_tag"
        private const val KEY_OFFER_NOT_A_PERSON = "offer_not_a_person"
        private const val KEY_SUBJECT = "subject"

        /** Where a tap opens: the visit's start, which may be well before this alert's own ([KEY_START]). */
        private const val KEY_TARGET_START = "target_start"

        /** Set on the second run, which adds the clip; the first adds the picture. */
        private const val KEY_CLIP = "clip"

        /** How many more times a clip that isn't there is asked for, and the step its waits grow by (WorkManager's shortest). */
        private const val CLIP_RETRIES = 3
        private const val CLIP_RETRY_STEP_MS = 10_000L

        private val CONNECTED = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun clipWorkName(id: String) = "alert-clip-$id"

        fun enqueue(context: Context, text: AlertNotification, eventId: String, startEpochSeconds: Double) {
            val input: Data = workDataOf(
                KEY_ID to text.id,
                KEY_TITLE to text.title,
                KEY_BODY to text.body,
                KEY_CAMERA to text.target?.cameraName,
                KEY_EVENT_ID to eventId,
                KEY_START to startEpochSeconds,
                KEY_URGENT to text.urgent,
                KEY_SILENT to text.silent,
                KEY_OFFER_CAR_TAG to text.offerCarTag,
                KEY_OFFER_NOT_A_PERSON to text.offerNotAPerson,
                KEY_SUBJECT to text.subject.name,
                KEY_TARGET_START to (text.target?.startEpochSeconds ?: startEpochSeconds),
            )
            val request = OneTimeWorkRequestBuilder<AlertMediaWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(CONNECTED)
                .setInputData(input)
                .build()
            // A later alert of the same visit (or a repeat push) restarts its media with the newest picture rather than running twice.
            // The older alert's clip, if still waiting, goes too: its picture's run schedules the newer one.
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(clipWorkName(text.id))
            work.enqueueUniqueWork("alert-media-${text.id}", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
