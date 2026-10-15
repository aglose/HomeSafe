package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.ui.AtlasBackdrop
import com.meticulouscreations.homesafe.atlas.ui.AtlasFormat
import com.meticulouscreations.homesafe.atlas.ui.AtlasPalette
import com.meticulouscreations.homesafe.atlas.ui.distanceText
import com.meticulouscreations.homesafe.atlas.ui.monthText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_drawer_detail
import homesafe.shared.generated.resources.atlas_drawer_empty
import homesafe.shared.generated.resources.atlas_drawer_locked
import homesafe.shared.generated.resources.atlas_drawer_open
import homesafe.shared.generated.resources.atlas_drawer_places
import homesafe.shared.generated.resources.atlas_drawer_subtitle
import homesafe.shared.generated.resources.atlas_drawer_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The atlas app's door: contour lines drifting while the drawer is open, with how many places its
 * owner's history takes in and how far it runs. While the app is [locked] it says only that, and
 * with no history yet, how to get one.
 */
@Composable
fun AtlasDrawerCard(atlas: AtlasUiState, locked: Boolean, animated: Boolean, onOpen: (Offset) -> Unit, modifier: Modifier = Modifier) {
    var center by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(22.dp)
    val palette = remember { AtlasPalette() }
    val secondary = Color(0xD1FFFFFF)
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClickLabel = stringResource(Res.string.atlas_drawer_open)) { onOpen(center) }
            .testTag("drawer_atlas"),
    ) {
        // Small enough to draw live, and stopped when the drawer is shut.
        AtlasBackdrop(palette.trail, palette.air, Modifier.matchParentSize(), seed = 0.55f, energy = 0.8f, running = animated)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x8C000000)))))
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Public, contentDescription = null, tint = Color(0xFFB85C00), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.atlas_drawer_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(stringResource(Res.string.atlas_drawer_subtitle), maxLines = 1, style = MaterialTheme.typography.labelSmall, color = secondary)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = secondary)
            }
            Spacer(Modifier.height(14.dp))
            val history = atlas.history
            when {
                locked -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("drawer_atlas_locked")) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = secondary, modifier = Modifier.size(14.dp))
                    Text(stringResource(Res.string.atlas_drawer_locked), style = MaterialTheme.typography.labelMedium, color = secondary)
                }

                // Not read yet, or not yet known to be this sign-in's: nothing of it is said.
                !atlas.loaded -> Unit

                history.isEmpty -> Text(stringResource(Res.string.atlas_drawer_empty), style = MaterialTheme.typography.labelMedium, color = secondary)

                else -> {
                    val places = history.places.size
                    Text(pluralStringResource(Res.plurals.atlas_drawer_places, places, AtlasFormat.grouped(places.toLong())), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                    Text(
                        stringResource(Res.string.atlas_drawer_detail, distanceText(atlas.analysis.records.groundMeters), monthText(CivilDate.of(history.firstDay).monthIndex)),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
