package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.VIDEO_ASPECT
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.ImmersiveSystemBars
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.components.RecordingTimeline
import com.meticulouscreations.homesafe.ui.components.VideoSource
import com.meticulouscreations.homesafe.ui.components.pinchZoomContent
import com.meticulouscreations.homesafe.ui.components.pinchZoomGestures
import com.meticulouscreations.homesafe.ui.components.rememberPinchZoomState
import com.meticulouscreations.homesafe.ui.components.zoomTapGestures
import com.meticulouscreations.homesafe.ui.fitVideo
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.ui.formatDuration
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.ui.windowHeight
import com.meticulouscreations.homesafe.viewmodel.CameraDetailUiState
import com.meticulouscreations.homesafe.viewmodel.CameraDetailViewModel
import com.meticulouscreations.homesafe.viewmodel.LandedPersonViewModel
import com.meticulouscreations.homesafe.viewmodel.MomentCarTagViewModel
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.TimelineSpan
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_behind_live
import homesafe.shared.generated.resources.camera_go_live
import homesafe.shared.generated.resources.camera_hide_controls
import homesafe.shared.generated.resources.camera_live
import homesafe.shared.generated.resources.camera_menu_detection_zones
import homesafe.shared.generated.resources.camera_menu_tag_cars
import homesafe.shared.generated.resources.camera_moment_clip_length
import homesafe.shared.generated.resources.camera_no_recordings
import homesafe.shared.generated.resources.camera_play
import homesafe.shared.generated.resources.camera_recent_activity
import homesafe.shared.generated.resources.camera_recent_empty
import homesafe.shared.generated.resources.camera_recent_loading
import homesafe.shared.generated.resources.camera_show_controls
import homesafe.shared.generated.resources.camera_timeline
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_more_options
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [openAtEpochSeconds] is set when the screen was opened from a detection rather than from the
 * camera grid — the Moments tab's full-screen button — and the player starts at that instant in
 * the recording instead of live.
 *
 * [openedEventId] is that detection, when known. If its car is one the classifier didn't name,
 * the screen offers to tag it, and with [tagCarOnOpen] (a notification's "Tag car" button) opens
 * the picker straight away. If it's a person nobody named, the screen offers "Not a person".
 *
 * [onClip] opens the clip editor around [anchorEpochSeconds] — the frame on screen, or "now" at
 * the live edge — with its splash landing at [originFraction] of the window (the scissors).
 *
 * Turn the phone on its side and the video takes the whole window: the header and the page under
 * the player go, the system bars hide, and the controls that were on the page — back, quality,
 * sound, clip, the timeline — come up over the picture on a tap and fade away again (see
 * [FullScreenPlayerChrome]). It is the same player in the same place in the composition, grown to
 * the window, so the stream never rebinds; turning back upright gives the page back as it was.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CameraDetailScreen(
    cameraName: String,
    warmStreamUrl: String?,
    warmPosterUrl: String?,
    sharedTransitionScope: SharedTransitionScope,
    onBack: () -> Unit,
    onEditDetectionZones: () -> Unit,
    onTagCars: () -> Unit = {},
    onClip: (anchorEpochSeconds: Double, originFraction: Offset) -> Unit = { _, _ -> },
    openAtEpochSeconds: Double? = null,
    openedEventId: String? = null,
    tagCarOnOpen: Boolean = false,
) {
    val viewModel = cameraDetailViewModel(cameraName)
    val tagViewModel: MomentCarTagViewModel = metroViewModel()
    val tagState by tagViewModel.uiState.collectAsStateWithLifecycle()
    val personViewModel: LandedPersonViewModel = metroViewModel()
    val personState by personViewModel.uiState.collectAsStateWithLifecycle()
    // Deliberately *not* collected here: `viewModel.playback`, which changes four times a
    // second while a recording plays (position polls) and on every pixel of a timeline drag.
    // Each piece of UI that needs it collects it itself (PlayerSurface, QuickActionsRow,
    // TimelineSection), so those updates recompose three small scopes rather than this whole
    // screen — header, recent-activity cards and all.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recentMoments by viewModel.recentMoments.collectAsStateWithLifecycle()
    val cameraAvailable = (uiState as? CameraDetailUiState.Found)?.streamUrl != null
    val hasQualityChoice = (uiState as? CameraDetailUiState.Found)?.let { it.gridStreamUrl != null && it.gridStreamUrl != it.streamUrl } ?: false
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fullScreen = isCompactLandscape()
    // Hidden from the moment this screen is the one arriving (or being returned to, from the clip
    // editor), and back as it starts to leave, so the camera list isn't revealed under hidden bars.
    if (fullScreen && animatedVisibilityScope.transition.targetState == EnterExitState.Visible) ImmersiveSystemBars()
    // The full-screen player fills the viewport from the top of the scroll, which is then held there.
    LaunchedEffect(fullScreen) { if (fullScreen) scrollState.scrollTo(0) }
    // In a window much wider than a phone's (a tablet on its side, the desktop) a full-width 16:9
    // player would be the whole page. It stops growing at the width whose 16:9 is
    // PLAYER_MAX_HEIGHT_FRACTION of the window's height, and centres, so the timeline under it
    // is still in sight. On a phone held upright that width is wider than the phone.
    val playerGutter = if (fullScreen) 0.dp else contentGutter(maxWidth = windowHeight() * PLAYER_MAX_HEIGHT_FRACTION * VIDEO_ASPECT, min = 0.dp)
    // How tall the scrolling area is — what the player grows into while zoomed. Held as state
    // rather than read here so that only the player's layout, not this screen, depends on it.
    val scrollViewportHeight = remember { mutableIntStateOf(0) }
    // 0 = the player is its 16:9 strip, 1 = it has taken over the viewport (see PlayerSurface).
    val zoomTakeover = remember { Animatable(0f) }

    // One line of feedback under the quick actions — what a tap did, or why it couldn't — that
    // clears itself. The words are kept separately so the fade-out still has something to fade.
    var hint by remember { mutableStateOf<QuickActionHint?>(null) }
    var hintText by remember { mutableStateOf<StringResource?>(null) }
    val showHint: (StringResource) -> Unit = { text ->
        hintText = text
        hint = QuickActionHint(text)
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(timeMillis = QUICK_ACTION_HINT_MS)
            hint = null
        }
    }

    // Arrived from a detection: seek to it once, on the way in. Keyed by the instant so a
    // recomposition doesn't yank the player back after the viewer has scrubbed away from it.
    LaunchedEffect(openAtEpochSeconds) {
        openAtEpochSeconds?.let(viewModel::playMoment)
    }
    LaunchedEffect(openedEventId) {
        openedEventId?.let {
            tagViewModel.lookUp(it, openPicker = tagCarOnOpen)
            personViewModel.lookUp(it)
        }
    }
    TagCarDialog(
        state = tagState,
        onTag = tagViewModel::tag,
        onNewCarDraftChange = tagViewModel::setNewCarDraft,
        onTagAsNewCar = tagViewModel::tagAsNewCar,
        onRetry = tagViewModel::retry,
        onDismiss = tagViewModel::dismiss,
    )

    Column(modifier = Modifier.fillMaxSize().background(if (fullScreen) Color.Black else MaterialTheme.colorScheme.background)) {
        // This header replaces the shell's top bar (the shell hides it while a nested screen is
        // up), so it steps in from the status bar itself. Full screen, the player's own chrome
        // carries the way back; the scrolling column below is the same one either way, so the
        // player in it stays put in the composition as the header comes and goes.
        if (!fullScreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = nestedHeaderVerticalPadding()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
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
                CameraOverflowMenu(onEditDetectionZones = onEditDetectionZones, onTagCars = onTagCars)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .onSizeChanged { scrollViewportHeight.intValue = it.height }
                .verticalScroll(scrollState, enabled = !fullScreen),
        ) {
            PlayerSurface(
                cameraAvailable = cameraAvailable,
                cameraName = cameraName,
                playerKey = cameraName,
                warmStreamUrl = warmStreamUrl,
                warmPosterUrl = warmPosterUrl,
                viewportHeight = scrollViewportHeight,
                // The scroll runs under the floating nav bar; the zoomed video is centred above it.
                bottomInsetPx = with(LocalDensity.current) { bottomNavClearance().roundToPx() },
                takeover = zoomTakeover,
                // The zoomed player fills the viewport only from the top of the scroll.
                onZoomStarted = { scope.launch { scrollState.animateScrollTo(0) } },
                fullScreen = fullScreen,
                hasQualityChoice = hasQualityChoice,
                hint = hintText.takeIf { hint != null },
                showHint = showHint,
                onBack = onBack,
                onClip = { origin -> onClip(viewModel.clipAnchorEpochSeconds(), origin) },
                // The header's own menu, in white, at the end of the chrome's top row.
                overflowMenu = { onOpenChange ->
                    CameraOverflowMenu(onEditDetectionZones = onEditDetectionZones, onTagCars = onTagCars, tint = Color.White, onOpenChange = onOpenChange)
                },
                modifier = with(sharedTransitionScope) {
                    // The video is the one thing that moves between here and the card (this
                    // screen only fades — see SharedElementPush / SharedElementPop); its corners
                    // round off on the way to the card and square up on the way here.
                    Modifier.padding(horizontal = playerGutter).sharedBounds(
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

            // Full screen there is no page: the player is the viewport, and what the page offered
            // is on the player's chrome.
            if (fullScreen) return@Column

            QuickActionsRow(
                hasQualityChoice = hasQualityChoice,
                cameraName = cameraName,
                showHint = showHint,
                onClip = { origin -> onClip(viewModel.clipAnchorEpochSeconds(), origin) },
                // The row straddles the player's bottom edge, so while the player has the
                // viewport its tops would peek out under the nav bar: fade it with the takeover.
                modifier = Modifier.graphicsLayer { alpha = 1f - zoomTakeover.value },
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = contentGutter())
                    .padding(top = 16.dp, bottom = bottomNavClearance()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AnimatedVisibility(visible = hint != null) {
                    Text(
                        text = hintText?.let { stringResource(it) }.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // The detection this screen was opened on, when its car went unrecognised.
                tagState.landed?.let { landed ->
                    TagCarPrompt(summary = landed.summary, taggedAs = tagState.tagged[landed.eventId], onTag = tagViewModel::openLanded)
                }

                // ... or when its person was one nobody named: was anyone there?
                NotAPersonPrompt(state = personState, onMark = personViewModel::mark, onUndo = personViewModel::undo)

                // Cars in view that a classifier still wants a name for. Polls and recomposes on
                // its own, and takes no room when there's nothing to ask about.
                LiveLabelingSection(cameraName = cameraName)

                TimelineSection(cameraName = cameraName)

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(Res.string.camera_recent_activity),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val moments = recentMoments
                    if (moments.isNullOrEmpty()) {
                        Text(
                            // Null is a question still out to the server, not an answer.
                            text = stringResource(if (moments == null) Res.string.camera_recent_loading else Res.string.camera_recent_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            moments.forEach { item ->
                                // Tapping a detection plays it in the player above, from its start.
                                RecentMomentCard(
                                    item = item,
                                    onClick = {
                                        viewModel.playMoment(item.event.startEpochSeconds)
                                        scope.launch { scrollState.animateScrollTo(0) }
                                    },
                                    onTagCar = { tagViewModel.open(item.event) },
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
 * This screen's view model, resolved from the graph rather than passed down as a parameter.
 *
 * `viewModel()` returns whatever is already in the nav entry's [ViewModelStore] for [key], and
 * only calls the factory when nothing is there — so every composable on this screen that asks
 * for it gets the *same* instance and the camera is polled once, not once per caller. That
 * holds only while they share a ViewModelStoreOwner (they are all inside one nav destination)
 * and pass the same key, which is why the key lives here and not at each call site.
 */
@Composable
internal fun cameraDetailViewModel(cameraName: String): CameraDetailViewModel =
    assistedMetroViewModel<CameraDetailViewModel, CameraDetailViewModel.Factory>(key = cameraName) {
        create(cameraName)
    }

/**
 * The video, plus its overlays: the LIVE pill, play/pause, buffering, and the "behind live" readout.
 *
 * At rest this is a 16:9 strip at the top of the scroll.
 * Zooming in — a pinch, a double tap, or a finger held on the picture, as on a Home card — takes
 * over the whole scroll viewport ([viewportHeight]): the surface grows downward
 * until it fills the screen, pushing the rest of the page out of view, and the video sits centred
 * in the part above the floating nav bar ([bottomInsetPx]), so the enlarged picture has the full
 * height of a portrait screen to spread into rather than being clipped to its own strip. Zooming
 * back out gives the space back.
 *
 * [fullScreen] — the phone on its side — is that same takeover held open, with nothing under it
 * to keep clear of: the surface is the viewport, black to its edges, and the picture is the
 * largest 16:9 that fits (a phone's screen is wider than that, so it stands between two black
 * bars until it is pinched larger). A tap then shows or hides the chrome ([FullScreenPlayerChrome])
 * rather than pausing, as every full-screen player does; pause is a button on the chrome. The
 * chrome stands in for the page the player has covered, so it is handed what the page's quick
 * actions were: [hasQualityChoice], the line of feedback they report into ([hint], null when there
 * is nothing to say; [showHint] to say it), the ways out — [onBack], and [onClip] into the editor —
 * and the header's [overflowMenu], which tells the chrome when it is open.
 */
@Composable
private fun PlayerSurface(
    cameraAvailable: Boolean,
    cameraName: String,
    playerKey: String,
    warmStreamUrl: String?,
    warmPosterUrl: String?,
    viewportHeight: IntState,
    bottomInsetPx: Int,
    takeover: Animatable<Float, *>,
    onZoomStarted: () -> Unit,
    fullScreen: Boolean,
    hasQualityChoice: Boolean,
    hint: StringResource?,
    showHint: (StringResource) -> Unit,
    onBack: () -> Unit,
    onClip: (originFraction: Offset) -> Unit,
    overflowMenu: @Composable (onOpenChange: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = cameraDetailViewModel(cameraName)
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val request = playback.playerRequest
    val zoom = rememberPinchZoomState()
    val haptic = LocalHapticFeedback.current
    // Read by the tap handler, which must not restart on a rotation.
    val isFullScreen by rememberUpdatedState(fullScreen)

    // The chrome is up on arrival, so the way back is in sight, and goes after a few seconds of
    // being left alone — but not while paused (the picture isn't going anywhere), mid-scrub,
    // with one of its menus open ([chromeHeld]), or while the keyboard's focus is on one of its
    // controls ([chromeFocused]). [chromeTouched] restarts the wait: each use of a control bumps it.
    var chromeShown by remember { mutableStateOf(true) }
    var chromeHeld by remember { mutableStateOf(false) }
    var chromeFocused by remember { mutableStateOf(false) }
    var chromeTouched by remember { mutableIntStateOf(0) }
    // Remembered: the gesture node is keyed on them, and must not restart under a finger.
    val onTap = remember(viewModel) { { if (isFullScreen) chromeShown = !chromeShown else viewModel.togglePlayPause() } }
    // The same tick a held Home card gives as its video lifts out.
    val onHoldZoom = remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
    // The chrome reports its menus and its focus while it is composed, and it can leave with
    // either still set — turned upright with a menu open, hidden by a tap. Whenever it goes,
    // or comes back afresh, nothing is holding it.
    LaunchedEffect(fullScreen, chromeShown) {
        if (!fullScreen || !chromeShown) {
            chromeHeld = false
            chromeFocused = false
        }
    }
    LaunchedEffect(fullScreen) { if (fullScreen) chromeShown = true }
    val scrubbing = playback.scrubEpochSeconds != null
    // How long "left alone" is belongs to the person: someone using a screen reader or a switch
    // has told the system how long controls that disappear should wait for them.
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(fullScreen, chromeShown, chromeHeld, chromeFocused, chromeTouched, playback.isPlaying, scrubbing) {
        if (fullScreen && chromeShown && !chromeHeld && !chromeFocused && playback.isPlaying && !scrubbing) {
            val wait = accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = FULL_SCREEN_CHROME_MS,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: FULL_SCREEN_CHROME_MS
            delay(timeMillis = wait)
            chromeShown = false
        }
    }
    val takeoverFraction: () -> Float = if (fullScreen) ({ 1f }) else ({ takeover.value })

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
            .zoomTakeoverHeight(takeover = takeoverFraction, viewportHeight = viewportHeight, fillViewport = fullScreen)
            .background(if (fullScreen) Color.Black else MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zoomTakeoverHeight(
                    takeover = takeoverFraction,
                    viewportHeight = viewportHeight,
                    bottomInsetPx = if (fullScreen) 0 else bottomInsetPx,
                    fillViewport = fullScreen,
                )
                .clipToBounds()
                .pinchZoomGestures(zoom),
        ) {
            // The video and its scrub preview zoom together; the pills and buttons over them don't.
            // The video keeps its 16:9 shape (both players stretch to fill) and stays centred, as
            // large as the surface allows: letterboxed while the surface is taller than that, and
            // pillarboxed where it is wider (full screen on a phone), until zoomed past either.
            Box(
                modifier = Modifier
                    .fitVideo()
                    .align(Alignment.Center)
                    .pinchZoomContent(zoom),
            ) {
                // Until the view model has chosen a stream (its first camera read is still in
                // flight), keep showing what the card was playing, on the same pooled player; the
                // view model's first request is that very stream, so nothing reloads. One call site
                // for both, so the surface bound as the transition starts is the one that stays:
                // swapping call sites mid-transition bound a second, empty surface, and the video
                // went blank just as the card finished flying in.
                val warmRequest = remember(warmStreamUrl, warmPosterUrl) {
                    warmStreamUrl?.let { PlayerRequest(VideoSource.Live(it, warmPosterUrl)) }
                }
                val shownRequest = request ?: warmRequest
                if (shownRequest != null) {
                    // Same playerKey as the grid card: this binds to the player the card was already
                    // running, so live video is on screen before the shared-element transition ends.
                    CameraStreamPlayer(
                        request = shownRequest,
                        modifier = Modifier.fillMaxSize(),
                        playerKey = playerKey,
                        onPositionChanged = viewModel::onPlayerPositionChanged,
                        onBufferingChanged = viewModel::onBufferingChanged,
                        onStreamStatusChanged = viewModel::onStreamStatusChanged,
                        onPlaybackEnded = viewModel::onPlaybackEnded,
                        onPlaybackError = viewModel::onPlaybackError,
                        onAudioAvailabilityChanged = viewModel::onAudioAvailabilityChanged,
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

            // Full screen the tap is for the chrome, so it is there to take even with no stream.
            if (request != null || fullScreen) {
                // Once the chrome has faded this layer is the only way to it, so full screen it is
                // a button in its own right: named for a screen reader, which can activate it, and
                // focusable, so Enter or Space on a keyboard does what a tap does. The pointer
                // input stays as it was — a clickable would swallow the double tap and the hold
                // that zoom.
                val toggleChromeLabel = stringResource(if (chromeShown) Res.string.camera_hide_controls else Res.string.camera_show_controls)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (fullScreen) {
                                Modifier
                                    .semantics {
                                        role = Role.Button
                                        onClick(label = toggleChromeLabel) {
                                            chromeShown = !chromeShown
                                            true
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        val activates = event.type == KeyEventType.KeyUp && event.key in ACTIVATION_KEYS
                                        if (activates) chromeShown = !chromeShown
                                        activates
                                    }
                                    .focusable()
                            } else {
                                Modifier
                            },
                        )
                        .zoomTapGestures(zoom, onTap = onTap, onHoldZoom = onHoldZoom),
                )
            }

            when {
                playback.isLoadingPlaylist || playback.isBuffering -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )

                // The chrome has a play button of its own in this very spot, and that one works.
                fullScreen && chromeShown -> Unit

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
                        contentDescription = stringResource(Res.string.camera_play),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            if (fullScreen) {
                // matchParentSize, not fillMaxSize: AnimatedVisibility measures to its content.
                AnimatedVisibility(
                    visible = chromeShown,
                    modifier = Modifier.matchParentSize(),
                    enter = fadeIn(tween(FULL_SCREEN_CHROME_FADE_MS)),
                    exit = fadeOut(tween(FULL_SCREEN_CHROME_FADE_MS)),
                ) {
                    FullScreenPlayerChrome(
                        cameraName = cameraName,
                        cameraAvailable = cameraAvailable,
                        hasQualityChoice = hasQualityChoice,
                        hint = hint,
                        showHint = showHint,
                        onBack = onBack,
                        onClip = onClip,
                        onTouch = { chromeTouched++ },
                        onHold = { chromeHeld = it },
                        onFocusChange = { chromeFocused = it },
                        overflowMenu = overflowMenu,
                    )
                }
                return@Box
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
                    cameraName = cameraName,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 40.dp),
                )
            }
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

/** How long the full-screen player's chrome stays up once it has been left alone. */
private const val FULL_SCREEN_CHROME_MS = 4_000L
private const val FULL_SCREEN_CHROME_FADE_MS = 180

/** The most of the window's height the player takes while the page is under it. */
private const val PLAYER_MAX_HEIGHT_FRACTION = 0.6f

/** The keys that press a focused button: what Enter, Space or a D-pad's centre does on any other one. */
private val ACTIVATION_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/**
 * Sizes the player surface between its 16:9 strip ([takeover] = 0) and the scroll viewport's
 * height less [bottomInsetPx] ([takeover] = 1) — see [playerSurfaceHeight]. Both inputs are read
 * during layout, so the takeover animation and a viewport resize re-measure this node without
 * recomposing anything. [fillViewport] is the full-screen player: the viewport's height exactly.
 */
private fun Modifier.zoomTakeoverHeight(
    takeover: () -> Float,
    viewportHeight: IntState,
    bottomInsetPx: Int = 0,
    fillViewport: Boolean = false,
): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = playerSurfaceHeight(width, viewportHeight.intValue, bottomInsetPx, takeover(), fillViewport)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

/**
 * How tall the player's surface is, [width] across, in a scroll viewport [viewportHeight] tall.
 *
 * On the page it runs from its 16:9 strip ([takeover] 0) to the viewport less [bottomInsetPx]
 * ([takeover] 1, pinched larger), and is never shorter than the strip: a page scrolls, so a
 * strip taller than what is left of the viewport is simply scrolled.
 *
 * [fillViewport] — full screen, the phone on its side — is the viewport and nothing else. That
 * window is wider than 16:9, so the strip would be *taller* than the viewport, and nothing
 * scrolls there: a surface the strip's height hangs off the foot of the window, taking the
 * bottom of the picture and the timeline with it. (Before the viewport has been measured, on the
 * first frame, there is only the strip to go by.)
 */
internal fun playerSurfaceHeight(width: Int, viewportHeight: Int, bottomInsetPx: Int, takeover: Float, fillViewport: Boolean): Int {
    val strip = (width / VIDEO_ASPECT).roundToInt()
    if (fillViewport && viewportHeight > 0) return viewportHeight
    val expanded = max(strip, viewportHeight - bottomInsetPx)
    return lerp(strip, expanded, takeover)
}

/** A fresh instance per tap (identity equality), so repeating the same words restarts the auto-clear. */
private class QuickActionHint(val text: StringResource)

/** Red and pulsing at the live edge; grey (and a button back to live) while watching history. */
@Composable
internal fun LivePill(isLive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            text = stringResource(if (isLive) Res.string.camera_live else Res.string.camera_go_live),
            style = MaterialTheme.typography.labelSmall,
            color = if (isLive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun BehindLiveReadout(playheadEpochSeconds: Double, cameraName: String, modifier: Modifier = Modifier) {
    val viewModel = cameraDetailViewModel(cameraName)
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
            text = stringResource(Res.string.camera_behind_live, formatDuration(now - playheadEpochSeconds)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The camera's timeline and the chips that choose how much of it to show.
 *
 * On the page it is headed "Timeline". [overVideo] is the full-screen player's: the heading gives
 * its place to the "behind live" readout (which on the page sits in the player's corner), and
 * [onTouch] reports each use of it, so the chrome it is part of stays up while it is in use.
 */
@Composable
internal fun TimelineSection(cameraName: String, modifier: Modifier = Modifier, overVideo: Boolean = false, onTouch: () -> Unit = {}) {
    val viewModel = cameraDetailViewModel(cameraName)
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val now by viewModel.nowEpochSeconds.collectAsStateWithLifecycle()
    val detections by viewModel.timelineDetections.collectAsStateWithLifecycle()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (overVideo) 8.dp else 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (overVideo) {
                val playhead = playback.scrubEpochSeconds ?: playback.playheadEpochSeconds
                // An empty box at the live edge, so the chips keep to the end of the row.
                if (playhead != null) BehindLiveReadout(playheadEpochSeconds = playhead, cameraName = cameraName) else Box(Modifier)
            } else {
                Text(
                    text = stringResource(Res.string.camera_timeline),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimelineSpan.entries.forEach { span ->
                    SpanChip(
                        span = span,
                        selected = span == playback.span,
                        onClick = {
                            onTouch()
                            viewModel.setSpan(span)
                        },
                    )
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
            onScrubEnd = {
                onTouch()
                viewModel.onScrubEnd()
            },
            onSeek = { epochSeconds ->
                onTouch()
                viewModel.seekTo(epochSeconds)
            },
            detections = detections,
            // A dot is a detection: play it from its start, as its Recent Activity card would.
            onDetectionTap = { startEpochSeconds ->
                onTouch()
                viewModel.playMoment(startEpochSeconds)
            },
        )

        val historyError = playback.historyError
        val hint = when {
            historyError != null && playback.segments.isEmpty() -> historyError.resolve()
            playback.segments.isEmpty() -> stringResource(Res.string.camera_no_recordings)
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
internal fun SpanChip(span: TimelineSpan, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = stringResource(span.label),
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
 * This camera's less-used settings, kept out of the page itself: the polygon editor — what
 * Google Home calls activity zones and Frigate calls masks — and tagging the cars in view by
 * hand, reached from the header rather than cards competing with the timeline and recent
 * activity for the eye.
 *
 * The full-screen player's chrome carries the same menu, in its own [tint], and is told through
 * [onOpenChange] while the menu is open so it doesn't fade away from under it.
 */
@Composable
private fun CameraOverflowMenu(
    onEditDetectionZones: () -> Unit,
    onTagCars: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary,
    onOpenChange: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val setExpanded: (Boolean) -> Unit = { open ->
        expanded = open
        onOpenChange(open)
    }
    Box {
        IconButton(onClick = { setExpanded(true) }, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(Res.string.common_more_options),
                tint = tint,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { setExpanded(false) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            // The same hairline every card on this screen carries, so the menu reads as one of them.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(Res.string.camera_menu_detection_zones), style = MaterialTheme.typography.labelLarge) },
                leadingIcon = { Icon(imageVector = Icons.Filled.CropFree, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    setExpanded(false)
                    onEditDetectionZones()
                },
            )
            // Also the way in when the home page's "In view now" strip is empty: a car it missed
            // leaves no strip to open this from.
            DropdownMenuItem(
                text = { Text(text = stringResource(Res.string.camera_menu_tag_cars), style = MaterialTheme.typography.labelLarge) },
                leadingIcon = { Icon(imageVector = Icons.Filled.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    setExpanded(false)
                    onTagCars()
                },
            )
        }
    }
}

@Composable
private fun RecentMomentCard(item: MomentItem, onClick: () -> Unit, onTagCar: () -> Unit) {
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
                Text(text = p.title.resolve(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(text = p.timeLabel.resolve(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                // A folded parked-car moment spans hours, which isn't a clip length: say how often it was seen instead.
                text = UiText.Joined(
                    parts = listOfNotNull(p.dateGroup, p.sightingsLabel ?: p.durationLabel?.let { UiText.of(Res.string.camera_moment_clip_length, it) }),
                    separator = UiText.of(Res.string.common_dot_separator),
                ).resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (item.canTagCar) TagCarButton(onClick = onTagCar, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
