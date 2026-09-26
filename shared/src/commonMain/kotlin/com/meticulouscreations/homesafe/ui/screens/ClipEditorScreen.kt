package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.ClipLimits
import com.meticulouscreations.homesafe.domain.model.ClipRange
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.ClipTrimmer
import com.meticulouscreations.homesafe.ui.components.ClipTrimmerStripHeight
import com.meticulouscreations.homesafe.ui.components.ClipTrimmerStripTop
import com.meticulouscreations.homesafe.ui.components.ImmersiveSystemBars
import com.meticulouscreations.homesafe.ui.components.clipMomentColor
import com.meticulouscreations.homesafe.ui.components.splashReveal
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.ui.formatDuration
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.ClipEditorUiState
import com.meticulouscreations.homesafe.viewmodel.ClipEditorViewModel
import com.meticulouscreations.homesafe.viewmodel.ClipMoment
import com.meticulouscreations.homesafe.viewmodel.ClipSaveState
import com.meticulouscreations.homesafe.viewmodel.TrimHandle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.round

/**
 * The clip editor, full screen, after Google Photos' video editor: the picture up top on black,
 * the selection playing on a loop; under it the running time, a play button beside a filmstrip
 * trimmed with two grips; then one-tap lengths and the detections in reach, each of which snaps
 * the selection to exactly that event. Save sits top right and morphs through saving to saved.
 *
 * It arrives as a splash: [splashOrigin] (a fraction of the window — where the scissors were) is
 * where the water lands, and it spreads until the editor covers everything, refracting the camera
 * page it is covering as it goes. The splash's progress *is* this entry's navigation transition
 * (see [SplashPush] / [SplashPop]), so leaving drains it back into the scissors, and predictive
 * back scrubs the drain under the finger. The chrome rises in over the last part of the splash, so
 * the water lands on a calm picture and the controls settle after it.
 *
 * Once the splash has landed the system bars are hidden (swipe from an edge to peek at them); not
 * before, so the page being covered doesn't jump up under the water as the status bar goes.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ClipEditorScreen(
    cameraName: String,
    anchorEpochSeconds: Double,
    splashOrigin: Offset,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navTransition = LocalNavAnimatedContentScope.current.transition
    val splash = navTransition.animateFloat(
        transitionSpec = {
            tween(durationMillis = if (targetState == EnterExitState.Visible) SPLASH_IN_MS else SPLASH_OUT_MS, easing = LinearEasing)
        },
        label = "clip-editor-splash",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    val landed = navTransition.currentState == EnterExitState.Visible && navTransition.targetState == EnterExitState.Visible
    if (landed) ImmersiveSystemBars()

    // The editor's view model lives exactly as long as this screen: its own store, cleared on the
    // way out, so a closed editor stops watching the camera's moments and the next one starts
    // fresh. (The activity-wide store would keep one per visit alive for the life of the app.)
    val store = rememberScreenViewModelStore()
    CompositionLocalProvider(LocalViewModelStoreOwner provides store) {
        val viewModel = assistedMetroViewModel<ClipEditorViewModel, ClipEditorViewModel.Factory>(key = "clip-editor:$cameraName") {
            create(cameraName, anchorEpochSeconds)
        }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        // Once per editor, not per position tick: the controls below skip recomposing on it.
        val trimmer = remember(viewModel) {
            TrimmerActions(
                onTrimStart = viewModel::beginTrim,
                onTrim = viewModel::trimTo,
                onTrimEnd = viewModel::endTrim,
                onScrubStart = viewModel::beginScrub,
                onScrub = viewModel::scrubTo,
                onScrubEnd = viewModel::endScrub,
                onTap = viewModel::tapAt,
                onPan = viewModel::panWindow,
                onZoom = viewModel::zoomWindow,
            )
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                // First, so the water carries everything below it — the black included.
                .splashReveal(progress = { splash.value }, originFraction = splashOrigin)
                .background(EditorBackground),
        ) {
            ClipEditorContent(
                cameraName = cameraName,
                state = state,
                splashProgress = { splash.value },
                onClose = onClose,
                onSave = viewModel::save,
                onRetryLoad = viewModel::retry,
                onDismissSaveError = viewModel::dismissSaveError,
                onTogglePlay = viewModel::togglePlayPause,
                onPlayerPosition = viewModel::onPlayerPositionChanged,
                onBuffering = viewModel::onBufferingChanged,
                onPlaybackEnd = viewModel::onPlaybackEnded,
                onPlaybackError = viewModel::onPlaybackError,
                snapshotUrl = viewModel::snapshotUrl,
                trimmer = trimmer,
                onSelectMoment = viewModel::selectMoment,
                onSetLength = viewModel::setLength,
            )
        }
    }
}

/** A [ViewModelStoreOwner] for one screen's time in the composition, cleared when it leaves. */
@Composable
private fun rememberScreenViewModelStore(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}

