package com.meticulouscreations.homesafe.fitness.domain

import kotlin.math.roundToInt

/**
 * When a run recorded here was really over, for one that was left recording: the runner stopped
 * and never pressed Finish, or pressed it long afterwards. Told from what there is, half a minute
 * ([BIN_MILLIS]) at a time:
 *
 * - **the phone's positions**: ground covered at the run's own pace, or not. The pace asked for
 *   is a jog's (15:00 a mile), or for a run that was a walk all the way, six tenths of its usual
 *   speed; faster than [FASTEST_METERS_PER_SECOND] is a car;
 * - **the heart**, where a band's readings cover it: ground covered at a running pace with the
 *   heart settled (under [HEART_UP] of what it ran at) is a ride home, unless a band says it was
 *   still a run;
 * - **a band's own session** of the same run, read from the health store: with no heart to tell
 *   by, ground covered after the band had stopped (and [WORN_GRACE_SECONDS]) isn't the run.
 *
 * Where the phone had no position at all, the band's session says, or the heart alone; with
 * neither, nothing was being run.
 *
 * The run is then over where its last stretch of running ended, and the runner had slowed to a
 * stop (for [SLOWING_MILLIS] at most: the walk home isn't the run). A stop of under [GAP_MILLIS] is
 * part of a run (a light, a drink); running that comes after a longer one counts only if there
 * were two minutes of it. Only a run that went on [TAIL_MILLIS] or more past that is touched:
 * anyone stands about for a minute before pressing Finish.
 *
 * This only ever shortens a run, and what was recorded past its end is kept: the run is worked
 * out from the events up to its end ([settled]).
 */
object RunEnds {
    const val BIN_MILLIS = 30_000L

    /** A run recorded this much longer than it was run is cut back; less is just the walk to the door. */
    const val TAIL_MILLIS = 5 * 60_000L

    /** Standing still this long ends a stretch of running: what comes after has to be a run again to count. */
    const val GAP_MILLIS = 5 * 60_000L

    /** Running after a long stop counts from this many half minutes of it. */
    private const val LEAST_RESUMED_BINS = 4

    /** A 3:50 mile, held for half a minute: past that the phone is in a car. */
    const val FASTEST_METERS_PER_SECOND = 7.0

    /** Slower than this isn't going anywhere: the GPS wandering, or pottering about. */
    private const val LEAST_METERS_PER_SECOND = 0.5

    /** A run that never reached a jog is judged by this share of its own usual speed. */
    private const val OWN_PACE_SHARE = 0.6

    /** The heart is still up while it is over this share of what it ran at. */
    const val HEART_UP = 0.75

    /** The heart is only gone by with readings through this many half minutes of running. */
    private const val LEAST_HEART_BINS = 6

    /** A band may stop its session a little before the runner does. */
    const val WORN_GRACE_SECONDS = 120L

    /** Coming to a stop after the last of the running is part of the run for this long. */
    const val SLOWING_MILLIS = 2 * 60_000L

    /** A stretch of distance that begins this soon after the last half minute of running is the same running. */
    private const val CONTIGUOUS_MILLIS = 10_000L

    private const val JOG_METERS_PER_SECOND = METERS_PER_MILE / Runs.SLOWEST_RUN_PACE_SECONDS

