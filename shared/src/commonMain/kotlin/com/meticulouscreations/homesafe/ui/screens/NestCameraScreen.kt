package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.CameraFraming
import com.meticulouscreations.homesafe.domain.model.VideoFit
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.ui.VIDEO_ASPECT
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.ImmersiveSystemBars
import com.meticulouscreations.homesafe.ui.components.KeepScreenOn
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.components.LocalPictureInPicture
import com.meticulouscreations.homesafe.ui.components.MeasuredVideoShapes
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.ShapedVideo
import com.meticulouscreations.homesafe.ui.components.VideoSource
import com.meticulouscreations.homesafe.ui.components.WebRtcEndpoint
import com.meticulouscreations.homesafe.ui.components.cropAsZoomRest
import com.meticulouscreations.homesafe.ui.components.isOnItsSide
import com.meticulouscreations.homesafe.ui.components.pictureInBox
import com.meticulouscreations.homesafe.ui.components.pinchZoomContent
import com.meticulouscreations.homesafe.ui.components.pinchZoomGestures
import com.meticulouscreations.homesafe.ui.components.rememberPictureInPicture
import com.meticulouscreations.homesafe.ui.components.rememberPinchZoomState
import com.meticulouscreations.homesafe.ui.components.zoomTapGestures
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.NestCameraViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_nest_fit_fill
import homesafe.shared.generated.resources.camera_nest_fit_whole
import homesafe.shared.generated.resources.camera_nest_live_only
import homesafe.shared.generated.resources.camera_nest_talk
import homesafe.shared.generated.resources.camera_picture_in_picture
import homesafe.shared.generated.resources.camera_sound_turn_off
import homesafe.shared.generated.resources.camera_sound_turn_on
import homesafe.shared.generated.resources.common_back
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A Google Nest camera, live. These cameras are go2rtc streams pulled from Google while
 * someone watches, with no Frigate camera behind them, so there is no timeline, no recordings and
 * nothing detected: the page is the video, a way back, the speaker, and a way to talk through the
 * camera, which hands over to Google Home (see `GoogleHomeApp` for why). The video zooms like a
 * Frigate camera's: pinch, double tap, or hold, and drag to look around once zoomed in. Zoomed,
 * it takes over the page under the header down to the nav bar, pushing the footer out of view, so
 * the enlarged picture has the height of the screen to spread into ([NestVideoAndFooter]). The
 * speaker is the same saved choice as theirs, so a camera opens with sound when it was left on.
 *
 * [warmStreamUrl] / [warmWebRtcSignalingUrl] are what the tapped card was playing. The screen
 * binds the same pooled player (keyed by [streamName], as the card is) and carries on with that
 * silent stream until it has a picture here, then moves to the camera's own stream over the
 * current route: the card may have been playing a smaller copy (`<name>_sub`), and the player
 * keeps showing it until the full one has a frame. The speaker being on (saved, or turned on
 * here) moves it to the one with sound, which go2rtc serves from the same connection to Google.
 * [warmStillUrl] is the card's last-seen still, for the moments before any frame.
 *
 * The picture is framed as the camera has been in Arrange mode: turned, and cropped, the crop
 * being where the zoom rests, so pinching out from it shows the whole picture. A picture filling
 * a frame of another shape pinches out past the frame, too, to the whole of it.
 *
 * Turned on its side the video takes the whole window, with the way back, the speaker and talking
 * over it.
 *
 * On Android, leaving the app from here, or the picture-in-picture button, floats the video in the
 * system's mini player, at the picture's shape: the window is the video, with nothing over it.
 *
 * A Nest camera's picture is whatever shape the model makes it (the doorbell's is tall). Shown
 * whole ([VideoFit.Fit], the default), the video's box here takes the picture's own shape, as
 * large as the page allows; filling the frame ([VideoFit.Fill]) keeps the box 16:9 and crops. The
 * button between the two appears once the picture turns out not to be 16:9, and the choice is
 * saved for every Nest camera, cards included (see [ShapedVideo]).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NestCameraScreen(
    streamName: String,
    warmStreamUrl: String?,
    warmWebRtcSignalingUrl: String?,
    sharedTransitionScope: SharedTransitionScope,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    warmStillUrl: String? = null,
) {
    val viewModel: NestCameraViewModel = metroViewModel()
    val soundOn by viewModel.soundOn.collectAsStateWithLifecycle()
    val fit by viewModel.fit.collectAsStateWithLifecycle()
    // The stream with sound once the speaker is on, saved from last time or turned on here. Once
    // it is, it stays: muting again is the player's job, not another reconnect.
    var wantedSound by rememberSaveable(streamName) { mutableStateOf(false) }
    val wantsSound = wantedSound || soundOn
    val liveSource by remember(streamName, wantsSound, warmStillUrl) { viewModel.liveSource(streamName, withSound = wantsSound, stillUrl = warmStillUrl) }
        .collectAsStateWithLifecycle(null)
    var status by remember(streamName) { mutableStateOf(LiveStreamStatus.Connecting) }
    // Only for the first frames, until the card's stream is playing here: then the route's own
    // source takes over, the camera's full stream, which follows the route (LAN to Tailscale).
    val warmSource = remember(warmStreamUrl, warmWebRtcSignalingUrl, warmStillUrl) {
        warmStreamUrl?.let {
            VideoSource.Live(
                url = it,
                posterUrl = warmStillUrl,
                webRtc = warmWebRtcSignalingUrl?.let { url -> WebRtcEndpoint(url, audio = false) },
                lastSeenPoster = warmStillUrl != null,
            )
        }
    }
    // Moving up before the card's stream has a picture here would leave nothing to show while the
    // full one starts; once it has one, the player keeps it up through the change. The card's
    // stream is given up on, too, when it hasn't played within WARM_SOURCE_GRACE_MS (it may never)
    // or when the route has moved on (LAN to Tailscale): its address is the old route's.
    var warmDone by rememberSaveable(streamName) { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (status == LiveStreamStatus.Live) {
            warmDone = true
        } else {
            delay(WARM_SOURCE_GRACE_MS)
            warmDone = true
        }
    }
    val warmOnRoute = warmSource != null && (liveSource == null || sameHost(warmSource.url, liveSource!!.url))
    val source = when {
        wantsSound -> liveSource
        warmOnRoute && !warmDone -> warmSource
        else -> liveSource ?: warmSource
    }
    val request = remember(source, soundOn) { source?.let { PlayerRequest(it, muted = !soundOn) } }
    // Live only, and never paused: the screen stays lit for as long as there is a stream to watch.
    if (request != null) KeepScreenOn()
    val framing by remember(streamName) { viewModel.framing(streamName) }.collectAsStateWithLifecycle(CameraFraming.UNTOUCHED)
    val turnDegrees = framing.orientation.degrees
    val pictureAspect = source?.let { MeasuredVideoShapes[it.url] }
    // The picture's shape as it is shown: stood on its side by a quarter turn, its inverse.
    val measuredAspect = pictureAspect?.let { if (isOnItsSide(turnDegrees)) 1f / it else it }
    val toggleSound = {
        wantedSound = true
        viewModel.toggleSound()
    }
    // Pinch, double tap or hold to zoom in on the picture, as on a Frigate camera's page.
    val zoom = rememberPinchZoomState()
    val haptic = LocalHapticFeedback.current
    val onTap = remember { {} }
    val onHoldZoom = remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }

    val fullScreen = isCompactLandscape()
    val inPictureInPicture = LocalPictureInPicture.current
    // The mini player takes the shape the video's box has here: the picture's own when shown whole.
    val pictureInPicture = rememberPictureInPicture(
        enabled = request != null,
        aspectRatio = if (fit == VideoFit.Fit) measuredAspect ?: VIDEO_ASPECT else VIDEO_ASPECT,
    )
    val pictureInPictureButton: (@Composable (Color) -> Unit)? = if (pictureInPicture.available) {
        { tint -> PictureInPictureButton(onClick = pictureInPicture::enter, tint = tint) }
    } else {
        null
    }
    // Only a picture that isn't its box's shape has anything to fit or fill. Upright the box is
    // 16:9; full screen it is the window, which rarely is, so there the button shows once the
    // shape is known (a saved Fill would otherwise crop a 16:9 camera with no way back).
    val fitMatters = measuredAspect != null && (fullScreen || abs(measuredAspect - VIDEO_ASPECT) > FIT_ASPECT_TOLERANCE)
    // The video's box: whole, the picture's own shape (16:9 until it is known); filling, the
    // 16:9 frame, or full screen the window.
    val window = LocalWindowInfo.current.containerSize
    val boxAspect = when {
        fit == VideoFit.Fit -> measuredAspect ?: VIDEO_ASPECT
        fullScreen && window.height > 0 -> window.width.toFloat() / window.height
        else -> VIDEO_ASPECT
    }
    // The crop is where the zoom rests rather than a cut of the picture, as on a Frigate
    // camera's page: a pinch can pan past its edges, as far as the picture's own, or close out
    // to the whole picture.
    val onItsSide = isOnItsSide(turnDegrees)
    val zoomRest = remember(framing.crop, boxAspect, pictureAspect, fit, onItsSide) {
        cropAsZoomRest(framing.crop, boxAspect = boxAspect, aspect = pictureAspect, fit = fit, onItsSide = onItsSide)
    }
    val picture = remember(boxAspect, pictureAspect, fit, onItsSide) {
        pictureInBox(boxAspect = boxAspect, aspect = pictureAspect, fit = fit, onItsSide = onItsSide)
    }
    SideEffect { zoom.restOn(zoomRest, picture) }
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    if (fullScreen && animatedVisibilityScope.transition.targetState == EnterExitState.Visible) ImmersiveSystemBars()
    val extraColors = LocalFrigateExtraColors.current

    // 0 = the video is its box on the page, 1 = it has taken over the page under the header, as
    // a Frigate camera's player does while zoomed (see CameraDetailScreen's PlayerSurface).
    // Driven from a snapshotFlow so the pinch's per-frame changes never recompose the page.
    val takeover = remember { Animatable(0f) }
    LaunchedEffect(zoom) {
        snapshotFlow { zoom.isZoomed }.collectLatest { zoomed ->
            takeover.animateTo(if (zoomed) 1f else 0f, tween(ZOOM_TAKEOVER_MS))
        }
    }
    val restBox = remember { NestVideoRestBox() }

    // Two boxes: the viewport, which takes the pinch, clips the picture and grows while zoomed;
    // and the content in its middle, the video's box, which keeps its size as the viewport grows
    // around it and is what the zoom scales.
    val video = @Composable { viewportModifier: Modifier, contentModifier: Modifier ->
        Box(modifier = viewportModifier.clipToBounds().pinchZoomGestures(zoom)) {
            Box(
                modifier = with(sharedTransitionScope) {
                    // The video is the one thing that travels between the card and here, and what the
                    // mini player grows from.
                    contentModifier.align(Alignment.Center).then(pictureInPicture.sourceModifier).sharedBounds(
                        sharedContentState = rememberSharedContentState(key = cameraVideoSharedKey(streamName)),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = CameraVideoBoundsTransform,
                        clipInOverlayDuringTransition = rememberCameraVideoOverlayClip(
                            animatedVisibilityScope = animatedVisibilityScope,
                            visibleRadius = 0.dp,
                            hiddenRadius = CAMERA_CARD_CORNER_RADIUS,
                        ),
                    )
                }.background(Color.Black)
                    .pinchZoomContent(zoom),
            ) {
                if (request != null) {
                    ShapedVideo(
                        streamUrl = request.source.url,
                        fit = fit,
                        modifier = Modifier.fillMaxSize(),
                        turnDegrees = turnDegrees,
                        // Filling, the picture spills past its box: kept, for the zoom to pan and
                        // pinch out to, and clipped by the viewport around it.
                        clip = false,
                    ) { videoModifier, onStreamStats ->
                        CameraStreamPlayer(
                            request = request,
                            modifier = videoModifier,
                            playerKey = streamName,
                            onStreamStatusChanged = { status = it },
                            onStreamStats = onStreamStats,
                            turnDegrees = turnDegrees,
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.VideocamOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.align(Alignment.Center).size(56.dp),
                    )
                }
            }
            // Over the picture and under the badge: a double tap or a hold zooms in where it lands.
            if (request != null) Box(modifier = Modifier.fillMaxSize().zoomTapGestures(zoom, onTap = onTap, onHoldZoom = onHoldZoom))
            // The mini player is too small for the badge, and its window is the picture alone.
            if (!inPictureInPicture) {
                // In the video's top corner, which is the viewport's unless a tall picture leaves room beside it.
                Box(modifier = contentModifier.align(Alignment.TopCenter)) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                        StatusBadge(enabled = true, status = status, textColor = extraColors.textPrimary, pillColor = extraColors.glassFill)
                    }
                }
            } else if (request != null && status != LiveStreamStatus.Live) {
                // The badge's words are gone, but not the sign that the picture is on its way.
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
            }
        }
    }

    if (fullScreen) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            // The viewport is the window, so a picture shown whole between bars zooms into them.
            // Filling, the window is the frame; whole, the picture's own shape, as large as the window allows.
            val content = if (fit == VideoFit.Fill) {
                Modifier.fillMaxSize()
            } else {
                Modifier.aspectRatio(boxAspect, matchHeightConstraintsFirst = true)
            }
            video(Modifier.fillMaxSize(), content)
            if (inPictureInPicture) return@Box
            Row(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                BackButton(onBack = onBack, tint = Color.White)
            }
            Row(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                if (fitMatters) FitButton(fit = fit, onToggle = viewModel::toggleFit, tint = Color.White)
                pictureInPictureButton?.invoke(Color.White)
                TalkIconButton(onTalk = viewModel::talkInGoogleHome, tint = Color.White)
                SoundButton(soundOn = soundOn, onToggle = toggleSound, tint = Color.White)
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Replaces the shell's top bar, which is hidden while a nested screen is up.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = nestedHeaderVerticalPadding()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack = onBack, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = cameraDisplayName(streamName),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
            )
            if (fitMatters) FitButton(fit = fit, onToggle = viewModel::toggleFit, tint = MaterialTheme.colorScheme.primary)
            pictureInPictureButton?.invoke(MaterialTheme.colorScheme.primary)
            SoundButton(soundOn = soundOn, onToggle = toggleSound, tint = MaterialTheme.colorScheme.primary)
        }
        // Whole, a tall picture gets a tall box, as large as the page leaves room for; filling, the
        // 16:9 frame. Zoomed, the video takes the rest of the page down to the nav bar.
        NestVideoAndFooter(
            takeover = { takeover.value },
            boxAspect = boxAspect,
            bottomInsetPx = with(LocalDensity.current) { bottomNavClearance().roundToPx() },
            restBox = restBox,
            backdrop = MaterialTheme.colorScheme.surfaceContainerLowest,
            video = { video(Modifier.fillMaxSize(), Modifier.restBox(restBox)) },
            footer = { NestCameraFooter(onTalk = viewModel::talkInGoogleHome) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * The size the video's box has on the page, upright, set as the page is laid out
 * ([NestVideoAndFooter]) and read by the box itself ([restBox]) as it is: the box keeps it while
 * the viewport around it grows with a zoom. Snapshot state, read only during layout.
 */
@Stable
internal class NestVideoRestBox {
    var size by mutableStateOf(IntSize.Zero)
}

/** At [box]'s size in the middle of the viewport, whatever size the viewport has grown to. */
internal fun Modifier.restBox(box: NestVideoRestBox): Modifier = layout { measurable, constraints ->
    val size = box.size.takeIf { it != IntSize.Zero } ?: IntSize(constraints.maxWidth, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The page under the header: the [video], at rest a box of [boxAspect]'s shape as wide as the page
 * and no taller than it leaves room for over the [footer], which sits under it. As [takeover] goes
 * from 0 to 1 (zoomed) the video's viewport grows from that box to the page's width and down to
 * [bottomInsetPx] above the foot of the page, clear of the floating nav bar, on a [backdrop] that runs to the foot under it, and the
 * footer is pushed off the bottom. Both are read during layout and drawing, so the takeover never
 * recomposes the page.
 */
@Composable
internal fun NestVideoAndFooter(
    takeover: () -> Float,
    boxAspect: Float,
    bottomInsetPx: Int,
    restBox: NestVideoRestBox,
    backdrop: Color,
    video: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(video, footer),
        modifier = modifier
            .clipToBounds()
            .drawBehind {
                val t = takeover()
                if (t > 0f) {
                    val restHeight = restBox.size.height.toFloat()
                    drawRect(backdrop, size = Size(size.width, lerp(restHeight, maxOf(restHeight, size.height), t)), alpha = t)
                }
            },
    ) { (videoMeasurables, footerMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val footer = footerMeasurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val rest = nestVideoRestSize(width, height - footer.height, boxAspect)
        restBox.size = rest
        val t = takeover()
        // At rest the viewport is the video's box, so a crop the zoom rests on shows as it was
        // framed, even where a tall picture leaves the page wider than the box.
        val viewportWidth = lerp(rest.width, width, t)
        val viewportHeight = lerp(rest.height, max(rest.height, height - bottomInsetPx), t)
        val viewport = videoMeasurables.first().measure(Constraints.fixed(viewportWidth, viewportHeight))
        layout(width, height) {
            viewport.place((width - viewportWidth) / 2, 0)
            footer.place(0, lerp(rest.height, max(rest.height, height), t))
        }
    }
}

/**
 * The video's box at rest, [width] across with [available] height for it: [boxAspect]'s shape, as
 * wide as the page unless that would be taller than [available], when it is that tall and narrower.
 */
internal fun nestVideoRestSize(width: Int, available: Int, boxAspect: Float): IntSize {
    if (width <= 0 || boxAspect <= 0f) return IntSize(width.coerceAtLeast(0), 0)
    val fullWidthHeight = (width / boxAspect).roundToInt()
    val room = available.coerceAtLeast(0)
    return if (fullWidthHeight <= room) {
        IntSize(width, fullWidthHeight)
    } else {
        IntSize((room * boxAspect).roundToInt().coerceAtMost(width), room)
    }
}

/**
 * Under the video: talking through the camera, named for the app it opens since the tap leaves
 * HomeSafe, and why the page has nothing else.
 */
@Composable
internal fun NestCameraFooter(onTalk: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = contentGutter(), vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FilledTonalButton(onClick = onTalk) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(Res.string.camera_nest_talk))
        }
        Text(
            text = stringResource(Res.string.camera_nest_live_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Full screen, where there is no room for the footer's labelled button. */
@Composable
private fun TalkIconButton(onTalk: () -> Unit, tint: Color) {
    IconButton(onClick = onTalk) {
        Icon(imageVector = Icons.Filled.Mic, contentDescription = stringResource(Res.string.camera_nest_talk), tint = tint)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, tint: Color) {
    IconButton(onClick = onBack) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = tint)
    }
}

/** Between the whole picture and filling the frame; the icon is what a tap does. */
@Composable
private fun FitButton(fit: VideoFit, onToggle: () -> Unit, tint: Color) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (fit == VideoFit.Fit) Icons.Filled.Crop else Icons.Filled.FitScreen,
            contentDescription = stringResource(if (fit == VideoFit.Fit) Res.string.camera_nest_fit_fill else Res.string.camera_nest_fit_whole),
            tint = tint,
        )
    }
}

/** How far from 16:9 a picture's shape must be before fitting and filling look any different. */
private const val FIT_ASPECT_TOLERANCE = 0.05f

@Composable
private fun PictureInPictureButton(onClick: () -> Unit, tint: Color) {
    IconButton(onClick = onClick) {
        Icon(imageVector = Icons.Filled.PictureInPictureAlt, contentDescription = stringResource(Res.string.camera_picture_in_picture), tint = tint)
    }
}

@Composable
private fun SoundButton(soundOn: Boolean, onToggle: () -> Unit, tint: Color) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (soundOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            contentDescription = stringResource(if (soundOn) Res.string.camera_sound_turn_off else Res.string.camera_sound_turn_on),
            tint = tint,
        )
    }
}

/** How long the page waits for the card's stream to play before moving to the camera's own. */
private const val WARM_SOURCE_GRACE_MS = 6_000L

/** Whether two stream URLs are on the same host: the same route to the server. */
private fun sameHost(a: String, b: String): Boolean = a.substringAfter("://").substringBefore('/') == b.substringAfter("://").substringBefore('/')