/** Everything the filmstrip can ask of the editor, bundled so the layout below doesn't thread nine lambdas. */
private class TrimmerActions(
    val onTrimStart: (TrimHandle) -> Unit,
    val onTrim: (TrimHandle, Double) -> Boolean,
    val onTrimEnd: () -> Unit,
    val onScrubStart: () -> Unit,
    val onScrub: (Double) -> Unit,
    val onScrubEnd: () -> Unit,
    val onTap: (Double) -> Boolean,
    val onPan: (Double) -> Unit,
    val onZoom: (Double, Double) -> Unit,
)

@Composable
private fun ClipEditorContent(
    cameraName: String,
    state: ClipEditorUiState,
    splashProgress: () -> Float,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onRetryLoad: () -> Unit,
    onDismissSaveError: () -> Unit,
    onTogglePlay: () -> Unit,
    onPlayerPosition: (Long) -> Unit,
    onBuffering: (Boolean) -> Unit,
    onPlaybackEnd: () -> Unit,
    onPlaybackError: () -> Unit,
    snapshotUrl: (Double, Int) -> String?,
    trimmer: TrimmerActions,
    onSelectMoment: (ClipMoment) -> Unit,
    onSetLength: (Double) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.save) {
        when (state.save) {
            ClipSaveState.Saved -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            is ClipSaveState.Failed -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                title = cameraDisplayName(cameraName),
                range = state.range,
                save = state.save,
                canSave = state.isReady,
                onClose = onClose,
                onSave = onSave,
                modifier = Modifier.chromeRise(splashProgress, fromTop = true),
            )

            PreviewStage(
                state = state,
                onTogglePlay = onTogglePlay,
                onRetryLoad = onRetryLoad,
                onPlayerPosition = onPlayerPosition,
                onBuffering = onBuffering,
                onPlaybackEnd = onPlaybackEnd,
                onPlaybackError = onPlaybackError,
                snapshotUrl = snapshotUrl,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            ControlsPanel(
                state = state,
                onTogglePlay = onTogglePlay,
                snapshotUrl = snapshotUrl,
                trimmer = trimmer,
                onSelectMoment = { moment ->
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelectMoment(moment)
                },
                onSetLength = { seconds ->
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSetLength(seconds)
                },
                modifier = Modifier.chromeRise(splashProgress, fromTop = false),
            )
        }

        SaveBanner(
            save = state.save,
            onRetry = onSave,
            onDismiss = onDismissSaveError,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

/**
 * Settles the chrome in over the last part of the splash — faded and a little displaced (down
 * from the top, up from the bottom) until the water is most of the way across — and lifts it
 * away again first thing as the splash drains. Read at draw time, so the splash never recomposes.
 */
private fun Modifier.chromeRise(splashProgress: () -> Float, fromTop: Boolean): Modifier = graphicsLayer {
    val t = ((splashProgress() - CHROME_REVEAL_FROM) / (1f - CHROME_REVEAL_FROM)).coerceIn(0f, 1f)
    val eased = 1f - (1f - t) * (1f - t)
    alpha = eased
    translationY = (1f - eased) * CHROME_RISE.toPx() * if (fromTop) -1f else 1f
}

@Composable
private fun EditorTopBar(
    title: String,
    range: ClipRange?,
    save: ClipSaveState,
    canSave: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Filled.Close, contentDescription = CLOSE_EDITOR_DESCRIPTION, tint = Color.White)
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = range?.let { "${formatClockTime(it.startEpochSeconds, withSeconds = true)} – ${formatClockTime(it.endEpochSeconds, withSeconds = true)}" }
                    ?: "Finding the recording…",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
                color = EditorSecondaryText,
                maxLines = 1,
            )
        }
        SaveButton(save = save, enabled = canSave, onSave = onSave)
    }
}

