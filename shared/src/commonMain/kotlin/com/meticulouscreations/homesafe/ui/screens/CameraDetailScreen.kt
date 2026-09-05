package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingHistoryUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingStreamUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMomentsUseCase
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.components.RecordingTimeline
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.ui.formatDuration
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.CameraDetailUiState
import com.meticulouscreations.homesafe.viewmodel.CameraDetailViewModel
import com.meticulouscreations.homesafe.viewmodel.PlaybackUiState
import com.meticulouscreations.homesafe.viewmodel.TimelineSpan
import kotlinx.coroutines.delay

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CameraDetailScreen(
    cameraName: String,
    observeCamerasUseCase: ObserveCamerasUseCase,
    connectionRepository: ConnectionRepository,
    getRecordingHistoryUseCase: GetRecordingHistoryUseCase,
    getRecordingStreamUseCase: GetRecordingStreamUseCase,
    observeMomentsUseCase: ObserveMomentsUseCase,
    sharedTransitionScope: SharedTransitionScope,
    onBack: () -> Unit,
    onEditDetectionZones: () -> Unit,
) {
    val viewModel = viewModel(key = cameraName) {
        CameraDetailViewModel(
            cameraName = cameraName,
            observeCamerasUseCase = observeCamerasUseCase,
            connectionRepository = connectionRepository,
            getRecordingHistoryUseCase = getRecordingHistoryUseCase,
            getRecordingStreamUseCase = getRecordingStreamUseCase,
            observeMomentsUseCase = observeMomentsUseCase,
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val recentMoments by viewModel.recentMoments.collectAsStateWithLifecycle()
    val activeConnection by connectionRepository.activeConnection.collectAsStateWithLifecycle()
    val cameraAvailable = (uiState as? CameraDetailUiState.Found)?.streamUrl != null
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // This header replaces the shell's top bar (the shell hides it while a nested screen is
        // up), so it steps in from the status bar itself.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = cameraDisplayName(cameraName),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
            )
            // The shell bar's route badge carries over so the user still knows whether video is
            // on the direct local path or Tailscale; a spacer keeps the title centred until known.
            val route = activeConnection?.route
            if (route == null) {
                Spacer(modifier = Modifier.size(48.dp))
            } else {
                ConnectionRouteBadge(route)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            PlayerSurface(
                playback = playback,
                cameraAvailable = cameraAvailable,
                viewModel = viewModel,
                playerKey = cameraName,
                modifier = with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = cameraVideoSharedKey(cameraName)),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-32).dp)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickActionButton(icon = Icons.AutoMirrored.Filled.VolumeOff, onClick = {})
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .clickable {},
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Talk",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
                QuickActionButton(icon = Icons.Filled.NotificationsActive, onClick = {})
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = bottomNavClearance()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TimelineSection(playback = playback, viewModel = viewModel)

                DetectionZonesCard(onClick = onEditDetectionZones)

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "Recent Activity",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (recentMoments.isEmpty()) {
                        Text(
                            text = "No detections on this camera yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            recentMoments.forEach { RecentMomentCard(it) }
                        }
                    }
                }
            }
        }
    }
}

/** The video, plus its overlays: the LIVE pill, play/pause, buffering, and the "behind live" readout. */
@Composable
private fun PlayerSurface(
    playback: PlaybackUiState,
    cameraAvailable: Boolean,
    viewModel: CameraDetailViewModel,
    playerKey: String,
    modifier: Modifier = Modifier,
) {
    val request = playback.playerRequest
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        if (request != null) {
            // Same playerKey as the grid card: this binds to the player the card was already
            // running, so live video is on screen before the shared-element transition ends.
            CameraStreamPlayer(
                request = request,
                modifier = Modifier.fillMaxSize(),
                playerKey = playerKey,
                onPositionChanged = viewModel::onPlayerPositionChanged,
                onBufferingChanged = viewModel::onBufferingChanged,
                onPlaybackEnded = viewModel::onPlaybackEnded,
                onPlaybackError = viewModel::onPlaybackError,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.VideocamOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.Center).size(56.dp),
            )
        }

        // While the finger is on the timeline, or the player is on its way to a seek target, a
        // real frame of that moment sits over the video — YouTube-style scrub previews, and no
        // pre-seek frame lingering while the new position buffers.
        val previewEpoch = playback.scrubEpochSeconds ?: playback.seekPreviewEpochSeconds
        if (previewEpoch != null) {
            SeekPreview(epochSeconds = previewEpoch, snapshotUrlFor = viewModel::recordingSnapshotUrl, modifier = Modifier.fillMaxSize())
        }

        if (request != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = interactionSource, indication = null, onClick = viewModel::togglePlayPause),
            )
        }

        when {
            playback.isLoadingPlaylist || playback.isBuffering -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(40.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            request != null && !playback.isPlaying -> Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        if (cameraAvailable) {
            LivePill(
                isLive = playback.isLive,
                onClick = viewModel::goLive,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
        }

        val playhead = playback.scrubEpochSeconds ?: playback.playheadEpochSeconds
        if (playhead != null) {
            BehindLiveReadout(
                playheadEpochSeconds = playhead,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 40.dp),
            )
        }
    }
}

