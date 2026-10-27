package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import com.meticulouscreations.homesafe.maps.BaseMap
import com.meticulouscreations.homesafe.navigation.ParkingDeepLink
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.ui.radar.SpotMap
import com.meticulouscreations.homesafe.weather.ui.radar.mapCredit
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_close
import homesafe.shared.generated.resources.parking_map_description
import homesafe.shared.generated.resources.parking_map_directions
import homesafe.shared.generated.resources.parking_map_limit_hours
import homesafe.shared.generated.resources.parking_map_limit_minutes
import homesafe.shared.generated.resources.parking_map_no_parking
import homesafe.shared.generated.resources.parking_map_place_east
import homesafe.shared.generated.resources.parking_map_place_north
import homesafe.shared.generated.resources.parking_map_place_south
import homesafe.shared.generated.resources.parking_map_place_west
import homesafe.shared.generated.resources.parking_map_title_due
import homesafe.shared.generated.resources.parking_map_title_now
import homesafe.shared.generated.resources.parking_map_when_other_day
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Where a parking notification's tap lands: the spot the car is parked in on a street map (on
 * whichever map is picked in Settings), what it must move for and by when, and directions there.
 * It comes up over whatever the app is showing, the sign-in screen too, and goes on Close, a tap
 * outside or Back, which shrinks it under the finger as it is pulled. [link] is the tap's
 * (`ParkingDeepLinks.pending`); [onClose] is told which one has been dismissed.
 */
@Composable
fun ParkingMapOverlay(link: ParkingDeepLink?, tiles: MapTileSource, onClose: (ParkingDeepLink) -> Unit, modifier: Modifier = Modifier) {
    // The last link shown stays drawn while the card leaves.
    var shown by remember { mutableStateOf(link) }
    if (link != null) shown = link
    val back = rememberPredictiveBack(enabled = link != null) { link?.let(onClose) }
    AnimatedVisibility(link != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { link?.let(onClose) }
                .safeDrawingPadding()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val alert = shown?.alert
            if (alert != null) {
                ParkingMapCard(
                    alert = alert,
                    tiles = tiles,
                    onClose = { link?.let(onClose) },
                    modifier = Modifier
                        .animateEnterExit(enter = slideInVertically { it / 3 }, exit = slideOutVertically { it / 3 })
                        .graphicsLayer {
                            val pulled = 1f - 0.08f * back.progress
                            scaleX = pulled
                            scaleY = pulled
                        },
                )
            }
        }
    }
}

/** The card itself, without the scrim around it: what the preview draws. */
@Composable
fun ParkingMapCard(alert: ParkingAlert, tiles: MapTileSource, onClose: () -> Unit, modifier: Modifier = Modifier, animated: Boolean = true) {
    val latitude = alert.latitude ?: return
    val longitude = alert.longitude ?: return
    val uriHandler = LocalUriHandler.current
    val baseMap by tiles.baseMap.collectAsStateWithLifecycle()
    Surface(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            // Taps on the card stay on it: only the scrim around it closes.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag("parking_map"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(parkingTitle(alert), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(parkingPlace(alert), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    parkingReason(alert)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            val description = stringResource(Res.string.parking_map_description, alert.carName)
            SpotMap(
                latitude = latitude,
                longitude = longitude,
                tiles = tiles,
                animated = animated,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .semantics { contentDescription = description },
            )
            // Google's map credits itself on the map; OpenStreetMap's contributors are named here.
            if (baseMap !is BaseMap.Google) {
                Text(mapCredit(baseMap, null), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { runCatching { uriHandler.openUri("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude") } }, modifier = Modifier.testTag("parking_map_directions")) {
                    Text(stringResource(Res.string.parking_map_directions))
                }
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = onClose, modifier = Modifier.testTag("parking_map_close")) {
                    Text(stringResource(Res.string.common_close))
                }
            }
        }
    }
}

/** "Move Andrew's Tesla by Thu 8:00 AM", or by "8:00 AM" alone when that is today, on this phone's clock. */
@Composable
private fun parkingTitle(alert: ParkingAlert): String {
    val by = alert.byEpochSeconds
    if (alert.event == ParkingAlert.Event.NOW || by == null) return stringResource(Res.string.parking_map_title_now, alert.carName)
    val now = Clock.System.now().epochSeconds
    val offset = utcOffset(by)
    val clock = WeatherFormat.clock(by, offset).resolve()
    val sameDay = WeatherFormat.weekdayIndex(by, offset) == WeatherFormat.weekdayIndex(now, utcOffset(now)) && by - now < 86_400
    val time = if (sameDay) clock else stringResource(Res.string.parking_map_when_other_day, stringResource(WeatherFormat.weekday(by, offset)), clock)
    return stringResource(Res.string.parking_map_title_due, alert.carName, time)
}

@Composable
private fun parkingPlace(alert: ParkingAlert): String = when (alert.side) {
    ParkingAlert.Side.NORTH -> stringResource(Res.string.parking_map_place_north, alert.street)
    ParkingAlert.Side.EAST -> stringResource(Res.string.parking_map_place_east, alert.street)
    ParkingAlert.Side.SOUTH -> stringResource(Res.string.parking_map_place_south, alert.street)
    ParkingAlert.Side.WEST -> stringResource(Res.string.parking_map_place_west, alert.street)
    null -> alert.street
}

/** What the sign says: the household's own words for it, else the limit or the no-parking window. */
@Composable
private fun parkingReason(alert: ParkingAlert): String? {
    val limit = alert.limitMinutes
    val from = alert.fromEpochSeconds
    val until = alert.untilEpochSeconds
    return when {
        alert.note != null -> alert.note
        limit != null && limit % 60 == 0 -> pluralStringResource(Res.plurals.parking_map_limit_hours, limit / 60, limit / 60)
        limit != null -> pluralStringResource(Res.plurals.parking_map_limit_minutes, limit, limit)
        from != null && until != null -> stringResource(
            Res.string.parking_map_no_parking,
            WeatherFormat.clock(from, utcOffset(from)).resolve(),
            WeatherFormat.clock(until, utcOffset(until)).resolve(),
        )
        else -> null
    }
}

private fun utcOffset(epochSeconds: Long): Int = TimeZone.currentSystemDefault().offsetAt(Instant.fromEpochSeconds(epochSeconds)).totalSeconds
