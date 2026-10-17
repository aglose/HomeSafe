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
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.ui.VIDEO_ASPECT
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.ImmersiveSystemBars
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.components.PlayerRequest
import com.meticulouscreations.homesafe.ui.components.VideoSource
import com.meticulouscreations.homesafe.ui.components.WebRtcEndpoint
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.NestCameraViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_nest_live_only
import homesafe.shared.generated.resources.camera_sound_turn_off
import homesafe.shared.generated.resources.camera_sound_turn_on
import homesafe.shared.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/**
 * A Google Nest camera, live. These cameras are go2rtc streams pulled from Google while
 * someone watches, with no Frigate camera behind them, so there is no timeline, no recordings and
 * nothing detected: the page is the video, a way back, and the speaker.
 *
 * [warmStreamUrl] / [warmWebRtcSignalingUrl] are what the tapped card was playing. The screen
 * binds the same pooled player (keyed by [streamName], as the card is) and carries on with that
 * silent stream, over the current route from then on; only the first tap on the speaker moves it
 * to the one with sound, which go2rtc serves from the same connection to Google.
 *
 * Turned on its side the video takes the whole window, with the way back and the speaker over it.
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
) {
    val viewModel: NestCameraViewModel = metroViewModel()
    val soundOn by viewModel.soundOn.collectAsStateWithLifecycle()
    // Once sound has been asked for, the stream with it stays: muting again is the player's job,
    // not another reconnect.
    var wantsSound by rememberSaveable(streamName) { mutableStateOf(false) }
    val liveSource by remember(streamName, wantsSound) { viewModel.liveSource(streamName, withSound = wantsSound) }
        .collectAsStateWithLifecycle(null)
    var status by remember(streamName) { mutableStateOf(LiveStreamStatus.Connecting) }
    // Only for the first frames, until the route's own source arrives: that is the same stream
    // while the route hasn't moved, and follows it (LAN to Tailscale) when it does.
    val warmSource = remember(warmStreamUrl, warmWebRtcSignalingUrl) {
        warmStreamUrl?.let { VideoSource.Live(it, webRtc = warmWebRtcSignalingUrl?.let { url -> WebRtcEndpoint(url, audio = false) }) }
    }
    val source = liveSource ?: warmSource.takeIf { !wantsSound }
    val request = remember(source, soundOn) { source?.let { PlayerRequest(it, muted = !soundOn) } }
    val toggleSound = {
        wantsSound = true
        viewModel.toggleSound()
    }

    val fullScreen = isCompactLandscape()
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
            }.background(Color.Black),
        ) {
            if (request != null) {
                CameraStreamPlayer(
                    request = request,
                    modifier = Modifier.fillMaxSize(),
                    playerKey = streamName,
                    onStreamStatusChanged = { status = it },
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.VideocamOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                StatusBadge(enabled = true, status = status, textColor = extraColors.textPrimary, pillColor = extraColors.glassFill)
            }
        }
    }

    if (fullScreen) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            // As large as the window allows at 16:9: the players stretch to fill, so it mustn't be the window's shape.
            video(Modifier.align(Alignment.Center).aspectRatio(VIDEO_ASPECT, matchHeightConstraintsFirst = true))
            Row(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                BackButton(onBack = onBack, tint = Color.White)
            }
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
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
            SoundButton(soundOn = soundOn, onToggle = toggleSound, tint = MaterialTheme.colorScheme.primary)
        }
        video(Modifier.fillMaxWidth().aspectRatio(VIDEO_ASPECT))
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
