package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.MomentsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel

private val MomentCategory.label: String
    get() = when (this) {
        MomentCategory.ALL -> "All Events"
        MomentCategory.PEOPLE -> "People"
        MomentCategory.VEHICLES -> "Vehicles"
        MomentCategory.ANIMALS -> "Animals"
    }

private val MomentCategory.icon: ImageVector?
    get() = when (this) {
        MomentCategory.ALL -> null
        MomentCategory.PEOPLE -> Icons.Filled.Person
        MomentCategory.VEHICLES -> Icons.Filled.DirectionsCar
        MomentCategory.ANIMALS -> Icons.Filled.Pets
    }

/** The "Moments" tab: Frigate's detections, newest first, filterable, each expandable to play its clip. */
@Composable
fun MomentsTabContent() {
    val viewModel: MomentsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MomentCategory.entries.forEach { category ->
                FilterChip(
                    category = category,
                    selected = category == state.selectedCategory,
                    onClick = { viewModel.selectCategory(category) },
                )
            }
        }

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        if (state.groups.isEmpty()) {
            EmptyMoments(category = state.selectedCategory, hasError = state.error != null)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = bottomNavClearance()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                state.groups.forEach { group ->
                    item(key = "header-${group.dateGroup}-${group.dateSubLabel}") {
                        MomentDateHeader(dateGroup = group.dateGroup, dateSubLabel = group.dateSubLabel)
                    }
                    items(group.items, key = { it.event.id }, contentType = { "moment-card" }) { item ->
                        val expanded = state.expandedEventId == item.event.id
                        val isDownloading = downloadState.downloadingEventId == item.event.id
                        val downloadSucceeded = downloadState.resultEventId == item.event.id && downloadState.resultError == null
                        val downloadErrorMessage = downloadState.resultError.takeIf { downloadState.resultEventId == item.event.id }
                        MomentCard(
                            item = item,
                            expanded = expanded,
                            clipRequest = if (expanded) state.clipRequest else null,
                            clipPosterUrl = if (expanded) state.clipPosterUrl else null,
                            clipError = if (expanded) state.clipError else null,
                            clipBuffering = expanded && state.clipBuffering,
                            isDownloading = isDownloading,
                            downloadSucceeded = downloadSucceeded,
                            downloadErrorMessage = downloadErrorMessage,
                            onClick = { viewModel.toggleExpanded(item.event) },
                            onClipBuffering = viewModel::onClipBuffering,
                            onClipError = viewModel::onClipPlaybackError,
                            onDownloadClick = { viewModel.downloadClip(item.event) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMoments(category: MomentCategory, hasError: Boolean) {
    // Frigate only produces events for the object labels it's configured to track. This server
    // tracks people only, so the Vehicles and Animals chips are empty by construction until
    // `objects.track` is widened — say so, rather than looking broken.
    val message = when {
        hasError -> "Couldn't reach the server for detections."
        category == MomentCategory.VEHICLES || category == MomentCategory.ANIMALS ->
            "Frigate is only watching for people right now, so there are no ${category.label.lowercase()} to show."
        else -> "No detections yet. Frigate is watching for people and will list them here."
    }
    Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomNavClearance()), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun FilterChip(category: MomentCategory, selected: Boolean, onClick: () -> Unit) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), CircleShape)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = category.icon
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.width(18.dp).aspectRatio(1f),
            )
        }
        Text(text = category.label, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}

@Composable
private fun MomentDateHeader(dateGroup: String, dateSubLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = dateGroup, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = dateSubLabel.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MomentCard(
    item: MomentItem,
    expanded: Boolean,
    clipRequest: PlayerRequest?,
    clipPosterUrl: String?,
    clipError: String?,
    clipBuffering: Boolean,
    isDownloading: Boolean,
    downloadSucceeded: Boolean,
    downloadErrorMessage: String?,
    onClick: () -> Unit,
    onClipBuffering: (Boolean) -> Unit,
    onClipError: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    val extraColors = LocalFrigateExtraColors.current
    val event = item.event
    val p = item.presentation
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                MomentThumbnail(url = item.thumbnailUrl)
                if (event.hasClip) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play clip",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp).background(extraColors.glassFill, CircleShape).padding(4.dp),
                    )
                    DownloadButton(
                        isDownloading = isDownloading,
                        succeeded = downloadSucceeded,
                        errorMessage = downloadErrorMessage,
                        onClick = onDownloadClick,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                if (p.durationLabel != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(extraColors.glassFill, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(12.dp).aspectRatio(1f),
                        )
                        Text(text = p.durationLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                } else if (event.isInProgress) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(extraColors.glassFill, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulsingDot(color = MaterialTheme.colorScheme.error, size = 6.dp, pulsing = true)
                        Text(text = "LIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = p.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = p.timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = event.cameraDisplayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = p.badgeLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The clip opens beneath the card rather than navigating away, so the feed stays in place.
        AnimatedVisibility(visible = expanded) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                // Error is checked before the player: an error that lands after the request was set
                // must win, or it would be masked behind a player that never paints a frame.
                when {
                    clipError != null -> Text(clipError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    clipRequest != null -> {
                        // Keyed by event so collapsing and reopening, or scrolling the card away and
                        // back, rebinds to the same player (paused where it was) instead of reloading.
                        CameraStreamPlayer(
                            request = clipRequest,
                            modifier = Modifier.fillMaxSize(),
                            playerKey = "event:${event.id}",
                            onPositionChanged = {},
                            onBufferingChanged = onClipBuffering,
                            onPlaybackEnded = {},
                            onPlaybackError = onClipError,
                        )
                        // Drawn after the player so it sits on top of the poster, which stays until the first frame.
                        if (clipBuffering) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                    else -> {
                        // The clip URL is still being resolved, but the frame at the event's start is
                        // already known — show it now so the box never opens empty.
                        if (clipPosterUrl != null) {
                            AsyncImage(
                                model = clipPosterUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

/** A small overlay button on the thumbnail: idle download icon, a spinner while in flight, then a brief check or error flash. */
@Composable
private fun DownloadButton(
    isDownloading: Boolean,
    succeeded: Boolean,
    errorMessage: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extraColors = LocalFrigateExtraColors.current
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(extraColors.glassFill)
            .clickable(enabled = !isDownloading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isDownloading -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            succeeded -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
            errorMessage != null -> Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = "Download failed: $errorMessage",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            else -> Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Download clip",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Coil goes through the app's shared Ktor client (see App.kt), which carries Frigate's session
 * cookie — that is what lets this hit the authenticated thumbnail endpoint.
 */
@Composable
private fun MomentThumbnail(url: String?) {
    if (url == null) {
        Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.width(32.dp).aspectRatio(1f),
        )
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}
