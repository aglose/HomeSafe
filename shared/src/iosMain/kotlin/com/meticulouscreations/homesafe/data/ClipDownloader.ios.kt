package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.platform.ClipDownloader

import com.meticulouscreations.homesafe.PlatformContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
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
 */
@OptIn(ExperimentalForeignApi::class)
private class IosClipDownloader(private val httpClient: HttpClient) : ClipDownloader {

    override suspend fun download(url: String, headers: Map<String, String>, fileName: String): Result<Unit> = runCatching {
        val bytes: ByteArray = httpClient.get(url) {
            headers.forEach { (key, value) -> header(key, value) }
        }.body()

        val path = NSTemporaryDirectory() + fileName
        writeToFile(path, bytes)

        withContext(Dispatchers.Main) {
            val activityController = UIActivityViewController(
                activityItems = listOf(NSURL.fileURLWithPath(path)),
                applicationActivities = null,
            )
            val presenter = requireNotNull(topViewController()) { "No screen to show the share sheet from" }
            presenter.presentViewController(activityController, animated = true, completion = null)
        }
    }

    /** Plain POSIX file I/O rather than NSData, so this doesn't depend on Foundation's byte-buffer factory overloads. */
    private fun writeToFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("Couldn't open $path for writing")
        try {
            bytes.usePinned { pinned ->
                if (bytes.isNotEmpty()) fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
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

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    IosClipDownloader(httpClient)
