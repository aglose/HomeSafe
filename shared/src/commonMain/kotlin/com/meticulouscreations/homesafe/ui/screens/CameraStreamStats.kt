package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.components.LiveStreamStats
import com.meticulouscreations.homesafe.ui.components.LiveTransport
import com.meticulouscreations.homesafe.ui.components.codecDisplayName
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_stats_bitrate
import homesafe.shared.generated.resources.camera_stats_buffer
import homesafe.shared.generated.resources.camera_stats_close
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
import homesafe.shared.generated.resources.camera_stats_measuring
import homesafe.shared.generated.resources.camera_stats_milliseconds
import homesafe.shared.generated.resources.camera_stats_network
import homesafe.shared.generated.resources.camera_stats_recording
import homesafe.shared.generated.resources.camera_stats_resolution
import homesafe.shared.generated.resources.camera_stats_seconds
import homesafe.shared.generated.resources.camera_stats_title
import homesafe.shared.generated.resources.camera_stats_video
import homesafe.shared.generated.resources.common_dot_separator
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One line of the stats panel: what is measured, and what it measured. */
@Immutable
internal data class StreamStatsRow(val label: StringResource, val value: UiText)

/**
 * What the panel says about [stats], top to bottom: the delay and what it is made of, then how
 * the video is arriving and what it is. A figure the player couldn't give has no row, except the
 * delay: it is what the panel is for, so where it can't be measured the panel says so.
 */
internal fun streamStatsRows(stats: LiveStreamStats): List<StreamStatsRow> = buildList {
    add(StreamStatsRow(Res.string.camera_stats_delay, stats.delayMs?.let(::durationText) ?: UiText.of(Res.string.camera_stats_delay_unknown)))
    stats.networkMs?.let { add(StreamStatsRow(Res.string.camera_stats_network, durationText(it))) }
    stats.bufferMs?.let { add(StreamStatsRow(Res.string.camera_stats_buffer, durationText(it))) }
    stats.decodeMs?.let { add(StreamStatsRow(Res.string.camera_stats_decode, durationText(it))) }
    val connection = when (stats.transport) {
        LiveTransport.WEBRTC -> Res.string.camera_stats_connection_webrtc
        LiveTransport.HLS -> Res.string.camera_stats_connection_hls
    }
    add(StreamStatsRow(Res.string.camera_stats_connection, UiText.of(connection)))
    val video = listOfNotNull(
        if (stats.width != null && stats.height != null) UiText.of(Res.string.camera_stats_resolution, stats.width, stats.height) else null,
        stats.framesPerSecond?.let { UiText.of(Res.string.camera_stats_frame_rate, it) },
        stats.codec?.let { codecDisplayName(it).asUiText() },
    )
    if (video.isNotEmpty()) add(StreamStatsRow(Res.string.camera_stats_video, video.joined()))
    stats.bitrateKbps?.let { add(StreamStatsRow(Res.string.camera_stats_bitrate, bitrateText(it))) }
    val dropped = listOfNotNull(
        stats.droppedFrames?.let { UiText.plural(Res.plurals.camera_stats_dropped_frames, it.toQuantity()) },
        stats.packetsLost?.let { UiText.plural(Res.plurals.camera_stats_lost_packets, it.toQuantity()) },
    )
    if (dropped.isNotEmpty()) add(StreamStatsRow(Res.string.camera_stats_dropped, dropped.joined()))
}

/** "240 ms" up to a second, "1.4 s" from there: nobody reads 1400 ms as fast as 1.4 s. */
internal fun durationText(milliseconds: Int): UiText = if (milliseconds < MS_PER_SECOND) {
    UiText.of(Res.string.camera_stats_milliseconds, milliseconds)
} else {
    UiText.of(Res.string.camera_stats_seconds, oneDecimal(milliseconds))
}

/** "640 kbps" up to a megabit, "2.1 Mbps" from there. */
internal fun bitrateText(kbps: Int): UiText = if (kbps < KBPS_PER_MBPS) {
    UiText.of(Res.string.camera_stats_kbps, kbps)
} else {
    UiText.of(Res.string.camera_stats_mbps, oneDecimal(kbps))
}

