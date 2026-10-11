package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.ui.components.LiveStreamStats
import com.meticulouscreations.homesafe.ui.components.LiveTransport
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_stats_bitrate
import homesafe.shared.generated.resources.camera_stats_buffer
import homesafe.shared.generated.resources.camera_stats_connection
import homesafe.shared.generated.resources.camera_stats_connection_hls
import homesafe.shared.generated.resources.camera_stats_connection_webrtc
import homesafe.shared.generated.resources.camera_stats_decode
import homesafe.shared.generated.resources.camera_stats_delay
import homesafe.shared.generated.resources.camera_stats_delay_unknown
import homesafe.shared.generated.resources.camera_stats_dropped
import homesafe.shared.generated.resources.camera_stats_dropped_frames
import homesafe.shared.generated.resources.camera_stats_frame_rate
import homesafe.shared.generated.resources.camera_stats_kbps
import homesafe.shared.generated.resources.camera_stats_lost_packets
import homesafe.shared.generated.resources.camera_stats_mbps
import homesafe.shared.generated.resources.camera_stats_milliseconds
import homesafe.shared.generated.resources.camera_stats_network
import homesafe.shared.generated.resources.camera_stats_resolution
import homesafe.shared.generated.resources.camera_stats_seconds
import homesafe.shared.generated.resources.camera_stats_video
import homesafe.shared.generated.resources.common_dot_separator
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the "stats for nerds" panel says about a reading, line by line. */
class StreamStatsRowsTest {

    private val dot = UiText.of(Res.string.common_dot_separator)

    @Test
    fun aWebRtcReadingListsTheDelayItsPartsAndTheVideo() {
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
            droppedFrames = 0,
            packetsLost = 3,
        )

        assertEquals(
            listOf(
                StreamStatsRow(Res.string.camera_stats_delay, UiText.of(Res.string.camera_stats_milliseconds, 142)),
                StreamStatsRow(Res.string.camera_stats_network, UiText.of(Res.string.camera_stats_milliseconds, 18)),
                StreamStatsRow(Res.string.camera_stats_buffer, UiText.of(Res.string.camera_stats_milliseconds, 116)),
                StreamStatsRow(Res.string.camera_stats_decode, UiText.of(Res.string.camera_stats_milliseconds, 6)),
                StreamStatsRow(Res.string.camera_stats_connection, UiText.of(Res.string.camera_stats_connection_webrtc)),
                StreamStatsRow(
                    Res.string.camera_stats_video,
                    UiText.Joined(
                        listOf(UiText.of(Res.string.camera_stats_resolution, 2960, 1668), UiText.of(Res.string.camera_stats_frame_rate, 20), "H.264".asUiText()),
                        dot,
                    ),
                ),
                StreamStatsRow(Res.string.camera_stats_bitrate, UiText.of(Res.string.camera_stats_mbps, "4.2")),
                StreamStatsRow(
                    Res.string.camera_stats_dropped,
                    UiText.Joined(
                        listOf(UiText.plural(Res.plurals.camera_stats_dropped_frames, 0), UiText.plural(Res.plurals.camera_stats_lost_packets, 3)),
                        dot,
                    ),
                ),
            ),
            streamStatsRows(stats),
        )
    }

    @Test
    fun anHlsReadingHasNoNetworkOrDecodeToShow() {
        val stats = LiveStreamStats(transport = LiveTransport.HLS, delayMs = 2_340, bufferMs = 1_120, bitrateKbps = 512, droppedFrames = 1)

        assertEquals(
            listOf(
                StreamStatsRow(Res.string.camera_stats_delay, UiText.of(Res.string.camera_stats_seconds, "2.3")),
                StreamStatsRow(Res.string.camera_stats_buffer, UiText.of(Res.string.camera_stats_seconds, "1.1")),
                StreamStatsRow(Res.string.camera_stats_connection, UiText.of(Res.string.camera_stats_connection_hls)),
                StreamStatsRow(Res.string.camera_stats_bitrate, UiText.of(Res.string.camera_stats_kbps, 512)),
                StreamStatsRow(Res.string.camera_stats_dropped, UiText.Joined(listOf(UiText.plural(Res.plurals.camera_stats_dropped_frames, 1)), dot)),
            ),
            streamStatsRows(stats),
        )
    }

    @Test
    fun aPlayerThatCannotMeasureTheDelaySaysSoRatherThanLeavingItOut() {
        val rows = streamStatsRows(LiveStreamStats(transport = LiveTransport.HLS))

        assertEquals(
            listOf(
                StreamStatsRow(Res.string.camera_stats_delay, UiText.of(Res.string.camera_stats_delay_unknown)),
                StreamStatsRow(Res.string.camera_stats_connection, UiText.of(Res.string.camera_stats_connection_hls)),
            ),
            rows,
        )
    }

    @Test
    fun aSecondOrMoreIsWrittenInSecondsToOneDecimal() {
        assertEquals(UiText.of(Res.string.camera_stats_milliseconds, 0), durationText(0))
        assertEquals(UiText.of(Res.string.camera_stats_milliseconds, 999), durationText(999))
        assertEquals(UiText.of(Res.string.camera_stats_seconds, "1.0"), durationText(1_000))
        assertEquals(UiText.of(Res.string.camera_stats_seconds, "1.4"), durationText(1_449))
        assertEquals(UiText.of(Res.string.camera_stats_seconds, "1.5"), durationText(1_450))
        assertEquals(UiText.of(Res.string.camera_stats_seconds, "12.0"), durationText(11_960))
    }

    @Test
    fun aMegabitOrMoreIsWrittenInMegabits() {
        assertEquals(UiText.of(Res.string.camera_stats_kbps, 640), bitrateText(640))
        assertEquals(UiText.of(Res.string.camera_stats_mbps, "2.1"), bitrateText(2_050))
    }
}
