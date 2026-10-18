package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.StationaryObjectPresentation
import com.meticulouscreations.homesafe.domain.model.doorGlance
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.InViewItem
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_chip_offline
import homesafe.shared.generated.resources.doors_chip_open
import homesafe.shared.generated.resources.doors_chip_unknown
import homesafe.shared.generated.resources.doors_home_open
import homesafe.shared.generated.resources.home_glance_all_doors_closed
import homesafe.shared.generated.resources.home_glance_doors_closed
import homesafe.shared.generated.resources.home_glance_open_in_view
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What's going on around the house, in one line at the top of Home: a pill per car parked in
 * view (its picture, name and where it is), a pill per door that needs a look (open, offline, or
 * not heard from yet), and one pill counting the doors that are simply closed. No headings: the
 * pills say what they are, so the cameras start as high up the screen as they can. It scrolls
 * sideways when there's more than fits.
 *
 * The details live a tap away: a car opens the In View page ([onOpenInView]: its arrival, last
 * sighting and the way to check and tag the cars), a door the Doors page ([onOpenDoors]).
 */
@Composable
internal fun HomeGlanceRow(
    inView: List<InViewItem>,
    doors: List<Door>,
    onOpenInView: () -> Unit,
    onOpenDoors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glance = doorGlance(doors)
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val openInView = stringResource(Res.string.home_glance_open_in_view)
        inView.forEach { item ->
            GlancePill(
                label = item.presentation.title.resolve(),
                detail = item.presentation.placeLabel,
                onClick = onOpenInView,
                onClickLabel = openInView,
                // A car the cameras stopped seeing a while ago is told quieter: it may have gone.
                faded = item.presentation.lastSeenLabel != null,
                leading = { CarThumbnail(item.thumbnailUrl) },
            )
        }
        val openDoors = stringResource(Res.string.doors_home_open)
        glance.shown.forEach { door ->
            val open = door.open == true
            val offline = door.online == false
            GlancePill(
                label = door.name,
                detail = stringResource(
                    when {
                        offline -> Res.string.doors_chip_offline
                        open -> Res.string.doors_chip_open
                        else -> Res.string.doors_chip_unknown
                    },
                ),
                onClick = onOpenDoors,
                onClickLabel = openDoors,
                container = if (open && !offline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                content = if (open && !offline) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                detailColor = if (offline) MaterialTheme.colorScheme.error else null,
                leading = { PillIcon(if (open) Icons.Filled.MeetingRoom else Icons.Filled.DoorFront) },
            )
        }
        if (glance.closedCount > 0) {
            GlancePill(
                label = if (glance.allClosed) {
                    stringResource(Res.string.home_glance_all_doors_closed)
                } else {
                    pluralStringResource(Res.plurals.home_glance_doors_closed, glance.closedCount, glance.closedCount)
                },
                detail = null,
                onClick = onOpenDoors,
                onClickLabel = openDoors,
                leading = { PillIcon(Icons.Filled.DoorFront) },
            )
        }
    }
}

/** One pill: a round [leading] picture or icon, a name, and a quieter word or two after it. */
@Composable
private fun GlancePill(
    label: String,
    detail: String?,
    onClick: () -> Unit,
    onClickLabel: String,
    leading: @Composable () -> Unit,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
    detailColor: Color? = null,
    faded: Boolean = false,
) {
    CompositionLocalProvider(LocalContentColor provides content) {
        Row(
            // 40dp to the eye, a 48dp target to the finger: the row is laid out 48dp tall around it.
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .heightIn(min = GLANCE_PILL_HEIGHT)
                .clip(CircleShape)
                .background(container)
                .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                .padding(start = 6.dp, end = 14.dp)
                .alpha(if (faded) 0.6f else 1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = detailColor ?: content.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PillIcon(icon: ImageVector) {
    Box(modifier = Modifier.size(GLANCE_LEADING_SIZE), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** The crop Frigate cut of the car's latest sighting, in a circle; a car icon until there is one. */
@Composable
private fun CarThumbnail(url: String?) {
    Box(
        modifier = Modifier.size(GLANCE_LEADING_SIZE).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            // Coil rides the app's authenticated Ktor client, which is what lets this hit Frigate's thumbnail endpoint.
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

private val GLANCE_PILL_HEIGHT = 40.dp
private val GLANCE_LEADING_SIZE = 28.dp

@Composable
private fun GlancePreviewFrame(content: @Composable () -> Unit) {
    FrigateTheme { Surface { Box(modifier = Modifier.padding(16.dp)) { content() } } }
}

/** A car in the driveway, the back door open and the other three closed. */
@Preview(name = "Home glance row", widthDp = 412)
@Composable
private fun HomeGlanceRowPreview() {
    GlancePreviewFrame {
        HomeGlanceRow(
            inView = listOf(previewGlanceCar),
            doors = listOf(Door("0x01", "Back door", open = true), Door("0x02", "Front door", open = false), Door("0x03", "Garage door", open = false)),
            onOpenInView = {},
            onOpenDoors = {},
        )
    }
}

/** Nothing parked, every door shut, one sensor not heard from in a day. */
@Preview(name = "Home glance row, doors only", widthDp = 412)
@Composable
private fun HomeGlanceRowDoorsPreview() {
    GlancePreviewFrame {
        HomeGlanceRow(
            inView = emptyList(),
            doors = listOf(Door("0x01", "Back door", open = false), Door("0x02", "Front door", open = false), Door("0x03", "Garage door", open = false, online = false)),
            onOpenInView = {},
            onOpenDoors = {},
        )
    }
}

private val previewGlanceCar = InViewItem(
    subject = StationaryObject(
        thumbnailEventId = "sighting-front_door",
        cameraName = "front_door",
        label = "car",
        subLabel = "sarahs_tesla",
        zones = listOf("driveway"),
        firstSeenEpochSeconds = 1_789_400_000.0,
        lastSeenEpochSeconds = 1_789_408_700.0,
        seenRecently = true,
        sightings = 4,
        sinceIsKnown = true,
    ),
    presentation = StationaryObjectPresentation(title = "Sarah's Tesla".asUiText(), placeLabel = "Driveway", sinceLabel = null, lastSeenLabel = null),
    thumbnailUrl = null,
)
