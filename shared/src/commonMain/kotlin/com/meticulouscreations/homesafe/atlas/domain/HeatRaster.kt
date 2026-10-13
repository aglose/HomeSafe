package com.meticulouscreations.homesafe.atlas.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The map's glow, worked out a square at a time and away from the screen: a picture of how often
 * each spot was gone through, with the soft light round it already in.
 *
 * The map used to draw this itself, on every frame: ten thousand dots or some thousands of lines,
 * each its own piece of work for the graphics chip, under a shader that looked thirteen times
 * round every pixel for the bloom. Here the same picture is made once for a square of the map and
 * kept, so a frame only has to put a couple of dozen pictures down.
 *
 * A square is [size] pixels a side. It is drawn into a field with [apron] more pixels on every
 * side, so that a dot or a line just outside still lights its edge and the bloom has something
 * to look at there. Not safe to share between threads: each caller has its own.
 */
class HeatRaster(val size: Int, val apron: Int) {
    private val side = size + 2 * apron

    /** How often, 0–255, row by row, apron and all. */
    private val field = ByteArray(side * side)

    /** The flights over it, as how much of each pixel an arc covers: drawn apart, since they are not part of the glow and take no bloom. */
    private val air = ByteArray(side * side)

    // The part of the field anything was drawn in, so the rest is neither bloomed nor cleared.
    private var dirtyLeft = side
    private var dirtyTop = side
    private var dirtyRight = -1
    private var dirtyBottom = -1

    /** Whether nothing has been drawn since [clear]. */
    val isEmpty: Boolean get() = dirtyRight < 0

    fun clear() {
        if (!isEmpty) {
            for (y in dirtyTop..dirtyBottom) {
                field.fill(0, y * side + dirtyLeft, y * side + dirtyRight + 1)
                air.fill(0, y * side + dirtyLeft, y * side + dirtyRight + 1)
            }
        }
        dirtyLeft = side
        dirtyTop = side
        dirtyRight = -1
        dirtyBottom = -1
    }

    private fun touched(left: Int, top: Int, right: Int, bottom: Int) {
        if (left < dirtyLeft) dirtyLeft = left
        if (top < dirtyTop) dirtyTop = top
        if (right > dirtyRight) dirtyRight = right
        if (bottom > dirtyBottom) dirtyBottom = bottom
    }

    /**
     * The counted squares of [level] as dots of [radius] pixels, each as bright as its square was
     * often crossed, for the years in [years]. Where dots overlap the brighter one shows, not their
     * sum: squares side by side along a road are one road, and each already says how often it was
     * driven. The square drawn has its top-left corner at ([originX], [originY]) in the map's 0–1
     * world, at [scale] pixels to the whole world.
     */
    fun dots(level: DensityLevel, years: Int, originX: Double, originY: Double, scale: Double, radius: Float) {
        val reach = (apron + radius + 1) / scale
        val span = size / scale
        val outer = radius + 0.5f
        val inner = max(0f, radius - 0.5f)
        level.forEachIn(originX - reach, originY - reach, originX + span + reach, originY + span + reach) { i ->
            if (level.years[i] and years != 0) {
                val cx = ((level.x[i] - originX) * scale).toFloat() + apron
                val cy = ((level.y[i] - originY) * scale).toFloat() + apron
                val left = max(0, floor(cx - outer).toInt())
                val right = min(side - 1, ceil(cx + outer).toInt())
                val top = max(0, floor(cy - outer).toInt())
                val bottom = min(side - 1, ceil(cy + outer).toInt())
                if (left <= right && top <= bottom) {
                    // Brightness that climbs fast at first and then levels: once and twice differ, a thousand and two thousand don't.
                    val bright = 1f - exp(-1.6f * level.weight[i] / level.typicalWeight)
                    val strength = (FAINTEST + (1f - FAINTEST) * bright) * 255f
                    for (y in top..bottom) {
                        val dy = y + 0.5f - cy
                        val row = y * side
                        for (x in left..right) {
                            val dx = x + 0.5f - cx
                            val d2 = dx * dx + dy * dy
                            if (d2 < outer * outer) {
                                val cover = if (d2 <= inner * inner) 1f else outer - sqrt(d2)
                                val value = (strength * cover).toInt()
                                if (value > (field[row + x].toInt() and 0xFF)) field[row + x] = value.toByte()
                            }
                        }
                    }
                    touched(left, top, right, bottom)
                }
            }
        }
    }

