package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.Door
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_chip_closed
import homesafe.shared.generated.resources.doors_chip_offline
import homesafe.shared.generated.resources.doors_chip_open
import homesafe.shared.generated.resources.doors_chip_unknown
import homesafe.shared.generated.resources.doors_home_open
import homesafe.shared.generated.resources.doors_title
import org.jetbrains.compose.resources.stringResource

/**
 * The doors on Home: one small card per sensor saying whether it's open, the open ones in the
 * accent colour. The caption opens the Doors page ([onOpen]), and so does every card. Like the
 * cars in view, a plain sideways-scrolling [Row]: a house has a handful of doors.
 */
@Composable
internal fun HomeDoorsStrip(doors: List<Door>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = stringResource(Res.string.doors_home_open), role = Role.Button, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(Res.string.doors_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            doors.forEach { door -> DoorChip(door = door, onClick = onOpen) }
        }
    }
}

@Composable
private fun DoorChip(door: Door, onClick: () -> Unit) {
    val open = door.open == true
    val offline = door.online == false
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (open) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (open) Icons.Filled.MeetingRoom else Icons.Filled.DoorFront, contentDescription = null, modifier = Modifier.size(22.dp))
            Column {
                Text(door.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(
                    text = stringResource(
                        when {
                            offline -> Res.string.doors_chip_offline
                            door.open == null -> Res.string.doors_chip_unknown
                            open -> Res.string.doors_chip_open
                            else -> Res.string.doors_chip_closed
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (offline) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        }
    }
}
