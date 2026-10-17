// Public on purpose: :androidApp's screenshot tests call these from another module (see below).
@file:Suppress("ktlint:compose:preview-public-check")

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.ConnectionProblem
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.preview.SharedTransitionPreview
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import com.meticulouscreations.homesafe.viewmodel.InViewItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/*
 * The Home tab, drawn from fixtures. These are the previews every renderer shares: Android
 * Studio draws them in the editor, `./gradlew :shared:renderPreviews` draws them on the JVM, and
 * :androidApp's screenshot tests draw them through Layoutlib (see docs/ui-previews.md). They are
 * public for that last one, its @PreviewTest functions living in another module; a preview only
 * the JVM renderer and Studio need stays private, as compose-rules prefers.
 *
 * No tile has a stream, so no player is composed; the cards show their placeholder and the
 * badge says what it says before a player has reported anything.
 */

@Preview(name = "Home", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedPreview() {
    HomeFeedPreviewContent(everyoneAway = false, cameras = previewTiles, inView = previewInView)
}

/** A server with Nest cameras too: the switch heads the cameras, on the outdoor ones as the page opens. */
@Preview(name = "Home, with indoor cameras", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedWithIndoorPreview() {
    HomeFeedPreviewContent(everyoneAway = false, cameras = previewTiles, inView = previewInView, placement = CameraPlacement.Outdoor)
}

/** Switched to the indoor (Nest) cameras. */
@Preview(name = "Home, indoor cameras", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedIndoorPreview() {
    HomeFeedPreviewContent(everyoneAway = false, cameras = previewIndoorTiles, inView = previewInView, placement = CameraPlacement.Indoor)
}

/** Arrange mode on the indoor cameras: each card with its turn and tilt controls, the nursery's picture set crooked. */
@Preview(name = "Home, arranging", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedArrangingPreview() {
    val tiles = previewIndoorTiles.map { if (it.camera.name == "nursery") it.copy(orientation = CameraOrientation(quarterTurns = 1, tiltDegrees = -8f)) else it }
    HomeFeedPreviewContent(everyoneAway = false, cameras = tiles, inView = emptyList(), placement = CameraPlacement.Indoor, arranging = true)
}

@Preview(name = "Home, loading", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedLoadingPreview() {
    HomeFeedPreviewContent(everyoneAway = false, cameras = null, inView = emptyList())
}

@Preview(name = "Home, everyone away", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedAwayPreview() {
    HomeFeedPreviewContent(everyoneAway = true, cameras = previewTiles.take(1), inView = emptyList())
}

/** Signed in on what the device kept, with Tailscale off on it: the notice leads the page. Only the JVM renderer and Studio draw it. */
@Preview(name = "Home, Tailscale off", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
private fun HomeFeedTailscaleOffPreview() {
    HomeFeedPreviewContent(everyoneAway = false, cameras = previewTiles, inView = emptyList(), connectionProblem = ConnectionProblem.TailscaleOff)
}

@Preview(name = "Home, no cameras", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedEmptyPreview() {
    HomeFeedPreviewContent(everyoneAway = false, cameras = emptyList(), inView = emptyList())
}

/**
 * Mid-refresh: the band open and scanning between where the shell's top bar sits (blank here; the
 * shell draws it) and the list it pushed down. Private: Layoutlib has no part in drawing the shader.
 */
@Preview(name = "Home, refreshing", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
private fun HomeFeedRefreshingPreview() {
    HomeFeedPreviewContent(everyoneAway = false, cameras = previewTiles, inView = previewInView, refreshing = true)
}

/** The camera card's corner pill in each state it can be in; sized to the pills, not a phone. */
@Preview(name = "Status badges")
@Composable
fun StatusBadgePreview() {
    FrigateTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge(enabled = true, status = LiveStreamStatus.Live, textColor = Color.White, pillColor = Color.Black)
            StatusBadge(enabled = true, status = LiveStreamStatus.Connecting, textColor = Color.White, pillColor = Color.Black)
            StatusBadge(enabled = true, status = LiveStreamStatus.Buffering, textColor = Color.White, pillColor = Color.Black)
            StatusBadge(enabled = false, status = LiveStreamStatus.Connecting, textColor = Color.White, pillColor = Color.Black)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeFeedPreviewContent(
    everyoneAway: Boolean,
    cameras: List<CameraTile>?,
    inView: List<InViewItem>,
    refreshing: Boolean = false,
    connectionProblem: ConnectionProblem? = null,
    placement: CameraPlacement? = null,
    arranging: Boolean = false,
) {
    FrigatePreview {
        SharedTransitionPreview {
            val zoomState = rememberCameraCardZoomState()
            HomeFeed(
                everyoneAway = everyoneAway,
                cameras = cameras,
                onAwayBack = {},
                inView = inView,
                refreshing = refreshing,
                // Only the refreshing preview is pullable, so the others draw exactly as their reference screenshots.
                onRefresh = if (refreshing) ({}) else null,
                connectionProblem = connectionProblem,
                onOpenTailscale = {},
                placement = placement,
                arranging = arranging,
                onArrangeToggle = {},
            ) { tile ->
                CameraCard(
                    tile = tile,
                    sharedTransitionScope = this@SharedTransitionPreview,
                    zoomState = zoomState,
                    onClick = {},
                    arranging = arranging,
                )
            }
        }
    }
}

/** A Pixel-sized phone in portrait, in dp. */
private const val PHONE_WIDTH_DP = 412
private const val PHONE_HEIGHT_DP = 915

private val previewTiles = listOf("front_door", "hikvision_2", "driveway").map { name ->
    CameraTile(camera = Camera(name = name, enabled = name != "driveway"), streamUrl = null, posterUrl = null)
}

private val previewIndoorTiles = listOf("living_room", "nursery").map { name ->
    CameraTile(camera = Camera(name = name, enabled = true, placement = CameraPlacement.Indoor), streamUrl = null, posterUrl = null)
}

/** The day [previewInView]'s car arrived, so its card reads "since 3:33 PM" (in UTC) rather than naming the date. */
private val PREVIEW_TODAY = LocalDate(2026, 9, 14)

private val previewInView = listOf(
    StationaryObject(
        thumbnailEventId = "sighting-front_door",
        cameraName = "front_door",
        label = "car",
        subLabel = "sarahs_tesla",
        zones = listOf("driveway"),
        firstSeenEpochSeconds = 1_789_400_000.0,
        lastSeenEpochSeconds = 1_789_408_700.0,
        seenRecently = true,
        sightings = 4,
        sinceIsKnown = true,
    ),
).map { subject -> InViewItem(subject = subject, presentation = subject.present(PREVIEW_TODAY, TimeZone.UTC), thumbnailUrl = null) }
