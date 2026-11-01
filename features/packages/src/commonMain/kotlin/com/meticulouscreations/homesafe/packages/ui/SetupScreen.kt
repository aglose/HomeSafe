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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.packages.PackagesUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_notify_delivered
import homesafe.shared.generated.resources.packages_notify_missing
import homesafe.shared.generated.resources.packages_notify_morning
import homesafe.shared.generated.resources.packages_notify_out
import homesafe.shared.generated.resources.packages_notify_porch
import homesafe.shared.generated.resources.packages_notify_problems
import homesafe.shared.generated.resources.packages_notify_rain
import homesafe.shared.generated.resources.packages_notify_returns
import homesafe.shared.generated.resources.packages_notify_shipped
import homesafe.shared.generated.resources.packages_setup_gift_body
import homesafe.shared.generated.resources.packages_setup_gift_person
import homesafe.shared.generated.resources.packages_setup_gift_season
import homesafe.shared.generated.resources.packages_setup_notify
import homesafe.shared.generated.resources.packages_setup_phone
import homesafe.shared.generated.resources.packages_setup_sources
import homesafe.shared.generated.resources.packages_source_camera
import homesafe.shared.generated.resources.packages_source_camera_off
import homesafe.shared.generated.resources.packages_source_camera_on
import homesafe.shared.generated.resources.packages_source_door
import homesafe.shared.generated.resources.packages_source_door_off
import homesafe.shared.generated.resources.packages_source_door_on
import homesafe.shared.generated.resources.packages_source_failed
import homesafe.shared.generated.resources.packages_source_mail
import homesafe.shared.generated.resources.packages_source_mail_off
import homesafe.shared.generated.resources.packages_source_mail_on
import homesafe.shared.generated.resources.packages_source_model
import homesafe.shared.generated.resources.packages_source_model_off
import homesafe.shared.generated.resources.packages_source_model_on
import homesafe.shared.generated.resources.packages_source_read_at
import homesafe.shared.generated.resources.packages_source_tracker
import homesafe.shared.generated.resources.packages_source_tracker_off
import homesafe.shared.generated.resources.packages_source_tracker_on
import homesafe.shared.generated.resources.packages_whose_body
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Whose phone this is, what the household wants to be told, and where it all comes from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SetupScreen(state: PackagesUiState, padding: PaddingValues, actions: PackagesActions) {
    val colors = PackagesTheme.colors
    val snapshot = state.snapshot
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp).testTag("packages_setup_page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (snapshot.people.isNotEmpty()) {
            PackagesCard(title = stringResource(Res.string.packages_setup_phone)) {
                Text(stringResource(Res.string.packages_whose_body), style = PackagesTheme.type.label, color = colors.textMuted)
                Spacer(Modifier.size(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.people.forEach { person ->
                        PackagesChoice(PackagesFormat.person(person), snapshot.viewer == person, { actions.onSetPhoneOwner(person.takeIf { snapshot.viewer != person }) })
                    }
                }
            }
            PackagesCard(title = stringResource(Res.string.packages_setup_gift_season)) {
                Text(stringResource(Res.string.packages_setup_gift_body), style = PackagesTheme.type.label, color = colors.textMuted)
                snapshot.people.forEach { person ->
                    SwitchRow(stringResource(Res.string.packages_setup_gift_person, PackagesFormat.person(person)), snapshot.settings.hideNew[person] == true, colors.gift, onChange = { actions.onSetHideNew(person, it) })
                }
            }
        }
        PackagesCard(title = stringResource(Res.string.packages_setup_notify)) {
            NOTICES.forEach { (kind, title) -> SwitchRow(stringResource(title), snapshot.settings.wants(kind), colors.tape, onChange = { actions.onSetNotify(kind, it) }, Modifier.testTag("notify_$kind")) }
        }
        PackagesCard(title = stringResource(Res.string.packages_setup_sources)) {
            SourceRow(snapshot.mailOn, stringResource(Res.string.packages_source_mail), if (snapshot.mailOn) stringResource(Res.string.packages_source_mail_on, snapshot.people.joinToString { PackagesFormat.person(it) }) else stringResource(Res.string.packages_source_mail_off))
            SourceRow(snapshot.cameraOn, stringResource(Res.string.packages_source_camera), if (snapshot.cameraOn) stringResource(Res.string.packages_source_camera_on, snapshot.cameraName) else stringResource(Res.string.packages_source_camera_off))
            SourceRow(snapshot.modelOn, stringResource(Res.string.packages_source_model), stringResource(if (snapshot.modelOn) Res.string.packages_source_model_on else Res.string.packages_source_model_off))
            SourceRow(snapshot.doorSensor, stringResource(Res.string.packages_source_door), stringResource(if (snapshot.doorSensor) Res.string.packages_source_door_on else Res.string.packages_source_door_off))
            SourceRow(snapshot.trackerOn, stringResource(Res.string.packages_source_tracker), stringResource(if (snapshot.trackerOn) Res.string.packages_source_tracker_on else Res.string.packages_source_tracker_off))
            snapshot.syncedAt?.let { Text(stringResource(Res.string.packages_source_read_at, agoText(it, snapshot.nowEpochSeconds)), style = PackagesTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(top = 8.dp)) }
            if (snapshot.failedMailboxes.isNotEmpty()) {
                Text(stringResource(Res.string.packages_source_failed, snapshot.failedMailboxes.joinToString { PackagesFormat.person(it) }), style = PackagesTheme.type.label, color = colors.warn, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private val NOTICES: List<Pair<String, StringResource>> = listOf(
    "shipped" to Res.string.packages_notify_shipped,
    "out_for_delivery" to Res.string.packages_notify_out,
    "delivered" to Res.string.packages_notify_delivered,
    "porch" to Res.string.packages_notify_porch,
    "rain" to Res.string.packages_notify_rain,
    "missing" to Res.string.packages_notify_missing,
    "problems" to Res.string.packages_notify_problems,
    "returns" to Res.string.packages_notify_returns,
    "morning" to Res.string.packages_notify_morning,
)

@Composable
private fun SwitchRow(title: String, on: Boolean, tint: androidx.compose.ui.graphics.Color, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = PackagesTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = PackagesTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = tint))
    }
}

@Composable
private fun SourceRow(on: Boolean, title: String, body: String) {
    val colors = PackagesTheme.colors
    Row(Modifier.padding(vertical = 6.dp)) {
        Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = if (on) colors.good else colors.textFaint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, style = PackagesTheme.type.bodyStrong, color = colors.text)
            Text(body, style = PackagesTheme.type.label, color = colors.textMuted)
        }
    }
}
