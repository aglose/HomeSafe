package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.maps.BaseMap
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.viewmodel.CarsUiState
import com.meticulouscreations.homesafe.viewmodel.CarsViewModel
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.data.MapTileStore
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
import homesafe.shared.generated.resources.household_cars_empty
import homesafe.shared.generated.resources.household_cars_map_description
import homesafe.shared.generated.resources.household_cars_move_by
import homesafe.shared.generated.resources.household_cars_move_now
import homesafe.shared.generated.resources.household_cars_no_places
import homesafe.shared.generated.resources.household_cars_select
import homesafe.shared.generated.resources.household_cars_show_all
import homesafe.shared.generated.resources.household_cars_title
import homesafe.shared.generated.resources.parking_map_directions
import homesafe.shared.generated.resources.parking_rules_toggle
import homesafe.shared.generated.resources.settings_refresh
import org.jetbrains.compose.resources.stringResource

/** The app's map tiles, for the cars' map: the one tile store the weather app's radar draws from too. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MapTilesViewModel(val tiles: MapTileStore) : ViewModel()

/**
 * The cars' map, from a Tesla's pill on Home: each of the household's Teslas on a street map
 * (whichever is picked in Settings), close in on them all, and under it a card per car saying
 * where it is, what the sign says where one is parked at the curb and by when it must move, and
 * how long ago Tesla last saw it. A tap on a card or a pin flies the map to that car; Show all
 * flies back out. Parking rules draws each watched street's curbs and their signs on the map,
 * and lists them under the cars, to check against the street's own (ParkingRules.kt). Read again on every visit, and every couple of minutes while it is up.
 */
@Composable
fun CarsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: CarsViewModel = metroViewModel()
    val maps: MapTilesViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    CarsContent(state = state, tiles = maps.tiles, onBack = onBack, onRefresh = viewModel::refresh, modifier = modifier)
}

/**
 * The page itself, from [state]: what the previews draw. [rulesShown] is whether the watched
 * streets' parking rules start out drawn on the map (see ParkingRules.kt); they always are while
 * no car has a place to show.
 */
@Composable
fun CarsContent(
    state: CarsUiState,
    tiles: MapTileSource,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    rulesShown: Boolean = false,
) {
    // The car or curb the map is on; null shows them all. Kept while the page is: it's only a key.
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    var showRules by rememberSaveable { mutableStateOf(rulesShown) }
    val carPins = remember(state.cars) {
        state.cars.mapNotNull { car ->
            val latitude = car.latitude ?: return@mapNotNull null
            val longitude = car.longitude ?: return@mapNotNull null
            MapPin(car.id, latitude, longitude)
        }
    }
    val curbs = remember(state.streets) { curbSigns(state.streets) }
    val rules = curbs.isNotEmpty() && (showRules || carPins.isEmpty())
    // The cars after the curbs' labels, so they are drawn over them.
    val pins = remember(carPins, curbs, rules) { if (rules) curbPins(curbs) + carPins else carPins }
    val lines = if (rules) curbLines(curbs, state.nowEpochSeconds, focus) else emptyList()
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
                    lines = lines,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = description }.testTag("cars_map"),
                ) { pin ->
                    val car = state.cars.firstOrNull { it.id == pin.key }
                    if (car != null) {
                        CarPin(car, selected = pin.key == focus, onClick = { focus = pin.key })
                    } else {
                        curbs.firstOrNull { it.key == pin.key }?.let { CurbLabel(it, state.nowEpochSeconds, selected = pin.key == focus, onClick = { focus = pin.key }) }
                    }
                }
                if (curbs.isNotEmpty() && carPins.isNotEmpty()) {
                    FilterChip(
                        selected = showRules,
                        onClick = { showRules = !showRules },
                        label = { Text(stringResource(Res.string.parking_rules_toggle)) },
                        leadingIcon = { Icon(Icons.Filled.LocalParking, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp).testTag("cars_parking_rules"),
                    )
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
                    selected = car.id == focus,
                    onClick = { focus = if (focus == car.id || car.latitude == null) null else car.id },
                )
            }
            if (rules) CurbSignsList(curbs, state.nowEpochSeconds, focus, onSelect = { key -> focus = if (focus == key) null else key })
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

/** One car: its name, where it is, the sign and its deadline at a curb, how long since Tesla saw it, and directions there. */
@Composable
private fun CarCard(car: HouseholdCar, nowEpochSeconds: Long, selected: Boolean, onClick: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(16.dp)
    val parked = car.parked
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                carSeen(car, nowEpochSeconds)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (car.asleep) Text(stringResource(Res.string.household_cars_asleep), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
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
