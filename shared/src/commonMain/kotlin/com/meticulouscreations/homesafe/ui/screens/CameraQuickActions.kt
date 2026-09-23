package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.StreamQuality
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName

/**
 * Quality, speaker and alerts under the player. The speaker takes the prominent centre slot
 * (none of the cameras have a microphone, so there is no two-way talk button). Quality and
 * sound are saved preferences shared by every camera. Collects `playback` and `alerts` itself
 * so the position polls that update `playback` while a recording plays recompose only this row.
 *
 * @param hasQualityChoice whether this camera has a second, lighter stream to switch to; without
 *   one the quality button still shows the saved choice but explains itself when tapped.
 */
@Composable
internal fun QuickActionsRow(
    cameraName: String,
    hasQualityChoice: Boolean,
    showHint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = cameraDetailViewModel(cameraName)
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val displayName = cameraDisplayName(cameraName)
    CameraQuickActions(
        displayName = displayName,
        quality = playback.quality,
        hasQualityChoice = hasQualityChoice,
        isMuted = playback.isMuted,
        hasAudio = playback.hasAudio,
        alertsEnabled = alerts.enabled,
        onQualitySelect = viewModel::setQuality,
        onQualityUnavailable = { showHint("This camera has a single stream quality") },
        onToggleSound = {
            val soundOn = playback.isMuted
            viewModel.toggleMuted()
            showHint(
                when {
                    soundOn && !playback.hasAudio -> "Sound on, but this stream has no audio"
                    soundOn -> "Sound on"
                    else -> "Sound off"
                },
            )
        },
        onToggleAlerts = {
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
        modifier = modifier,
    )
}

/**
 * The row itself, stateless: three round buttons, each captioned with the state it is in, so the
 * row reads at a glance without long-pressing anything. The two toggles share one visual language
 * — filled in the accent when on, a quiet outline with a struck-through icon when off — and the
 * quality button, which is a choice rather than a toggle, opens a menu that says what each option
 * does.
 *
 * The circles straddle the player's bottom edge (the row is pulled up over it) while the
 * captions fall below the video, clear of the "behind live" readout in the player's corner.
 */
@Composable
internal fun CameraQuickActions(
    displayName: String,
    quality: StreamQuality,
    hasQualityChoice: Boolean,
    isMuted: Boolean,
    hasAudio: Boolean,
    alertsEnabled: Boolean,
    onQualitySelect: (StreamQuality) -> Unit,
    onQualityUnavailable: () -> Unit,
    onToggleSound: () -> Unit,
    onToggleAlerts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .offset(y = -QUICK_ACTION_OVERLAP)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        // Quality: a picker rather than a cycle, so the options explain themselves before one is
        // chosen. Only the full stream carries audio, so SD also dims the speaker (through the
        // player's own audio-availability report).
        var qualityMenuOpen by remember { mutableStateOf(false) }
        Box {
            QuickActionButton(
                icon = Icons.Filled.Tune,
                label = qualityLabel(quality),
                contentDescription = "Video quality: ${qualityLabel(quality)}",
                available = hasQualityChoice,
                onClick = { if (hasQualityChoice) qualityMenuOpen = true else onQualityUnavailable() },
            )
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
        QuickActionButton(
            icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            label = soundLabel(isMuted),
            contentDescription = if (isMuted) "Turn sound on" else "Turn sound off",
            active = !isMuted,
            available = hasAudio,
            size = PRIMARY_QUICK_ACTION_SIZE,
            iconSize = PRIMARY_QUICK_ACTION_ICON_SIZE,
            onClick = onToggleSound,
        )
        // Bell: this camera's alerts, the same rules the Settings tab edits place by place.
        QuickActionButton(
            icon = if (alertsEnabled) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
            label = alertsLabel(alertsEnabled),
            contentDescription = if (alertsEnabled) "Turn off alerts for $displayName" else "Turn on alerts for $displayName",
            active = alertsEnabled,
            onClick = onToggleAlerts,
        )
    }
}

/** The three stream choices, each with a line on what it trades, the one in force ticked. */
@Composable
private fun QualityMenu(expanded: Boolean, selected: StreamQuality, onSelect: (StreamQuality) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        // The same hairline as the camera screen's overflow menu.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
    ) {
        Text(
            text = "Video quality",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        StreamQuality.entries.forEach { option ->
            DropdownMenuItem(
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = qualityLabel(option), style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = qualityDescription(option),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = if (option == selected) {
                    { Icon(imageVector = Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
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
 * A round action under the player, captioned with [label]. [active] fills it in the accent (a
 * toggle that's on) and null means it isn't a toggle at all, so it keeps the accent tint without
 * the fill; a toggle that's off goes quiet, so the accent only ever means "on". [available] false
 * dims it but keeps it tappable, so the tap can say why it did nothing.
 *
 * The caption is part of the button — one tap target, read out together with the icon's
 * description — and every button sits in the same fixed-width slot, so a caption changing
 * length ("Alerts on" to "Alerts off") never nudges its neighbours.
 */
@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean? = null,
    available: Boolean = true,
    size: Dp = QUICK_ACTION_SIZE,
    iconSize: Dp = QUICK_ACTION_ICON_SIZE,
) {
    val on = active == true
    val background = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    val tint = when (active) {
        true -> MaterialTheme.colorScheme.onPrimaryContainer
        false -> MaterialTheme.colorScheme.onSurfaceVariant
        null -> MaterialTheme.colorScheme.primary
    }
    // The press ripples on the circle, though the whole slot (caption included) takes the tap.
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(QUICK_ACTION_SLOT_WIDTH)
            .alpha(if (available) 1f else UNAVAILABLE_ALPHA)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Every circle is centred in a box as tall as the largest one, so the three line up
        // through their middles and the captions share a baseline.
        Box(modifier = Modifier.size(PRIMARY_QUICK_ACTION_SIZE), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(background, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (on) 0f else 0.2f), CircleShape)
                    .indication(interactionSource, ripple()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The quality button's caption: the short name a video menu would use for each stream. */
internal fun qualityLabel(quality: StreamQuality): String = when (quality) {
    StreamQuality.AUTO -> "Auto"
    StreamQuality.HIGH -> "HD"
    StreamQuality.LOW -> "SD"
}

/** What each quality choice trades, for the line under it in the picker. */
internal fun qualityDescription(quality: StreamQuality): String = when (quality) {
    StreamQuality.AUTO -> "Quick to start, then sharpens"
    StreamQuality.HIGH -> "Full resolution, with the camera's sound"
    StreamQuality.LOW -> "Lighter on data, no sound"
}

/** The speaker's caption. The saved preference, not whether the current stream has audio — the dimming says that. */
internal fun soundLabel(isMuted: Boolean): String = if (isMuted) "Muted" else "Sound on"

internal fun alertsLabel(enabled: Boolean): String = if (enabled) "Alerts on" else "Alerts off"

/** How far the row is pulled up over the player, so the circles straddle its bottom edge. */
private val QUICK_ACTION_OVERLAP = 32.dp

/** Material's disabled-content alpha, for a control that's present but can't act yet. */
private const val UNAVAILABLE_ALPHA = 0.38f
private val QUICK_ACTION_SIZE = 64.dp
private val QUICK_ACTION_ICON_SIZE = 24.dp
private val PRIMARY_QUICK_ACTION_SIZE = 80.dp
private val PRIMARY_QUICK_ACTION_ICON_SIZE = 32.dp
private val QUICK_ACTION_SLOT_WIDTH = 80.dp
