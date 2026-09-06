package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.PinchZoomState
import com.meticulouscreations.homesafe.ui.components.pinchZoomContent
import com.meticulouscreations.homesafe.ui.components.pinchZoomGestures
import com.meticulouscreations.homesafe.ui.components.rememberPinchZoomState
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.components.RecordingTimeline
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.ui.formatDuration
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.CameraDetailUiState
import com.meticulouscreations.homesafe.viewmodel.CameraDetailViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import com.meticulouscreations.homesafe.viewmodel.TimelineSpan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CameraDetailScreen(
    cameraName: String,
    warmStreamUrl: String?,
    warmPosterUrl: String?,
    sharedTransitionScope: SharedTransitionScope,
    onBack: () -> Unit,
    onEditDetectionZones: () -> Unit,
) {
    val viewModel = assistedMetroViewModel<CameraDetailViewModel, CameraDetailViewModel.Factory>(key = cameraName) {
        create(cameraName)
    }
    // Deliberately *not* collected here: `viewModel.playback`, which changes four times a
    // second while a recording plays (position polls) and on every pixel of a timeline drag.
    // Each piece of UI that needs it collects it itself (PlayerSurface, QuickActionsRow,
    // TimelineSection), so those updates recompose three small scopes rather than this whole
    // screen — header, recent-activity cards and all.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recentMoments by viewModel.recentMoments.collectAsStateWithLifecycle()
    val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()
    val cameraAvailable = (uiState as? CameraDetailUiState.Found)?.streamUrl != null
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // How tall the scrolling area is — what the player grows into while zoomed. Held as state
    // rather than read here so that only the player's layout, not this screen, depends on it.
    val scrollViewportHeight = remember { mutableIntStateOf(0) }
    // 0 = the player is its 16:9 strip, 1 = it has taken over the viewport (see PlayerSurface).
    val zoomTakeover = remember { Animatable(0f) }

    // One line of feedback under the quick actions — what a tap did, or why it couldn't — that
    // clears itself. The words are kept separately so the fade-out still has something to fade.
    var hint by remember { mutableStateOf<QuickActionHint?>(null) }
    var hintText by remember { mutableStateOf("") }
    val showHint: (String) -> Unit = { text ->
        hintText = text
        hint = QuickActionHint(text)
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(QUICK_ACTION_HINT_MS)
            hint = null
        }
    }

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
                .onSizeChanged { scrollViewportHeight.intValue = it.height }
                .verticalScroll(scrollState),
        ) {
            PlayerSurface(
                cameraAvailable = cameraAvailable,
                viewModel = viewModel,
                playerKey = cameraName,
                warmStreamUrl = warmStreamUrl,
                warmPosterUrl = warmPosterUrl,
                viewportHeight = scrollViewportHeight,
                // The scroll runs under the floating nav bar; the zoomed video is centred above it.
                bottomInsetPx = with(LocalDensity.current) { bottomNavClearance().roundToPx() },
                takeover = zoomTakeover,
                // The zoomed player fills the viewport only from the top of the scroll.
                onZoomStarted = { scope.launch { scrollState.animateScrollTo(0) } },
                modifier = with(sharedTransitionScope) {
                    // The video is the one thing that moves between here and the card (this
                    // screen only fades — see SharedElementPush / SharedElementPop); its corners
                    // round off on the way to the card and square up on the way here.
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = cameraVideoSharedKey(cameraName)),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = CameraVideoBoundsTransform,
                        clipInOverlayDuringTransition = rememberCameraVideoOverlayClip(
                            animatedVisibilityScope = animatedVisibilityScope,
                            visibleRadius = 0.dp,
                            hiddenRadius = CAMERA_CARD_CORNER_RADIUS,
                        ),
                    )
                },
            )

            QuickActionsRow(
                viewModel = viewModel,
                cameraName = cameraName,
                showHint = showHint,
                // The row straddles the player's bottom edge, so while the player has the
                // viewport its tops would peek out under the nav bar: fade it with the takeover.
                modifier = Modifier.graphicsLayer { alpha = 1f - zoomTakeover.value },
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = bottomNavClearance()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AnimatedVisibility(visible = hint != null) {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                TimelineSection(viewModel = viewModel)

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
                            recentMoments.forEach { item ->
                                // Tapping a detection plays it in the player above, from its start.
                                RecentMomentCard(
                                    item = item,
                                    onClick = {
                                        viewModel.playMoment(item.event.startEpochSeconds)
                                        scope.launch { scrollState.animateScrollTo(0) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The video, plus its overlays: the LIVE pill, play/pause, buffering, and the "behind live" readout.
 *
 * At rest this is a 16:9 strip at the top of the scroll. Zooming in takes over the whole scroll
 * viewport ([viewportHeight]): the surface grows downward until it fills the screen, pushing the
 * rest of the page out of view, and the video sits centred in the part above the floating nav
 * bar ([bottomInsetPx]), so the enlarged picture has the full height of a portrait screen to
 * spread into rather than being clipped to its own strip. Zooming back out gives the space back.
 * On a landscape screen the strip is already taller than the viewport, so nothing moves.
 */
@Composable
private fun PlayerSurface(
    cameraAvailable: Boolean,
    viewModel: CameraDetailViewModel,
    playerKey: String,
    warmStreamUrl: String?,
    warmPosterUrl: String?,
    viewportHeight: IntState,
    bottomInsetPx: Int,
    takeover: Animatable<Float, *>,
    onZoomStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val request = playback.playerRequest
    val zoom = rememberPinchZoomState()
    val scope = rememberCoroutineScope()

    // [takeover]: 0 = the 16:9 strip, 1 = the full viewport. Driven from a snapshotFlow so the
    // pinch's per-frame scale changes never recompose this surface; only the zoomed flip does.
    LaunchedEffect(zoom) {
        snapshotFlow { zoom.isZoomed }.collectLatest { zoomed ->
            if (zoomed) onZoomStarted()
            takeover.animateTo(if (zoomed) 1f else 0f, tween(ZOOM_TAKEOVER_MS))
        }
    }

    // Two boxes grow together: the outer one is the backdrop and runs the full viewport, under
    // the nav bar, so nothing below shows through; the inner one is the zoom viewport — what
    // receives the pinch, clips the picture and centres it — and stops above the nav bar.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .zoomTakeoverHeight(takeover = { takeover.value }, viewportHeight = viewportHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zoomTakeoverHeight(takeover = { takeover.value }, viewportHeight = viewportHeight, bottomInsetPx = bottomInsetPx)
                .clipToBounds()
                .pinchZoomGestures(zoom),
        ) {
            // The video and its scrub preview zoom together; the pills and buttons over them don't.
            // The video keeps its 16:9 shape (both players stretch to fill) and stays centred, so
            // while the surface is taller than the strip it is letterboxed until zoomed past that.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .align(Alignment.Center)
                    .pinchZoomContent(zoom),
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
                        onAudioAvailabilityChanged = viewModel::onAudioAvailabilityChanged,
                    )
                } else if (warmStreamUrl != null) {
                    // The view model hasn't chosen a stream yet (its first camera read is still in
                    // flight): keep showing what the card was playing, on the same pooled player.
                    // The view model's first request is that very stream, so nothing reloads.
                    CameraStreamPlayer(
                        streamUrl = warmStreamUrl,
                        modifier = Modifier.fillMaxSize(),
                        posterUrl = warmPosterUrl,
                        playerKey = playerKey,
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
            }

            if (request != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(zoom) {
                            detectTapGestures(
                                onTap = { viewModel.togglePlayPause() },
                                onDoubleTap = { tapAt ->
                                    scope.launch {
                                        if (zoom.isZoomed) zoom.animateReset() else zoom.animateZoomTo(PinchZoomState.DOUBLE_TAP_SCALE, tapAt)
                                    }
                                },
                            )
                        },
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
}

/**
 * Speaker, talk and alerts under the player. Collects `playback` and `alerts` itself so the
 * position polls that update `playback` while a recording plays recompose only this row.
 */
@Composable
private fun QuickActionsRow(
    viewModel: CameraDetailViewModel,
    cameraName: String,
    showHint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .offset(y = (-32).dp)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val displayName = cameraDisplayName(cameraName)
        // Speaker: the player's own audio, once it has some — go2rtc's grid sub-streams
        // and Frigate's recordings are video-only, so a tap explains rather than silently failing.
        QuickActionButton(
            icon = if (playback.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (playback.isMuted) "Unmute" else "Mute",
            active = !playback.isMuted,
            available = playback.hasAudio,
            onClick = {
                if (playback.hasAudio) viewModel.toggleMuted() else showHint("This stream has no audio to play")
            },
        )
        // Two-way talk needs a WebRTC backchannel go2rtc doesn't have for these cameras
        // yet; the button stays where the design puts it, dimmed, and says so when tapped.
        Box(
            modifier = Modifier
                .size(80.dp)
                .alpha(UNAVAILABLE_ALPHA)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .clickable { showHint("Two-way talk isn't available for this camera yet") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Talk (not available)",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        // Bell: this camera's alerts, the same rules the Settings tab edits place by place.
        QuickActionButton(
            icon = if (alerts.enabled) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
            contentDescription = if (alerts.enabled) "Turn off alerts for $displayName" else "Turn on alerts for $displayName",
            active = alerts.enabled,
            onClick = {
                val enabled = !alerts.enabled
                viewModel.setAlertsEnabled(enabled)
                showHint(
                    when {
                        !enabled -> "Alerts off for $displayName"
                        !alerts.pushNotificationsEnabled -> "Alerts on for $displayName. Notifications are off in Settings."
                        else -> "Alerts on for $displayName"
                    },
                )
            },
        )
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
    // FillBounds, like the video underneath (see CameraStreamPlayer), so the preview lands exactly over it.
    Box(modifier) {
        shownUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
        settledUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { shownUrl = url },
            )
        }
    }
}

private const val SCRUB_PREVIEW_DEBOUNCE_MS = 150L

private const val QUICK_ACTION_HINT_MS = 3_000L
private const val ZOOM_TAKEOVER_MS = 300

/**
 * Sizes the player surface between its 16:9 strip ([takeover] = 0) and the scroll viewport's
 * height less [bottomInsetPx] ([takeover] = 1), never shorter than the strip. Both inputs are read
 * during layout, so the takeover animation and a viewport resize re-measure this node without
 * recomposing anything.
 */
private fun Modifier.zoomTakeoverHeight(takeover: () -> Float, viewportHeight: IntState, bottomInsetPx: Int = 0): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val strip = (width * 9f / 16f).roundToInt()
    val expanded = max(strip, viewportHeight.intValue - bottomInsetPx)
    val height = lerp(strip, expanded, takeover())
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

/** Material's disabled-content alpha, for a control that's present but can't act yet. */
private const val UNAVAILABLE_ALPHA = 0.38f

/** A fresh instance per tap (identity equality), so repeating the same words restarts the auto-clear. */
private class QuickActionHint(val text: String)

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
private fun TimelineSection(viewModel: CameraDetailViewModel) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
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

/**
 * A round secondary action under the player. [active] fills it (a toggle that's on);
 * [available] false dims it but keeps it tappable, so the tap can say why it did nothing.
 */
@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    available: Boolean = true,
) {
    val background = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    val tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(64.dp)
            .alpha(if (available) 1f else UNAVAILABLE_ALPHA)
            .clip(CircleShape)
            .background(background, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (active) 0f else 0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
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
                text = "Name areas like the driveway, and choose what to ignore",
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
private fun RecentMomentCard(item: MomentItem, onClick: () -> Unit) {
    val p = item.presentation
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
