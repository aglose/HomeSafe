package com.meticulouscreations.homesafe.weather.ui

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
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.WeatherUiState
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import com.meticulouscreations.homesafe.weather.domain.WeatherStory
import com.meticulouscreations.homesafe.weather.ui.sky.SkyFreeze
import com.meticulouscreations.homesafe.weather.ui.sky.SkyScene
import com.meticulouscreations.homesafe.weather.ui.sky.WeatherSky
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_drawer_empty
import homesafe.shared.generated.resources.weather_drawer_open
import homesafe.shared.generated.resources.weather_drawer_subtitle
import homesafe.shared.generated.resources.weather_drawer_title
import homesafe.shared.generated.resources.weather_place_current
import org.jetbrains.compose.resources.stringResource

/**
 * The weather app's door: the sky over the place it's showing, moving while the drawer is open,
 * with the temperature, what it's doing there, and the day's one sentence written on it.
 */
@Composable
fun WeatherDrawerCard(weather: WeatherUiState, animated: Boolean, onOpen: (Offset) -> Unit, modifier: Modifier = Modifier) {
    var center by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(22.dp)
    val entry = weather.selected
    val report = entry?.report
    val scene = remember(entry?.place, report?.current, weather.nowEpochSeconds / 300) {
        val latitude = entry?.place?.latitude ?: 40.0
        val longitude = entry?.place?.longitude ?: -100.0
        report?.let { SkyScene.of(it.current, latitude, longitude, weather.nowEpochSeconds) }
            ?: SkyScene.of(WeatherKind.MOSTLY_CLEAR, null, 8.0, 270, null, latitude, longitude, weather.nowEpochSeconds)
    }
    val secondary = Color(0xD1FFFFFF)
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClickLabel = stringResource(Res.string.weather_drawer_open)) { onOpen(center) }
            .testTag("drawer_weather"),
    ) {
        // Small enough to draw live: the drawer's one moving thing, and stopped when it's shut.
        WeatherSky(scene, Modifier.matchParentSize(), glass = false, running = animated, freeze = if (weather.preferences.stillSky) SkyFreeze() else null)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x80000000)))))
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.WbSunny, contentDescription = null, tint = Color(0xFF1F6BD6), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.weather_drawer_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        entry?.place?.name?.ifBlank { stringResource(Res.string.weather_place_current) } ?: stringResource(Res.string.weather_drawer_subtitle),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                    )
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = secondary)
            }
            Spacer(Modifier.height(14.dp))
            if (report != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(WeatherFormat.degrees(report.current.temperatureC, weather.units), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(report.current.kind.label(report.current.isDay)), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                        Text(
                            remember(report, weather.units, weather.nowEpochSeconds) { WeatherStory.headline(report, weather.units, weather.nowEpochSeconds) }.resolve(),
                            style = MaterialTheme.typography.labelSmall,
                            color = secondary,
                            maxLines = 2,
                        )
                    }
                }
            } else {
                Text(
                    entry?.error?.resolve() ?: stringResource(Res.string.weather_drawer_empty),
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
                )
            }
        }
    }
}
