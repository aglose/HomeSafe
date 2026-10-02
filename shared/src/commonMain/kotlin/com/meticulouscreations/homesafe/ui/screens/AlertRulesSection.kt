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
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.alerts_caption_poller
import homesafe.shared.generated.resources.alerts_caption_relay
import homesafe.shared.generated.resources.alerts_category_animals
import homesafe.shared.generated.resources.alerts_category_people
import homesafe.shared.generated.resources.alerts_category_vehicles
import homesafe.shared.generated.resources.alerts_chip_noisy
import homesafe.shared.generated.resources.alerts_custom
import homesafe.shared.generated.resources.alerts_custom_description
import homesafe.shared.generated.resources.alerts_fine_tune
import homesafe.shared.generated.resources.alerts_hide_zones
import homesafe.shared.generated.resources.alerts_loading_cameras
import homesafe.shared.generated.resources.alerts_no_cameras
import homesafe.shared.generated.resources.alerts_noisy_hint_day
import homesafe.shared.generated.resources.alerts_noisy_hint_week
import homesafe.shared.generated.resources.alerts_notifications
import homesafe.shared.generated.resources.alerts_notifications_blocked
import homesafe.shared.generated.resources.alerts_notifications_off
import homesafe.shared.generated.resources.alerts_notifications_on_poller
import homesafe.shared.generated.resources.alerts_notifications_on_relay
import homesafe.shared.generated.resources.alerts_notifications_unsupported
import homesafe.shared.generated.resources.alerts_only_strangers
import homesafe.shared.generated.resources.alerts_only_strangers_faces_off
import homesafe.shared.generated.resources.alerts_only_strangers_faces_unknown
import homesafe.shared.generated.resources.alerts_only_strangers_off
import homesafe.shared.generated.resources.alerts_only_strangers_on
import homesafe.shared.generated.resources.alerts_only_when_away
import homesafe.shared.generated.resources.alerts_only_when_away_off
import homesafe.shared.generated.resources.alerts_only_when_away_on
import homesafe.shared.generated.resources.alerts_open_notification_settings
import homesafe.shared.generated.resources.alerts_place_anywhere
import homesafe.shared.generated.resources.alerts_place_anywhere_else
import homesafe.shared.generated.resources.alerts_preset_everything
import homesafe.shared.generated.resources.alerts_preset_everything_description
import homesafe.shared.generated.resources.alerts_preset_people_driveway_cars
import homesafe.shared.generated.resources.alerts_preset_people_driveway_cars_description
import homesafe.shared.generated.resources.alerts_preset_people_only
import homesafe.shared.generated.resources.alerts_preset_people_only_description
import homesafe.shared.generated.resources.alerts_preset_people_vehicles
import homesafe.shared.generated.resources.alerts_preset_people_vehicles_description
import homesafe.shared.generated.resources.alerts_quiet_from_button
import homesafe.shared.generated.resources.alerts_quiet_from_title
import homesafe.shared.generated.resources.alerts_quiet_hours
import homesafe.shared.generated.resources.alerts_quiet_hours_empty
import homesafe.shared.generated.resources.alerts_quiet_hours_off
import homesafe.shared.generated.resources.alerts_quiet_hours_on
import homesafe.shared.generated.resources.alerts_quiet_set
import homesafe.shared.generated.resources.alerts_quiet_to_button
import homesafe.shared.generated.resources.alerts_quiet_until_title
import homesafe.shared.generated.resources.alerts_relay_policy
import homesafe.shared.generated.resources.alerts_relay_what_youll_hear
import homesafe.shared.generated.resources.alerts_send_test
import homesafe.shared.generated.resources.alerts_test_sent
import homesafe.shared.generated.resources.alerts_time_am
import homesafe.shared.generated.resources.alerts_time_pm
import homesafe.shared.generated.resources.alerts_title
import homesafe.shared.generated.resources.alerts_what_to_hear
import homesafe.shared.generated.resources.alerts_zones_caption
import homesafe.shared.generated.resources.common_cancel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The Settings tab's "Alerts" section: the notifications switch and, once it's on, what to hear
 * about and when. The rules are per place — every zone on every camera, plus each camera's
 * "anywhere else" — which on a real install is a couple of dozen switches, so they're led by a
 * row of presets and only laid out in full when the user asks, or when they've been tuned into
 * something no preset describes. Where last week's detections say a rule would be noisy, its
 * switch says how noisy.
 *
 * The rules only drive the in-app poller, which stands down on a phone the relay pushes to
 * ([SettingsUiState.relayPushes]). There the relay decides what's worth a sound, so the rules and
 * presets give way to a line saying what that is.
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
    SettingsSection(title = stringResource(Res.string.alerts_title), icon = Icons.Filled.Notifications) {
        if (!state.notificationsSupported) {
            SettingsToggleRow(
                title = stringResource(Res.string.alerts_notifications),
                description = stringResource(Res.string.alerts_notifications_unsupported),
                checked = false,
                enabled = false,
                onCheckedChange = {},
            )
            return@SettingsSection
        }
        val blocked = state.notificationPermission == NotificationPermission.DENIED
        SettingsToggleRow(
            title = stringResource(Res.string.alerts_notifications),
            description = stringResource(
                when {
                    blocked -> Res.string.alerts_notifications_blocked
                    state.pushNotificationsActive && state.relayPushes -> Res.string.alerts_notifications_on_relay
                    state.pushNotificationsActive -> Res.string.alerts_notifications_on_poller
                    else -> Res.string.alerts_notifications_off
                },
            ),
            checked = state.pushNotificationsActive,
            enabled = !blocked,
            onCheckedChange = onPushNotifications,
        )
        if (blocked) {
            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(Res.string.alerts_open_notification_settings)) }
        }
        if (state.pushNotificationsActive) {
            val alerts = state.alerts
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            SettingsToggleRow(
                title = stringResource(Res.string.alerts_only_when_away),
                description = stringResource(if (alerts.onlyWhenAway) Res.string.alerts_only_when_away_on else Res.string.alerts_only_when_away_off),
                checked = alerts.onlyWhenAway,
                onCheckedChange = onOnlyWhenAway,
            )
            // With only Away alerts left, the rules below would decide nothing: Away alerts ignore
            // zones, quiet hours and the stranger rule alike. They keep their values for later.
            if (!alerts.onlyWhenAway) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                if (state.relayPushes) {
                    RelayAlertPolicy()
                } else {
                    AlertRules(state, onPreset = onPreset, onZoneCategory = onZoneCategory, onLoadVolume = onLoadVolume)
                }
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
                    title = stringResource(Res.string.alerts_only_strangers),
                    description = stringResource(
                        when (faces) {
                            true -> if (alerts.quietFamiliarPeople) Res.string.alerts_only_strangers_on else Res.string.alerts_only_strangers_off
                            false -> Res.string.alerts_only_strangers_faces_off
                            null -> Res.string.alerts_only_strangers_faces_unknown
                        },
                    ),
                    checked = faces == true && alerts.quietFamiliarPeople,
                    enabled = faces == true,
                    onCheckedChange = onQuietFamiliar,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSendTest) { Text(stringResource(Res.string.alerts_send_test)) }
                if (state.testNotificationSent) SettingsCaption(stringResource(Res.string.alerts_test_sent))
            }
            SettingsCaption(stringResource(if (state.relayPushes) Res.string.alerts_caption_relay else Res.string.alerts_caption_poller))
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
        SettingsCaption(stringResource(Res.string.alerts_loading_cameras))
        return
    }
    if (cameras.isEmpty()) {
        SettingsCaption(stringResource(Res.string.alerts_no_cameras))
        return
    }
    // Asked once per visit; the view model reads the server at most once however often this runs.
    LaunchedEffect(onLoadVolume) { onLoadVolume() }
    val places = state.alertPlaces
    val preset = state.alerts.matchingPreset(places)
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(Res.string.alerts_what_to_hear), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        AlertPresetChips(
            offered = AlertPreset.offeredFor(places),
            selected = preset,
            onPreset = onPreset,
        )
        SettingsCaption(stringResource(preset?.let(::presetDescription) ?: Res.string.alerts_custom_description))
        noisiestRuleHint(state.alerts, cameras, state.alertVolume)?.let { hint ->
            Text(text = hint.resolve(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        // A custom set of rules is only readable as the grid, so it's always shown then.
        val showGrid = expanded || preset == null
        if (preset != null) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) Res.string.alerts_hide_zones else Res.string.alerts_fine_tune))
            }
        }
        if (showGrid) {
            SettingsCaption(stringResource(Res.string.alerts_zones_caption))
            cameras.forEach { camera ->
                CameraAlertZones(camera = camera, alerts = state.alerts, volume = state.alertVolume, onZoneCategory = onZoneCategory)
            }
        }
    }
}

