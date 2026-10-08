package com.meticulouscreations.homesafe.weather.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** Every pixel is opaque or clear, so premultiplied and not are the same bytes: blue, green, red, alpha. */
internal actual fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val argb = pixels[i]
        bytes[i * 4] = argb.toByte()
        bytes[i * 4 + 1] = (argb shr 8).toByte()
        bytes[i * 4 + 2] = (argb shr 16).toByte()
        bytes[i * 4 + 3] = (argb ushr 24).toByte()
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
