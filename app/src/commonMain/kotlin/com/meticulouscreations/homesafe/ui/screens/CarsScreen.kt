package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.CarLive
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.maps.BaseMap
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.viewmodel.CarsUiState
import com.meticulouscreations.homesafe.viewmodel.CarsViewModel
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.data.MapTileStore
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferencesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import com.meticulouscreations.homesafe.weather.ui.radar.MapPin
import com.meticulouscreations.homesafe.weather.ui.radar.PinMap
import com.meticulouscreations.homesafe.weather.ui.radar.mapCredit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.household_cars_asleep
import homesafe.shared.generated.resources.household_cars_battery
import homesafe.shared.generated.resources.household_cars_battery_km
import homesafe.shared.generated.resources.household_cars_battery_miles
import homesafe.shared.generated.resources.household_cars_charged
import homesafe.shared.generated.resources.household_cars_charging
import homesafe.shared.generated.resources.household_cars_charging_full_in
import homesafe.shared.generated.resources.household_cars_duration_hours
import homesafe.shared.generated.resources.household_cars_duration_minutes
import homesafe.shared.generated.resources.household_cars_empty
import homesafe.shared.generated.resources.household_cars_inside
import homesafe.shared.generated.resources.household_cars_live
import homesafe.shared.generated.resources.household_cars_locked
import homesafe.shared.generated.resources.household_cars_map_description
import homesafe.shared.generated.resources.household_cars_move_by
import homesafe.shared.generated.resources.household_cars_move_now
import homesafe.shared.generated.resources.household_cars_no_places
import homesafe.shared.generated.resources.household_cars_plugged_in
import homesafe.shared.generated.resources.household_cars_select
import homesafe.shared.generated.resources.household_cars_sentry
import homesafe.shared.generated.resources.household_cars_show_all
import homesafe.shared.generated.resources.household_cars_title
import homesafe.shared.generated.resources.household_cars_trip
import homesafe.shared.generated.resources.household_cars_trip_no_eta
import homesafe.shared.generated.resources.household_cars_unlocked
import homesafe.shared.generated.resources.parking_map_directions
import homesafe.shared.generated.resources.settings_refresh
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The units the weather app writes in (Settings there), for a car's range and cabin temperature. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class CarUnitsViewModel(weather: WeatherPreferencesRepository) : ViewModel() {
    val units: StateFlow<WeatherUnits> = weather.observe()
        .map { it.units }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeatherUnits())
}

/** The app's map tiles, for the cars' map: the one tile store the weather app's radar draws from too. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MapTilesViewModel(val tiles: MapTileStore) : ViewModel()

/**
 * The cars' map, from a Tesla's pill on Home: each of the household's Teslas on a street map
 * (whichever is picked in Settings), close in on them all, and under it a card per car saying
 * where it is, what the sign says where one is parked at the curb and by when it must move, how
 * long ago Tesla last saw it, and what a car that streams last said: battery, charging, the trip
 * it is on, locks, Sentry and the cabin's temperature. A tap on a card or a pin flies the map to that car; Show all
 * flies back out. Read again on every visit, and every couple of minutes while it is up.
 */
@Composable
fun CarsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: CarsViewModel = metroViewModel()
    val maps: MapTilesViewModel = metroViewModel()
    val unitsViewModel: CarUnitsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val units by unitsViewModel.units.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    CarsContent(state = state, tiles = maps.tiles, onBack = onBack, onRefresh = viewModel::refresh, modifier = modifier, units = units)
}

/** The page itself, from [state]: what the previews draw. */
@Composable
fun CarsContent(
    state: CarsUiState,
    tiles: MapTileSource,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    units: WeatherUnits = WeatherUnits(),
) {
    // The car the map is on; null shows them all. Kept while the page is: it's only a car's name.
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    val pins = remember(state.cars) {
        state.cars.mapNotNull { car ->
            val latitude = car.latitude ?: return@mapNotNull null
            val longitude = car.longitude ?: return@mapNotNull null
            MapPin(car.id, latitude, longitude)
        }
    }
    val baseMap by tiles.baseMap.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CarsHeader(onBack = onBack, onRefresh = onRefresh)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (pins.isNotEmpty()) {
                val description = stringResource(Res.string.household_cars_map_description)
                PinMap(
                    pins = pins,
                    tiles = tiles,
                    focus = focus?.takeIf { key -> pins.any { it.key == key } },
                    animated = animated,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = description }.testTag("cars_map"),
                ) { pin ->
                    val car = state.cars.first { it.id == pin.key }
                    CarPin(car, selected = pin.key == focus, onClick = { focus = pin.key })
                }
                if (focus != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 4.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    ) {
                        TextButton(onClick = { focus = null }, modifier = Modifier.testTag("cars_show_all")) {
                            Text(stringResource(Res.string.household_cars_show_all))
                        }
                    }
                }
            } else if (!state.loaded) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                // No cars, or cars the server has no place for yet (it hasn't heard from Tesla since it started).
                Text(
                    stringResource(if (state.cars.isEmpty()) Res.string.household_cars_empty else Res.string.household_cars_no_places),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 360.dp).padding(24.dp),
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                // The cards end above the shell's floating nav: in a list this short, room to scroll
                // clear of it would leave the second card half under it at rest.
                .padding(bottom = bottomNavClearance())
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Google's map credits itself on the map; OpenStreetMap's contributors are named here.
            if (pins.isNotEmpty() && baseMap !is BaseMap.Google) {
                Text(mapCredit(baseMap, null), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            state.cars.forEach { car ->
                CarCard(
                    car = car,
                    nowEpochSeconds = state.nowEpochSeconds,
                    units = units,
                    selected = car.id == focus,
                    onClick = { focus = if (focus == car.id || car.latitude == null) null else car.id },
                )
            }
        }
    }
}

