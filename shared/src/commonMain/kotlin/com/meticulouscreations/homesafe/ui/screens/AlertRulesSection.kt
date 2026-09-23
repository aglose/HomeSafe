package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.AlertPreset
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertVolume
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.QuietHours
import com.meticulouscreations.homesafe.domain.model.matchingPreset
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import kotlin.math.roundToInt

/**
 * The Settings tab's "Alerts" section: the notifications switch and, once it's on, what to hear
 * about and when. The rules are per place — every zone on every camera, plus each camera's
 * "anywhere else" — which on a real install is a couple of dozen switches, so they're led by a
 * row of presets and only laid out in full when the user asks, or when they've been tuned into
 * something no preset describes. Where last week's detections say a rule would be noisy, its
 * switch says how noisy.
 */
@Composable
internal fun AlertsSection(
    state: SettingsUiState,
    onPushNotifications: (Boolean) -> Unit,
    onZoneCategory: (AlertZone, MomentCategory, Boolean) -> Unit,
    onPreset: (AlertPreset) -> Unit,
    onQuietHours: (QuietHours) -> Unit,
    onOnlyWhenAway: (Boolean) -> Unit,
    onQuietFamiliar: (Boolean) -> Unit,
    onLoadVolume: () -> Unit,
    onOpenSettings: () -> Unit,
    onSendTest: () -> Unit,
) {
    SettingsSection(title = "Alerts", icon = Icons.Filled.Notifications) {
        if (!state.notificationsSupported) {
            SettingsToggleRow(
                title = "Notifications",
                description = "Not available on this platform. Use the Android or iOS app for alerts.",
                checked = false,
                enabled = false,
                onCheckedChange = {},
            )
            return@SettingsSection
        }
        val blocked = state.notificationPermission == NotificationPermission.DENIED
        SettingsToggleRow(
            title = "Notifications",
            description = when {
                blocked -> "Blocked in system settings. Allow notifications for HomeSafe to turn this on."
                state.pushNotificationsActive -> "On — a notification for each new detection while HomeSafe is running."
                else -> "Get a notification when a camera sees something."
            },
            checked = state.pushNotificationsActive,
            enabled = !blocked,
            onCheckedChange = onPushNotifications,
        )
        if (blocked) {
            OutlinedButton(onClick = onOpenSettings) { Text("Open notification settings") }
        }
        if (state.pushNotificationsActive) {
            val alerts = state.alerts
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            SettingsToggleRow(
                title = "Only when everyone's away",
                description = if (alerts.onlyWhenAway) {
                    "On — quiet while anyone's home. Once every phone is away, each person seen notifies on the Away alerts channel."
                } else {
                    "Stay quiet while anyone's home, and hear only the Away alerts once the house is empty."
                },
                checked = alerts.onlyWhenAway,
                onCheckedChange = onOnlyWhenAway,
            )
            // With only Away alerts left, the rules below would decide nothing: Away alerts ignore
            // zones, quiet hours and the stranger rule alike. They keep their values for later.
            if (!alerts.onlyWhenAway) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                AlertRules(state, onPreset = onPreset, onZoneCategory = onZoneCategory, onLoadVolume = onLoadVolume)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                QuietHoursRows(
                    enabled = alerts.quietHours.enabled,
                    startMinute = alerts.quietHours.startMinute,
                    endMinute = alerts.quietHours.endMinute,
                    onChange = onQuietHours,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                val faces = state.overview?.faceRecognitionEnabled
                SettingsToggleRow(
                    title = "Only strangers",
                    description = when (faces) {
                        true -> if (alerts.quietFamiliarPeople) {
                            "On — people Frigate recognises come and go quietly. Anyone it can't place still notifies."
                        } else {
                            "Skip the notification when Frigate recognises the person. Name faces under Recognition below."
                        }

                        false -> "Needs face recognition, which is off in Frigate's config."

                        null -> "Needs face recognition on the server."
                    },
                    checked = faces == true && alerts.quietFamiliarPeople,
                    enabled = faces == true,
                    onCheckedChange = onQuietFamiliar,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSendTest) { Text("Send test notification") }
                if (state.testNotificationSent) SettingsCaption("Sent")
            }
            SettingsCaption(
                "HomeSafe checks Frigate for new detections every 15 seconds while it's open or recently in the background. " +
                    "Frigate has no push service for phones, so nothing arrives once the system stops the app.",
            )
        }
    }
}

