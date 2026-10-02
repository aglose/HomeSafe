package com.meticulouscreations.homesafe.data

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.ClipDownloadProgress
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.download_cancelled
import homesafe.shared.generated.resources.download_connection_lost
import homesafe.shared.generated.resources.download_failed_code
import homesafe.shared.generated.resources.download_file_exists
import homesafe.shared.generated.resources.download_frigate_refused
import homesafe.shared.generated.resources.download_no_space
import homesafe.shared.generated.resources.download_too_many_redirects
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Hands the clip off to Android's own [DownloadManager] rather than fetching it in-process:
 * that gets a system download notification, resilience across the app being backgrounded or
 * killed, and — since minSdk 33 is well past scoped storage — a write into the public Downloads
 * collection with no storage permission needed, all for free.
 *
 * How it's getting on is read by polling the download's row rather than waiting on
 * [DownloadManager.ACTION_DOWNLOAD_COMPLETE]: that broadcast comes from the downloads provider,
 * another app, so a receiver registered not-exported (as Android 14+ asks of anything that
 * isn't meant for other apps) may never hear it — leaving the caller saving forever. Polling
 * also gives the progress the broadcast can't.
 */
private class AndroidClipDownloader(context: Context) : ClipDownloader {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        onProgress: (ClipDownloadProgress) -> Unit,
    ): Result<Unit> = runCatching {
        val request = DownloadManager.Request(url.toUri())
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("video/mp4")
        headers.forEach { (key, value) -> request.addRequestHeader(key, value) }

        val downloadId = downloadManager.enqueue(request)
        onProgress(ClipDownloadProgress.Preparing)
        awaitCompletion(downloadId, onProgress)
    }

    /**
     * Follows [downloadId] until [DownloadManager] finishes it, failing if it didn't succeed. A
     * cancelled caller stops following; the download itself carries on, notification and all.
     */
    private suspend fun awaitCompletion(downloadId: Long, onProgress: (ClipDownloadProgress) -> Unit) {
        var last: ClipDownloadProgress = ClipDownloadProgress.Preparing
        while (true) {
            val row = withContext(Dispatchers.IO) { query(downloadId) }
                // Gone: cancelled from its notification, or cleared from the Downloads app.
                ?: throw LocalizedException(UiText.of(Res.string.download_cancelled), technical = "Download cancelled")
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> return
                DownloadManager.STATUS_FAILED -> throw failure(row.reason)
            }
            val progress = when {
                row.bytesSoFar <= 0L -> ClipDownloadProgress.Preparing
                row.totalBytes > 0L -> ClipDownloadProgress.Downloading((row.bytesSoFar.toDouble() / row.totalBytes).toFloat().coerceIn(0f, 1f))
                else -> ClipDownloadProgress.Downloading(fraction = null)
            }
            if (progress != last) {
                last = progress
                onProgress(progress)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun query(downloadId: Long): DownloadRow? =
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            DownloadRow(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                bytesSoFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }

    /** [reason] is the HTTP status for a server's refusal, or one of [DownloadManager]'s ERROR_ codes. */
    private fun failure(reason: Int): LocalizedException {
        val text = when (reason) {
            in 400..599 -> UiText.of(Res.string.download_frigate_refused, reason.toString())
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> UiText.of(Res.string.download_no_space)
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> UiText.of(Res.string.download_file_exists)
            DownloadManager.ERROR_HTTP_DATA_ERROR -> UiText.of(Res.string.download_connection_lost)
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> UiText.of(Res.string.download_too_many_redirects)
            else -> UiText.of(Res.string.download_failed_code, reason.toString())
        }
        return LocalizedException(text, technical = "Download failed ($reason)")
    }

    private class DownloadRow(val status: Int, val reason: Int, val bytesSoFar: Long, val totalBytes: Long)
}

/** Often enough for a progress ring to move smoothly; a query is one small row. */
private const val POLL_INTERVAL_MS = 500L

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    AndroidClipDownloader(platformContext.context.applicationContext)
