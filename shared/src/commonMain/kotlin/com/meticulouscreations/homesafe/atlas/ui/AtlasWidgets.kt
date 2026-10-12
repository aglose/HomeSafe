package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.atlas.ui.shader.ATLAS_CONTOUR_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.rememberWeatherShaderClock
import com.meticulouscreations.homesafe.weather.ui.shader.weatherShaderOrNull

/**
 * Contour lines drifting across a dark country, in two colours: what the drawer's card and the
 * heads of the screens are drawn on. [seed] gives each its own ground; [energy] (0–1) is how
 * brightly the lines are lit. Still when not [running], and a plain wash where there is no shader.
 */
@Composable
internal fun AtlasBackdrop(tint: Color, tint2: Color, modifier: Modifier = Modifier, seed: Float = 0f, energy: Float = 0.6f, running: Boolean = true) {
    val shaded = LocalAtlasShaders.current
    val shader = remember(shaded) { if (shaded) weatherShaderOrNull(ATLAS_CONTOUR_SHADER) else null }
    val clock = rememberWeatherShaderClock(running)
    Box(
        modifier.drawBehind {
            if (shader != null) {
                shader.setUniform("size", size.width, size.height)
                shader.setUniform("time", clock.floatValue)
                shader.setUniform("seed", seed)
                shader.setUniform("tint", tint)
                shader.setUniform("tint2", tint2)
                shader.setUniform("energy", energy)
                drawRect(shader.brush())
            } else {
                drawRect(Brush.linearGradient(listOf(tint2.copy(alpha = 0.55f), Color(0xFF04060B))))
            }
        },
    )
}

/** A small round button with an icon, named for a screen reader by [label]. */
@Composable
internal fun AtlasRoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = AtlasTheme.colors.text, fill: Color = Color.Transparent) {
    Box(
        modifier.size(size).clip(CircleShape).background(fill).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A row of choices of which one is picked: the sort of a list, the measure of a calendar. Scrolls sideways where they don't fit. */
@Composable
internal fun <T> AtlasChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val picked = value == selected
            Text(
                label,
                style = AtlasTheme.type.label,
                color = if (picked) colors.background else colors.textMuted,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (picked) colors.trail else colors.surfaceRaised)
                    .border(1.dp, if (picked) Color.Transparent else colors.hairline, CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(value) }
                    .semantics { this.selected = picked }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** A figure with what it counts beneath it. */
@Composable
internal fun AtlasStat(value: String, label: String, modifier: Modifier = Modifier, tint: Color = AtlasTheme.colors.text) {
    Column(modifier) {
        Text(value, style = AtlasTheme.type.title, color = tint, maxLines = 1)
        Text(label, style = AtlasTheme.type.label, color = AtlasTheme.colors.textFaint, maxLines = 1)
    }
}

internal val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        TravelMode.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
        TravelMode.CYCLE -> Icons.AutoMirrored.Filled.DirectionsBike
        TravelMode.DRIVE -> Icons.Filled.DirectionsCar
        TravelMode.MOTORCYCLE -> Icons.Filled.TwoWheeler
        TravelMode.BUS -> Icons.Filled.DirectionsBus
        TravelMode.TRAIN -> Icons.Filled.Train
        TravelMode.SUBWAY -> Icons.Filled.Subway
        TravelMode.TRAM -> Icons.Filled.Tram
        TravelMode.FLY -> Icons.Filled.Flight
        TravelMode.FERRY, TravelMode.BOAT -> Icons.Filled.DirectionsBoat
        TravelMode.SKI -> Icons.Filled.DownhillSkiing
        TravelMode.OTHER -> Icons.Filled.MoreHoriz
    }
