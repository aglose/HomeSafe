package com.meticulouscreations.homesafe.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
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
import homesafe.shared.generated.resources.download_write_failed
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Hands the clip off to Android's own [DownloadManager] rather than fetching it in-process:
 * that gets a system download notification and resilience across the app being backgrounded,
 * for free. It lands in the app's own external files first, not Downloads, because Frigate's
 * clip is a fragmented MP4 that other apps can't attach; [saveClipToDownloads] rewrites it into
 * the public Downloads collection (no storage permission needed since scoped storage) and the
 * download's own entry, file and notification are then removed.
 *
 * How it's getting on is read by polling the download's row rather than waiting on
 * [DownloadManager.ACTION_DOWNLOAD_COMPLETE]: that broadcast comes from the downloads provider,
 * another app, so a receiver registered not-exported (as Android 14+ asks of anything that
 * isn't meant for other apps) may never hear it — leaving the caller saving forever. Polling
 * also gives the progress the broadcast can't.
 */
private class AndroidClipDownloader(private val context: Context) : ClipDownloader {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        onProgress: (ClipDownloadProgress) -> Unit,
    ): Result<Unit> = runCatching {
        val staging = withContext(Dispatchers.IO) { stagingFile(fileName) }
        val request = DownloadManager.Request(url.toUri())
            .setTitle(fileName)
            // Only while it runs: the finished file moves to Downloads and this entry is removed.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(staging))
            .setMimeType("video/mp4")
        headers.forEach { (key, value) -> request.addRequestHeader(key, value) }

        val downloadId = downloadManager.enqueue(request)
        onProgress(ClipDownloadProgress.Preparing)
        // A caller that goes away (the moments page closed) still gets the clip into Downloads,
        // as it did when DownloadManager wrote it there itself; only the waiting is abandoned.
        withContext(NonCancellable) {
            try {
                awaitCompletion(downloadId, onProgress)
                withContext(Dispatchers.IO) {
                    try {
                        saveClipToDownloads(context.contentResolver, staging, fileName)
                    } catch (e: Exception) {
                        throw LocalizedException(UiText.of(Res.string.download_write_failed), technical = "Couldn't save $fileName to Downloads: ${e.message}")
                    }
                }
            } finally {
                // Takes the staged file and the notification with it.
                downloadManager.remove(downloadId)
                withContext(Dispatchers.IO) { staging.delete() }
            }
        }
    }

    /**
     * A fresh file in the app's external files for [fileName] to download into. DownloadManager
     * only writes to external storage, and this part of it is the app's own and never scanned
     * into the gallery. Anything left from a download the process died during is swept away.
     */
    private fun stagingFile(fileName: String): File {
        val root = context.getExternalFilesDir(null)
            ?: throw LocalizedException(UiText.of(Res.string.download_write_failed), technical = "No external storage")
        val dir = File(root, STAGING_DIR)
        dir.mkdirs()
        val stale = System.currentTimeMillis() - STAGED_FILE_MAX_AGE_MS
        dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        return File(dir, "${UUID.randomUUID()}-$fileName")
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

private const val STAGING_DIR = "clip-downloads"

/** Old enough that no download still running could own it. */
private const val STAGED_FILE_MAX_AGE_MS = 24L * 60 * 60 * 1000

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    AndroidClipDownloader(platformContext.context.applicationContext)