/**
 * What to hear about: the presets, which one the rules currently amount to ("Custom" when none),
 * a warning for the loudest rule that's on, and the per-place grid behind a "Fine-tune" toggle.
 */
@Composable
private fun AlertRules(
    state: SettingsUiState,
    onPreset: (AlertPreset) -> Unit,
    onZoneCategory: (AlertZone, MomentCategory, Boolean) -> Unit,
    onLoadVolume: () -> Unit,
) {
    val cameras = state.overview?.cameras?.filter { it.enabled }
    if (cameras == null) {
        SettingsCaption("Loading cameras…")
        return
    }
    if (cameras.isEmpty()) {
        SettingsCaption("No cameras on this server.")
        return
    }
    // Asked once per visit; the view model reads the server at most once however often this runs.
    LaunchedEffect(onLoadVolume) { onLoadVolume() }
    val places = state.alertPlaces
    val preset = state.alerts.matchingPreset(places)
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "What to hear about", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        AlertPresetChips(
            offered = AlertPreset.offeredFor(places),
            selected = preset,
            onPreset = onPreset,
        )
        SettingsCaption(preset?.let(::presetDescription) ?: "Custom — tuned place by place below.")
        noisiestRuleHint(state.alerts, cameras, state.alertVolume)?.let { hint ->
            Text(text = hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        // A custom set of rules is only readable as the grid, so it's always shown then.
        val showGrid = expanded || preset == null
        if (preset != null) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide zones" else "Fine-tune by zone")
            }
        }
        if (showGrid) {
            SettingsCaption("Zones come from each camera's detection zones.")
            cameras.forEach { camera ->
                CameraAlertZones(camera = camera, alerts = state.alerts, volume = state.alertVolume, onZoneCategory = onZoneCategory)
            }
        }
    }
}

/** One chip per offered preset, and a "Custom" chip — shown selected, not tappable — when the rules match none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertPresetChips(offered: List<AlertPreset>, selected: AlertPreset?, onPreset: (AlertPreset) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        offered.forEach { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onPreset(preset) },
                label = { Text(presetLabel(preset)) },
                colors = alertChipColors(),
            )
        }
        if (selected == null) {
            FilterChip(selected = true, onClick = {}, enabled = false, label = { Text("Custom") }, colors = alertChipColors())
        }
    }
}

/**
 * The quiet-hours switch and, while it's on, the two ends of the window as buttons that open a
 * clock. The window is kept while the switch is off, so it comes back as the user left it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursRows(enabled: Boolean, startMinute: Int, endMinute: Int, onChange: (QuietHours) -> Unit) {
    val window = QuietHours(enabled, startMinute, endMinute)
    // Which end of the window the clock dialog is picking, or null while it's closed.
    var editing by rememberSaveable { mutableStateOf<Boolean?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsToggleRow(
            title = "Quiet hours",
            description = if (enabled) {
                "On — nothing from ${formatMinuteOfDay(startMinute)} to ${formatMinuteOfDay(endMinute)}. Away alerts still come through."
            } else {
                "Silence ordinary alerts overnight. Away alerts still come through."
            },
            checked = enabled,
            onCheckedChange = { onChange(window.copy(enabled = it)) },
        )
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { editing = true }) { Text("From ${formatMinuteOfDay(startMinute)}") }
                OutlinedButton(onClick = { editing = false }) { Text("To ${formatMinuteOfDay(endMinute)}") }
            }
            if (startMinute == endMinute) {
                SettingsCaption("The window starts where it ends, so it's empty. Pick a different end time.", error = true)
            }
        }
    }
    val start = editing ?: return
    val initial = if (start) startMinute else endMinute
    val pickerState = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = { editing = null },
        title = { Text(if (start) "Quiet from" else "Quiet until") },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = {
                val minute = pickerState.hour * 60 + pickerState.minute
                onChange(if (start) window.copy(startMinute = minute) else window.copy(endMinute = minute))
                editing = null
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
    )
}

/**
 * One camera's places — each drawn zone, then "anywhere else" — with a chip per category that
 * alerts there. A chip reflects the effective choice, so a zone the user never touched shows
 * the defaults rather than nothing. A chip whose rule would have fired often last week says how
 * often, on or off, so a noisy one can be spotted before it's switched on as well as after.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraAlertZones(
    camera: CameraPipeline,
    alerts: AlertSettings,
    volume: AlertVolume?,
    onZoneCategory: (AlertZone, MomentCategory, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = camera.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        alertPlacesOf(camera).forEach { (place, label) ->
            val chosen = alerts.categoriesFor(place)
            Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ALERT_CATEGORIES.forEach { (category, name) ->
                        val selected = category in chosen
                        val perDay = volume?.perDay(place, category) ?: 0.0
                        FilterChip(
                            selected = selected,
                            onClick = { onZoneCategory(place, category, !selected) },
                            label = { Text(if (perDay >= AlertVolume.NOISY_PER_DAY) "$name · ~${perDay.roundToInt()}/day" else name) },
                            colors = alertChipColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun alertChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    // Only the "Custom" chip is ever disabled: it's a state, not a choice, but it still reads as the selected one.
    disabledSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
    disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/** A camera's places with the name each goes by on screen; the same order as [SettingsUiState.alertPlaces]. */
