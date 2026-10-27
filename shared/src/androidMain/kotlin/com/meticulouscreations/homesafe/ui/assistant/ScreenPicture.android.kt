package com.meticulouscreations.homesafe.ui.assistant

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal actual fun encodeScreenJpeg(bitmap: ImageBitmap, maxSide: Int): ByteArray? = runCatching {
    // A layer's picture comes back as a hardware bitmap, which has to be copied out to be scaled.
    val source = bitmap.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    val scale = (maxSide.toFloat() / max(source.width, source.height)).coerceAtMost(1f)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt().coerceAtLeast(1), (source.height * scale).roundToInt().coerceAtLeast(1), true)
    } else {
        source
    }
    ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, SCREEN_PICTURE_QUALITY, out)
        out.toByteArray()
    }
}.getOrNull()