/**
 * The recording frame for [epochSeconds], debounced so a drag asks Frigate for a frame only once
 * the finger has paused for a beat (each frame is an ffmpeg extraction server-side), and layered
 * over the previous frame so a still-loading one never flashes the video through.
 */
@Composable
private fun SeekPreview(epochSeconds: Double, snapshotUrlFor: (Double) -> String?, modifier: Modifier = Modifier) {
    var settledUrl by remember { mutableStateOf<String?>(null) }
    var shownUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(epochSeconds) {
        // The first moment shows immediately (a tap, or the seek target); only successive drag
        // positions wait for the finger to pause.
        if (settledUrl != null) delay(SCRUB_PREVIEW_DEBOUNCE_MS)
        settledUrl = snapshotUrlFor(epochSeconds)
    }
    Box(modifier) {
        shownUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        settledUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { shownUrl = url },
            )
        }
    }
}

private const val SCRUB_PREVIEW_DEBOUNCE_MS = 150L

/** Red and pulsing at the live edge; grey (and a button back to live) while watching history. */
@Composable
private fun LivePill(isLive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dotColor = if (isLive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
            .clickable(enabled = !isLive, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLive) {
            PulsingDot(color = dotColor, size = 8.dp)
        } else {
            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        }
        Text(
            text = if (isLive) "LIVE" else "GO LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = if (isLive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BehindLiveReadout(playheadEpochSeconds: Double, viewModel: CameraDetailViewModel, modifier: Modifier = Modifier) {
    val now by viewModel.nowEpochSeconds.collectAsStateWithLifecycle()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LocalFrigateExtraColors.current.glassFill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClockTime(playheadEpochSeconds, withSeconds = true),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "-${formatDuration(now - playheadEpochSeconds)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineSection(playback: PlaybackUiState, viewModel: CameraDetailViewModel) {
    val now by viewModel.nowEpochSeconds.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Timeline",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimelineSpan.entries.forEach { span ->
                    SpanChip(span = span, selected = span == playback.span, onClick = { viewModel.setSpan(span) })
                }
            }
        }

        RecordingTimeline(
            segments = playback.segments,
            span = playback.span,
            nowEpochSeconds = now,
            playheadEpochSeconds = playback.playheadEpochSeconds,
            scrubEpochSeconds = playback.scrubEpochSeconds,
            isLive = playback.isLive,
            onScrubStart = viewModel::onScrubStart,
            onScrub = viewModel::onScrub,
            onScrubEnd = viewModel::onScrubEnd,
            onSeek = viewModel::seekTo,
        )

        val hint = when {
            playback.historyError != null && playback.segments.isEmpty() -> "Couldn't load recordings: ${playback.historyError}"
            playback.segments.isEmpty() -> "No recordings in this window yet"
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpanChip(span: TimelineSpan, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = span.label,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        modifier = Modifier
            .clip(CircleShape)
            .background(background, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (selected) 0f else 0.2f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun QuickActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Entry point to the polygon editor: what Google Home calls activity zones, Frigate calls masks. */
@Composable
private fun DetectionZonesCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CropFree,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "Detection zones", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "Mask out areas you don't want detected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentMomentCard(item: MomentItem) {
    val p = item.presentation
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            // Coil rides the app's authenticated Ktor client, which is what lets this hit Frigate's thumbnail endpoint.
            if (item.thumbnailUrl != null) {
                AsyncImage(model = item.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(imageVector = Icons.Filled.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = p.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(text = p.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = listOfNotNull(p.dateGroup, p.durationLabel?.let { "$it clip" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
