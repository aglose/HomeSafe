package com.meticulouscreations.homesafe.atlas.domain

import kotlin.math.roundToLong

/**
 * Google's encoded polyline, which is how the relay packs a path (`polyline` in `relay/relay.py`):
 * each number as its difference from the one before, in five-bit pieces written as printable
 * characters. Positions are to five decimal places, about a metre.
 */
object Polyline {

    /** Latitude, longitude, latitude, longitude… in degrees. Stops at the first thing that isn't part of one. */
    fun decode(encoded: String): DoubleArray {
        val steps = steps(encoded)
        val out = DoubleArray(steps.size - steps.size % 2)
        var latitude = 0
        var longitude = 0
        for (i in 0 until out.size / 2) {
            latitude += steps[2 * i]
            longitude += steps[2 * i + 1]
            out[2 * i] = latitude / 1e5
            out[2 * i + 1] = longitude / 1e5
        }
        return out
    }

    /** Whole numbers packed the same way: each was written as its difference from the last, and comes back as itself. */
    fun decodeNumbers(encoded: String): IntArray {
        val out = steps(encoded)
        for (i in 1 until out.size) out[i] += out[i - 1]
        return out
    }

    /** The differences as they were written, one per number. */
    private fun steps(encoded: String): IntArray {
        val out = IntArray(encoded.length)
        var count = 0
        var index = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var piece: Int
            do {
                piece = encoded[index++].code - 63
                if (piece < 0 || shift > 30) return out.copyOf(count)
                result = result or ((piece and 0x1F) shl shift)
                shift += 5
            } while (piece >= 0x20 && index < encoded.length)
            // A number cut off mid-way isn't one.
            if (piece >= 0x20) break
            out[count++] = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }
        return out.copyOf(count)
    }

    /** The other way, for tests and fixtures: [degrees] as latitude, longitude, latitude, longitude… */
    fun encode(degrees: DoubleArray): String = buildString {
        var latitude = 0L
        var longitude = 0L
        for (i in 0 until degrees.size / 2) {
            val a = (degrees[2 * i] * 1e5).roundToLong()
            val b = (degrees[2 * i + 1] * 1e5).roundToLong()
            appendNumber(a - latitude)
            appendNumber(b - longitude)
            latitude = a
            longitude = b
        }
    }

    fun encodeNumbers(numbers: IntArray): String = buildString {
        var last = 0L
        numbers.forEach {
            appendNumber(it - last)
            last = it.toLong()
        }
    }

    private fun StringBuilder.appendNumber(number: Long) {
        var value = if (number < 0) (number shl 1).inv() else number shl 1
        while (value >= 0x20) {
            append(((0x20L or (value and 0x1F)) + 63).toInt().toChar())
            value = value shr 5
        }
        append((value + 63).toInt().toChar())
    }
}
