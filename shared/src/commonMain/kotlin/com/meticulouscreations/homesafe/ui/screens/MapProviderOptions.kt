package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.maps.GoogleMapsStatus
import com.meticulouscreations.homesafe.maps.MapProvider
import com.meticulouscreations.homesafe.maps.MapSettings
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.maps_google_key_forget
import homesafe.shared.generated.resources.maps_google_key_help
import homesafe.shared.generated.resources.maps_google_key_label
import homesafe.shared.generated.resources.maps_google_key_save
import homesafe.shared.generated.resources.maps_google_status_checking
import homesafe.shared.generated.resources.maps_google_status_failed
import homesafe.shared.generated.resources.maps_google_status_no_key
import homesafe.shared.generated.resources.maps_google_status_unchecked
import homesafe.shared.generated.resources.maps_google_status_working
import homesafe.shared.generated.resources.maps_settings_caption
import homesafe.shared.generated.resources.vault_key_saved
import org.jetbrains.compose.resources.stringResource

/**
 * Whose map the app's maps are drawn on: OpenStreetMap, or Google's with a key of the person's
 * own. Picking Google opens out the key: pasted in and saved (sealed into the household's vault
 * on the server, never shown again), or forgotten, with a line saying whether Google took it.
 * While the vault can't be opened (signed out, not let in yet) it says why instead. On the
 * Settings page these are what the "Maps" row opens out to, the row itself naming the one in use.
 */
@Composable
internal fun MapProviderOptions(
    settings: MapSettings,
    onProvider: (MapProvider) -> Unit,
    onGoogleKey: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsCaption(stringResource(Res.string.maps_settings_caption))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MapProvider.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = option == settings.provider, onClick = { onProvider(option) }, role = Role.RadioButton)
                        .padding(vertical = 6.dp)
                        .testTag("settings_maps_${option.name.lowercase()}"),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The row handles the click, so the button only shows the choice.
                    RadioButton(selected = option == settings.provider, onClick = null, modifier = Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(option.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(option.blurb), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (settings.provider == MapProvider.GOOGLE) GoogleKeyRows(settings, onGoogleKey)
    }
}

@Composable
private fun GoogleKeyRows(settings: MapSettings, onGoogleKey: (String?) -> Unit) {
    // One column, so the options above space these as they space each other.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val keysNote = settings.keysNote
        if (keysNote != null) {
            SettingsCaption(keysNote.resolve())
        } else if (settings.hasGoogleKey) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.vault_key_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onGoogleKey(null) }, modifier = Modifier.testTag("settings_maps_forget_key")) {
                    Text(stringResource(Res.string.maps_google_key_forget))
                }
            }
        } else {
            // Only ever in memory: saved state would put the key, in the clear, in the platform's saved-instance Bundle.
            var typed by remember { mutableStateOf("") }
            val save = { if (typed.isNotBlank()) onGoogleKey(typed).also { typed = "" } }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(stringResource(Res.string.maps_google_key_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().testTag("settings_maps_key"),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingsCaption(stringResource(Res.string.maps_google_key_help), modifier = Modifier.weight(1f))
                TextButton(onClick = { save() }, enabled = typed.isNotBlank(), modifier = Modifier.testTag("settings_maps_save_key")) {
                    Text(stringResource(Res.string.maps_google_key_save))
                }
            }
        }
        settings.saveError?.let { SettingsCaption(it.resolve(), error = true) }
        if (keysNote != null) return@Column
        val status = settings.google
        SettingsCaption(
            when {
                !settings.hasGoogleKey -> stringResource(Res.string.maps_google_status_no_key)
                status is GoogleMapsStatus.Failed -> stringResource(Res.string.maps_google_status_failed, status.reason.resolve())
                status == GoogleMapsStatus.Checking -> stringResource(Res.string.maps_google_status_checking)
                status == GoogleMapsStatus.Working -> stringResource(Res.string.maps_google_status_working)
                else -> stringResource(Res.string.maps_google_status_unchecked)
            },
            error = settings.hasGoogleKey && status is GoogleMapsStatus.Failed,
        )
    }
}