@Composable
private fun CarsHeader(onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            stringResource(Res.string.household_cars_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.settings_refresh), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A car on the map: a round badge on its spot, its name under it. One with somewhere to be gone by is drawn in the accent colour. */
@Composable
private fun CarPin(car: HouseholdCar, selected: Boolean, onClick: () -> Unit) {
    val due = car.parked?.moveByEpochSeconds != null
    val container = when {
        selected -> MaterialTheme.colorScheme.primary
        due -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        due -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    // The badge is what's placed on the spot; the name hangs below it, outside its bounds.
    Box(Modifier.size(PIN_SIZE)) {
        Surface(
            shape = CircleShape,
            color = container,
            contentColor = content,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.household_cars_select), onClick = onClick)
                // The name under it is drawn beside the badge, not in it: a screen reader hears it here.
                .semantics {
                    contentDescription = car.name
                    this.selected = selected
                },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(20.dp)) }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            modifier = Modifier.align(Alignment.TopCenter).wrapContentSize(unbounded = true).offset(y = PIN_SIZE + 4.dp),
        ) {
            Text(car.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/**
 * One car: its name, where it is, the sign and its deadline at a curb, the trip and battery it
 * streamed, how long since Tesla saw it with its locks and Sentry, and directions there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarCard(car: HouseholdCar, nowEpochSeconds: Long, units: WeatherUnits, selected: Boolean, onClick: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(16.dp)
    val parked = car.parked
    val live = car.live
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = stringResource(Res.string.household_cars_select), onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)
            .testTag("cars_card_${car.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(car.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (parked != null) parkingPlace(parked.street, parked.side) else carWhereabouts(car),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val by = parked?.moveByEpochSeconds
            if (by != null) {
                Text(
                    if (by <= nowEpochSeconds) stringResource(Res.string.household_cars_move_now) else stringResource(Res.string.household_cars_move_by, parkingWhen(by, nowEpochSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                parkingReason(parked.note, parked.limitMinutes, parked.fromEpochSeconds, parked.untilEpochSeconds)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // A route the car has stopped streaming may be over: only while it streams.
            val streaming = live?.streaming(nowEpochSeconds) == true
            if (live != null && streaming) {
                carTrip(live)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (live != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    carBattery(live, units)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    carCharging(live)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = MaterialTheme.typography.labelSmall
                val quiet = MaterialTheme.colorScheme.onSurfaceVariant
                // A car streaming now is where it says: a parked one streams its place only once it moves,
                // so "seen 3 hours ago" would read as stale beside it.
                if (streaming) {
                    Text(stringResource(Res.string.household_cars_live), style = label, color = MaterialTheme.colorScheme.primary)
                } else {
                    carSeen(car, nowEpochSeconds)?.let { Text(it, style = label, color = quiet) }
                    if (car.asleep) Text(stringResource(Res.string.household_cars_asleep), style = label, color = MaterialTheme.colorScheme.tertiary)
                }
                when (live?.locked) {
                    true -> Text(stringResource(Res.string.household_cars_locked), style = label, color = quiet)
                    false -> Text(stringResource(Res.string.household_cars_unlocked), style = label, color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
                if (live?.sentry == true) Text(stringResource(Res.string.household_cars_sentry), style = label, color = quiet)
                live?.insideCelsius?.let { Text(stringResource(Res.string.household_cars_inside, WeatherFormat.degrees(it, units)), style = label, color = quiet) }
            }
        }
        val latitude = car.latitude
        val longitude = car.longitude
        if (latitude != null && longitude != null) {
            Spacer(Modifier.size(4.dp))
            TextButton(onClick = { runCatching { uriHandler.openUri("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude") } }) {
                Text(stringResource(Res.string.parking_map_directions))
            }
        }
    }
}

private val PIN_SIZE = 36.dp

/** "72% · 215 mi", in the weather app's distance; null when the car hasn't said its battery. */
@Composable
private fun carBattery(live: CarLive, units: WeatherUnits): String? {
    val battery = live.batteryPercent ?: return null
    val miles = live.rangeMiles ?: return stringResource(Res.string.household_cars_battery, battery)
    return when (units.measures) {
        MeasureSystem.IMPERIAL -> stringResource(Res.string.household_cars_battery_miles, battery, miles.roundToInt())
        MeasureSystem.METRIC -> stringResource(Res.string.household_cars_battery_km, battery, (miles * KM_PER_MILE).roundToInt())
    }
}

/** Charging, how long until full, done, or plugged in and not charging; null when unplugged. */
@Composable
private fun carCharging(live: CarLive): String? = when (live.charging) {
    CarLive.Charging.CHARGING -> {
        val minutes = live.minutesToFull
        if (minutes != null) stringResource(Res.string.household_cars_charging_full_in, carDuration(minutes)) else stringResource(Res.string.household_cars_charging)
    }

    CarLive.Charging.COMPLETE -> stringResource(Res.string.household_cars_charged)

    CarLive.Charging.PLUGGED_IN -> stringResource(Res.string.household_cars_plugged_in)

    null -> null
}

/** Where the car is navigating to and how far off it is; null when it isn't navigating. */
@Composable
private fun carTrip(live: CarLive): String? {
    val destination = live.destination ?: return null
    val minutes = live.minutesToArrival ?: return stringResource(Res.string.household_cars_trip_no_eta, destination)
    return stringResource(Res.string.household_cars_trip, destination, carDuration(minutes))
}

@Composable
private fun carDuration(minutes: Int): String = if (minutes < 60) {
    stringResource(Res.string.household_cars_duration_minutes, minutes)
} else {
    stringResource(Res.string.household_cars_duration_hours, minutes / 60, minutes % 60)
}

private const val KM_PER_MILE = 1.609344
