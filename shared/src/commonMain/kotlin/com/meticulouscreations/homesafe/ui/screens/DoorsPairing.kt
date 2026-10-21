package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.viewmodel.DoorJoinProgress
import com.meticulouscreations.homesafe.viewmodel.DoorJoinRow
import com.meticulouscreations.homesafe.viewmodel.DoorsUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_save
import homesafe.shared.generated.resources.doors_pairing_hint
import homesafe.shared.generated.resources.doors_pairing_open
import homesafe.shared.generated.resources.doors_pairing_open_until
import homesafe.shared.generated.resources.doors_pairing_start
import homesafe.shared.generated.resources.doors_pairing_start_caption
import homesafe.shared.generated.resources.doors_pairing_stop
import homesafe.shared.generated.resources.doors_rename_label
import homesafe.shared.generated.resources.doors_rename_placeholder
import homesafe.shared.generated.resources.doors_rename_title
import org.jetbrains.compose.resources.stringResource

/** What the pairing card and the door cards' names ask of the page. */
@Immutable
internal class DoorsPairingActions(
    val onStartPairing: () -> Unit = {},
    val onStopPairing: () -> Unit = {},
    val onRename: (doorId: String, name: String) -> Unit = { _, _ -> },
)

internal const val DOORS_PAIR_TEST_TAG = "doors_pair"

/**
 * Pairing door sensors without the server's Zigbee2MQTT page: a button that lets sensors join
 * the Zigbee network for a few minutes, and while it does, how long for, what to do at the
 * sensor, and each one that joins as it's set up. What joined stays listed after the window
 * closes, so "Name it below" is still there to follow. Drawn only for a relay that can pair.
 */
@Composable
internal fun DoorPairingCard(state: DoorsUiState, actions: DoorsPairingActions) {
    val pairing = state.pairing ?: return
    if (!pairing.open) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions.onStartPairing,
                enabled = state.listening && !state.pairingBusy,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.testTag(DOORS_PAIR_TEST_TAG),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(Res.string.doors_pairing_start))
            }
            if (state.joined.isEmpty()) SettingsCaption(stringResource(Res.string.doors_pairing_start_caption))
            state.joined.forEach { DoorJoinLine(it) }
        }
        return
    }
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = pairing.untilMillis?.let { stringResource(Res.string.doors_pairing_open_until, formatClockTime(it / 1000.0)) }
                    ?: stringResource(Res.string.doors_pairing_open),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onStopPairing, enabled = !state.pairingBusy, modifier = Modifier.testTag(DOORS_PAIR_TEST_TAG)) {
                Text(stringResource(Res.string.doors_pairing_stop))
            }
        }
        SettingsCaption(stringResource(Res.string.doors_pairing_hint))
        if (state.joined.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { state.joined.forEach { DoorJoinLine(it) } }
        }
    }
}

@Composable
private fun DoorJoinLine(join: DoorJoinRow) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val failed = join.progress == DoorJoinProgress.FAILED
        if (join.progress == DoorJoinProgress.WORKING) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (failed) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = join.text.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Names [door]'s sensor. One just paired, still called by its address, starts blank. */
@Composable
internal fun DoorRenameDialog(door: Door, onDismiss: () -> Unit, onSave: (name: String) -> Unit) {
    var name by rememberSaveable(door.id) { mutableStateOf(if (door.unnamed) "" else door.name) }
    val save = { if (name.isNotBlank()) onSave(name.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.doors_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.doors_rename_label)) },
                placeholder = { Text(stringResource(Res.string.doors_rename_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        },
        confirmButton = { TextButton(onClick = save, enabled = name.isNotBlank()) { Text(stringResource(Res.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
