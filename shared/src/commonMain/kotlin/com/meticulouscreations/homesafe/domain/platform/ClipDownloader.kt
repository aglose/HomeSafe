package com.meticulouscreations.homesafe.domain.platform

/**
 * Saves a clip to the device so it's available outside the app: into Downloads on Android (through
 * MediaStore) and on a Mac, and on iOS, which has no shared Downloads folder an app can write
 * into, to the share sheet (Save Video / Save to Files). Each fetches the clip itself with the
 * app's own HTTP client.
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
