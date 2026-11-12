package com.meticulouscreations.homesafe.data

import android.content.Context
import android.util.Log
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.ClipDownloadProgress
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.network.requireSuccess
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.download_frigate_refused
import homesafe.shared.generated.resources.download_write_failed
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Fetches the clip with the app's shared, already-authenticated [httpClient] into the app's
 * cache, then [saveClipToDownloads] rewrites it into the public Downloads collection (Frigate's
 * clip is a fragmented MP4 other apps can't attach; no storage permission needed since scoped
 * storage) and the cached copy is deleted.
 *
 * Not Android's `DownloadManager`, which this used to hand the URL to: from Android 17 a
 * connection to the local network needs `ACCESS_LOCAL_NETWORK`, which this app holds and the
 * system downloads provider (another app) does not. At home, where the server's address is a
 * LAN one, its connections were dropped on the phone and it sat retrying, the clip "saving" for
 * minutes, while the same address worked from the app. Fetching in-process goes out on the
 * app's own permission and route, as iOS and the desktop already do, and a connection that
 * can't be made fails within [CONNECT_TIMEOUT_MS] with a message instead of waiting forever.
 *
 * The response is streamed to the file a chunk at a time (a clip can run to hundreds of
 * megabytes), with the shared client's request timeout lifted for this one call: Frigate cutting
 * a long clip before it sends a byte can take longer than that. A stalled connection is still
 * caught by the socket timeout.
 */
private class AndroidClipDownloader(private val context: Context, private val httpClient: HttpClient) : ClipDownloader {

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        onProgress: (ClipDownloadProgress) -> Unit,
    ): Result<Unit> {
        // A caller that goes away (the moments page closed) still gets the clip into Downloads,
        // as it did when the system downloaded it; only the waiting is abandoned. The timeouts
        // bound how long it can run on.
        val result = withContext(NonCancellable) { runCatching { downloadAndSave(url, headers, fileName, onProgress) } }
        // But its result mustn't reach a cancelled caller: a newer download may already be
        // showing where this one's "Downloaded" would land.
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun downloadAndSave(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        onProgress: (ClipDownloadProgress) -> Unit,
    ) {
        onProgress(ClipDownloadProgress.Preparing)
        val staging = withContext(Dispatchers.IO) { stagingFile(fileName) }
        try {
            httpClient.prepareGet(url) {
                headers.forEach { (key, value) -> header(key, value) }
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }.execute { response ->
                response.requireSuccess(Res.string.download_frigate_refused)
                writeToFile(staging, response.bodyAsChannel(), response.contentLength(), onProgress)
            }
            withContext(Dispatchers.IO) {
                try {
                    saveClipToDownloads(context.contentResolver, staging, fileName)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't save $fileName to Downloads", e)
                    throw LocalizedException(UiText.of(Res.string.download_write_failed), technical = "Couldn't save $fileName to Downloads: ${e.message}")
                }
            }
        } finally {
            withContext(Dispatchers.IO) { staging.delete() }
        }
    }

    private suspend fun writeToFile(file: File, body: ByteReadChannel, totalBytes: Long?, onProgress: (ClipDownloadProgress) -> Unit) = withContext(Dispatchers.IO) {
        try {
            file.outputStream().buffered().use { out ->
                val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
                var received = 0L
                var last: ClipDownloadProgress = ClipDownloadProgress.Preparing
                while (true) {
                    val read = body.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    received += read
                    val fraction = totalBytes?.takeIf { it > 0L }?.let { (received.toDouble() / it).toFloat().coerceIn(0f, 1f) }
                    // A percent at a time: a chunk per call would recompose the ring for nothing.
                    val progress = ClipDownloadProgress.Downloading(fraction?.let { (it * PERCENT).toInt() / PERCENT })
                    if (progress != last) {
                        last = progress
                        onProgress(progress)
                    }
                }
            }
        } catch (e: IOException) {
            // A connection that dropped mid-body is the network's to explain, not the disk's.
            if (body.isClosedForRead && body.closedCause != null) throw e
            Log.w(TAG, "Couldn't write the clip to $file", e)
            throw LocalizedException(UiText.of(Res.string.download_write_failed), technical = "Couldn't write the clip to $file: ${e.message}")
        }
    }

    /**
     * A fresh file in the app's cache for [fileName] to arrive in. Anything a download the
     * process died during left behind is swept away once it is old enough that nothing still
     * writes it.
     */
    private fun stagingFile(fileName: String): File {
        val dir = File(context.cacheDir, STAGING_DIR)
        dir.mkdirs()
        val stale = System.currentTimeMillis() - STAGED_FILE_MAX_AGE_MS
        dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        return File(dir, "${UUID.randomUUID()}-$fileName")
    }
}

private const val TAG = "ClipDownloader"

private const val STAGING_DIR = "clip-downloads"

/** Old enough that no download still running could own it. */
private const val STAGED_FILE_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** How much of the clip is held in memory at once on its way to the file. */
private const val DOWNLOAD_CHUNK_BYTES = 256 * 1024

private const val PERCENT = 100f

/** How long to wait for the server to answer the connection before saying it can't be reached. */
private const val CONNECT_TIMEOUT_MS = 15_000L

/** How long the connection may go quiet (Frigate still cutting, or the network gone) before the save gives up. */
private const val SOCKET_TIMEOUT_MS = 120_000L

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    AndroidClipDownloader(platformContext.context.applicationContext, httpClient)