    /**
     * The paths themselves, each stretch a line [halfWidth] pixels either side of its middle, for
     * the tracks whose bit in [trackYears] is among [years]. Here each line is one journey, and
     * journeys along the same road do add up, [JOURNEY] at a time until the road is as bright as
     * it gets.
     */
    fun lines(tracks: List<Track>, trackYears: IntArray, years: Int, originX: Double, originY: Double, scale: Double, halfWidth: Float) {
        val reach = (apron + halfWidth + 1) / scale
        val west = originX - reach
        val east = originX + size / scale + reach
        val north = originY - reach
        val south = originY + size / scale + reach
        for (index in tracks.indices) {
            val track = tracks[index]
            if (track.maxX < west || track.minX > east || track.maxY < north || track.minY > south || trackYears[index] and years == 0) continue
            // Worked out wide and only then narrowed: close in, a path that began far away is millions of pixels off.
            val shiftX = track.originX - originX
            val shiftY = track.originY - originY
            val xy = track.xy
            var previousX = 0f
            var previousY = 0f
            for (i in 0 until track.size) {
                val x = ((xy[2 * i] + shiftX) * scale).toFloat() + apron
                val y = ((xy[2 * i + 1] + shiftY) * scale).toFloat() + apron
                // A gap of a hundred and fifty kilometres between fixes is a flight or a hole in the record, not a road.
                if (i > 0 && abs(xy[2 * i] - xy[2 * i - 2]) < JUMP && abs(xy[2 * i + 1] - xy[2 * i - 1]) < JUMP) {
                    line(field, previousX, previousY, x, y, halfWidth, JOURNEY, summed = true)
                }
                previousX = x
                previousY = y
            }
        }
    }

    /**
     * The flights, each an arc between its two ends ([ends]: from x, from y, to x, to y in the
     * map's world, flight after flight), bowed toward the pole as a great circle is on this
     * projection: a fine line [core] pixels either side of its middle, on a faint band [wide]
     * either side. An arc shorter than [least] pixels is left out, as a hop too small to read.
     */
    fun arcs(ends: DoubleArray, originX: Double, originY: Double, scale: Double, core: Float, wide: Float, least: Float) {
        val limit = side + wide + 1
        for (i in 0 until ends.size / 4) {
            // The short way round the world, and again a world over, for the one that crosses the date line.
            var toX = ends[4 * i + 2]
            if (toX - ends[4 * i] > 0.5) toX -= 1.0 else if (toX - ends[4 * i] < -0.5) toX += 1.0
            for (turn in 0..1) {
                val shift = if (turn == 0) 0.0 else if (toX < 0.0) 1.0 else if (toX > 1.0) -1.0 else break
                val ax = ((ends[4 * i] + shift - originX) * scale).toFloat() + apron
                val ay = ((ends[4 * i + 1] - originY) * scale).toFloat() + apron
                val bx = ((toX + shift - originX) * scale).toFloat() + apron
                val by = ((ends[4 * i + 3] - originY) * scale).toFloat() + apron
                val span = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                if (span < least) continue
                val cx = (ax + bx) / 2f
                val cy = (ay + by) / 2f - span * BOW
                // Wholly off one side of the field: the arc lies within its two ends and the point it bows toward.
                if (max(ax, bx) < -wide - 1 || min(ax, bx) > limit || max(ay, by) < -wide - 1 || min(min(ay, by), cy) > limit) continue
                val pieces = (span / 10f).toInt().coerceIn(6, 64)
                var fromX = ax
                var fromY = ay
                for (piece in 1..pieces) {
                    val t = piece.toFloat() / pieces
                    val u = 1f - t
                    val x = u * u * ax + 2 * u * t * cx + t * t * bx
                    val y = u * u * ay + 2 * u * t * cy + t * t * by
                    line(air, fromX, fromY, x, y, wide, BAND, summed = false)
                    line(air, fromX, fromY, x, y, core, 255f, summed = false)
                    fromX = x
                    fromY = y
                }
            }
        }
    }

