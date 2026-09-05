package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.network.frigateLiveStreamUrl
import com.meticulouscreations.homesafe.network.frigateSnapshotUrl
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.HomeViewModel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The "Home" tab's content: greeting, status, and the cameras reported by the connected Frigate server. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeTabContent(
    observeCamerasUseCase: ObserveCamerasUseCase,
    connectionRepository: ConnectionRepository,
    sharedTransitionScope: SharedTransitionScope,
    onCameraClick: (String) -> Unit = {},
) {
    val extraColors = LocalFrigateExtraColors.current
    val viewModel = viewModel { HomeViewModel(observeCamerasUseCase, connectionRepository) }
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()

    // A LazyColumn (not a plain scrolling Column) so off-screen camera cards aren't composed.
    // Their players (pooled per camera, see CameraStreamPlayer's playerKey) pause the moment a
    // card scrolls out and resume at the live edge when it scrolls back in, so only the cameras
    // actually on screen are decoding; with several 4K streams that concurrency was a real
    // contributor to stutter.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = tabContentPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            // Fixed for the life of this screen: a greeting that flips mid-scroll would be odd.
            val greeting = remember { greetingForHour(currentLocalHour()) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.displayLarge,
                    color = extraColors.textPrimary,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PulsingDot(color = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = "System Secure",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        val loadedCameras = cameras
        if (loadedCameras == null) {
            // First cache read still in flight — a blank beat, not a false "no cameras".
        } else if (loadedCameras.isEmpty()) {
            item {
                Text(
                    text = "No cameras found on this server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(loadedCameras, key = { it.name }) { camera ->
                CameraCard(
                    camera = camera,
                    serverUrl = serverUrl.orEmpty(),
                    sharedTransitionScope = sharedTransitionScope,
                    modifier = Modifier.clickable { onCameraClick(camera.name) },
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CameraCard(
    camera: Camera,
    serverUrl: String,
    sharedTransitionScope: SharedTransitionScope,
    modifier: Modifier = Modifier,
) {
    val extraColors = LocalFrigateExtraColors.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    Box(
        modifier = with(sharedTransitionScope) {
            modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = cameraVideoSharedKey(camera.name)),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.surfaceContainerHigh),
                ),
            ),
    ) {
        if (camera.enabled) {
            // The grid uses the camera's (possibly lower-quality) grid stream — full quality is
            // reserved for the single-camera detail view. The player is keyed by camera name so
            // the detail screen picks up this very player (already decoding) when the card is
            // tapped, and this card gets it back — still warm — on the way out.
            CameraStreamPlayer(
                streamUrl = frigateLiveStreamUrl(serverUrl, camera.gridStreamName),
                modifier = Modifier.fillMaxSize(),
                posterUrl = frigateSnapshotUrl(serverUrl, camera.name, height = GRID_POSTER_HEIGHT),
                playerKey = camera.name,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.Center).size(56.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = camera.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = extraColors.textPrimary,
                modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
            )
            StatusBadge(enabled = camera.enabled, textColor = extraColors.textPrimary, pillColor = extraColors.glassFill)
        }
    }
}

/** "Good Morning" / "Good Afternoon" / "Good Evening" for a 0–23 hour. */
internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    else -> "Good Evening"
}

@OptIn(ExperimentalTime::class)
private fun currentLocalHour(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

/** Server-side downscale for grid posters: plenty for a card, ~30 KB per refresh instead of a full detect frame. */
private const val GRID_POSTER_HEIGHT = 480

/** The shared-element key for a camera's video area, matched between the grid card and the detail screen. */
internal fun cameraVideoSharedKey(cameraName: String): String = "camera-video-$cameraName"

@Composable
private fun StatusBadge(enabled: Boolean, textColor: Color, pillColor: Color) {
    val statusColor = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .background(pillColor, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PulsingDot(color = statusColor, size = 6.dp, pulsing = enabled)
        Text(
            text = if (enabled) "Enabled" else "Disabled",
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}
