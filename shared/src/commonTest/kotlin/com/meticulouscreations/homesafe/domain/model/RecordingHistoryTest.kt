package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecordingHistoryTest {

    private val hour = 3600.0
    private val base = 100 * hour // an exact hour boundary

    private fun segment(start: Double, length: Double = 10.0) =
        RecordingSegment(startEpochSeconds = start, endEpochSeconds = start + length)

    // Three clips at the end of hour 100, two at the start of hour 101, then a gap, then one more in hour 101.
    private val history = RecordingHistory(
        listOf(
            segment(base + hour - 30),
            segment(base + hour - 20),
            segment(base + hour - 10),
            segment(base + hour),
            segment(base + hour + 10),
            segment(base + hour + 600),
        ).shuffled(),
    )

    @Test
    fun sortsAndDropsUnplayableSegments() {
        val messy = RecordingHistory(
            listOf(
                segment(30.0),
                RecordingSegment(startEpochSeconds = 0.0, endEpochSeconds = 0.0),
                RecordingSegment(startEpochSeconds = 5.0, endEpochSeconds = 700.0),
                segment(10.0),
            ),
        )
        assertEquals(listOf(10.0, 30.0), messy.segments.map { it.startEpochSeconds })
        assertEquals(40.0, messy.latestEndEpochSeconds)
    }

    @Test
    fun emptyHistoryHasNoLatestEnd() {
        assertNull(RecordingHistory.EMPTY.latestEndEpochSeconds)
        assertNull(RecordingHistory.EMPTY.playlistFor(123.0))
    }

    @Test
    fun playlistForGroupsByHourOfSegmentStart() {
        val playlist = assertNotNull(history.playlistFor(base + hour - 15))
        assertEquals(3, playlist.segments.size)
        assertEquals(base + hour - 30, playlist.startEpochSeconds)
        assertEquals(base + hour, playlist.endEpochSeconds)
    }

    @Test
    fun playlistForInsideGapUsesNextRecording() {
        val playlist = assertNotNull(history.playlistFor(base + hour + 300))
        assertEquals(3, playlist.segments.size)
        assertEquals(base + hour, playlist.startEpochSeconds)
    }

    @Test
    fun playlistForAfterEverythingIsNull() {
        assertNull(history.playlistFor(base + hour + 700))
    }

    @Test
    fun playlistAfterContinuesIntoNextHourThenEnds() {
        val first = assertNotNull(history.playlistFor(base + hour - 15))
        val second = assertNotNull(history.playlistAfter(first))
        assertEquals(base + hour, second.startEpochSeconds)
        assertNull(history.playlistAfter(second))
    }
}
