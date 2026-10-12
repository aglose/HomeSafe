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
import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_figure_settings_caption
import org.jetbrains.compose.resources.stringResource

/**
 * Which figure performs the exercises in the fitness app: the rendered lifter, the stone figure
 * or the forged study. The movement and the muscles shown are the same in all three; an
 * exercise's own page in Fitness has the same choice as chips. On the Settings page these are
 * what the "Exercise figures" row opens out to, the row itself naming the one in use.
 */
@Composable
internal fun FigureStyleOptions(style: FigureStyle, onStyle: (FigureStyle) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsCaption(stringResource(Res.string.fitness_figure_settings_caption))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FigureStyle.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = option == style, onClick = { onStyle(option) }, role = Role.RadioButton)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The row handles the click, so the button only shows the choice.
                    RadioButton(selected = option == style, onClick = null, modifier = Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(option.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(option.blurb), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
