package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.home_placement_indoor
import homesafe.shared.generated.resources.home_placement_outdoor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Home page's quick swap between the outdoor cameras and the indoor ones: two pills in one,
 * the chosen side filled. A pair of tabs to a screen reader, since that is what it does to the
 * list under it.
 */
@Composable
internal fun CameraPlacementSwitch(
    selected: CameraPlacement,
    onSelect: (CameraPlacement) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = CAMERA_PLACEMENT_SWITCH_TEST_TAG,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
            .padding(4.dp)
            .selectableGroup()
            .testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CameraPlacement.entries.forEach { placement ->
            PlacementOption(
                label = placement.label,
                icon = placement.icon,
                selected = placement == selected,
                onClick = { onSelect(placement) },
            )
        }
    }
}

@Composable
private fun PlacementOption(label: StringResource, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "placement-option-background",
    )
    val foreground by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "placement-option-foreground",
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background, CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
        Text(text = stringResource(label), style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}

private val CameraPlacement.label: StringResource
    get() = when (this) {
        CameraPlacement.Outdoor -> Res.string.home_placement_outdoor
        CameraPlacement.Indoor -> Res.string.home_placement_indoor
    }

private val CameraPlacement.icon: ImageVector
    get() = when (this) {
        CameraPlacement.Outdoor -> Icons.Outlined.Park
        CameraPlacement.Indoor -> Icons.Outlined.Weekend
    }

internal const val CAMERA_PLACEMENT_SWITCH_TEST_TAG = "home-camera-placement-switch"

/** The same switch floating at the foot of the Home page, within reach of the thumb. */
internal const val FLOATING_PLACEMENT_SWITCH_TEST_TAG = "home-floating-placement-switch"
