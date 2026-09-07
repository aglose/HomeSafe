package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.StatusBadge
import kotlin.test.Test

/**
 * The camera card's corner pill, rendered for real. The wording is pinned by StatusBadgeLabelTest;
 * this is here so the two indicators — the pulsing dot and the three buffering dots — are both
 * actually composed on the platforms this source set reaches.
 *
 * `autoAdvance = false` for the same reason as [HomeFeedUiTest]: both indicators animate forever,
 * so the composition is never idle.
 */
@OptIn(ExperimentalTestApi::class)
class StatusBadgeUiTest {

    private fun runBadge(enabled: Boolean, status: LiveStreamStatus, expected: String) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                StatusBadge(
                    enabled = enabled,
                    status = status,
                    textColor = Color.White,
                    pillColor = Color.Black,
                )
            }
        }

        onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun aPlayingStreamShowsLiveOnAPulsingDot() =
        runBadge(enabled = true, status = LiveStreamStatus.Live, expected = "Live")

    @Test
    fun aStreamWithNoPictureYetShowsConnectingOnTheThreeDots() =
        runBadge(enabled = true, status = LiveStreamStatus.Connecting, expected = "Connecting")

    @Test
    fun aStarvedStreamKeepsLiveButSwapsInTheThreeDots() =
        runBadge(enabled = true, status = LiveStreamStatus.Buffering, expected = "Live")

    @Test
    fun aCameraOffInFrigateShowsDisabled() =
        runBadge(enabled = false, status = LiveStreamStatus.Connecting, expected = "Disabled")
}
