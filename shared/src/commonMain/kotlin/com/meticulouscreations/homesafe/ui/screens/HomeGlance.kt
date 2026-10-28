package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.CarFix
import com.meticulouscreations.homesafe.domain.model.CurbSpot
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
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
import homesafe.shared.generated.resources.doors_detail_battery_low
import homesafe.shared.generated.resources.doors_home_open
import homesafe.shared.generated.resources.home_glance_all_doors_closed
import homesafe.shared.generated.resources.home_glance_doors_closed
import homesafe.shared.generated.resources.home_glance_open_in_view
import homesafe.shared.generated.resources.household_cars_open
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What's going on around the house, at the top of Home, as rows of pills: the cars parked in
 * view (each one's picture, name and where it is), then the doors (a pill per door that needs a
 * look — open, offline, or not heard from yet — and one counting the doors that are simply
 * closed), then the household's Teslas (each one's name and where it is: the street it is
 * parked on, home, driving or away). A row is left out when it has nothing in it. No headings: the pills say what
 * they are, so the cameras start as high up the screen as they can. Each row scrolls sideways
 * when there's more than fits.
 *
 * The details live a tap away: a car opens the In View page ([onOpenInView]: its arrival, last
 * sighting and the way to check and tag the cars), a door the Doors page ([onOpenDoors]), a
 * Tesla the cars' map ([onOpenCars]).
 */
@Composable
internal fun HomeGlanceRows(
    inView: List<InViewItem>,
    doors: List<Door>,
    onOpenInView: () -> Unit,
    onOpenDoors: () -> Unit,
    modifier: Modifier = Modifier,
    cars: List<HouseholdCar> = emptyList(),
    onOpenCars: () -> Unit = {},
) {
    // The pills are laid out 48dp tall around a 40dp shape, so the rows already sit 8dp apart.
    Column(modifier = modifier.fillMaxWidth()) {
        if (inView.isNotEmpty()) InViewPills(inView, onOpenInView)
        if (doors.isNotEmpty()) DoorPills(doors, onOpenDoors)
        if (cars.isNotEmpty()) CarPills(cars, onOpenCars)
    }
}

@Composable
private fun CarPills(cars: List<HouseholdCar>, onOpenCars: () -> Unit) {
    val openCars = stringResource(Res.string.household_cars_open)
    PillRow {
        cars.forEach { car ->
            // A car with somewhere to be gone by stands out, as an open door does.
            val due = car.parked?.moveByEpochSeconds != null
            GlancePill(
                label = car.name,
                detail = carWhereabouts(car),
                onClick = onOpenCars,
                onClickLabel = openCars,
                container = if (due) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                content = if (due) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                leading = { PillIcon(Icons.Filled.DirectionsCar) },
            )
        }
    }
}

@Composable
private fun PillRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun InViewPills(inView: List<InViewItem>, onOpenInView: () -> Unit) {
    val openInView = stringResource(Res.string.home_glance_open_in_view)
    PillRow {
        inView.forEach { item ->
            GlancePill(
                label = item.presentation.title.resolve(),
                detail = item.presentation.placeLabel,
                onClick = onOpenInView,
                onClickLabel = openInView,
                // A car the cameras stopped seeing a while ago is drawn quieter, and a screen reader
                // hears when it was last seen: it may have gone.
                faded = item.presentation.lastSeenLabel != null,
                stateDescription = item.presentation.lastSeenLabel?.resolve(),
                leading = { CarThumbnail(item.thumbnailUrl) },
            )
        }
    }
}

@Composable
private fun DoorPills(doors: List<Door>, onOpenDoors: () -> Unit) {
    val glance = doorGlance(doors)
    val openDoors = stringResource(Res.string.doors_home_open)
    PillRow {
        glance.shown.forEach { door ->
            val open = door.open == true
            val offline = door.online == false
            // A closed door is only here for its battery: say so, in the same warning colour as offline.
            val lowBattery = !offline && door.open == false && door.needsBattery
            GlancePill(
                label = door.name,
                detail = stringResource(
                    when {
                        offline -> Res.string.doors_chip_offline
                        open -> Res.string.doors_chip_open
                        lowBattery -> Res.string.doors_detail_battery_low
                        else -> Res.string.doors_chip_unknown
                    },
                ),
                onClick = onOpenDoors,
                onClickLabel = openDoors,
                container = if (open && !offline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                content = if (open && !offline) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                detailColor = if (offline || lowBattery) MaterialTheme.colorScheme.error else null,
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
    stateDescription: String? = null,
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
                .semantics { if (stateDescription != null) this.stateDescription = stateDescription }
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
                    // A faded pill's detail isn't dimmed a second time: both together fall below 4.5:1.
                    color = detailColor ?: if (faded) content else content.copy(alpha = 0.7f),
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
@Preview(name = "Home glance rows", widthDp = 412)
@Composable
private fun HomeGlanceRowsPreview() {
    GlancePreviewFrame {
        HomeGlanceRows(
            inView = listOf(previewGlanceCar),
            doors = listOf(Door("0x01", "Back door", open = true), Door("0x02", "Front door", open = false), Door("0x03", "Garage door", open = false)),
            onOpenInView = {},
            onOpenDoors = {},
            cars = previewGlanceTeslas,
        )
    }
}

/** Nothing parked, every door shut, one sensor gone quiet. */
@Preview(name = "Home glance rows, doors only", widthDp = 412)
@Composable
private fun HomeGlanceRowsDoorsPreview() {
    GlancePreviewFrame {
        HomeGlanceRows(
            inView = emptyList(),
            doors = listOf(Door("0x01", "Back door", open = false), Door("0x02", "Front door", open = false), Door("0x03", "Garage door", open = false, online = false)),
            onOpenInView = {},
            onOpenDoors = {},
        )
    }
}

/** Every door shut, but the front door's sensor wants a new battery. */
@Preview(name = "Home glance rows, a low battery", widthDp = 412)
@Composable
private fun HomeGlanceRowsLowBatteryPreview() {
    GlancePreviewFrame {
        HomeGlanceRows(
            inView = emptyList(),
            doors = listOf(Door("0x01", "Back door", open = false), Door("0x02", "Front door", open = false, battery = 12), Door("0x03", "Garage door", open = false)),
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

/** One Tesla at the curb with a sign coming up, one at home. */
private val previewGlanceTeslas = listOf(
    HouseholdCar(
        id = "andrews_tesla",
        name = "Andrew's Tesla",
        lastSeen = CarFix(37.77004, -122.25732, 270f, 1_789_405_000, moving = false, home = true),
        parked = CurbSpot(37.77004, -122.25732, "Elm St", side = null, sinceEpochSeconds = 1_789_400_000, moveByEpochSeconds = 1_789_430_000, note = "Street sweeping", limitMinutes = null, fromEpochSeconds = null, untilEpochSeconds = null),
        asleep = true,
    ),
    HouseholdCar(
        id = "sarahs_car",
        name = "Sarah's Tesla",
        lastSeen = CarFix(37.77020, -122.25800, 90f, 1_789_408_000, moving = false, home = true),
        parked = null,
        asleep = false,
    ),
)
