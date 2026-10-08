package com.meticulouscreations.homesafe.weather.data

import androidx.compose.ui.graphics.ImageBitmap
import com.meticulouscreations.homesafe.weather.domain.RadarSource

/** A picture made from [pixels]: [width] by [height] ARGB values, each either fully opaque or fully clear. */
internal expect fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap

/**
 * Turns a radar tile back into what it measures. A tile arrives as a picture in its provider's
 * colours, and three providers' frames share one loop (the MRMS mosaic, the HRRR forecast,
 * RainViewer), each in a table of its own. Looking each colour up in the table it came from
 * gives the reflectivity, which is rewritten here as a grey level: black-to-white for
 * [MIN_DBZ] to [MAX_DBZ], and clear where there's no echo.
 *
 * The radar shader then draws that one way whatever the source, and gets something it can
 * smooth and blend: two colours from a table average to a third colour that means nothing,
 * but two reflectivities average to the reflectivity between them.
 */
internal object RadarDecoder {
    /** Below this it is drizzle too light to matter, ground clutter, or insects. */
    const val MIN_DBZ = 8f

    /** Hail. Nothing in weather reads higher. */
    const val MAX_DBZ = 75f

    /** How strong an echo [dbz] is, 0–1 on the scale the shader's colours are laid along. */
    fun level(dbz: Float): Float = ((dbz - MIN_DBZ) / (MAX_DBZ - MIN_DBZ)).coerceIn(0f, 1f)

    fun dbz(level: Float): Float = MIN_DBZ + level * (MAX_DBZ - MIN_DBZ)

    fun tableFor(source: RadarSource, forecast: Boolean): RadarColorTable = when {
        source == RadarSource.RAINVIEWER -> RadarPalettes.rainViewer
        forecast -> RadarPalettes.n0q
        else -> RadarPalettes.lcref
    }

    /** [tile] with every colour replaced by its strength as a grey, read against [table]. */
    fun decode(tile: ImageBitmap, table: RadarColorTable): ImageBitmap {
        val width = tile.width
        val height = tile.height
        val pixels = IntArray(width * height)
        tile.readPixels(pixels)
        return imageBitmapOf(decode(pixels, table), width, height)
    }

    /** The same on bare ARGB values, in place. */
    fun decode(pixels: IntArray, table: RadarColorTable): IntArray {
        val known = table.greys
        // A tile has a few dozen colours at most; anything not in the table (a provider that
        // changed a shade, a smoothed edge) is matched once to its nearest entry and remembered.
        val guessed = HashMap<Int, Int>()
        for (i in pixels.indices) {
            val argb = pixels[i]
            if (argb ushr 24 < 0x40) {
                pixels[i] = 0
                continue
            }
            val rgb = argb and 0xFFFFFF
            // Black is every table's "nothing here", whether or not the tile troubled to make it clear.
            if (rgb == 0) {
                pixels[i] = 0
                continue
            }
            val grey = known[rgb] ?: guessed.getOrPut(rgb) { greyOf(nearest(rgb, table), table) }
            pixels[i] = if (grey == 0) 0 else (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
        return pixels
    }

    /** Colour to grey level (0 meaning no echo) for every entry of [table]: see [RadarColorTable.greys]. */
    internal fun lookup(table: RadarColorTable): Map<Int, Int> {
        val map = HashMap<Int, Int>(table.colors.size * 2)
        // A colour used for a run of values stands for the lowest of them.
        for (i in table.colors.indices) {
            val rgb = table.colors[i]
            if (rgb != 0 && rgb !in map) map[rgb] = greyOf(i, table)
        }
        return map
    }

    private fun greyOf(index: Int, table: RadarColorTable): Int {
        if (index < 0) return 0
        val dbz = table.dbz(index)
        if (dbz < MIN_DBZ) return 0
        // Never 0 for a real echo: 0 is "nothing here".
        return (level(dbz) * 254f).toInt() + 1
    }

    private fun nearest(rgb: Int, table: RadarColorTable): Int {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (i in table.colors.indices) {
            val c = table.colors[i]
            if (c == 0) continue
            val dr = (c shr 16 and 0xFF) - r
            val dg = (c shr 8 and 0xFF) - g
            val db = (c and 0xFF) - b
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }
}
