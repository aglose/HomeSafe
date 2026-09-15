package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.time.ExperimentalTime

/**
 * Where the Moments feed's window sits in time, and whether there is more of it to fetch.
 *
 * The feed is a window that opens at an instant and runs back into the past a page at a time.
 * Live, the window opens at "now" and its first page is re-read on a timer so new detections
 * arrive on their own; looking back, it opens at the end of a chosen day and nothing above it
 * is shown at all — older moments are found by paging down from there, not by scrolling up
 * through everything since.
 */
data class MomentsPaging(
    /**
     * The window's top edge: only moments that started before this instant are in the feed. Null
     * is the live feed, open at now.
     */
    val beforeEpochSeconds: Double? = null,
    /** A request for the next page down is in flight. */
    val loadingOlder: Boolean = false,
    /**
     * The server has more below the last page loaded. False once a page comes back short, which
     * is Frigate saying its retention has run out — and false while nothing is loaded at all.
     */
    val hasOlder: Boolean = false,
)

/**
 * The first instant of the day after this one, in [timeZone], as Frigate epoch seconds: what a
 * feed window opened "at the end of this day" asks for as its `before`.
 */
@OptIn(ExperimentalTime::class)
fun LocalDate.endOfDayEpochSeconds(timeZone: TimeZone = TimeZone.currentSystemDefault()): Double =
    plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone).epochSeconds.toDouble()
