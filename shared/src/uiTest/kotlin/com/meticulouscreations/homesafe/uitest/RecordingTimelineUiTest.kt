package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.ui.components.HELD_ZOOM
import com.meticulouscreations.homesafe.ui.components.RecordingTimeline
import com.meticulouscreations.homesafe.ui.components.TimelineDetection
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.preview.previewNowEpochSeconds
import com.meticulouscreations.homesafe.ui.preview.previewRecordingSegments
import com.meticulouscreations.homesafe.viewmodel.TimelineSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The camera screen's DVR scrubber, touched for real. It is one Canvas with no semantics of its
 * own, so every test aims at a spot on it by geometry: the window is the three hours ending at
 * [now], left edge to right, so a point a fraction of the way across is that fraction of the
 * window. The bars take the lower part of the 108dp strip (from 50dp down); the detection dots
 * sit in a lane above them, centred at 38dp.
 *
 * What is pinned: a tap on the bars seeks to where it landed; a tap above them, near a dot, plays
 * from that detection's start instead — and falls back to a seek to the same instant when the
 * caller has no separate handler — while a tap above them away from any dot, or on the bars
 * under one, is still a seek; and a drag is a scrub, bracketed by exactly one start and one end.
 * A press held still past the long-press timeout is a scrub too, from where it was pressed, and
 * then moves [HELD_ZOOM] times finer than a plain drag, as the bubble it swells magnifies.
 *
 * Wrapped in a [Box] so the strip keeps its own height rather than being stretched by
 * [FrigatePreview]'s Surface. The glass lens's springs settle on their own, so the clock is left to run.
 */
@OptIn(ExperimentalTestApi::class)
class RecordingTimelineUiTest {

    private val now = previewNowEpochSeconds
    private val span = TimelineSpan.THREE_HOURS
    private val windowStart = now - span.seconds

    /** A fraction of the way across the strip, as a moment in the window. */
    private fun at(fraction: Double): Double = windowStart + fraction * span.seconds

    /** A tap's landing is only as exact as a pixel: allow a hundredth of the window (under two minutes). */
    private val slack = span.seconds * 0.01

    private fun ComposeUiTest.setUpTimeline(
        detections: List<TimelineDetection> = emptyList(),
        onSeek: (Double) -> Unit = {},
        onDetectionTap: (Double) -> Unit = {},
        onScrubStart: () -> Unit = {},
        onScrub: (Double) -> Unit = {},
        onScrubEnd: () -> Unit = {},
    ) {
        setContent {
            FrigatePreview {
                Box {
                    RecordingTimeline(
                        segments = previewRecordingSegments,
                        span = span,
                        nowEpochSeconds = now,
                        playheadEpochSeconds = null,
                        scrubEpochSeconds = null,
                        isLive = true,
                        onScrubStart = onScrubStart,
                        onScrub = onScrub,
                        onScrubEnd = onScrubEnd,
                        onSeek = onSeek,
                        modifier = Modifier.testTag(TIMELINE_TAG),
                        detections = detections,
                        onDetectionTap = onDetectionTap,
                    )
                }
            }
        }
    }

    @Test
    fun drawsAsAStripOfItsOwnHeightWhileScrubbing() = runComposeUiTest {
        setContent {
            FrigatePreview {
                Box {
                    RecordingTimeline(
                        segments = previewRecordingSegments,
                        span = span,
                        nowEpochSeconds = now,
                        playheadEpochSeconds = at(0.5),
                        // The scrub label is drawn only while scrubbing, and is laid-out text: it has to fit.
                        scrubEpochSeconds = at(0.5),
                        isLive = false,
                        onScrubStart = {},
                        onScrub = {},
                        onScrubEnd = {},
                        onSeek = {},
                        modifier = Modifier.testTag(TIMELINE_TAG),
                        detections = listOf(TimelineDetection(at(0.25), MomentCategory.PEOPLE)),
                    )
                }
            }
        }

        onNodeWithTag(TIMELINE_TAG).assertIsDisplayed().assertHeightIsEqualTo(108.dp)
    }

    @Test
    fun aTapOnTheBarsSeeksToWhereItLanded() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        setUpTimeline(onSeek = { seeks += it })

        onNodeWithTag(TIMELINE_TAG).performTouchInput { click(Offset(centerX, BARS_Y.dp.toPx())) }

