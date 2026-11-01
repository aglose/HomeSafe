package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.packages.PackagesUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_action_owner
import homesafe.shared.generated.resources.packages_action_surprise
import homesafe.shared.generated.resources.packages_action_surprise_body
import homesafe.shared.generated.resources.packages_add_nickname_hint
import homesafe.shared.generated.resources.packages_add_number
import homesafe.shared.generated.resources.packages_add_number_body
import homesafe.shared.generated.resources.packages_add_number_hint
import homesafe.shared.generated.resources.packages_add_paste
import homesafe.shared.generated.resources.packages_add_save
import homesafe.shared.generated.resources.packages_notes_nickname
import org.jetbrains.compose.resources.stringResource

/** A parcel no email told of, by its tracking number: whichever carrier's it is, the relay works out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddScreen(state: PackagesUiState, padding: PaddingValues, actions: PackagesActions) {
    val colors = PackagesTheme.colors
    val snapshot = state.snapshot
    val clipboard = LocalClipboardManager.current
    var tracking by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf(snapshot.viewer) }
    var surprise by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PackagesCard {
            Text(stringResource(Res.string.packages_add_number), style = PackagesTheme.type.bodyStrong, color = colors.text)
            Text(stringResource(Res.string.packages_add_number_body), style = PackagesTheme.type.label, color = colors.textMuted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            PackagesField(tracking, { tracking = it }, stringResource(Res.string.packages_add_number_hint), Modifier.testTag("add_tracking"))
            Spacer(Modifier.height(8.dp))
            PackagesButton(stringResource(Res.string.packages_add_paste), { clipboard.getText()?.text?.let { tracking = it.trim() } }, Modifier.fillMaxWidth(), icon = Icons.Filled.ContentPaste, primary = false)
        }
        PackagesCard {
            Text(stringResource(Res.string.packages_notes_nickname), style = PackagesTheme.type.bodyStrong, color = colors.text)
            Spacer(Modifier.height(8.dp))
            PackagesField(nickname, { nickname = it }, stringResource(Res.string.packages_add_nickname_hint), Modifier.testTag("add_nickname"))
            if (snapshot.people.size > 1) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(Res.string.packages_action_owner), style = PackagesTheme.type.bodyStrong, color = colors.text)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.people.forEach { person -> PackagesChoice(PackagesFormat.person(person), owner == person, { owner = person }) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CardGiftcard, contentDescription = null, tint = colors.gift, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.packages_action_surprise), style = PackagesTheme.type.bodyStrong, color = colors.text)
                    Text(stringResource(Res.string.packages_action_surprise_body), style = PackagesTheme.type.label, color = colors.textFaint)
                }
                // Kept from everyone but its owner, so it needs one.
                Switch(surprise && owner != null, { surprise = it }, enabled = owner != null, colors = SwitchDefaults.colors(checkedTrackColor = colors.gift))
            }
        }
        PackagesButton(
            stringResource(Res.string.packages_add_save),
            { actions.onAdd(tracking.trim(), nickname.trim().ifEmpty { null }, owner, surprise && owner != null) },
            Modifier.fillMaxWidth().testTag("add_save"),
            enabled = tracking.isNotBlank(),
        )
    }
}
