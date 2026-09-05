package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import io.ktor.client.HttpClient

/** Builds the platform's [ClipDownloader]. [httpClient] is the app's shared, cookie-authenticated Ktor client. */
expect fun createClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader
