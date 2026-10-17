package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.StreamQuality
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_action_clip_description
import homesafe.shared.generated.resources.camera_hint_single_quality
import homesafe.shared.generated.resources.camera_hint_sound_off
import homesafe.shared.generated.resources.camera_hint_sound_on
import homesafe.shared.generated.resources.camera_hint_sound_on_no_audio
import homesafe.shared.generated.resources.camera_quality_auto
import homesafe.shared.generated.resources.camera_quality_auto_description
import homesafe.shared.generated.resources.camera_quality_button_description
import homesafe.shared.generated.resources.camera_quality_high
import homesafe.shared.generated.resources.camera_quality_high_description
import homesafe.shared.generated.resources.camera_quality_low
import homesafe.shared.generated.resources.camera_quality_low_description
import homesafe.shared.generated.resources.camera_quality_menu_title
import homesafe.shared.generated.resources.camera_quality_selected
import homesafe.shared.generated.resources.camera_sound_turn_off
import homesafe.shared.generated.resources.camera_sound_turn_on
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The bar along the bottom of the player: the LIVE pill and how far behind live the picture is on
 * its left, quality, speaker and clip on its right. It sits under the video, never over it, in the
 * player's own backdrop colour, so it reads as part of the player and leaves the whole picture
 * clear. None of the cameras have a microphone, so there is no two-way talk button. Quality and
 * sound are saved preferences shared by every camera. Collects `playback` itself so the position
 * polls that update it while a recording plays recompose only this bar.
 *
 * @param cameraAvailable whether there is a live stream to offer; without one there is no LIVE pill.
 * @param hasQualityChoice whether this camera has a second, lighter stream to switch to; without
 *   one the quality button still shows the saved choice but explains itself when tapped.
 * @param onClip opens the clip editor; given where the button sits, as a fraction of the window,
 *   which is where the editor's splash lands.
 */
@Composable
internal fun QuickActionsRow(
    cameraName: String,
    cameraAvailable: Boolean,
    hasQualityChoice: Boolean,
    showHint: (StringResource) -> Unit,
    onClip: (originFraction: Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = cameraDetailViewModel(cameraName)
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    CameraQuickActions(
        displayName = cameraDisplayName(cameraName),
        quality = playback.quality,
        hasQualityChoice = hasQualityChoice,
        isMuted = playback.isMuted,
        hasAudio = playback.hasAudio,
        onQualitySelect = viewModel::setQuality,
        onQualityUnavailable = { showHint(Res.string.camera_hint_single_quality) },
        onToggleSound = {
            showHint(soundToggleHint(wasMuted = playback.isMuted, hasAudio = playback.hasAudio))
            viewModel.toggleMuted()
        },
        onClip = onClip,
        modifier = modifier,
    ) {
        if (cameraAvailable) LivePill(isLive = playback.isLive, onClick = viewModel::goLive)
        val playhead = playback.scrubEpochSeconds ?: playback.playheadEpochSeconds
        if (playhead != null) BehindLiveReadout(playheadEpochSeconds = playhead, cameraName = cameraName, onVideo = false)
    }
}

/**
 * The bar itself, stateless: [leading] (the LIVE pill and the time) at the start, then three icon
 * buttons. Each says the state it is in without a caption: the quality button is the quality's
 * own short name in a badge, a choice that opens a menu saying what each option does; the speaker
 * is a toggle, its icon struck through when muted and tinted in the accent when on, so the accent
 * only ever means "on"; and the scissors are a plain action.
 *
 * The buttons keep their 48dp whatever the width. When [leading] doesn't fit beside them (a narrow
 * phone, enlarged text) it takes a row of its own above them rather than being cut short, and
 * within that row the pill and the time wrap onto separate lines if they must ([PlayerBarLayout]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CameraQuickActions(
    displayName: String,
    quality: StreamQuality,
    hasQualityChoice: Boolean,
    isMuted: Boolean,
    hasAudio: Boolean,
    onQualitySelect: (StreamQuality) -> Unit,
    onQualityUnavailable: () -> Unit,
    onToggleSound: () -> Unit,
    onClip: (originFraction: Offset) -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
) {
    PlayerBarLayout(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PLAYER_BAR_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(start = 16.dp, end = 4.dp),
        leading = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                content = leading,
            )
        },
    ) {
        // Quality: a picker rather than a cycle, so the options explain themselves before one is
        // chosen. Only the full stream carries audio, so SD also dims the speaker (through the
        // player's own audio-availability report).
        var qualityMenuOpen by remember { mutableStateOf(false) }
        Box {
            val qualityName = stringResource(qualityLabel(quality))
            PlayerBarButton(
                contentDescription = stringResource(Res.string.camera_quality_button_description, qualityName),
                available = hasQualityChoice,
                onClick = { if (hasQualityChoice) qualityMenuOpen = true else onQualityUnavailable() },
            ) {
                Text(
                    text = qualityName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            QualityMenu(
                expanded = qualityMenuOpen,
                selected = quality,
                onSelect = { choice ->
                    qualityMenuOpen = false
                    onQualitySelect(choice)
                },
                onDismiss = { qualityMenuOpen = false },
            )
        }
        // Speaker: the saved sound preference. It flips even while what's playing is silent
        // (go2rtc's sub-streams and Frigate's recordings are video-only), so the choice is
        // ready when audio arrives; the dimming says the stream has nothing to play right now.
        PlayerBarButton(
            contentDescription = stringResource(if (isMuted) Res.string.camera_sound_turn_on else Res.string.camera_sound_turn_off),
            available = hasAudio,
            onClick = onToggleSound,
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
        // Scissors: cut a clip from what's playing (or just behind live). The button remembers
        // where it is so the editor's splash can land exactly under the finger.
        val clipOrigin = remember { OriginProbe() }
        PlayerBarButton(
            contentDescription = stringResource(Res.string.camera_action_clip_description, displayName),
            onClick = { onClip(clipOrigin.fraction()) },
            modifier = Modifier.onGloballyPositioned { clipOrigin.coordinates = it },
        ) {
            Icon(imageVector = Icons.Filled.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * [leading] at the start and [buttons] at the end of one row, both centred on it, while they fit
 * side by side; otherwise [leading] on a row of its own, as wide as the bar, with the buttons on
 * the row under it, still at the end. Which it is comes from [leading]'s one-line width, so the
 * status is never squeezed narrower than it reads.
 */
@Composable
private fun PlayerBarLayout(leading: @Composable () -> Unit, modifier: Modifier = Modifier, buttons: @Composable RowScope.() -> Unit) {
    Layout(
        contents = listOf(leading, { Row(verticalAlignment = Alignment.CenterVertically, content = buttons) }),
        modifier = modifier,
    ) { (leadingMeasurables, buttonMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val buttonRow = buttonMeasurables.single().measure(loose)
        val leadingMeasurable = leadingMeasurables.single()
        val gap = PLAYER_BAR_GAP.roundToPx()
        val beside = (constraints.maxWidth - buttonRow.width - gap).coerceAtLeast(0)
        val fitsBeside = leadingMeasurable.maxIntrinsicWidth(Constraints.Infinity) <= beside
        if (fitsBeside) {
            val status = leadingMeasurable.measure(loose.copy(maxWidth = beside))
            val height = maxOf(constraints.minHeight, status.height, buttonRow.height)
            layout(constraints.maxWidth, height) {
                status.placeRelative(0, (height - status.height) / 2)
                buttonRow.placeRelative(constraints.maxWidth - buttonRow.width, (height - buttonRow.height) / 2)
            }
        } else {
            // A row of its own, kept as far from the bar's end as its start is (the bar's end padding is less, for the buttons' sake).
            val inset = PLAYER_BAR_STACKED_INSET.roundToPx()
            val top = PLAYER_BAR_STACKED_TOP.roundToPx()
            val status = leadingMeasurable.measure(loose.copy(maxWidth = (constraints.maxWidth - inset).coerceAtLeast(0)))
            val height = maxOf(constraints.minHeight, top + status.height + buttonRow.height)
            layout(constraints.maxWidth, height) {
                status.placeRelative(0, top)
                buttonRow.placeRelative(constraints.maxWidth - buttonRow.width, height - buttonRow.height)
            }
        }
    }
}

/**
 * What a tap on the speaker did, in a line: sound on, sound off, or on but with nothing to play
 * (go2rtc's sub-streams and Frigate's recordings are video-only).
 */
internal fun soundToggleHint(wasMuted: Boolean, hasAudio: Boolean): StringResource = when {
    wasMuted && !hasAudio -> Res.string.camera_hint_sound_on_no_audio
    wasMuted -> Res.string.camera_hint_sound_on
    else -> Res.string.camera_hint_sound_off
}

/** The three stream choices, each with a line on what it trades, the one in force ticked. */
@Composable
internal fun QualityMenu(expanded: Boolean, selected: StreamQuality, onSelect: (StreamQuality) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        // The same hairline as the camera screen's overflow menu.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
    ) {
        Text(
            text = stringResource(Res.string.camera_quality_menu_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        StreamQuality.entries.forEach { option ->
            DropdownMenuItem(
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = stringResource(qualityLabel(option)), style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = stringResource(qualityDescription(option)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = if (option == selected) {
                    { Icon(imageVector = Icons.Filled.Check, contentDescription = stringResource(Res.string.camera_quality_selected), tint = MaterialTheme.colorScheme.primary) }
                } else {
                    null
                },
                onClick = { onSelect(option) },
                // Two lines per option: room above and below so neighbouring options don't run together.
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * One of the bar's buttons: a full 48dp to tap, round ripple, [content] drawn in the middle and
 * read out as [contentDescription]. [available] false dims it but keeps it tappable, so the tap
 * can say why it did nothing.
 */
@Composable
private fun PlayerBarButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    available: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = PLAYER_BAR_BUTTON_SIZE, minHeight = PLAYER_BAR_BUTTON_SIZE)
            .clip(CircleShape)
            .alpha(if (available) 1f else UNAVAILABLE_ALPHA)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.clearAndSetSemantics {}) { content() }
    }
}

/** The quality button's badge: the short name a video menu would use for each stream. */
internal fun qualityLabel(quality: StreamQuality): StringResource = when (quality) {
    StreamQuality.AUTO -> Res.string.camera_quality_auto
    StreamQuality.HIGH -> Res.string.camera_quality_high
    StreamQuality.LOW -> Res.string.camera_quality_low
}

/** What each quality choice trades, for the line under it in the picker. */
internal fun qualityDescription(quality: StreamQuality): StringResource = when (quality) {
    StreamQuality.AUTO -> Res.string.camera_quality_auto_description
    StreamQuality.HIGH -> Res.string.camera_quality_high_description
    StreamQuality.LOW -> Res.string.camera_quality_low_description
}

/**
 * Where a quick action's button sits, captured on placement and read on tap: the centre of the
 * button as a fraction of the window, (0.5, 0.5) until it has been placed. A plain holder, not
 * state — nothing redraws when it moves; only the next tap reads it.
 */
internal class OriginProbe {
    var coordinates: LayoutCoordinates? = null

    fun fraction(): Offset {
        val placed = coordinates?.takeIf { it.isAttached } ?: return Offset(0.5f, 0.5f)
        val window = placed.findRootCoordinates().size
        if (window.width <= 0 || window.height <= 0) return Offset(0.5f, 0.5f)
        val centre = placed.boundsInRoot().center
        return Offset(centre.x / window.width, centre.y / window.height)
    }
}

/** Material's disabled-content alpha, for a control that's present but can't act yet. */
private const val UNAVAILABLE_ALPHA = 0.38f

/** The bar's height: a 48dp button with a little air above and below. */
private val PLAYER_BAR_HEIGHT = 52.dp
private val PLAYER_BAR_BUTTON_SIZE = 48.dp

/** The least room between the status and the buttons when they share the row. */
private val PLAYER_BAR_GAP = 8.dp

/** Stacked, how much the status row stops short of the bar's end, and how far it sits below the top. */
private val PLAYER_BAR_STACKED_INSET = 12.dp
private val PLAYER_BAR_STACKED_TOP = 10.dp
