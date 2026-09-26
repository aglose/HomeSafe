package com.meticulouscreations.homesafe.domain.platform

/**
 * Saves a detection's clip to the device so it's available outside the app. Neither mobile
 * platform lets a third-party app just write into a shared "Downloads" folder the same way:
 * Android's `DownloadManager` can, but iOS has no public equivalent, so the iOS implementation
 * fetches the clip itself and hands it to the share sheet (Save Video / Save to Files) instead.
 *
 * A domain-level port; each platform supplies the implementation from the data layer (see `createClipDownloader`).
 */
interface ClipDownloader {
    /**
     * Fetches [url] (sending [headers], e.g. the session cookie) and saves it on-device as
     * [fileName], telling [onProgress] how far it has got along the way.
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        onProgress: (ClipDownloadProgress) -> Unit = {},
    ): Result<Unit>
}

/** How far a clip's download has got. */
sealed interface ClipDownloadProgress {
    /**
     * Asked for, and no bytes back yet. Frigate cuts a recording clip with ffmpeg before it sends
     * the first byte, so a long clip can sit here for a while before anything arrives.
     */
    data object Preparing : ClipDownloadProgress

    /** Bytes are arriving: [fraction] of the file is here, or null when the server didn't say how big it is. */
    data class Downloading(val fraction: Float?) : ClipDownloadProgress
}