    /**
     * One stretch from ([ax], [ay]) to ([bx], [by]) in the field's pixels, with round ends, as
     * strong as [strength] where it covers a pixel whole. [summed], it adds to what is there;
     * otherwise the stronger of the two stays.
     */
    private fun line(into: ByteArray, ax: Float, ay: Float, bx: Float, by: Float, halfWidth: Float, strength: Float, summed: Boolean) {
        val outer = halfWidth + 0.5f
        val limit = side - 1 + outer
        // Wholly off one side of the field.
        if ((ax < -outer && bx < -outer) || (ay < -outer && by < -outer) || (ax > limit && bx > limit) || (ay > limit && by > limit)) return
        val dx = bx - ax
        val dy = by - ay
        val length2 = dx * dx + dy * dy
        if (length2 < 1e-6f) return
        // Walked along whichever way it runs furthest, a column (or a row) at a time; [u] is along
        // that way and [v] across it, so one loop does for both.
        val steep = abs(dy) > abs(dx)
        var u0 = if (steep) ay else ax
        var v0 = if (steep) ax else ay
        var u1 = if (steep) by else bx
        var v1 = if (steep) bx else by
        if (u0 > u1) {
            val u = u0
            u0 = u1
            u1 = u
            val v = v0
            v0 = v1
            v1 = v
        }
        val du = u1 - u0
        val dv = v1 - v0
        val slope = dv / du
        // Across the line a pixel is this share of its offset along [v] away from the middle.
        val across = du / sqrt(length2)
        val reach = outer / across
        val from = max(0, floor(u0 - outer).toInt())
        val to = min(side - 1, ceil(u1 + outer).toInt())
        if (from > to) return
        var low = side
        var high = -1
        for (u in from..to) {
            val uc = u + 0.5f
            val along = uc.coerceIn(u0, u1)
            val middle = v0 + (along - u0) * slope
            val first = max(0, floor(middle - reach).toInt())
            val last = min(side - 1, ceil(middle + reach).toInt())
            if (first > last) continue
            if (first < low) low = first
            if (last > high) high = last
            // Past either end the nearest point is the end itself: the round of the cap.
            val capped = uc < u0 || uc > u1
            val offU = uc - along
            for (v in first..last) {
                val offV = v + 0.5f - middle
                val distance = if (capped) sqrt(offU * offU + offV * offV) else abs(offV) * across
                if (distance < outer) {
                    val at = if (steep) u * side + v else v * side + u
                    val was = into[at].toInt() and 0xFF
                    if (was < 255) {
                        val drawn = (strength * min(1f, outer - distance)).toInt()
                        val now = if (summed) min(255, was + drawn) else max(was, drawn)
                        if (now != was) into[at] = now.toByte()
                    }
                }
            }
        }
        if (high >= 0) if (steep) touched(low, from, high, to) else touched(from, low, to, high)
    }

    // The bloom's two rings, as the six places round a pixel to look for each.
    private var ringSpread = -1f
    private val nearAt = IntArray(6)
    private val farAt = IntArray(6)

    private fun rings(spread: Float) {
        if (spread == ringSpread) return
        ringSpread = spread
        check(ceil(spread * FAR_RING).toInt() + 1 <= apron) { "A bloom of $spread pixels reaches past an apron of $apron" }
        for (i in 0 until 6) {
            val angle = i * 1.0472f
            // The pixel each look lands in: the rings are a few pixels across, and the light is soft enough not to show the rounding.
            nearAt[i] = (sin(angle) * spread * NEAR_RING).roundToInt() * side + (cos(angle) * spread * NEAR_RING).roundToInt()
            farAt[i] = (-cos(angle) * spread * FAR_RING).roundToInt() * side + (sin(angle) * spread * FAR_RING).roundToInt()
        }
    }

