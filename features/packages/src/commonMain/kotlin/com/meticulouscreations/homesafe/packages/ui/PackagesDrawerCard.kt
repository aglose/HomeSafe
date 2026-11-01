package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Inventory2
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
import com.meticulouscreations.homesafe.packages.PackagesUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_drawer_attention
import homesafe.shared.generated.resources.packages_drawer_nothing
import homesafe.shared.generated.resources.packages_drawer_on_way
import homesafe.shared.generated.resources.packages_drawer_open
import homesafe.shared.generated.resources.packages_drawer_porch
import homesafe.shared.generated.resources.packages_drawer_subtitle
import homesafe.shared.generated.resources.packages_drawer_title
import homesafe.shared.generated.resources.packages_drawer_today
import homesafe.shared.generated.resources.packages_drawer_waiting
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The packages app's door: a box turning under the porch light, warmer the nearer things are, with
 * what's at the door or due today. With nothing to say (nothing coming, or not signed in) it just
 * says what the app is.
 */
@Composable
fun PackagesDrawerCard(packages: PackagesUiState, animated: Boolean, onOpen: (Offset) -> Unit, modifier: Modifier = Modifier) {
    var center by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(22.dp)
    val secondary = Color(0xD1FFFFFF)
    val board = packages.board
    val warm = packageWarmth(packages)
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClickLabel = stringResource(Res.string.packages_drawer_open)) { onOpen(center) }
            .testTag("drawer_packages"),
    ) {
        PackagesBackdrop(Modifier.matchParentSize(), seed = 0.2f, warm = warm, running = animated)
        // Small enough to draw live, and stopped when the drawer is shut.
        Box(Modifier.matchParentSize()) {
            ParcelBox(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp).size(92.dp), warm = warm, running = animated)
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color(0x66000000), 0.75f to Color(0x11000000), 1f to Color.Transparent)))
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Inventory2, contentDescription = null, tint = Color(0xFF8A5A22), modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.packages_drawer_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(stringResource(Res.string.packages_drawer_subtitle), maxLines = 1, style = MaterialTheme.typography.labelSmall, color = secondary)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = secondary)
            }
            Spacer(Modifier.height(14.dp))
            when {
                !packages.loaded -> Text(stringResource(Res.string.packages_drawer_waiting), style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1)
                board.attention.isNotEmpty() -> Text(pluralStringResource(Res.plurals.packages_drawer_attention, board.attention.size, board.attention.size), style = MaterialTheme.typography.titleSmall, color = Color(0xFFFFC9A3), maxLines = 1)
                board.onPorch.isNotEmpty() -> Text(pluralStringResource(Res.plurals.packages_drawer_porch, board.onPorch.size, board.onPorch.size), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                board.today.isNotEmpty() -> Text(pluralStringResource(Res.plurals.packages_drawer_today, board.today.size, board.today.size), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                board.onTheWay > 0 -> Text(pluralStringResource(Res.plurals.packages_drawer_on_way, board.onTheWay, board.onTheWay), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                else -> Text(stringResource(Res.string.packages_drawer_nothing), style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1)
            }
            val detail = board.onTheWay.takeIf { it > 0 && (board.attention.isNotEmpty() || board.onPorch.isNotEmpty()) }
            if (detail != null) {
                Text(pluralStringResource(Res.plurals.packages_drawer_on_way, detail, detail), style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1)
            }
        }
    }
}

/** How warm the porch light is: cold with nothing coming, full with something at the door. */
internal fun packageWarmth(packages: PackagesUiState): Float {
    val board = packages.board
    return when {
        board.onPorch.isNotEmpty() || board.attention.isNotEmpty() -> 1f
        board.today.isNotEmpty() -> 0.85f
        board.onTheWay > 0 -> 0.5f
        else -> 0.18f
    }
}