/**
 * What a phone the relay pushes to hears, in place of the zone rules: the relay's own policy for
 * when someone is home (see `docs/away-mode.md`), which no per-zone switch here would change.
 */
@Composable
private fun RelayAlertPolicy() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(Res.string.alerts_relay_what_youll_hear), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        SettingsCaption(stringResource(Res.string.alerts_relay_policy))
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
                label = { Text(stringResource(presetLabel(preset))) },
                colors = alertChipColors(),
            )
        }
        if (selected == null) {
            FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(stringResource(Res.string.alerts_custom)) }, colors = alertChipColors())
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
            title = stringResource(Res.string.alerts_quiet_hours),
            description = if (enabled) {
                stringResource(Res.string.alerts_quiet_hours_on, formatMinuteOfDay(startMinute), formatMinuteOfDay(endMinute))
            } else {
                stringResource(Res.string.alerts_quiet_hours_off)
            },
            checked = enabled,
            onCheckedChange = { onChange(window.copy(enabled = it)) },
        )
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { editing = true }) { Text(stringResource(Res.string.alerts_quiet_from_button, formatMinuteOfDay(startMinute))) }
                OutlinedButton(onClick = { editing = false }) { Text(stringResource(Res.string.alerts_quiet_to_button, formatMinuteOfDay(endMinute))) }
            }
            if (startMinute == endMinute) {
                SettingsCaption(stringResource(Res.string.alerts_quiet_hours_empty), error = true)
            }
        }
    }
    val start = editing ?: return
    val initial = if (start) startMinute else endMinute
    val pickerState = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = { editing = null },
        title = { Text(stringResource(if (start) Res.string.alerts_quiet_from_title else Res.string.alerts_quiet_until_title)) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = {
                val minute = pickerState.hour * 60 + pickerState.minute
                onChange(if (start) window.copy(startMinute = minute) else window.copy(endMinute = minute))
                editing = null
            }) { Text(stringResource(Res.string.alerts_quiet_set)) }
        },
        dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(Res.string.common_cancel)) } },
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
                Text(text = label.resolve(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ALERT_CATEGORIES.forEach { (category, nameRes) ->
                        val selected = category in chosen
                        val perDay = volume?.perDay(place, category) ?: 0.0
                        val name = stringResource(nameRes)
                        FilterChip(
                            selected = selected,
                            onClick = { onZoneCategory(place, category, !selected) },
                            label = { Text(if (perDay >= AlertVolume.NOISY_PER_DAY) stringResource(Res.string.alerts_chip_noisy, name, perDay.roundToInt()) else name) },
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
private fun alertPlacesOf(camera: CameraPipeline): List<Pair<AlertZone, UiText>> =
    camera.zones.map { AlertZone(camera.name, it.name) to it.displayName.asUiText() } +
        (AlertZone(camera.name, null) to UiText.of(if (camera.zones.isEmpty()) Res.string.alerts_place_anywhere else Res.string.alerts_place_anywhere_else))

/**
 * "Front Yard · Street · Vehicles would have alerted about 280 times last week." — the loudest
 * rule that's switched on, if it's loud enough to be worth a word; null otherwise, or before the
 * estimate has arrived. When the sample ran out short of a week the rate is quoted per day.
 */
private fun noisiestRuleHint(alerts: AlertSettings, cameras: List<CameraPipeline>, volume: AlertVolume?): UiText? {
    volume ?: return null
    val loudest = cameras.flatMap { camera -> alertPlacesOf(camera).map { (place, label) -> Triple(camera, place, label) } }
        .flatMap { (camera, place, label) -> alerts.categoriesFor(place).map { category -> Triple(camera.displayName to label, category, volume.perDay(place, category)) } }
        .filter { (_, _, perDay) -> perDay >= AlertVolume.NOISY_PER_DAY }
        .maxByOrNull { (_, _, perDay) -> perDay }
        ?: return null
    val (where, category, perDay) = loudest
    val (cameraName, place) = where
    val name = UiText.of(ALERT_CATEGORIES.first { it.first == category }.second)
    return if (volume.days >= FULL_WEEK_DAYS) {
        val times = (perDay * 7).roundToInt()
        UiText.plural(Res.plurals.alerts_noisy_hint_week, times, cameraName, place, name, times)
    } else {
        val times = perDay.roundToInt()
        UiText.plural(Res.plurals.alerts_noisy_hint_day, times, cameraName, place, name, times)
    }
}

private fun presetLabel(preset: AlertPreset): StringResource = when (preset) {
    AlertPreset.PEOPLE_ONLY -> Res.string.alerts_preset_people_only
    AlertPreset.PEOPLE_AND_DRIVEWAY_CARS -> Res.string.alerts_preset_people_driveway_cars
    AlertPreset.PEOPLE_AND_VEHICLES -> Res.string.alerts_preset_people_vehicles
    AlertPreset.EVERYTHING -> Res.string.alerts_preset_everything
}

private fun presetDescription(preset: AlertPreset): StringResource = when (preset) {
    AlertPreset.PEOPLE_ONLY -> Res.string.alerts_preset_people_only_description
    AlertPreset.PEOPLE_AND_DRIVEWAY_CARS -> Res.string.alerts_preset_people_driveway_cars_description
    AlertPreset.PEOPLE_AND_VEHICLES -> Res.string.alerts_preset_people_vehicles_description
    AlertPreset.EVERYTHING -> Res.string.alerts_preset_everything_description
}

/** "10:00 PM" — minutes after midnight, on the same 12-hour clock as the rest of the app. */
@Composable
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour = (minuteOfDay / 60) % 24
    val minute = minuteOfDay % 60
    return stringResource(if (hour < 12) Res.string.alerts_time_am else Res.string.alerts_time_pm, (hour + 11) % 12 + 1, minute.toString().padStart(2, '0'))
}

/** A week's estimate, give or take the hour the sample might fall short by. */
private const val FULL_WEEK_DAYS = 6.9

private val ALERT_CATEGORIES = listOf(
    MomentCategory.PEOPLE to Res.string.alerts_category_people,
    MomentCategory.VEHICLES to Res.string.alerts_category_vehicles,
    MomentCategory.ANIMALS to Res.string.alerts_category_animals,
)