    /** The picture last made: kept and written over, since whoever takes one copies it into an image at once. */
    private val pixels = IntArray(size * size)

    /**
     * What was drawn, with its light: [size] by [size] pixels, row by row, each opaque with the
     * line itself in its red (the spot and the ring just round it, so a row of dots reads as one
     * even line), the halo in its green (the wide ring, which is what makes a faint,
     * once-driven road visible at all) and the flights in its blue. [spread] is the bloom's
     * reach in pixels. Null when there is nothing in the square at all, which is most of the
     * Earth. The array is this raster's own and is written over by the next picture: copy what
     * is to be kept.
     */
    fun picture(spread: Float): IntArray? {
        if (isEmpty) return null
        rings(spread)
        val reach = ceil(spread * FAR_RING).toInt() + 1
        val left = max(apron, dirtyLeft - reach)
        val right = min(apron + size - 1, dirtyRight + reach)
        val top = max(apron, dirtyTop - reach)
        val bottom = min(apron + size - 1, dirtyBottom + reach)
        if (left > right || top > bottom) return null
        pixels.fill(OPAQUE)
        // Whole numbers throughout: sixty-five thousand pixels a square, and a dozen looks round each.
        val n0 = nearAt[0]
        val n1 = nearAt[1]
        val n2 = nearAt[2]
        val n3 = nearAt[3]
        val n4 = nearAt[4]
        val n5 = nearAt[5]
        val f0 = farAt[0]
        val f1 = farAt[1]
        val f2 = farAt[2]
        val f3 = farAt[3]
        val f4 = farAt[4]
        val f5 = farAt[5]
        for (y in top..bottom) {
            val out = (y - apron) * size - apron
            for (x in left..right) {
                val at = y * side + x
                val near = (field[at + n0].toInt() and 0xFF) + (field[at + n1].toInt() and 0xFF) + (field[at + n2].toInt() and 0xFF) +
                    (field[at + n3].toInt() and 0xFF) + (field[at + n4].toInt() and 0xFF) + (field[at + n5].toInt() and 0xFF)
                val far = (field[at + f0].toInt() and 0xFF) + (field[at + f1].toInt() and 0xFF) + (field[at + f2].toInt() and 0xFF) +
                    (field[at + f3].toInt() and 0xFF) + (field[at + f4].toInt() and 0xFF) + (field[at + f5].toInt() and 0xFF)
                val flight = air[at].toInt() and 0xFF
                if (near or far or flight != 0 || field[at].toInt() != 0) {
                    // The spot at four tenths and the near ring at six; the far ring at six tenths and the near at three. Each ring is six looks.
                    val body = min(255, ((field[at].toInt() and 0xFF) * 4 + near) / 10)
                    val halo = min(255, (far * 2 + near) / 20)
                    pixels[out + x] = OPAQUE or (body shl 16) or (halo shl 8) or flight
                }
            }
        }
        return pixels
    }

    companion object {
        /** How bright a square crossed once is drawn, against 1 for the most crossed. */
        const val FAINTEST = 0.16f

        /** What one journey along a line adds, of 255: five of them and the road is as bright as it gets. */
        const val JOURNEY = 51f

        /** The bloom's rings, as shares of its spread: a tight one and a wide one. */
        const val NEAR_RING = 0.5f
        const val FAR_RING = 1.35f

        /** How far a flight's arc bows, as a share of the distance between its ends. */
        const val BOW = 0.18f

        /** How strong the faint band along a flight is drawn, of 255, against its fine line. */
        const val BAND = 58f

        /** Two fixes further apart than this are not joined. About 150 km at the equator. */
        private const val JUMP = (150.0 / 40_075.0).toFloat()

        private const val OPAQUE = 0xFF shl 24
    }
}