/** A count of thousandths as units with one decimal place, rounded: 1449 is "1.4", 1450 "1.5". */
private fun oneDecimal(thousandths: Int): String {
    val tenths = (thousandths + 50) / 100
    return "${tenths / 10}.${tenths % 10}"
}

private fun List<UiText>.joined(): UiText = UiText.Joined(this, separator = UiText.of(Res.string.common_dot_separator))

private fun Long.toQuantity(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private const val MS_PER_SECOND = 1_000
private const val KBPS_PER_MBPS = 1_000

/**
 * The camera's "stats for nerds" panel, when its view model says it is up ([CameraDetailViewModel.statsShown]).
 * Collects the readings itself, so one arriving every second recomposes this and not the player it sits on.
 */
@Composable
internal fun PlayerStreamStats(cameraName: String, modifier: Modifier = Modifier) {
    val viewModel = cameraDetailViewModel(cameraName)
    val shown by viewModel.statsShown.collectAsStateWithLifecycle()
    if (!shown) return
    val stats by viewModel.streamStats.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    StreamStatsPanel(stats = stats, isLive = playback.isLive, onClose = viewModel::toggleStats, modifier = modifier)
}

/**
 * What the live stream is doing, over a corner of its video, as a video player's "stats for
 * nerds" does it: a label and a figure a line. [stats] is null until the player's first reading;
 * while a recording plays ([isLive] false) there is nothing live to measure, and it says that instead.
 */
@Composable
internal fun StreamStatsPanel(stats: LiveStreamStats?, isLive: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .widthIn(max = STATS_PANEL_MAX_WIDTH)
            .clip(shape)
            .background(LocalFrigateExtraColors.current.glassFill)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)
            .testTag(STREAM_STATS_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.camera_stats_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // A small glyph in a full-size target: the panel is over the video, and must not be bigger than it has to.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.camera_stats_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        when {
            !isLive -> StatsNote(Res.string.camera_stats_recording)
            stats == null -> StatsNote(Res.string.camera_stats_measuring)
            else -> streamStatsRows(stats).forEach { row -> StatsLine(row) }
        }
    }
}

@Composable
private fun StatsNote(text: StringResource) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 6.dp),
    )
}

@Composable
private fun StatsLine(row: StreamStatsRow) {
    Row(modifier = Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(row.label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(STATS_LABEL_WIDTH),
        )
        Text(
            text = row.value.resolve(),
            // Figures that change every second, in digits that are all one width, so the line doesn't jitter as they do.
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal const val STREAM_STATS_TEST_TAG = "stream-stats"

private val STATS_PANEL_MAX_WIDTH = 288.dp
private val STATS_LABEL_WIDTH = 88.dp
private const val TABULAR_FIGURES = "tnum"

/** The panel as each kind of connection fills it, and the two things it says when it has no figures. */
@Preview(name = "Stream stats")
@Composable
private fun StreamStatsPanelPreview() {
    FrigateTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StreamStatsPanel(
                stats = LiveStreamStats(
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
                ),
                isLive = true,
                onClose = {},
            )
            StreamStatsPanel(
                stats = LiveStreamStats(
                    transport = LiveTransport.HLS,
                    delayMs = 2_340,
                    bufferMs = 1_120,
                    width = 640,
                    height = 360,
                    framesPerSecond = 25,
                    bitrateKbps = 512,
                    codec = "video/avc",
                    droppedFrames = 1,
                ),
                isLive = true,
                onClose = {},
            )
            StreamStatsPanel(stats = LiveStreamStats(transport = LiveTransport.HLS, width = 1920, height = 1080, framesPerSecond = 15, codec = "h264"), isLive = true, onClose = {})
            StreamStatsPanel(stats = null, isLive = true, onClose = {})
            StreamStatsPanel(stats = null, isLive = false, onClose = {})
        }
    }
}
