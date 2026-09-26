package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UINavigationController
import platform.UIKit.UITabBarController
import platform.UIKit.UIViewController
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * iOS has no public "Downloads" folder a third-party app can write into the way Android's
 * [android.app.DownloadManager] does, so this fetches the clip itself (via the app's shared,
 * already-authenticated [httpClient]) and hands it to the system share sheet — "Save Video" /
 * "Save to Files" from there is the iOS equivalent of downloading it.
 *
 * The response is streamed to the temporary file a chunk at a time rather than read into memory
 * whole: a clip can run to [com.meticulouscreations.homesafe.domain.model.ClipLimits.MAX_SECONDS],
 * which at a camera's main-stream bitrate is hundreds of megabytes.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosClipDownloader(private val httpClient: HttpClient) : ClipDownloader {

    override suspend fun download(url: String, headers: Map<String, String>, fileName: String): Result<Unit> = runCatching {
        val path = NSTemporaryDirectory() + fileName
        httpClient.prepareGet(url) {
            headers.forEach { (key, value) -> header(key, value) }
        }.execute { response ->
            check(response.status.isSuccess()) { "Frigate answered ${response.status} for the clip" }
            writeToFile(path, response.bodyAsChannel())
        }

        withContext(Dispatchers.Main) {
            val activityController = UIActivityViewController(
                activityItems = listOf(NSURL.fileURLWithPath(path)),
                applicationActivities = null,
            )
            val presenter = requireNotNull(topViewController()) { "No screen to show the share sheet from" }
            presenter.presentViewController(activityController, animated = true, completion = null)
        }
    }

    /**
     * Copies [body] into the file at [path], [DOWNLOAD_CHUNK_BYTES] at a time. Plain POSIX file
     * I/O rather than NSData, so this doesn't depend on Foundation's byte-buffer factory overloads.
     */
    private suspend fun writeToFile(path: String, body: ByteReadChannel) = withContext(Dispatchers.IO) {
        val file = fopen(path, "wb") ?: error("Couldn't open $path for writing")
        try {
            val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
            while (true) {
                val read = body.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                val written = buffer.usePinned { pinned -> fwrite(pinned.addressOf(0), 1uL, read.toULong(), file) }
                check(written == read.toULong()) { "Couldn't write the clip to $path" }
            }
        } finally {
            fclose(file)
        }
    }

    /** The front-most presented view controller, so the share sheet shows over whatever's actually on screen. */
    private fun topViewController(
        base: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController,
    ): UIViewController? = when (base) {
        is UINavigationController -> topViewController(base.visibleViewController)
        is UITabBarController -> topViewController(base.selectedViewController)
        else -> base?.presentedViewController?.let { topViewController(it) } ?: base
    }
}

/** How much of the clip is held in memory at once on its way to the file. */
private const val DOWNLOAD_CHUNK_BYTES = 256 * 1024

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    IosClipDownloader(httpClient)