private fun alertPlacesOf(camera: CameraPipeline): List<Pair<AlertZone, String>> =
    camera.zones.map { AlertZone(camera.name, it.name) to it.displayName } +
        (AlertZone(camera.name, null) to if (camera.zones.isEmpty()) "Anywhere" else "Anywhere else")

/**
 * "Front Yard · Street · Vehicles would have alerted about 280 times last week." — the loudest
 * rule that's switched on, if it's loud enough to be worth a word; null otherwise, or before the
 * estimate has arrived. When the sample ran out short of a week the rate is quoted per day.
 */
private fun noisiestRuleHint(alerts: AlertSettings, cameras: List<CameraPipeline>, volume: AlertVolume?): String? {
    volume ?: return null
    val loudest = cameras.flatMap { camera -> alertPlacesOf(camera).map { (place, label) -> Triple(camera, place, label) } }
        .flatMap { (camera, place, label) -> alerts.categoriesFor(place).map { category -> Triple("${camera.displayName} · $label", category, volume.perDay(place, category)) } }
        .filter { (_, _, perDay) -> perDay >= AlertVolume.NOISY_PER_DAY }
        .maxByOrNull { (_, _, perDay) -> perDay }
        ?: return null
    val (where, category, perDay) = loudest
    val name = ALERT_CATEGORIES.first { it.first == category }.second
    val often = if (volume.days >= FULL_WEEK_DAYS) "about ${(perDay * 7).roundToInt()} times last week" else "about ${perDay.roundToInt()} times a day lately"
    return "$where · $name would have alerted $often."
}

private fun presetLabel(preset: AlertPreset): String = when (preset) {
    AlertPreset.PEOPLE_ONLY -> "People only"
    AlertPreset.PEOPLE_AND_DRIVEWAY_CARS -> "People + driveway cars"
    AlertPreset.PEOPLE_AND_VEHICLES -> "People + vehicles"
    AlertPreset.EVERYTHING -> "Everything"
}

private fun presetDescription(preset: AlertPreset): String = when (preset) {
    AlertPreset.PEOPLE_ONLY -> "People anywhere on any camera. Cars and animals stay quiet."
    AlertPreset.PEOPLE_AND_DRIVEWAY_CARS -> "People anywhere, and cars only in zones named like a driveway, garage or parking spot — not passing traffic."
    AlertPreset.PEOPLE_AND_VEHICLES -> "People and vehicles anywhere, street traffic included. The out-of-the-box rules."
    AlertPreset.EVERYTHING -> "People, vehicles and animals anywhere."
}

/** "10:00 PM" — minutes after midnight, on the same 12-hour clock as the rest of the app. */
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour = (minuteOfDay / 60) % 24
    val minute = minuteOfDay % 60
    return "${(hour + 11) % 12 + 1}:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

/** A week's estimate, give or take the hour the sample might fall short by. */
private const val FULL_WEEK_DAYS = 6.9

private val ALERT_CATEGORIES = listOf(
    MomentCategory.PEOPLE to "People",
    MomentCategory.VEHICLES to "Vehicles",
    MomentCategory.ANIMALS to "Animals",
)
