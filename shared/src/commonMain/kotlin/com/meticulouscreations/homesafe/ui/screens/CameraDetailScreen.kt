package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingHistoryUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetRecordingStreamUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
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

@Composable
fun CameraDetailScreen(
    cameraName: String,
    observeCamerasUseCase: ObserveCamerasUseCase,
    connectionRepository: ConnectionRepository,
    getRecordingHistoryUseCase: GetRecordingHistoryUseCase,
    getRecordingStreamUseCase: GetRecordingStreamUseCase,
    onBack: () -> Unit,
) {
    val viewModel = viewModel {
        CameraDetailViewModel(
            cameraName = cameraName,
            observeCamerasUseCase = observeCamerasUseCase,
            connectionRepository = connectionRepository,
            getRecordingHistoryUseCase = getRecordingHistoryUseCase,
            getRecordingStreamUseCase = getRecordingStreamUseCase,
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val cameraAvailable = (uiState as? CameraDetailUiState.Found)?.streamUrl != null

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                text = cameraName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Filled.Sensors,
                    contentDescription = "Status",
                    tint = MaterialTheme.colorScheme.primary,
                )
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
                    .padding(top = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TimelineSection(playback = playback, viewModel = viewModel)

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "Recent Activity",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PackageEventCard()
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ActivityCard(
                                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                                label = "Person",
                                labelColor = MaterialTheme.colorScheme.secondaryContainer,
                                value = "6:15 PM",
                                modifier = Modifier.weight(1f),
                            )
                            ActivityCard(
                                icon = Icons.Filled.Pets,
                                label = "Animal",
                                labelColor = MaterialTheme.colorScheme.outline,
                                value = "3:20 PM",
                                modifier = Modifier.weight(1f),
                            )
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
) {
    val request = playback.playerRequest
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        if (request != null) {
            CameraStreamPlayer(
                request = request,
                modifier = Modifier.fillMaxSize(),
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

@Composable
private fun PackageEventCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.LocalShipping,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalShipping,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Package",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
                Text(
                    text = "8:42 PM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Package delivered to porch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ActivityCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    labelColor: Color,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
