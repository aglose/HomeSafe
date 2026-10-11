package com.meticulouscreations.homesafe.weather.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How wide the Forecast tab's strip draws a day, for windows from a small phone's to a desktop's. */
class DayTimelineTest {

    @Test
    fun daysThatAllFitShareTheWidth() {
        assertEquals(100, dayColumnWidth(viewport = 1_000, least = 64, days = 10))
        assertEquals(64, dayColumnWidth(viewport = 640, least = 64, days = 10))
    }

    @Test
    fun aPhoneShowsFiveAndAHalfOfTenDays() {
        // 412 dp across, less the card's margins.
        val column = dayColumnWidth(viewport = 384, least = 64, days = 10)
        assertEquals(70, column)
        assertEquals(5.5f, 384f / column, 0.05f)
    }

    @Test
    fun whenTheyDontFitTheWindowEndsHalfwayThroughADayAndNoneIsNarrowerThanTheLeast() {
        for (viewport in 100..639) {
            val column = dayColumnWidth(viewport, least = 64, days = 10)
            assertTrue(column >= 64, "$viewport wide: a day $column wide")
            val shown = viewport.toFloat() / column
            // Nothing to cut in half when the window holds less than a day and a half. A day is a
            // whole number of pixels wide, so the cut is near the middle, not on it.
            if (shown >= 1.5f) assertTrue(abs(shown - shown.toInt() - 0.5f) < 0.1f, "$viewport wide shows $shown days")
        }
    }

    @Test
    fun aStripWithNoWidthYetOrNoDaysStillAnswers() {
        assertEquals(64, dayColumnWidth(viewport = 0, least = 64, days = 10))
        assertEquals(64, dayColumnWidth(viewport = 384, least = 64, days = 0))
        assertEquals(30, dayColumnWidth(viewport = 30, least = 64, days = 10))
    }
}
