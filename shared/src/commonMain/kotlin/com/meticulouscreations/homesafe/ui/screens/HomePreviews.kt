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
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.StationaryObjectPresentation
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.preview.SharedTransitionPreview
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import com.meticulouscreations.homesafe.viewmodel.InViewItem

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

@Preview(name = "Home, no cameras", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedEmptyPreview() {
    HomeFeedPreviewContent(everyoneAway = false, cameras = emptyList(), inView = emptyList())
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
private fun HomeFeedPreviewContent(everyoneAway: Boolean, cameras: List<CameraTile>?, inView: List<InViewItem>) {
    FrigatePreview {
        SharedTransitionPreview {
            val zoomState = rememberCameraCardZoomState()
            HomeFeed(
                everyoneAway = everyoneAway,
                cameras = cameras,
                onAwayBack = {},
                statusHeadline = if (cameras == null) null else "Person at Backyard",
                statusDetails = cameras?.let { previewStatusDetails(camerasOn = it.count { tile -> tile.camera.enabled }, everyoneAway = everyoneAway) },
                inView = inView,
            ) { tile ->
                CameraCard(
                    tile = tile,
                    sharedTransitionScope = this@SharedTransitionPreview,
                    zoomState = zoomState,
                    onClick = {},
                )
            }
        }
    }
}

private fun previewStatusDetails(camerasOn: Int, everyoneAway: Boolean): String {
    val cameras = if (camerasOn == 1) "1 camera on" else "$camerasOn cameras on"
    return "3 min ago · $cameras · ${if (everyoneAway) "Everyone away" else "Everyone home"}"
}

/** A Pixel-sized phone in portrait, in dp. */
private const val PHONE_WIDTH_DP = 412
private const val PHONE_HEIGHT_DP = 915

private val previewTiles = listOf("front_door", "hikvision_2", "driveway").map { name ->
    CameraTile(camera = Camera(name = name, enabled = name != "driveway"), streamUrl = null, posterUrl = null)
}

private val previewInView = listOf(
    InViewItem(
        subject = StationaryObject(
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
        presentation = StationaryObjectPresentation(
            title = "Sarah's Tesla",
            placeLabel = "Driveway",
            sinceLabel = "since 3:33 PM",
            lastSeenLabel = null,
        ),
        thumbnailUrl = null,
    ),
)
