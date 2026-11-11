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
import java.io.File
import java.util.UUID
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
        // Kept for the clip's run, which shows it beside the clip. Under this run's own id, which only its clip is told: a
        // later alert of the same visit keeps its own, and neither's runs can read or remove the other's.
        val kept = id.toString()
        keepPicture(kept, picture)
        if (picture != null) {
            AlertNotificationPoster.show(applicationContext, text.copy(thumbnail = picture))
            Log.i(TAG, "alert ${text.id}: picture added")
        }
        // With or without its picture: a text-only notification still gets its clip.
        val request = OneTimeWorkRequestBuilder<AlertMediaWorker>()
            .setInitialDelay(media.clipWaitMs(start), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, CLIP_RETRY_STEP_MS, TimeUnit.MILLISECONDS)
            .setConstraints(CONNECTED)
            .setInputData(Data.Builder().putAll(inputData).putBoolean(KEY_CLIP, true).putString(KEY_KEPT_PICTURE, kept).build())
            .build()
        // A later alert of the same visit replaces this one's clip with its own, as it does the picture.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(clipWorkName(text.id), ExistingWorkPolicy.REPLACE, request)
        return Result.success()
    }

    private fun muted() = NotificationMuteStore.isMuted(applicationContext)

    /** One ask per run. Not there yet: again after 10 s, 20 s and 30 s (Android may stretch those), which is as long as it usually takes. */
    private suspend fun addClip(media: PushedAlertMedia, text: AlertNotification, eventId: String): Result {
        val clip = media.clip(eventId)
        if (clip == null && runAttemptCount < CLIP_RETRIES) return Result.retry()
        // The picture is the notification's icon beside the clip: the one its own run kept, so the notification never
        // loses a picture it already shows; asked for again only if that run got none.
        val kept = inputData.getString(KEY_KEPT_PICTURE)
        val picture = clip?.let { kept?.let(::keptPicture) ?: media.picture(eventId) }
        // Stopped while the two were on their way (replaced, or the network went): the kept picture stays for a run that comes again.
        if (isStopped) return Result.success()
        // No clip after the last ask, or muted meanwhile: the clip doesn't come. Otherwise it plays, and a run stopped
        // while it does is cancelled there, before the kept picture goes, so one that comes again still has it.
        if (clip != null && !muted()) {
            AlertNotificationPoster.show(applicationContext, text.copy(thumbnail = picture, animation = clip))
            Log.i(TAG, "alert ${text.id}: clip added")
        }
        kept?.let { keepPicture(it, null) }
        return Result.success()
    }

    /**
     * The picture between the two runs: a few kilobytes in the cache directory, under the picture run's id. Null forgets
     * it. One whose clip run never came (replaced work, a reinstall) is swept here once it is [KEPT_PICTURE_MAX_AGE_MS] old.
     */
    private fun keepPicture(key: String, picture: ByteArray?) {
        runCatching {
            val directory = File(applicationContext.cacheDir, KEPT_PICTURES).apply { mkdirs() }
            val oldest = System.currentTimeMillis() - KEPT_PICTURE_MAX_AGE_MS
            directory.listFiles()?.filter { it.lastModified() < oldest }?.forEach { it.delete() }
            val file = File(directory, keptPictureName(key))
            if (picture != null) file.writeBytes(picture) else file.delete()
        }
    }

    private fun keptPicture(key: String): ByteArray? =
        runCatching { File(File(applicationContext.cacheDir, KEPT_PICTURES), keptPictureName(key)).takeIf { it.isFile }?.readBytes() }.getOrNull()

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

        private const val KEPT_PICTURES = "alert-pictures"
        private const val KEPT_PICTURE_MAX_AGE_MS = 60 * 60_000L

        /** The clip run's input: the name its picture was kept under. */
        private const val KEY_KEPT_PICTURE = "kept_picture"

        /** A file name from a key that came through work data: never a path. */
        private fun keptPictureName(key: String) = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

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
