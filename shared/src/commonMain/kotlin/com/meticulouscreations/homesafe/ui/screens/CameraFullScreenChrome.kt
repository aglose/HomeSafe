package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_action_clip_description
import homesafe.shared.generated.resources.camera_hint_single_quality
import homesafe.shared.generated.resources.camera_pause
import homesafe.shared.generated.resources.camera_play
import homesafe.shared.generated.resources.camera_quality_button_description
import homesafe.shared.generated.resources.camera_sound_turn_off
import homesafe.shared.generated.resources.camera_sound_turn_on
import homesafe.shared.generated.resources.common_back
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What a camera's video wears while it has the whole window (the phone on its side; see
 * [CameraDetailScreen]): everything the page offered, laid over the picture instead of under it.
 * Along the top, on a wash dark enough to read over a bright sky: the way back, the camera's
 * name, the LIVE pill, and the page's three quick actions (quality, sound, clip) as plain round
 * buttons. In the middle, play and pause. Along the bottom, the timeline with its span chips and
 * the "behind live" readout. All of it fades in and out together; the caller decides when.
 *
 * Each row steps in from the cutout and the system bars by itself, since the video beneath runs
 * to the glass. Collects `playback` itself, like the page's own rows, so position ticks while a
 * recording plays recompose this and not the screen.
 *
 * [onTouch] reports each use of a control, [onHold] whether a menu is open, and [onFocusChange]
 * whether the keyboard's focus is on one of the controls: the caller's reasons to keep the chrome
 * up. [hint] is the quick actions' line of feedback, said under the
 * top row here because there is no page to say it on. [overflowMenu] is the page header's menu
 * (detection zones, tag cars), which ends the top row and reports its own opening and closing.
 */
@Composable
internal fun FullScreenPlayerChrome(
    cameraName: String,
    cameraAvailable: Boolean,
    hasQualityChoice: Boolean,
    hint: StringResource?,
    showHint: (StringResource) -> Unit,
    onBack: () -> Unit,
    onClip: (originFraction: Offset) -> Unit,
    onTouch: () -> Unit,
    onHold: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    overflowMenu: @Composable (onOpenChange: (Boolean) -> Unit) -> Unit = {},
) {
    val viewModel = cameraDetailViewModel(cameraName)
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val displayName = cameraDisplayName(cameraName)

    Box(modifier = modifier.fillMaxSize().onFocusChanged { onFocusChange(it.hasFocus) }.testTag(FULL_SCREEN_CHROME_TEST_TAG)) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = CHROME_SCRIM_ALPHA), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = Color.White)
            }
            // The name and the pill take what the buttons leave, the name giving way first.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (cameraAvailable) {
                    LivePill(
                        isLive = playback.isLive,
                        onClick = {
                            onTouch()
                            viewModel.goLive()
                        },
                    )
                }
            }

            // The page's quick actions, without their captions: the icon and its tint say the
            // state, and a tap that changes something says what in the hint under this row.
            var qualityMenuOpen by remember { mutableStateOf(false) }
            Box {
                ChromeButton(
                    icon = Icons.Filled.Tune,
                    contentDescription = stringResource(Res.string.camera_quality_button_description, stringResource(qualityLabel(playback.quality))),
                    available = hasQualityChoice,
                    onClick = {
                        onTouch()
                        if (hasQualityChoice) {
                            qualityMenuOpen = true
                            onHold(true)
                        } else {
                            showHint(Res.string.camera_hint_single_quality)
                        }
                    },
                )
                QualityMenu(
                    expanded = qualityMenuOpen,
                    selected = playback.quality,
                    onSelect = { choice ->
                        qualityMenuOpen = false
                        onHold(false)
                        viewModel.setQuality(choice)
                    },
                    onDismiss = {
                        qualityMenuOpen = false
                        onHold(false)
                    },
                )
            }
            ChromeButton(
                icon = if (playback.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(if (playback.isMuted) Res.string.camera_sound_turn_on else Res.string.camera_sound_turn_off),
                active = !playback.isMuted,
                available = playback.hasAudio,
                onClick = {
                    onTouch()
                    showHint(soundToggleHint(wasMuted = playback.isMuted, hasAudio = playback.hasAudio))
                    viewModel.toggleMuted()
                },
            )
            // The editor's splash lands where the scissors are, as it does from the page.
            val clipOrigin = remember { OriginProbe() }
            ChromeButton(
                icon = Icons.Filled.ContentCut,
                contentDescription = stringResource(Res.string.camera_action_clip_description, displayName),
                onClick = { onClip(clipOrigin.fraction()) },
                modifier = Modifier.onGloballyPositioned { clipOrigin.coordinates = it },
            )
            overflowMenu(onHold)
        }

        AnimatedVisibility(
            visible = hint != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = HINT_TOP),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            // The words outlast the hint by the length of the fade.
            var lastHint by remember { mutableStateOf(hint) }
            if (hint != null) lastHint = hint
            Text(
                text = lastHint?.let { stringResource(it) }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(LocalFrigateExtraColors.current.glassFill)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Nothing to play or pause without a stream, and the spinner has this spot while one loads.
        if (playback.playerRequest != null && !playback.isLoadingPlaylist && !playback.isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
                    .clickable(role = Role.Button) {
                        onTouch()
                        viewModel.togglePlayPause()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playback.isPlaying) Res.string.camera_pause else Res.string.camera_play),
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        TimelineSection(
            cameraName = cameraName,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = CHROME_SCRIM_ALPHA))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
            overVideo = true,
            onTouch = onTouch,
        )
    }
}

/**
 * One of the chrome's round buttons: glass, white, a full 48dp to tap. [active] true tints it in
 * the accent (the speaker, on); [available] false dims it but keeps it tappable, so the tap can
 * say why it did nothing — the same language as the page's quick actions.
 */
@Composable
private fun ChromeButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    available: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.alpha(if (available) 1f else UNAVAILABLE_CHROME_ALPHA),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else LocalFrigateExtraColors.current.glassFill,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
    }
}

/** The full-screen player's chrome, for tests: there while the controls are up over the video. */
const val FULL_SCREEN_CHROME_TEST_TAG = "camera_full_screen_chrome"

/** How dark the washes behind the top and bottom rows are at the window's edge. */
private const val CHROME_SCRIM_ALPHA = 0.72f

/** Material's disabled-content alpha, as the page's quick actions use for a control that can't act yet. */
private const val UNAVAILABLE_CHROME_ALPHA = 0.38f

/** Below the top row's buttons. */
private val HINT_TOP = 64.dp
