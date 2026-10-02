package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.ClipDownloadProgress
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.download_unavailable_platform
import io.ktor.client.HttpClient

/** "Download to the phone" doesn't apply in a browser tab; matches the precedent set by [createBiometricCredentialStore]'s web fallback. */
private class UnavailableClipDownloader : ClipDownloader {
    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        onProgress: (ClipDownloadProgress) -> Unit,
    ): Result<Unit> =
        Result.failure(LocalizedException(UiText.of(Res.string.download_unavailable_platform), technical = "Downloading clips isn't available on this platform"))
}

actual fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
    UnavailableClipDownloader()