/**
 * Save, as Google Photos' "Save copy" pill: tap it and the label gives way to a spinner, then a
 * tick. Only a clip that's loaded can be saved; while one is saving, taps are ignored.
 */
@Composable
private fun SaveButton(save: ClipSaveState, enabled: Boolean, onSave: () -> Unit) {
    val phase = when (save) {
        ClipSaveState.Idle, is ClipSaveState.Failed -> SavePhase.READY
        ClipSaveState.Saving -> SavePhase.SAVING
        ClipSaveState.Saved -> SavePhase.SAVED
    }
    val tappable = enabled && phase == SavePhase.READY
    val container = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = tappable, role = Role.Button, onClick = onSave)
            .semantics {
                contentDescription = when (phase) {
                    SavePhase.READY -> "Save clip"
                    SavePhase.SAVING -> "Saving clip"
                    SavePhase.SAVED -> "Clip saved"
                }
            }
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(tween(180, delayMillis = 60)) + scaleIn(tween(220), initialScale = 0.85f))
                    .togetherWith(fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.85f))
            },
            label = "save-phase",
        ) { shown ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (shown) {
                    SavePhase.READY -> Unit
                    SavePhase.SAVING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), color = content, strokeWidth = 2.dp)
                    SavePhase.SAVED -> Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = when (shown) {
                        SavePhase.READY -> "Save"
                        SavePhase.SAVING -> "Saving"
                        SavePhase.SAVED -> "Saved"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                )
            }
        }
    }
}

private enum class SavePhase { READY, SAVING, SAVED }

/**
 * The picture, as large as the space allows at 16:9, on black. A tap plays or pauses. While a grip
 * or the playhead is held (or a seek hasn't landed yet) a real frame of that instant covers the
 * video, so what's on screen is always exactly where the selection starts, ends, or is scrubbed to.
 */
