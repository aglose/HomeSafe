package com.meticulouscreations.homesafe.atlas.domain

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/**
 * Where the paths go, counted: the world cut into 2^[level] by 2^[level] squares, and for each
 * square a path crosses, how many crossed it ([weight]) and in which years ([years], a bit per
 * year from [AtlasDensity.baseYear]). ([x], [y]) is the square's middle in the map's 0–1 world.
 *
 * The squares are kept in blocks of [BLOCK] by [BLOCK], block by block, so the ones in view are
 * found without looking at the rest ([forEachIn]).
 */
@Immutable
class DensityLevel(
    val level: Int,
    val x: FloatArray,
    val y: FloatArray,
    val weight: FloatArray,
    val years: IntArray,
    private val blocks: LongArray,
    private val blockStart: IntArray,
    /** A weight most squares are under, for setting how bright a square is against the rest. */
    val typicalWeight: Float,
) {
    val size: Int get() = weight.size

    /** Calls [each] with the index of every square whose block touches the box, in the map's 0–1 world. */
    inline fun forEachIn(minX: Double, minY: Double, maxX: Double, maxY: Double, each: (Int) -> Unit) {
        val perSide = 1 shl (level - BLOCK_BITS)
        val fromX = floor(minX * perSide).toInt().coerceIn(0, perSide - 1)
        val toX = floor(maxX * perSide).toInt().coerceIn(0, perSide - 1)
        val fromY = floor(minY * perSide).toInt().coerceIn(0, perSide - 1)
        val toY = floor(maxY * perSide).toInt().coerceIn(0, perSide - 1)
        for (blockY in fromY..toY) {
            for (blockX in fromX..toX) {
                val at = blockAt(blockY.toLong() * perSide + blockX)
                if (at >= 0) for (i in start(at) until start(at + 1)) each(i)
            }
        }
    }

    /** Where [block] is among the blocks that hold anything, or -1. */
    @PublishedApi
    internal fun blockAt(block: Long): Int {
        var low = 0
        var high = blocks.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                blocks[mid] < block -> low = mid + 1
                blocks[mid] > block -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    @PublishedApi
    internal fun start(at: Int): Int = blockStart[at]

    companion object {
        const val BLOCK_BITS = 5
        const val BLOCK = 1 shl BLOCK_BITS
    }
}

/** [DensityLevel]s from coarse to fine, one for each doubling: each is drawn while its squares are a few pixels across. */
@Immutable
class AtlasDensity(val levels: List<DensityLevel>, val baseYear: Int) {

    /**
     * The level to draw when the map is at [zoom] with tiles [tilePx] wide: the coarsest whose
     * squares come out no wider than [maxCellPx], so that dots drawn on them run together into
     * lines. Null when even the finest is too coarse for that, which is when the paths themselves
     * are drawn instead (and when there is nothing counted at all).
     */
    fun levelFor(zoom: Double, tilePx: Float, maxCellPx: Float): DensityLevel? {
        val wanted = ceil(zoom + ln(tilePx / maxCellPx.toDouble()) / ln(2.0)).toInt()
        val finest = levels.lastOrNull() ?: return null
        if (wanted > finest.level) return null
        return levels.firstOrNull { it.level >= wanted }
    }

    /** The bit that stands for [year] in a square's `years`. */
    fun yearBit(year: Int): Int = 1 shl (year - baseYear).coerceIn(0, 31)

    /** Every year up to and including [year]. */
    fun yearsThrough(year: Int): Int {
        val bits = (year - baseYear + 1).coerceIn(0, 32)
        return if (bits >= 32) -1 else (1 shl bits) - 1
    }

    companion object {
        val Empty = AtlasDensity(emptyList(), 1970)

        /** Squares from about eighty kilometres across down to about eighty metres. */
        val LEVELS = IntArray(11) { 9 + it }

        /** Two points further apart than this on the map are not joined: a flight, or a gap in the record. About 150 km at the equator. */
        private const val JUMP = 150.0 / 40_075.0

        /**
         * Counts [tracks] into every level. A path counts once for each square it crosses, however
         * long it dwelt there, so a road driven a thousand times is a thousand and the car park at
         * the end of it isn't a million.
         */
        fun of(tracks: List<Track>): AtlasDensity {
            if (tracks.isEmpty()) return Empty
            val baseYear = tracks.minOf { CivilDate.of(localDay(it.start, it.offsetMinutes)).year }
            val levels = LEVELS.map { level -> level(level, tracks, baseYear) }
            return AtlasDensity(levels.filter { it.size > 0 }, baseYear)
        }

        private class Keys(capacity: Int = 1 shl 14) {
            var values = LongArray(capacity)
            var size = 0

            fun add(value: Long) {
                if (size == values.size) values = values.copyOf(size * 2)
                values[size++] = value
            }
        }

        private fun level(level: Int, tracks: List<Track>, baseYear: Int): DensityLevel {
            val perSide = 1 shl level
            val blocksPerSide = perSide shr DensityLevel.BLOCK_BITS
            val keys = Keys()
            tracks.forEach { track ->
                val year = (CivilDate.of(localDay(track.start, track.offsetMinutes)).year - baseYear).coerceIn(0, 31)
                var lastCell = -1L
                for (i in 0 until track.size) {
                    val x = track.x(i)
                    val y = track.y(i)
                    // The squares between this point and the last, when they are one journey.
                    val steps = if (i == 0) {
                        1
                    } else {
                        val dx = abs(x - track.x(i - 1))
                        val dy = abs(y - track.y(i - 1))
                        if (dx > JUMP || dy > JUMP) 1 else max(1, ceil(max(dx, dy) * perSide * 2).toInt())
                    }
                    for (step in 1..steps) {
                        val t = step.toDouble() / steps
                        val px = if (steps == 1) x else track.x(i - 1) + (x - track.x(i - 1)) * t
                        val py = if (steps == 1) y else track.y(i - 1) + (y - track.y(i - 1)) * t
                        val cellX = floor(px * perSide).toLong().coerceIn(0, perSide - 1L)
                        val cellY = floor(py * perSide).toLong().coerceIn(0, perSide - 1L)
                        val block = (cellY shr DensityLevel.BLOCK_BITS) * blocksPerSide + (cellX shr DensityLevel.BLOCK_BITS)
                        val within = (cellY and (DensityLevel.BLOCK - 1L)) * DensityLevel.BLOCK + (cellX and (DensityLevel.BLOCK - 1L))
                        val cell = block * (DensityLevel.BLOCK * DensityLevel.BLOCK) + within
                        if (cell != lastCell) {
                            keys.add(cell * 32 + year)
                            lastCell = cell
                        }
                    }
                }
            }
            keys.values.sort(0, keys.size)

            // Runs of one square (in whichever years) become one entry.
            var cells = 0
            var previous = -1L
            for (i in 0 until keys.size) {
                val cell = keys.values[i] / 32
                if (cell != previous) cells++
                previous = cell
            }
            val x = FloatArray(cells)
            val y = FloatArray(cells)
            val weight = FloatArray(cells)
            val years = IntArray(cells)
            val blocks = Keys(256)
            val starts = ArrayList<Int>()
            var at = -1
            previous = -1L
            var previousBlock = -1L
            val blockCells = DensityLevel.BLOCK * DensityLevel.BLOCK
            for (i in 0 until keys.size) {
                val key = keys.values[i]
                val cell = key / 32
                if (cell != previous) {
                    at++
                    previous = cell
                    val block = cell / blockCells
                    val within = (cell % blockCells).toInt()
                    if (block != previousBlock) {
                        blocks.add(block)
                        starts += at
                        previousBlock = block
                    }
                    val cellX = (block % blocksPerSide) * DensityLevel.BLOCK + within % DensityLevel.BLOCK
                    val cellY = (block / blocksPerSide) * DensityLevel.BLOCK + within / DensityLevel.BLOCK
                    x[at] = ((cellX + 0.5) / perSide).toFloat()
                    y[at] = ((cellY + 0.5) / perSide).toFloat()
                }
                weight[at] += 1f
                years[at] = years[at] or (1 shl (key % 32).toInt())
            }
            starts += cells
            val typical = if (cells == 0) 1f else weight.copyOf().also { it.sort() }[(cells * 9) / 10]
            return DensityLevel(level, x, y, weight, years, blocks.values.copyOf(blocks.size), starts.toIntArray(), max(1f, typical))
        }
    }
}
