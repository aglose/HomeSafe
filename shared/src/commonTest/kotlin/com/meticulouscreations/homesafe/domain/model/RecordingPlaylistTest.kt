package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingPlaylistTest {

    // Two 10s clips, a 30s gap, then another 10s clip.
    private val playlist = RecordingPlaylist(
        listOf(
            RecordingSegment(startEpochSeconds = 1000.0, endEpochSeconds = 1010.0),
            RecordingSegment(startEpochSeconds = 1010.0, endEpochSeconds = 1020.0),
            RecordingSegment(startEpochSeconds = 1050.0, endEpochSeconds = 1060.0),
        ),
    )

    @Test
    fun rejectsEmptyPlaylist() {
        assertFailsWith<IllegalArgumentException> { RecordingPlaylist(emptyList()) }
    }

    @Test
    fun rangeAndDurationCollapseGaps() {
        assertEquals(1000.0, playlist.startEpochSeconds)
        assertEquals(1060.0, playlist.endEpochSeconds)
        assertEquals(30.0, playlist.durationSeconds)
    }

    @Test
    fun positionInsideAClipIsOffsetPlusElapsed() {
        assertEquals(0.0, playlist.positionSecondsFor(1000.0))
        assertEquals(5.0, playlist.positionSecondsFor(1005.0))
        assertEquals(15.0, playlist.positionSecondsFor(1015.0))
        assertEquals(22.0, playlist.positionSecondsFor(1052.0))
    }

    @Test
    fun positionInsideAGapSnapsToNextClip() {
        assertEquals(20.0, playlist.positionSecondsFor(1030.0))
    }

    @Test
    fun positionOutsidePlaylistClamps() {
        assertEquals(0.0, playlist.positionSecondsFor(900.0))
        assertEquals(30.0, playlist.positionSecondsFor(2000.0))
    }

    @Test
    fun epochAtInvertsPositionAcrossGaps() {
        assertEquals(1000.0, playlist.epochSecondsAt(0.0))
        assertEquals(1015.0, playlist.epochSecondsAt(15.0))
        assertEquals(1050.0, playlist.epochSecondsAt(20.0))
        assertEquals(1057.0, playlist.epochSecondsAt(27.0))
        assertEquals(1060.0, playlist.epochSecondsAt(99.0))
        assertEquals(1000.0, playlist.epochSecondsAt(-1.0))
    }

    @Test
    fun usesFrigateDurationNotWallClockLength() {
        val skewed = RecordingPlaylist(
            listOf(
                RecordingSegment(startEpochSeconds = 0.0, endEpochSeconds = 10.0, durationSeconds = 10.5),
                RecordingSegment(startEpochSeconds = 10.0, endEpochSeconds = 20.0),
            ),
        )
        assertEquals(20.5, skewed.durationSeconds)
        assertEquals(10.5, skewed.positionSecondsFor(10.0))
        assertEquals(15.0, skewed.epochSecondsAt(15.5))
    }

    @Test
    fun containsCoversGapsButNotTheEnd() {
        assertTrue(1030.0 in playlist)
        assertTrue(1000.0 in playlist)
        assertFalse(1060.0 in playlist)
        assertFalse(999.0 in playlist)
    }
}
