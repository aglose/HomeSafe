package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import kotlinx.coroutines.delay

/**
 * The second and third stages of a notification (see [AlertNotification]): re-posts [text] with
 * its [thumbnail], then with its [previewGif]. Shared by the in-app alerts
 * ([DetectionAlertService]) and pushes (Android's messaging service), which differ only in where
 * the bytes come from.
 *
 * Frigate cuts a preview from the detection's first 20 s and serves whatever frames exist when
 * asked, so it is asked for once [windowSeconds] past [startEpochSeconds] have gone by (at once,
 * for an old detection), and again after each of [retryDelaysMs] while it isn't there.
 */
internal suspend fun addAlertMedia(
    text: AlertNotification,
    startEpochSeconds: Double,
    clock: () -> Double,
    thumbnail: suspend () -> Result<ByteArray>,
    previewGif: suspend () -> Result<ByteArray>,
    post: suspend (AlertNotification) -> Unit,
    windowSeconds: Double = PREVIEW_WINDOW_SECONDS,
    retryDelaysMs: List<Long> = PREVIEW_RETRY_DELAYS_MS,
) {
    val withPicture = thumbnail().getOrNull()
        ?.let { text.copy(thumbnail = it) }
        ?.also { post(it) }
        ?: text
    val untilComplete = (startEpochSeconds + windowSeconds - clock()).coerceIn(0.0, windowSeconds)
    delay((untilComplete * 1000).toLong())
    for (wait in listOf(0L) + retryDelaysMs) {
        delay(wait)
        val gif = previewGif().getOrNull() ?: continue
        post(withPicture.copy(animation = gif))
        return
    }
}

/** Frigate's 20 s preview window, plus a little for its last frames to land. */
internal const val PREVIEW_WINDOW_SECONDS = 22.0

/** A preview that 404s at first (no frames flushed yet) usually appears within the minute. */
internal val PREVIEW_RETRY_DELAYS_MS = listOf(10_000L, 20_000L, 30_000L)