    /**
     * When the run from [startEpochMillis] to [endEpochMillis] was really over, or null when it
     * ended about when it was ended. [heart] is a band's readings through it and [worn] the
     * sessions (in epoch seconds) a band recorded of the same run; both may be empty.
     */
    fun inferred(startEpochMillis: Long, endEpochMillis: Long, events: List<RunEvent>, heart: List<HealthSample> = emptyList(), worn: List<LongRange> = emptyList()): Long? {
        val length = endEpochMillis - startEpochMillis
        if (length < TAIL_MILLIS) return null
        val within = events.filter { it.epochMillis <= endEpochMillis }
        val tracker = RunTracker.replay(startEpochMillis, within)
        val spans = tracker.distance.sortedBy { it.startEpochSeconds }
        val count = ((length + BIN_MILLIS - 1) / BIN_MILLIS).toInt()

        fun binOf(millis: Long): Int = ((millis - startEpochMillis) / BIN_MILLIS).toInt()

        fun binStart(bin: Int): Long = startEpochMillis + bin * BIN_MILLIS

        fun binEnd(bin: Int): Long = minOf(binStart(bin) + BIN_MILLIS, endEpochMillis)

        // The ground covered in each half minute, a stretch that lies across two shared out by time.
        val meters = DoubleArray(count)
        for (span in spans) {
            val from = span.startEpochSeconds * 1_000
            val to = span.endEpochSeconds * 1_000
            if (to <= from) continue
            for (bin in binOf(from).coerceAtLeast(0)..binOf(to - 1).coerceAtMost(count - 1)) {
                val shared = minOf(to, binEnd(bin)) - maxOf(from, binStart(bin))
                if (shared > 0) meters[bin] += span.meters * shared / (to - from)
            }
        }
        val speeds = DoubleArray(count) { bin -> (binEnd(bin) - binStart(bin)).takeIf { it > 0 }?.let { meters[bin] * 1_000 / it } ?: 0.0 }
        val going = speeds.filter { it >= LEAST_METERS_PER_SECOND && it <= FASTEST_METERS_PER_SECOND }.sorted()
        if (going.isEmpty()) return null
        val least = minOf(JOG_METERS_PER_SECOND, going[going.size / 2] * OWN_PACE_SHARE)

        val located = BooleanArray(count)
        for (event in within) {
            if (event.kind == RunEventKind.FIX && event.accuracyMeters <= RunTracker.WORST_ACCURACY_METERS && event.epochMillis >= startEpochMillis) {
                located[binOf(event.epochMillis).coerceAtMost(count - 1)] = true
            }
        }
        val recorded = tracker.track().pauses
        val pauses = recorded.mapIndexed { index, pause ->
            // One never resumed lasted to the end, whatever the last thing recorded was.
            val open = tracker.paused && index == recorded.lastIndex
            pause.startEpochSeconds * 1_000..if (open) endEpochMillis else pause.endEpochSeconds * 1_000
        }
        val paused = BooleanArray(count) { bin -> pauses.any { (binStart(bin) + binEnd(bin)) / 2 in it } }
        val moving = BooleanArray(count) { bin -> !paused[bin] && speeds[bin] >= least && speeds[bin] <= FASTEST_METERS_PER_SECOND }

        val up = heartUp(startEpochMillis, count, moving, heart)
        val wornFrom = worn.minOfOrNull { it.first }
        val wornTo = worn.maxOfOrNull { it.last }?.plus(WORN_GRACE_SECONDS)

        val running = BooleanArray(count) { bin ->
            val heartUp = up?.get(bin)
            val inWorn = if (wornFrom != null && wornTo != null) binStart(bin) / 1_000 <= wornTo && binEnd(bin) / 1_000 >= wornFrom else null
            when {
                paused[bin] -> false
                moving[bin] -> !(heartUp == false && inWorn != true) && !(heartUp == null && inWorn == false)
                located[bin] -> false
                // No position to tell by: the band's session if there is one, else the heart alone.
                inWorn != null -> inWorn && heartUp != false
                else -> heartUp == true
            }
        }

        // Stretches of running, a stop shorter than the gap inside one: the last bin of each, and how much running was in it.
        val gap = (GAP_MILLIS / BIN_MILLIS).toInt()
        var lastBin = -1
        var stretchBins = 0
        var endBin = -1
        var first = true
        for (bin in 0 until count) {
            if (!running[bin]) continue
            if (lastBin >= 0 && bin - lastBin > gap) {
                if (first || stretchBins >= LEAST_RESUMED_BINS) endBin = lastBin
                first = false
                stretchBins = 0
            }
            stretchBins++
            lastBin = bin
        }
        if (lastBin >= 0 && (first || stretchBins >= LEAST_RESUMED_BINS)) endBin = lastBin
        if (endBin < 0) return null

        // On to where the runner came to a stop: the last strides past the half minute, and slowing to a walk after them.
        // Not into a ride, and not for long: a walk home isn't the run.
        val ranUntil = binEnd(endBin)
        var over = ranUntil
        for (span in spans) {
            val from = span.startEpochSeconds * 1_000
            val to = span.endEpochSeconds * 1_000
            if (to <= over) continue
            val bin = binOf((from + to) / 2).coerceIn(0, count - 1)
            val speed = span.meters * 1_000 / (to - from)
            if (from > over + CONTIGUOUS_MILLIS || speed < LEAST_METERS_PER_SECOND || speed > FASTEST_METERS_PER_SECOND || (moving[bin] && !running[bin])) break
            over = minOf(to, ranUntil + SLOWING_MILLIS)
            if (over < to) break
        }
        over = minOf(over, endEpochMillis)
        return over.takeIf { endEpochMillis - it >= TAIL_MILLIS }
    }

    /**
     * Whether the heart was still up in each half minute, null where no reading fell in it. Null
     * altogether without readings through enough of the running to say what "up" is for this run.
     */
    private fun heartUp(startEpochMillis: Long, count: Int, moving: BooleanArray, heart: List<HealthSample>): Array<Boolean?>? {
        val sums = DoubleArray(count)
        val readings = IntArray(count)
        for (sample in heart) {
            if (sample.value.roundToInt() !in HeartZones.PLAUSIBLE) continue
            val bin = ((sample.epochSeconds * 1_000 - startEpochMillis) / BIN_MILLIS).toInt()
            if (sample.epochSeconds * 1_000 < startEpochMillis || bin >= count) continue
            sums[bin] += sample.value
            readings[bin]++
        }
        val ranAt = (0 until count).filter { moving[it] && readings[it] > 0 }.map { sums[it] / readings[it] }.sorted()
        if (ranAt.size < LEAST_HEART_BINS) return null
        val floor = ranAt[ranAt.size / 2] * HEART_UP
        return Array(count) { bin -> if (readings[bin] > 0) sums[bin] / readings[bin] >= floor else null }
    }

    /** The [events] that are the run's, for one that ended at [endEpochMillis]: what was recorded after it is kept, and passed over. */
    fun upTo(endEpochMillis: Long, events: List<RunEvent>): List<RunEvent> = events.filter { it.epochMillis <= endEpochMillis }

    /** [run] ended where it was really over ([inferred]), added up again from its [events]; null when it stands as it is. */
    fun settled(run: RecordedRun, events: List<RunEvent>, heart: List<HealthSample> = emptyList(), worn: List<LongRange> = emptyList()): RecordedRun? {
        val over = inferred(run.startEpochMillis, run.endEpochMillis, events, heart, worn) ?: return null
        return RunTracker.replay(run.startEpochMillis, upTo(over, events)).finished(run.id, over, run.utcOffsetSeconds)
    }
}
