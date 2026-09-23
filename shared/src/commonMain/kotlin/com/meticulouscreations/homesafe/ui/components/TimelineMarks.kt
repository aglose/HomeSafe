package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import kotlin.math.abs

/** A detection as [RecordingTimeline] marks it: when it started, and what it was. */
@Immutable
data class TimelineDetection(val startEpochSeconds: Double, val category: MomentCategory)

/**
 * One dot on the timeline, standing for [count] detections that started too close together to
 * tell apart at this width. It sits at the earliest of them, [epochSeconds], which is also where
 * a tap on it plays from — the start of what happened there.
 */
@Immutable
internal data class TimelineMarker(
    val x: Float,
    val epochSeconds: Double,
    /** The one that matters most among them: see [MARKER_PRIORITY]. */
    val category: MomentCategory,
    val count: Int,
)

/**
 * Places [detections] across a timeline [width] pixels wide showing [spanSeconds] from
 * [windowStartEpochSeconds], merging any that start within [mergeWithin] pixels of a marker
 * already placed, left to right, so a busy evening reads as a row of dots rather than a smear.
 * Merging is anchored on each marker's first detection, so no two markers end up closer than
 * [mergeWithin], however long the run. Detections outside the window are left off.
 */
internal fun timelineMarkers(
    detections: List<TimelineDetection>,
    windowStartEpochSeconds: Double,
    spanSeconds: Double,
    width: Float,
    mergeWithin: Float,
): List<TimelineMarker> {
    val windowEnd = windowStartEpochSeconds + spanSeconds
    val markers = ArrayList<TimelineMarker>()
    detections
        .filter { it.startEpochSeconds in windowStartEpochSeconds..windowEnd }
        .sortedBy { it.startEpochSeconds }
        .forEach { detection ->
            val x = ((detection.startEpochSeconds - windowStartEpochSeconds) / spanSeconds * width).toFloat()
            val last = markers.lastOrNull()
            if (last != null && x - last.x < mergeWithin) {
                val category = minOf(last.category, detection.category, compareBy { MARKER_PRIORITY.indexOf(it) })
                markers[markers.lastIndex] = last.copy(category = category, count = last.count + 1)
            } else {
                markers += TimelineMarker(x = x, epochSeconds = detection.startEpochSeconds, category = detection.category, count = 1)
            }
        }
    return markers
}

/** The marker nearest [x], if one is within [reach] pixels of it: what a tap there meant. */
internal fun List<TimelineMarker>.markerNear(x: Float, reach: Float): TimelineMarker? =
    minByOrNull { abs(it.x - x) }?.takeIf { abs(it.x - x) <= reach }

/**
 * A merged marker takes the colour of the detection someone is most likely looking for: a
 * person at the door over the dog that followed them, an animal over the cars passing by.
 */
private val MARKER_PRIORITY = listOf(MomentCategory.PEOPLE, MomentCategory.ANIMALS, MomentCategory.VEHICLES, MomentCategory.ALL)

/**
 * Where one tick's label would go: its tick's [ordinal] (how many tick intervals it is from the
 * epoch, in local time, so it is the same number for the same tick however the window moves),
 * the tick's [centerX], and how wide the label is.
 */
internal data class TickLabelSlot(val ordinal: Long, val centerX: Float, val labelWidth: Float)

/**
 * Which tick labels to draw so that none overlap, as indices into [slots]. A label that would
 * hang off either end (within [inset] of it) is left off rather than nudged in, which is what
 * used to butt it up against its neighbour ("5:00 PM5:30 PM" at the 3h span on a phone). The
 * rest are thinned to every second, third, ... tick — whichever is the least thinning that
 * leaves [gap] between neighbours — chosen by [TickLabelSlot.ordinal], so the labels that stay
 * are round times (every hour of half-hour ticks) and don't hop from tick to tick as "now"
 * slides the window along.
 */
internal fun tickLabelsToDraw(slots: List<TickLabelSlot>, width: Float, inset: Float, gap: Float): List<Int> {
    val fitting = slots.indices.filter { i ->
        val half = slots[i].labelWidth / 2f
        slots[i].centerX - half >= inset && slots[i].centerX + half <= width - inset
    }
    for (stride in LABEL_STRIDES) {
        val kept = fitting.filter { slots[it].ordinal.mod(stride.toLong()) == 0L }
        val clear = kept.zipWithNext().all { (a, b) ->
            val right = slots[a].centerX + slots[a].labelWidth / 2f
            val left = slots[b].centerX - slots[b].labelWidth / 2f
            left - right >= gap
        }
        if (clear) return kept
    }
    return emptyList()
}

private val LABEL_STRIDES = listOf(1, 2, 3, 4, 6, 8, 12)
