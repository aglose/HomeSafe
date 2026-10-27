package com.meticulouscreations.homesafe.ui.assistant

import androidx.compose.ui.graphics.ImageBitmap

/**
 * [bitmap] as a JPEG no larger than [maxSide] px on its longer side, for sending Claude a look at the
 * screen; null if it can't be encoded. Big enough to read the screen's text, small enough to send
 * quickly and cost few tokens. Slow enough to keep off the main thread.
 */
internal expect fun encodeScreenJpeg(bitmap: ImageBitmap, maxSide: Int = SCREEN_PICTURE_MAX_SIDE): ByteArray?

internal const val SCREEN_PICTURE_MAX_SIDE = 1280
internal const val SCREEN_PICTURE_QUALITY = 80
