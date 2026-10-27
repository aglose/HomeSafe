package com.meticulouscreations.homesafe.ui.assistant

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

internal actual fun encodeScreenJpeg(bitmap: ImageBitmap, maxSide: Int): ByteArray? = runCatching {
    val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
    val scale = (maxSide.toFloat() / max(image.width, image.height)).coerceAtMost(1f)
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
    surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, SCREEN_PICTURE_QUALITY)?.bytes
}.getOrNull()
