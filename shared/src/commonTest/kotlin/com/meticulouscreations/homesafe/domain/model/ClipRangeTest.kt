package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The clip editor's one-tap selections: snapping to a detection, and the length chips. */
class ClipRangeTest {

    private val earliest = 1_000.0
    private val latest = 2_000.0

    @Test
    fun aDetectionIsHeldWithABeatEitherSide() {
        val range = clipRangeForMoment(1_500.0, 1_512.0, earliest, latest)

        assertEquals(1_500.0 - ClipLimits.MOMENT_LEAD_IN_SECONDS, range.startEpochSeconds)
        assertEquals(1_512.0 + ClipLimits.MOMENT_TAIL_SECONDS, range.endEpochSeconds)
    }

    @Test
    fun aDetectionAtTheEdgeOfWhatWasRecordedSlidesInWholeRatherThanBeingCut() {
        val atStart = clipRangeForMoment(1_000.5, 1_010.0, earliest, latest)
        assertEquals(earliest, atStart.startEpochSeconds)
        assertEquals(1_010.0 - 1_000.5 + ClipLimits.MOMENT_LEAD_IN_SECONDS + ClipLimits.MOMENT_TAIL_SECONDS, atStart.durationSeconds, 1e-9)

        val atEnd = clipRangeForMoment(1_990.0, 1_999.0, earliest, latest)
        assertEquals(latest, atEnd.endEpochSeconds)
    }

    @Test
    fun aDetectionLongerThanAClipMayBeKeepsItsStartAndStopsAtTheLimit() {
        val parked = clipRangeForMoment(1_100.0, 1_900.0, earliest, latest)

        assertEquals(1_100.0 - ClipLimits.MOMENT_LEAD_IN_SECONDS, parked.startEpochSeconds)
        assertEquals(ClipLimits.MAX_SECONDS, parked.durationSeconds)
    }

    @Test
    fun anInstantaneousDetectionStillMakesAClipLongEnoughToSave() {
        val blip = clipRangeForMoment(1_500.0, 1_500.0, earliest, latest)

        assertEquals(ClipLimits.MOMENT_LEAD_IN_SECONDS + ClipLimits.MOMENT_TAIL_SECONDS, blip.durationSeconds)
    }

    @Test
    fun aLengthChipKeepsTheStartAndSlidesBackOnlyWhenItMust() {
        val range = ClipRange(1_200.0, 1_220.0)

        assertEquals(ClipRange(1_200.0, 1_260.0), range.withLength(60.0, earliest, latest))

        val nearEnd = ClipRange(1_970.0, 1_990.0).withLength(60.0, earliest, latest)
        assertEquals(ClipRange(latest - 60.0, latest), nearEnd)
    }

    @Test
    fun aLengthChipRespectsTheClipLimits() {
        val range = ClipRange(1_200.0, 1_220.0)

        assertEquals(ClipLimits.MIN_SECONDS, range.withLength(0.5, earliest, latest).durationSeconds)
        assertEquals(ClipLimits.MAX_SECONDS, range.withLength(10_000.0, 0.0, 100_000.0).durationSeconds)
    }

    @Test
    fun withLessRecordedThanAskedForTheClipIsEverythingThereIs() {
        val short = ClipRange(1_000.0, 1_005.0).withLength(60.0, 1_000.0, 1_030.0)

        assertEquals(ClipRange(1_000.0, 1_030.0), short)
    }

    @Test
    fun theStripStaysPutWhenTheNewSelectionIsAlreadyOnIt() {
        val window = ClipWindow(1_100.0, 1_220.0)

        assertSame(window, window.holding(ClipRange(1_150.0, 1_180.0), earliest, latest))
    }

    @Test
    fun theStripRecentresOnASelectionItCantShow() {
        val window = ClipWindow(1_100.0, 1_220.0)
        val far = ClipRange(1_700.0, 1_730.0)

        val moved = window.holding(far, earliest, latest)

        assertEquals(ClipWindow.around(far, earliest, latest), moved)
        assertEquals(true, far.startEpochSeconds >= moved.startEpochSeconds && far.endEpochSeconds <= moved.endEpochSeconds)
    }
}
