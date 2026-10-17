package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.appicon.AppIcon
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.settings_app_icon_caption
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The icons the app can wear on the home screen, as they look there, the one in use ringed. On
 * the Settings page these are what the "App icon" row opens out to, the row itself naming the one
 * in use.
 */
@Composable
internal fun AppIconOptions(icon: AppIcon, onIcon: (AppIcon) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCaption(stringResource(Res.string.settings_app_icon_caption))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon.entries.forEach { option ->
                val selected = option == icon
                Column(
                    modifier = Modifier
                        .width(76.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(selected = selected, onClick = { onIcon(option) }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The label under the tile names it, so the picture needs no description of its own.
                    Image(
                        painter = painterResource(option.preview),
                        contentDescription = null,
                        modifier = Modifier
                            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(18.dp))
                            .padding(4.dp)
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Text(
                        stringResource(option.label),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
