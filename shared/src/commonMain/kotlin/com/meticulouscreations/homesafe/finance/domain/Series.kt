package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable

/**
 * A time series: [times] in epoch seconds, ascending, and the [values] at them. Parallel primitive
 * arrays rather than a list of points, because the daily economic series run to thousands of
 * points and the charts walk them every frame while a finger scrubs.
 *
 * Never mutated after construction (hence [Immutable], which the arrays alone wouldn't earn).
 */
@Immutable
class Series(val times: LongArray, val values: DoubleArray) {
    init {
        require(times.size == values.size) { "times and values differ in length" }
    }

    val size: Int get() = values.size
    val isEmpty: Boolean get() = values.isEmpty()

    val lastValue: Double? get() = values.lastOrNull()
    val lastTime: Long? get() = times.lastOrNull()
    val firstValue: Double? get() = values.firstOrNull()

    fun min(): Double = values.minOrNull() ?: 0.0
    fun max(): Double = values.maxOrNull() ?: 0.0

    /** The points at or after [epochSeconds]. */
    fun since(epochSeconds: Long): Series {
        val from = times.indexOfFirst { it >= epochSeconds }.let { if (it < 0) size else it }
        if (from == 0) return this
        return Series(times.copyOfRange(from, size), values.copyOfRange(from, size))
    }

    /** The last value at or before [epochSeconds], or null if the series starts after it. */
    fun valueAtOrBefore(epochSeconds: Long): Double? = indexAtOrBefore(epochSeconds).takeIf { it >= 0 }?.let { values[it] }

    /** The index of the last point at or before [epochSeconds], or -1 if the series starts after it. */
    fun indexAtOrBefore(epochSeconds: Long): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= epochSeconds) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /**
     * Each point's percentage change from a year before it, which is how inflation is quoted from
     * a price index. A point is dropped when there's no reading within a few days of a year back
     * — FRED leaves a month blank now and then (October 2025's CPI, after the shutdown), and
     * bridging to the month before would quote a 13-month change as a year's.
     */
    fun yearOverYearPercent(): Series {
        val t = ArrayList<Long>(size)
        val v = ArrayList<Double>(size)
        for (i in 0 until size) {
            // A year back, with a few days' grace either side: a leap year moves a monthly
            // series' same-month reading by a day.
            val target = times[i] - YEAR_SECONDS
            val k = indexAtOrBefore(target + 3 * DAY_SECONDS)
            if (k < 0 || times[k] < target - 3 * DAY_SECONDS) continue
            val prior = values[k]
            if (prior == 0.0) continue
            t += times[i]
            v += (values[i] / prior - 1.0) * 100.0
        }
        return Series(t.toLongArray(), v.toDoubleArray())
    }

    /** Pointwise [op] of this and [other], at this series' times, using [other]'s reading at or before each. */
    fun combine(other: Series, op: (Double, Double) -> Double?): Series {
        val t = ArrayList<Long>(size)
        val v = ArrayList<Double>(size)
        for (i in 0 until size) {
            val o = other.valueAtOrBefore(times[i]) ?: continue
            val r = op(values[i], o) ?: continue
            t += times[i]
            v += r
        }
        return Series(t.toLongArray(), v.toDoubleArray())
    }

    /** The series thinned to at most [maxPoints], keeping each bucket's last point (and the series' first), for drawing. */
    fun downsample(maxPoints: Int): Series {
        if (size <= maxPoints || maxPoints < 2) return this
        val step = (size - 1).toDouble() / (maxPoints - 1)
        val t = LongArray(maxPoints)
        val v = DoubleArray(maxPoints)
        for (i in 0 until maxPoints) {
            val idx = if (i == maxPoints - 1) size - 1 else (i * step).toInt()
            t[i] = times[idx]
            v[i] = values[idx]
        }
        return Series(t, v)
    }

    override fun equals(other: Any?): Boolean =
        other is Series && times.contentEquals(other.times) && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * times.contentHashCode() + values.contentHashCode()

    override fun toString(): String = "Series(size=$size, last=$lastValue)"

    companion object {
        val Empty = Series(LongArray(0), DoubleArray(0))
        const val DAY_SECONDS = 86_400L
        const val YEAR_SECONDS = 365L * DAY_SECONDS

        fun of(points: List<Pair<Long, Double>>): Series =
            Series(LongArray(points.size) { points[it].first }, DoubleArray(points.size) { points[it].second })
    }
}