@Composable
private fun PreviewStage(
    state: ClipEditorUiState,
    onTogglePlay: () -> Unit,
    onRetryLoad: () -> Unit,
    onPlayerPosition: (Long) -> Unit,
    onBuffering: (Boolean) -> Unit,
    onPlaybackEnd: () -> Unit,
    onPlaybackError: () -> Unit,
    snapshotUrl: (Double, Int) -> String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PREVIEW_CORNER))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            val request = state.playerRequest
            val error = state.loadError
            when {
                // Nothing to clip, or the recording stopped playing: say why, and offer to start over.
                error != null -> LoadFailed(message = error, onRetry = onRetryLoad, modifier = Modifier.align(Alignment.Center))

                state.isLoading -> Shimmer(modifier = Modifier.fillMaxSize())

                request != null -> {
                    CameraStreamPlayer(
                        request = request,
                        modifier = Modifier.fillMaxSize(),
                        // Its own player: the camera page's pooled one pauses once nothing shows it.
                        playerKey = null,
                        onPositionChanged = onPlayerPosition,
                        onBufferingChanged = onBuffering,
                        onPlaybackEnded = onPlaybackEnd,
                        onPlaybackError = onPlaybackError,
                    )
                    state.previewEpochSeconds?.let { epoch ->
                        FramePreview(epochSeconds = epoch, snapshotUrl = snapshotUrl, modifier = Modifier.fillMaxSize())
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures(onTap = { onTogglePlay() }) },
                    )
                }
            }

            if (state.isReady && error == null) {
                val holding = state.activeHandle != null || state.isScrubbing
                AnimatedVisibility(
                    visible = !state.isPlaying && !holding,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.7f),
                    exit = fadeOut(tween(120)) + scaleOut(tween(150), targetScale = 1.2f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
                if (state.isBuffering && state.isPlaying && !holding) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(36.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
                state.playheadEpochSeconds?.let { playhead ->
                    Text(
                        text = formatClockTime(playhead, withSeconds = true),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(LocalFrigateExtraColors.current.glassFill)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * The recording frame at [epochSeconds], debounced so a drag asks Frigate for a frame only once the
 * finger pauses (each one is an ffmpeg extraction server-side), and layered over the last frame so
 * one still loading never flashes the video through. The camera page's scrub preview, here too.
 */
@Composable
private fun FramePreview(epochSeconds: Double, snapshotUrl: (Double, Int) -> String?, modifier: Modifier = Modifier) {
    var settledUrl by remember { mutableStateOf<String?>(null) }
    var shownUrl by remember { mutableStateOf<String?>(null) }
    val latestSnapshotUrl by rememberUpdatedState(snapshotUrl)
    LaunchedEffect(epochSeconds) {
        if (settledUrl != null) delay(FRAME_PREVIEW_DEBOUNCE_MS)
        settledUrl = latestSnapshotUrl(epochSeconds, PREVIEW_FRAME_HEIGHT)
    }
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

@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = Icons.Filled.ErrorOutline, contentDescription = null, tint = EditorSecondaryText, modifier = Modifier.size(32.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center)
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(text = "Try again", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

/**
 * Under the picture: where in the clip the playhead is and how long the clip runs, the play
 * button beside the filmstrip (Photos keeps it there, by the thumb that's trimming), then the
 * length chips and the detections in reach.
 */
@Composable
private fun ControlsPanel(
    state: ClipEditorUiState,
    onTogglePlay: () -> Unit,
    snapshotUrl: (Double, Int) -> String?,
    trimmer: TrimmerActions,
    onSelectMoment: (ClipMoment) -> Unit,
    onSetLength: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A line of coaching until the viewer has trimmed once; after that they know.
    var coached by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.activeHandle) { if (state.activeHandle != null) coached = true }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClipTimeReadout(range = state.range, playheadEpochSeconds = state.playheadEpochSeconds, modifier = Modifier.fillMaxWidth())

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
            PlayPauseButton(
                isPlaying = state.isPlaying,
                enabled = state.isReady,
                onClick = onTogglePlay,
                // Centred on the strip, not on the bubble lane above it.
                modifier = Modifier.padding(top = ClipTrimmerStripTop + (ClipTrimmerStripHeight - PLAY_BUTTON_SIZE) / 2),
            )
            Spacer(modifier = Modifier.width(12.dp))
            val window = state.window
            val range = state.range
            if (window != null && range != null) {
                ClipTrimmer(
                    window = window,
                    range = range,
                    playheadEpochSeconds = state.playheadEpochSeconds,
                    recorded = state.recorded,
                    activeHandle = state.activeHandle,
                    isScrubbing = state.isScrubbing,
                    thumbnailUrl = { epoch -> snapshotUrl(epoch, FILMSTRIP_FRAME_HEIGHT) },
                    onTrimStart = trimmer.onTrimStart,
                    onTrim = trimmer.onTrim,
                    onTrimEnd = trimmer.onTrimEnd,
                    onScrubStart = trimmer.onScrubStart,
                    onScrub = trimmer.onScrub,
                    onScrubEnd = trimmer.onScrubEnd,
                    onTap = trimmer.onTap,
                    onPan = trimmer.onPan,
                    onZoom = trimmer.onZoom,
                    moments = state.moments,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Shimmer(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = ClipTrimmerStripTop)
                        .height(ClipTrimmerStripHeight)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }

        AnimatedVisibility(
            visible = state.isReady && !coached,
            enter = fadeIn(),
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            Text(
                text = "Drag the ends to trim · pinch the strip for finer steps",
                style = MaterialTheme.typography.labelMedium,
                color = EditorSecondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        state.range?.let { range ->
            LengthChips(range = range, onSetLength = onSetLength)
        }

        AnimatedVisibility(
            visible = state.moments.isNotEmpty(),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
            exit = fadeOut(tween(150)),
        ) {
            MomentChips(moments = state.moments, range = state.range, onSelect = onSelectMoment)
        }
    }
}

/** "0:07 / 0:20": where the playhead is inside the clip, and how long the clip runs. */
@Composable
private fun ClipTimeReadout(range: ClipRange?, playheadEpochSeconds: Double?, modifier: Modifier = Modifier) {
    val tabular = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR_FIGURES)
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (range == null) {
            Text(text = "–:–– / –:––", style = tabular, color = EditorSecondaryText)
        } else {
            val into = ((playheadEpochSeconds ?: range.startEpochSeconds) - range.startEpochSeconds).coerceIn(0.0, range.durationSeconds)
            Text(text = formatDuration(into), style = tabular, color = Color.White)
            Text(text = "  /  ", style = tabular, color = EditorSecondaryText)
            Text(text = formatDuration(round(range.durationSeconds)), style = tabular, color = EditorSecondaryText)
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pressScale by animateFloatAsState(if (enabled) 1f else 0.9f, label = "play-enabled")
    Box(
        modifier = modifier
            .size(PLAY_BUTTON_SIZE)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.6f))
                    .togetherWith(fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.6f))
            },
            label = "play-pause",
        ) { playing ->
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * One-tap lengths. The one the clip already is (to the second) is lit; picking one keeps the
 * clip's start and moves its end, sliding the whole clip back only if it would run past what exists.
 */
@Composable
private fun LengthChips(range: ClipRange, onSetLength: (Double) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Length", style = MaterialTheme.typography.labelLarge, color = EditorSecondaryText, modifier = Modifier.padding(end = 4.dp))
        ClipLimits.PRESET_SECONDS.forEach { seconds ->
            EditorChip(
                selected = abs(range.durationSeconds - seconds) < 0.5,
                onClick = { onSetLength(seconds) },
                contentDescription = "Make the clip ${lengthLabel(seconds)} long",
            ) {
                Text(text = lengthLabel(seconds), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The detections in reach, oldest first, each a chip with its colour, what it was and when. Tapping
 * one makes the clip exactly that event; the chips of every event the clip already holds are lit,
 * so "does this clip have the person in it?" is answered at a glance.
 */
@Composable
private fun MomentChips(moments: List<ClipMoment>, range: ClipRange?, onSelect: (ClipMoment) -> Unit) {
    val extra = LocalFrigateExtraColors.current
    val other = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Detected nearby",
            style = MaterialTheme.typography.labelLarge,
            color = EditorSecondaryText,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(moments, key = { it.id }) { moment ->
                val held = range != null &&
                    moment.startEpochSeconds >= range.startEpochSeconds - HOLD_TOLERANCE_SECONDS &&
                    moment.endEpochSeconds <= range.endEpochSeconds + HOLD_TOLERANCE_SECONDS
                EditorChip(
                    selected = held,
                    onClick = { onSelect(moment) },
                    contentDescription = "Clip ${moment.title} at ${moment.timeLabel}",
                ) {
                    Box(modifier = Modifier.size(8.dp).background(clipMomentColor(extra, moment.category, other), CircleShape))
                    Text(
                        text = moment.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                    Text(text = moment.timeLabel, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** A pill on black: a hairline when off, the accent container when [selected]. */
@Composable
private fun EditorChip(
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.06f)
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
    val border = if (selected) Color.Transparent else Color.White.copy(alpha = 0.16f)
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            content()
        }
    }
}

/**
 * What happened to the save, said once at the foot of the screen: a moment of "Clip saved", or
 * why it failed with a way to try again. The save button says the same thing in its own way.
 */
@Composable
private fun SaveBanner(save: ClipSaveState, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(save) {
        showSaved = save == ClipSaveState.Saved
        if (showSaved) {
            delay(SAVED_BANNER_MS)
            showSaved = false
        }
    }
    val failure = save as? ClipSaveState.Failed
    // What the banner last said, kept past the state that caused it so it still has the same
    // words (and the same look) while it slides away.
    val memory = remember { BannerMemory() }
    if (failure != null) {
        memory.failed = true
        memory.message = failure.message
    } else if (showSaved) {
        memory.failed = false
    }

    AnimatedVisibility(
        visible = showSaved || failure != null,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val failed = memory.failed
            Icon(
                imageVector = if (failed) Icons.Filled.ErrorOutline else Icons.Filled.Check,
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (failed) "Couldn't save: ${memory.message}" else "Clip saved",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (failed) {
                BannerAction(label = "Dismiss", onClick = onDismiss)
                BannerAction(label = "Retry", onClick = onRetry)
            }
        }
    }
}

/**
 * The save banner's last words. A plain holder rather than state: it only changes in the same
 * composition that reads it, so there's nothing to invalidate (the clip trimmer's bubble does the same).
 */
private class BannerMemory(var failed: Boolean = false, var message: String = "")

@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** A soft sweep of light across a placeholder, for the picture and the strip while the recording loads. */
@Composable
private fun Shimmer(modifier: Modifier = Modifier) {
    val sweep = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)),
        label = "shimmer-sweep",
    )
    val base = MaterialTheme.colorScheme.surfaceContainer
    val glow = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier = modifier) {
        val band = size.width * 0.6f
        val x = -band + sweep.value * (size.width + band)
        drawRect(base)
        drawRect(Brush.linearGradient(listOf(base, glow, base), start = Offset(x, 0f), end = Offset(x + band, size.height)))
    }
}

/** "10s", "30s", "1m", "2m". */
internal fun lengthLabel(seconds: Double): String {
    val whole = seconds.toLong()
    return if (whole < 60 || whole % 60 != 0L) "${whole}s" else "${whole / 60}m"
}

/** The close button's description: what tests and screen readers find it by. */
internal const val CLOSE_EDITOR_DESCRIPTION = "Close clip editor"

private val EditorBackground = Color.Black
private val EditorSecondaryText = Color.White.copy(alpha = 0.64f)
private const val TABULAR_FIGURES = "tnum"

/** The chrome starts settling in once the splash is this far across, and is done when it lands. */
private const val CHROME_REVEAL_FROM = 0.55f
private val CHROME_RISE = 24.dp

private val PREVIEW_CORNER = 16.dp
private val PLAY_BUTTON_SIZE = 48.dp

/** Full-width 16:9, like the camera page's player; the filmstrip's frames are small. */
private const val PREVIEW_FRAME_HEIGHT = 720
private const val FILMSTRIP_FRAME_HEIGHT = 120
private const val FRAME_PREVIEW_DEBOUNCE_MS = 150L

/** A detection "is in the clip" if the clip covers it to within this much at either end. */
private const val HOLD_TOLERANCE_SECONDS = 0.5

private const val SAVED_BANNER_MS = 2_500L
private const val SHIMMER_MS = 1_300
