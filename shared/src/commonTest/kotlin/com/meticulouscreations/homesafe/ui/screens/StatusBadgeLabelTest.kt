package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.home_badge_connecting
import homesafe.shared.generated.resources.home_badge_disabled
import homesafe.shared.generated.resources.home_badge_live
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the camera card's corner pill says — the word is a claim about the picture, not the config. */
class StatusBadgeLabelTest {

    @Test
    fun aCameraTurnedOffInFrigateIsDisabledWhateverThePlayerSays() {
        LiveStreamStatus.entries.forEach { status ->
            assertEquals(Res.string.home_badge_disabled, statusBadgeLabel(enabled = false, status = status), "status $status")
        }
    }

    @Test
    fun livePlaybackIsTheOnlyThingThatEarnsTheWordLive() {
        assertEquals(Res.string.home_badge_live, statusBadgeLabel(enabled = true, status = LiveStreamStatus.Live))
    }

    @Test
    fun aStreamWithNothingOnScreenYetSaysConnecting() {
        assertEquals(Res.string.home_badge_connecting, statusBadgeLabel(enabled = true, status = LiveStreamStatus.Connecting))
    }

    @Test
    fun aStreamThatConnectedAndThenStarvedIsStillLive() {
        // The connection is good and a frame is up; the dots carry the stall, not the word.
        assertEquals(Res.string.home_badge_live, statusBadgeLabel(enabled = true, status = LiveStreamStatus.Buffering))
    }
}
