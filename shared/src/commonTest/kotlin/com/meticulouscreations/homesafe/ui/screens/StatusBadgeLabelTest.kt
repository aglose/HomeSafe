package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the camera card's corner pill says — the word is a claim about the picture, not the config. */
class StatusBadgeLabelTest {

    @Test
    fun aCameraTurnedOffInFrigateIsDisabledWhateverThePlayerSays() {
        LiveStreamStatus.entries.forEach { status ->
            assertEquals("Disabled", statusBadgeLabel(enabled = false, status = status), "status $status")
        }
    }

    @Test
    fun livePlaybackIsTheOnlyThingThatEarnsTheWordLive() {
        assertEquals("Live", statusBadgeLabel(enabled = true, status = LiveStreamStatus.Live))
    }

    @Test
    fun aStreamWithNothingOnScreenYetSaysConnecting() {
        assertEquals("Connecting", statusBadgeLabel(enabled = true, status = LiveStreamStatus.Connecting))
    }

    @Test
    fun aStreamThatConnectedAndThenStarvedIsStillLive() {
        // The connection is good and a frame is up; the dots carry the stall, not the word.
        assertEquals("Live", statusBadgeLabel(enabled = true, status = LiveStreamStatus.Buffering))
    }
}
