package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import io.ktor.client.HttpClient

/**
 * Saves a detection's clip to the device so it's available outside the app. Neither mobile
 * platform lets a third-party app just write into a shared "Downloads" folder the same way:
 * Android's [android.app.DownloadManager] can, but iOS has no public equivalent, so the iOS
 * implementation fetches the clip itself and hands it to the share sheet (Save Video / Save to
 * Files) instead.
 */
interface ClipDownloader {
    /** Fetches [url] (sending [headers], e.g. the session cookie) and saves it on-device as [fileName]. */
    suspend fun download(url: String, headers: Map<String, String>, fileName: String): Result<Unit>
}

/** Builds the platform's [ClipDownloader]. [httpClient] is the app's shared, cookie-authenticated Ktor client. */
expect fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader
