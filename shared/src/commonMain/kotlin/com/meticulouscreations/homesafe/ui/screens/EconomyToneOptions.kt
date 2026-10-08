package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.tone_settings_caption
import org.jetbrains.compose.resources.stringResource

/**
 * How the finance app's Economy and Risk tabs talk: straight talk (the numbers, their lines and
 * their history) or the bright side (the same numbers, with the upsides). The data is the same
 * either way; the Economy tab has the same switch at its top. On the Settings page these are
 * what the "Economy commentary" row opens out to, the row itself naming the one in use.
 */
@Composable
internal fun EconomyToneOptions(tone: EconomyTone, onTone: (EconomyTone) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsCaption(stringResource(Res.string.tone_settings_caption))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EconomyTone.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = option == tone, onClick = { onTone(option) }, role = Role.RadioButton)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The row handles the click, so the button only shows the choice.
                    RadioButton(selected = option == tone, onClick = null, modifier = Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(option.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(option.blurb), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
