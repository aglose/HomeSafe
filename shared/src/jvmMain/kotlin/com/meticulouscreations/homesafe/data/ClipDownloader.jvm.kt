package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.platform.ClipDownloader

import com.meticulouscreations.homesafe.PlatformContext
import io.ktor.client.HttpClient

/** "Download to the phone" doesn't apply on desktop; matches the precedent set by [createBiometricCredentialStore]'s desktop fallback. */
private class UnavailableClipDownloader : ClipDownloader {
    override suspend fun download(url: String, headers: Map<String, String>, fileName: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Downloading clips isn't available on desktop"))
}

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    UnavailableClipDownloader()