        assertEquals(1, seeks.size)
        assertEquals(at(0.5), seeks.single(), slack)
    }

    @Test
    fun aTapOnADotPlaysFromWhenThatDetectionStarted() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        val played = mutableListOf<Double>()
        val start = at(0.5)
        setUpTimeline(
            detections = listOf(TimelineDetection(start, MomentCategory.PEOPLE)),
            onSeek = { seeks += it },
            onDetectionTap = { played += it },
        )

        onNodeWithTag(TIMELINE_TAG).performTouchInput { click(Offset(centerX, DOT_LANE_Y.dp.toPx())) }

        assertEquals(listOf(start), played)
        assertTrue(seeks.isEmpty(), "a tap on a dot is not also a seek, but seeked to $seeks")
    }

    @Test
    fun withoutItsOwnHandlerADotTapSeeksToTheDetectionNotToTheFinger() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        val start = at(0.5)
        setContent {
            FrigatePreview {
                Box {
                    RecordingTimeline(
                        segments = previewRecordingSegments,
                        span = span,
                        nowEpochSeconds = now,
                        playheadEpochSeconds = null,
                        scrubEpochSeconds = null,
                        isLive = true,
                        onScrubStart = {},
                        onScrub = {},
                        onScrubEnd = {},
                        onSeek = { seeks += it },
                        modifier = Modifier.testTag(TIMELINE_TAG),
                        detections = listOf(TimelineDetection(start, MomentCategory.VEHICLES)),
                    )
                }
            }
        }

        // A finger's width off the dot, but within its reach: the dot takes it.
        onNodeWithTag(TIMELINE_TAG).performTouchInput { click(Offset(centerX + 10.dp.toPx(), DOT_LANE_Y.dp.toPx())) }

        assertEquals(listOf(start), seeks, "the seek should snap to the detection's start")
    }

    @Test
    fun aTapAboveTheBarsAwayFromAnyDotIsStillASeek() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        val played = mutableListOf<Double>()
        setUpTimeline(
            detections = listOf(TimelineDetection(at(0.1), MomentCategory.ANIMALS)),
            onSeek = { seeks += it },
            onDetectionTap = { played += it },
        )

        onNodeWithTag(TIMELINE_TAG).performTouchInput { click(Offset(width * 0.9f, DOT_LANE_Y.dp.toPx())) }

        assertTrue(played.isEmpty(), "no dot was near the tap, but it played $played")
        assertEquals(1, seeks.size)
        assertEquals(at(0.9), seeks.single(), slack)
    }

    @Test
    fun aTapOnTheBarsUnderADotSeeksRatherThanPlayingIt() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        val played = mutableListOf<Double>()
        setUpTimeline(
            detections = listOf(TimelineDetection(at(0.5), MomentCategory.PEOPLE)),
            onSeek = { seeks += it },
            onDetectionTap = { played += it },
        )

        onNodeWithTag(TIMELINE_TAG).performTouchInput { click(Offset(centerX, BARS_Y.dp.toPx())) }

        assertTrue(played.isEmpty(), "only the lane above the bars is aimed at dots, but it played $played")
        assertEquals(1, seeks.size)
        assertEquals(at(0.5), seeks.single(), slack)
    }

    @Test
    fun aDragScrubsFromOneStartToOneEnd() = runComposeUiTest {
        var starts = 0
        var ends = 0
        val scrubs = mutableListOf<Double>()
        val seeks = mutableListOf<Double>()
        setUpTimeline(
            onSeek = { seeks += it },
            onScrubStart = { starts++ },
            onScrub = { scrubs += it },
            onScrubEnd = { ends++ },
        )

        onNodeWithTag(TIMELINE_TAG).performTouchInput {
            swipe(
                start = Offset(width * 0.2f, BARS_Y.dp.toPx()),
                end = Offset(width * 0.8f, BARS_Y.dp.toPx()),
                durationMillis = 500,
            )
        }

        assertEquals(1, starts, "one drag is one scrub")
        assertEquals(1, ends, "the scrub must be ended, or the player stays paused on it")
        assertTrue(scrubs.size > 1, "the playhead should follow the finger, but moved ${scrubs.size} time(s)")
        assertEquals(at(0.8), scrubs.last(), slack)
        assertTrue(seeks.isEmpty(), "a drag is not a tap, but seeked to $seeks")
    }

    @Test
    fun aHeldPressScrubsFromWhereItWasHeldAndFinerThanADrag() = runComposeUiTest {
        var starts = 0
        var ends = 0
        val scrubs = mutableListOf<Double>()
        val seeks = mutableListOf<Double>()
        setUpTimeline(
            onSeek = { seeks += it },
            onScrubStart = { starts++ },
            onScrub = { scrubs += it },
            onScrubEnd = { ends++ },
        )
        val across = 0.3f

        onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(Offset(centerX, BARS_Y.dp.toPx()))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(4) { moveBy(Offset(width * across / 4, 0f)) }
            up()
        }

        assertEquals(1, starts, "one hold is one scrub")
        assertEquals(1, ends, "the scrub must be ended, or the player stays paused on it")
        assertEquals(at(0.5), scrubs.first(), slack, "a hold starts the scrub where it was pressed")
        // A plain drag this far would have scrubbed to at(0.8).
        assertEquals(at(0.5 + across / HELD_ZOOM), scrubs.last(), slack)
        assertTrue(seeks.isEmpty(), "a hold is not a tap, but seeked to $seeks")
    }

    private companion object {
        const val TIMELINE_TAG = "timeline"

        /** Well inside the bars, which run from 50dp down to 92dp. */
        const val BARS_Y = 80

        /** The middle of the detection dots' lane. */
        const val DOT_LANE_Y = 38
    }
}
