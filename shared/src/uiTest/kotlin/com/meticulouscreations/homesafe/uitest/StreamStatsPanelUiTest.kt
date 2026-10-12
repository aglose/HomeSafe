package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.components.LiveStreamStats
import com.meticulouscreations.homesafe.ui.components.LiveTransport
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.StreamStatsPanel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The camera screen's "stats for nerds" panel, rendered for real. Which lines a reading gets is
 * pinned by StreamStatsRowsTest; this is here for the words those lines come out as, the two
 * things the panel says when it has no reading, and the way out of it.
 */
@OptIn(ExperimentalTestApi::class)
class StreamStatsPanelUiTest {

    @Test
    fun aReadingIsLaidOutAsLabelledFigures() = runComposeUiTest {
        val stats = LiveStreamStats(
            transport = LiveTransport.WEBRTC,
            delayMs = 142,
            networkMs = 18,
            bufferMs = 116,
            decodeMs = 6,
            width = 2960,
            height = 1668,
            framesPerSecond = 20,
            bitrateKbps = 4_180,
            codec = "video/H264",
            droppedFrames = 1,
            packetsLost = 3,
        )
        setContent { FrigatePreview { StreamStatsPanel(stats = stats, isLive = true, onClose = {}) } }

        onNodeWithText("Stats for nerds").assertIsDisplayed()
        onNodeWithText("Behind server").assertIsDisplayed()
        onNodeWithText("142 ms").assertIsDisplayed()
        onNodeWithText("18 ms").assertIsDisplayed()
        onNodeWithText("WebRTC").assertIsDisplayed()
        onNodeWithText("2960×1668 · 20 fps · H.264").assertIsDisplayed()
        onNodeWithText("4.2 Mbps").assertIsDisplayed()
        onNodeWithText("1 frame · 3 packets").assertIsDisplayed()
    }

    @Test
    fun beforeTheFirstReadingItSaysItIsMeasuring() = runComposeUiTest {
        setContent { FrigatePreview { StreamStatsPanel(stats = null, isLive = true, onClose = {}) } }

        onNodeWithText("Measuring…").assertIsDisplayed()
    }

    @Test
    fun whileARecordingPlaysItPointsBackToLive() = runComposeUiTest {
        // A reading left over from before the recording started must not be passed off as its own.
        val stale = LiveStreamStats(transport = LiveTransport.WEBRTC, delayMs = 142)
        setContent { FrigatePreview { StreamStatsPanel(stats = stale, isLive = false, onClose = {}) } }

        onNodeWithText("Go live to see the stream's stats").assertIsDisplayed()
        onNodeWithText("142 ms").assertDoesNotExist()
    }

    @Test
    fun theCrossClosesItAndIsBigEnoughToHit() = runComposeUiTest {
        var closed = 0
        setContent { FrigatePreview { StreamStatsPanel(stats = null, isLive = true, onClose = { closed++ }) } }

        // The glyph is small; what takes the tap is Material's 48 dp, inside the panel so none of it is clipped away.
        onNode(hasClickAction()).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        onNodeWithContentDescription("Close stats for nerds").performClick()

        assertEquals(1, closed)
    }
}
