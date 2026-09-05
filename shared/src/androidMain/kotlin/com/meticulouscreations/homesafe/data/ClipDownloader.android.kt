package com.meticulouscreations.homesafe.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import androidx.core.net.toUri
import com.meticulouscreations.homesafe.PlatformContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hands the clip off to Android's own [DownloadManager] rather than fetching it in-process:
 * that gets a system download notification, resilience across the app being backgrounded or
 * killed, and — since minSdk 33 is well past scoped storage — a write into the public Downloads
 * collection with no storage permission needed, all for free.
 */
private class AndroidClipDownloader(private val context: Context) : ClipDownloader {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override suspend fun download(url: String, headers: Map<String, String>, fileName: String): Result<Unit> = runCatching {
        val request = DownloadManager.Request(url.toUri())
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("video/mp4")
        headers.forEach { (key, value) -> request.addRequestHeader(key, value) }

        val downloadId = downloadManager.enqueue(request)
        awaitCompletion(downloadId)
    }

    /** Suspends until [DownloadManager] reports [downloadId] finished, failing if its final status wasn't successful. */
    private suspend fun awaitCompletion(downloadId: Long): Unit = suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                runCatching { context.unregisterReceiver(this) }

                val status = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else DownloadManager.STATUS_FAILED
                }
                if (!continuation.isActive) return
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(IllegalStateException("Download failed (status $status)"))
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }
}

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    AndroidClipDownloader(platformContext.context.applicationContext)
