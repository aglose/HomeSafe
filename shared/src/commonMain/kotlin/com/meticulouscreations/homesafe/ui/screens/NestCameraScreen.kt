package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.CameraFraming
import com.meticulouscreations.homesafe.domain.model.VideoFit
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.ui.VIDEO_ASPECT
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.ImmersiveSystemBars
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.components.MeasuredVideoShapes
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.ShapedVideo
import com.meticulouscreations.homesafe.ui.components.VideoSource
import com.meticulouscreations.homesafe.ui.components.WebRtcEndpoint
import com.meticulouscreations.homesafe.ui.components.isOnItsSide
import com.meticulouscreations.homesafe.ui.components.pinchZoomContent
import com.meticulouscreations.homesafe.ui.components.pinchZoomGestures
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
import homesafe.shared.generated.resources.camera_sound_turn_off
import homesafe.shared.generated.resources.camera_sound_turn_on
import homesafe.shared.generated.resources.common_back
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * A Google Nest camera, live. These cameras are go2rtc streams pulled from Google while
 * someone watches, with no Frigate camera behind them, so there is no timeline, no recordings and
 * nothing detected: the page is the video, a way back, and the speaker. The video zooms like a
 * Frigate camera's: pinch, double tap, or hold, and drag to look around once zoomed in. The
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
 * The picture is framed as the camera has been in Arrange mode: turned, and cropped.
 *
 * Turned on its side the video takes the whole window, with the way back and the speaker over it.
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
    val framing by remember(streamName) { viewModel.framing(streamName) }.collectAsStateWithLifecycle(CameraFraming.UNTOUCHED)
    val turnDegrees = framing.orientation.degrees
    // The picture's shape as it is shown: stood on its side by a quarter turn, its inverse.
    val measuredAspect = source?.let { MeasuredVideoShapes[it.url] }?.let { if (isOnItsSide(turnDegrees)) 1f / it else it }
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
    // Only a picture that isn't its box's shape has anything to fit or fill. Upright the box is
    // 16:9; full screen it is the window, which rarely is, so there the button shows once the
    // shape is known (a saved Fill would otherwise crop a 16:9 camera with no way back).
    val fitMatters = measuredAspect != null && (fullScreen || abs(measuredAspect - VIDEO_ASPECT) > FIT_ASPECT_TOLERANCE)
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    if (fullScreen && animatedVisibilityScope.transition.targetState == EnterExitState.Visible) ImmersiveSystemBars()
    val extraColors = LocalFrigateExtraColors.current

    val video = @Composable { modifier: Modifier ->
        Box(
            modifier = with(sharedTransitionScope) {
                // The video is the one thing that travels between the card and here.
                modifier.sharedBounds(
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
                .clipToBounds()
                .pinchZoomGestures(zoom),
        ) {
            if (request != null) {
                ShapedVideo(
                    streamUrl = request.source.url,
                    fit = fit,
                    modifier = Modifier.fillMaxSize().pinchZoomContent(zoom),
                    turnDegrees = turnDegrees,
                    crop = { framing.crop },
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
            // Over the picture and under the badge: a double tap or a hold zooms in where it lands.
            if (request != null) Box(modifier = Modifier.fillMaxSize().zoomTapGestures(zoom, onTap = onTap, onHoldZoom = onHoldZoom))
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                StatusBadge(enabled = true, status = status, textColor = extraColors.textPrimary, pillColor = extraColors.glassFill)
            }
        }
    }

    if (fullScreen) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            // Filling, the window is the frame; whole, the picture's own shape (16:9 until it is known), as large as the window allows.
            val box = if (fit == VideoFit.Fill) {
                Modifier.fillMaxSize()
            } else {
                Modifier.align(Alignment.Center).aspectRatio(measuredAspect ?: VIDEO_ASPECT, matchHeightConstraintsFirst = true)
            }
            video(box)
            Row(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                BackButton(onBack = onBack, tint = Color.White)
            }
            Row(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                if (fitMatters) FitButton(fit = fit, onToggle = viewModel::toggleFit, tint = Color.White)
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
            SoundButton(soundOn = soundOn, onToggle = toggleSound, tint = MaterialTheme.colorScheme.primary)
        }
        // Whole, a tall picture gets a tall box, as large as the page leaves room for; filling, the 16:9 frame.
        val boxAspect = if (fit == VideoFit.Fit) measuredAspect ?: VIDEO_ASPECT else VIDEO_ASPECT
        Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            video(Modifier.aspectRatio(boxAspect))
        }
        Text(
            text = stringResource(Res.string.camera_nest_live_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = contentGutter(), vertical = 16.dp),
        )
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
