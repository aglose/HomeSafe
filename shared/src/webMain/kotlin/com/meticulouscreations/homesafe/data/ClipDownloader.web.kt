package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import io.ktor.client.HttpClient

/** "Download to the phone" doesn't apply in a browser tab; matches the precedent set by [createBiometricCredentialStore]'s web fallback. */
private class UnavailableClipDownloader : ClipDownloader {
    override suspend fun download(url: String, headers: Map<String, String>, fileName: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Downloading clips isn't available on this platform"))
}

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    UnavailableClipDownloader()
