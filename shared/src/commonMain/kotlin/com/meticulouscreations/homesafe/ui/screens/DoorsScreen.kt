package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.doorDayText
import com.meticulouscreations.homesafe.domain.model.doorHealthText
import com.meticulouscreations.homesafe.domain.model.doorStateText
import com.meticulouscreations.homesafe.domain.model.label
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.viewmodel.DoorHistoryDay
import com.meticulouscreations.homesafe.viewmodel.DoorHistoryEntry
import com.meticulouscreations.homesafe.viewmodel.DoorsUiState
import com.meticulouscreations.homesafe.viewmodel.DoorsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_history_closed
import homesafe.shared.generated.resources.doors_history_empty
import homesafe.shared.generated.resources.doors_history_notified
import homesafe.shared.generated.resources.doors_history_opened
import homesafe.shared.generated.resources.doors_history_title
import homesafe.shared.generated.resources.doors_name_it
import homesafe.shared.generated.resources.doors_none_body
import homesafe.shared.generated.resources.doors_none_body_pair
import homesafe.shared.generated.resources.doors_none_title
import homesafe.shared.generated.resources.doors_not_listening
import homesafe.shared.generated.resources.doors_notify_caption
import homesafe.shared.generated.resources.doors_notify_title
import homesafe.shared.generated.resources.doors_rename_action
import homesafe.shared.generated.resources.doors_subtitle
import homesafe.shared.generated.resources.doors_title
import homesafe.shared.generated.resources.doors_unsupported
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The door sensors (docs/doors.md): each door, how it stands and how its sensor is doing, when its
 * openings notify, and the last week of openings and closings. Opened from the Doors strip on
 * Home and from Settings.
 */
@Composable
fun DoorsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: DoorsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    SettingsPage(
        title = stringResource(Res.string.doors_title),
        subtitle = stringResource(Res.string.doors_subtitle),
        onBack = onBack,
        modifier = modifier,
        onRefresh = viewModel::refresh,
    ) {
        DoorsContent(
            state = state,
            onNotify = viewModel::setNotify,
            plan = DoorsPlanActions(
                onSelectFloor = viewModel::selectFloor,
                onStartPlacing = viewModel::startPlacing,
                onFinishPlacing = viewModel::finishPlacing,
                onChoosePlacingDoor = viewModel::choosePlacingDoor,
                onPlaceAt = viewModel::placeAt,
                onRemoveFromPlan = viewModel::removeFromPlan,
            ),
            pairing = DoorsPairingActions(
                onStartPairing = viewModel::startPairing,
                onStopPairing = viewModel::stopPairing,
                onRename = viewModel::rename,
            ),
        )
    }
}

/**
 * The page under its header: the floor plan with the doors on it when the box has one
 * ([DoorsFloorPlan], [plan]), pairing a sensor ([DoorPairingCard]), each door's card, and the
 * history. A door's dot on the plan scrolls its card into view.
 */
@Composable
internal fun DoorsContent(
    state: DoorsUiState,
    onNotify: (doorId: String, notify: DoorNotify) -> Unit,
    plan: DoorsPlanActions = DoorsPlanActions(),
    pairing: DoorsPairingActions = DoorsPairingActions(),
) {
    val cards = remember { mutableMapOf<String, BringIntoViewRequester>() }
    val scope = rememberCoroutineScope()
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    state.doors.firstOrNull { it.id == renaming }?.let { door ->
        DoorRenameDialog(
            door = door,
            onDismiss = { renaming = null },
            onSave = { name ->
                pairing.onRename(door.id, name)
                renaming = null
            },
        )
    }
    state.error?.let { SettingsCaption(it.resolve(), error = true) }
    when {
        !state.supported -> SettingsCard { SettingsCaption(stringResource(Res.string.doors_unsupported)) }

        !state.loaded -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        else -> {
            if (!state.listening) SettingsCaption(stringResource(Res.string.doors_not_listening), error = true)
            DoorsFloorPlan(state = state, actions = plan, onShowDoor = { id -> scope.launch { cards[id]?.bringIntoView() } })
            DoorPairingCard(state = state, actions = pairing)
            if (state.doors.isEmpty()) {
                SettingsCard {
                    Text(stringResource(Res.string.doors_none_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    SettingsCaption(stringResource(if (state.pairing != null) Res.string.doors_none_body_pair else Res.string.doors_none_body))
                }
            } else {
                state.doors.forEach { door ->
                    DoorCard(
                        door = door,
                        nowMillis = state.nowMillis,
                        onNotify = { onNotify(door.id, it) },
                        // Renaming goes through Zigbee2MQTT, which only a relay that can pair asks.
                        onRename = if (state.pairing != null) ({ renaming = door.id }) else null,
                        modifier = Modifier.bringIntoViewRequester(cards.getOrPut(door.id) { BringIntoViewRequester() }),
                    )
                }
                SettingsCaption(stringResource(Res.string.doors_notify_caption))
                DoorHistory(days = state.history)
            }
        }
    }
}

/** The colour a door is drawn in: the accent colour while it's open, quiet while it's closed. */
@Composable
private fun doorTint(open: Boolean?): Color =
    if (open == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoorCard(door: Door, nowMillis: Long, onNotify: (DoorNotify) -> Unit, onRename: (() -> Unit)?, modifier: Modifier = Modifier) {
    SettingsCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                imageVector = if (door.open == true) Icons.Filled.MeetingRoom else Icons.Filled.DoorFront,
                contentDescription = null,
                tint = doorTint(door.open),
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(door.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = doorStateText(door, nowMillis).resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (door.open == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                doorHealthText(door)?.let { SettingsCaption(it.resolve(), error = door.online == false || door.needsBattery) }
            }
            if (onRename != null && !door.unnamed) {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.doors_rename_action, door.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (onRename != null && door.unnamed) {
            FilledTonalButton(onClick = onRename) { Text(stringResource(Res.string.doors_name_it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.doors_notify_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoorNotify.entries.forEach { notify ->
                    FilterChip(
                        selected = door.notify == notify,
                        onClick = { if (door.notify != notify) onNotify(notify) },
                        label = { Text(stringResource(notify.label)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DoorHistory(days: List<DoorHistoryDay>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.doors_history_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        if (days.isEmpty()) {
            SettingsCaption(stringResource(Res.string.doors_history_empty))
            return@Column
        }
        SettingsGroup {
            days.forEachIndexed { index, day ->
                if (index > 0) SettingsRowDivider()
                Text(
                    text = doorDayText(day.daysAgo).resolve(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                day.entries.forEach { entry -> DoorHistoryRow(entry) }
            }
        }
    }
}

@Composable
private fun DoorHistoryRow(entry: DoorHistoryEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (entry.open) Icons.Filled.MeetingRoom else Icons.Filled.DoorFront,
            contentDescription = null,
            tint = doorTint(entry.open),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(if (entry.open) Res.string.doors_history_opened else Res.string.doors_history_closed, entry.doorName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (entry.notified) {
            Icon(
                imageVector = Icons.Filled.NotificationsActive,
                contentDescription = stringResource(Res.string.doors_history_notified),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(formatClockTime(entry.atMillis / 1000.0), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
