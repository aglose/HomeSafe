package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.domain.model.MomentCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineMarksTest {

    /** A 3h window 300 px wide: a pixel is 36 seconds, which keeps the arithmetic below legible. */
    private val windowStart = 1_789_400_000.0
    private val span = 10_800.0
    private val width = 300f

    private fun at(px: Int, category: MomentCategory = MomentCategory.PEOPLE) = TimelineDetection(windowStart + px * 36.0, category)

    @Test
    fun eachDetectionIsADotAtItsStart() {
        val markers = timelineMarkers(listOf(at(100), at(10, MomentCategory.VEHICLES)), windowStart, span, width, mergeWithin = 12f)

        assertEquals(listOf(10f, 100f), markers.map { it.x }, "left to right, whatever order they came in")
        assertEquals(listOf(MomentCategory.VEHICLES, MomentCategory.PEOPLE), markers.map { it.category })
        assertEquals(listOf(1, 1), markers.map { it.count })
        assertEquals(windowStart + 360.0, markers[0].epochSeconds, "a tap plays from where the detection started")
    }

    @Test
    fun detectionsTooCloseToTellApartShareOneDotAtTheEarliest() {
        val markers = timelineMarkers(
            listOf(at(50, MomentCategory.VEHICLES), at(55, MomentCategory.ANIMALS), at(58, MomentCategory.VEHICLES), at(70)),
            windowStart,
            span,
            width,
            mergeWithin = 12f,
        )

        assertEquals(2, markers.size)
        val merged = markers[0]
        assertEquals(50f, merged.x)
        assertEquals(windowStart + 50 * 36.0, merged.epochSeconds, "the start of what happened there")
        assertEquals(3, merged.count)
        assertEquals(MomentCategory.ANIMALS, merged.category, "an animal outranks the cars passing by")
        assertEquals(70f, markers[1].x, "20 px on is its own dot")
    }

    @Test
    fun aLongRunOfDetectionsBecomesEvenlySpacedDotsRatherThanOneSmear() {
        // A detection every 4 px for 60 px: anchoring on each dot's first detection caps how far a dot reaches.
        val markers = timelineMarkers((0..60 step 4).map { at(100 + it, MomentCategory.VEHICLES) }, windowStart, span, width, mergeWithin = 12f)

        assertEquals(listOf(100f, 112f, 124f, 136f, 148f, 160f), markers.map { it.x })
        assertEquals(16, markers.sumOf { it.count }, "every detection is in exactly one dot")
    }

    @Test
    fun aPersonAnywhereInAMergedDotColoursIt() {
        val markers = timelineMarkers(listOf(at(20, MomentCategory.VEHICLES), at(22, MomentCategory.ALL), at(25)), windowStart, span, width, mergeWithin = 12f)

        assertEquals(MomentCategory.PEOPLE, markers.single().category)
    }

    @Test
    fun detectionsOutsideTheWindowAreLeftOff() {
        val markers = timelineMarkers(
            listOf(TimelineDetection(windowStart - 60, MomentCategory.PEOPLE), at(150), TimelineDetection(windowStart + span + 60, MomentCategory.PEOPLE)),
            windowStart,
            span,
            width,
            mergeWithin = 12f,
        )

        assertEquals(listOf(150f), markers.map { it.x })
    }

    @Test
    fun aTapFindsTheNearestDotWithinReachAndNothingBeyondIt() {
        val markers = timelineMarkers(listOf(at(100), at(130)), windowStart, span, width, mergeWithin = 12f)

        assertEquals(130f, markers.markerNear(120f, reach = 18f)?.x)
        assertEquals(100f, markers.markerNear(110f, reach = 18f)?.x)
        assertNull(markers.markerNear(200f, reach = 18f))
        assertNull(emptyList<TimelineMarker>().markerNear(0f, reach = 18f))
    }

    @Test
    fun halfHourLabelsThatWouldTouchAreThinnedToTheHours() {
        // The 3h span on a phone (2026-09-22): 45 px labels on 50 px half-hour ticks ran into each other.
        val slots = (0 until 6).map { TickLabelSlot(ordinal = 100L + it, centerX = 30f + it * 50f, labelWidth = 45f) }

        val drawn = tickLabelsToDraw(slots, width = 310f, inset = 4f, gap = 8f)

        assertEquals(listOf(0, 2, 4), drawn, "every even ordinal: the hours, not whichever tick happened to be first")
    }

    @Test
    fun labelsWithRoomToSpareAreAllDrawn() {
        val slots = (0 until 4).map { TickLabelSlot(ordinal = it.toLong(), centerX = 40f + it * 80f, labelWidth = 45f) }

        assertEquals(listOf(0, 1, 2, 3), tickLabelsToDraw(slots, width = 320f, inset = 4f, gap = 8f))
    }

    @Test
    fun aLabelThatWouldHangOffAnEdgeIsLeftOffRatherThanPushedIntoItsNeighbour() {
        val slots = listOf(
            TickLabelSlot(ordinal = 1, centerX = 5f, labelWidth = 45f),
            TickLabelSlot(ordinal = 2, centerX = 105f, labelWidth = 45f),
            TickLabelSlot(ordinal = 3, centerX = 205f, labelWidth = 45f),
            TickLabelSlot(ordinal = 4, centerX = 298f, labelWidth = 45f),
        )

        assertEquals(listOf(1, 2), tickLabelsToDraw(slots, width = 300f, inset = 4f, gap = 8f))
    }

    @Test
    fun theSameTicksKeepTheirLabelsAsTheWindowSlides() {
        fun drawnOrdinals(shift: Float): List<Long> {
            val slots = (0 until 7).map { TickLabelSlot(ordinal = 40L + it, centerX = 30f + it * 48f - shift, labelWidth = 45f) }
            return tickLabelsToDraw(slots, width = 340f, inset = 4f, gap = 8f).map { slots[it].ordinal }
        }

        // A few pixels of "now" moving on must not flip which half of the ticks is labelled.
        assertEquals(drawnOrdinals(0f).filter { it % 2 == 0L }, drawnOrdinals(0f))
        assertEquals(drawnOrdinals(6f).filter { it % 2 == 0L }, drawnOrdinals(6f))
    }
}
