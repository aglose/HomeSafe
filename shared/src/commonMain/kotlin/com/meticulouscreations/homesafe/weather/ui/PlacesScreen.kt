package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.PlaceSearch
import com.meticulouscreations.homesafe.weather.PlaceWeather
import com.meticulouscreations.homesafe.weather.WeatherUiState
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import com.meticulouscreations.homesafe.weather.ui.sky.SkyFreeze
import com.meticulouscreations.homesafe.weather.ui.sky.SkyScene
import com.meticulouscreations.homesafe.weather.ui.sky.WeatherSky
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_done
import homesafe.shared.generated.resources.weather_city_austin
import homesafe.shared.generated.resources.weather_city_austin_region
import homesafe.shared.generated.resources.weather_city_boston
import homesafe.shared.generated.resources.weather_city_boston_region
import homesafe.shared.generated.resources.weather_city_chicago
import homesafe.shared.generated.resources.weather_city_chicago_region
import homesafe.shared.generated.resources.weather_city_denver
import homesafe.shared.generated.resources.weather_city_denver_region
import homesafe.shared.generated.resources.weather_city_honolulu
import homesafe.shared.generated.resources.weather_city_honolulu_region
import homesafe.shared.generated.resources.weather_city_london
import homesafe.shared.generated.resources.weather_city_london_region
import homesafe.shared.generated.resources.weather_city_los_angeles
import homesafe.shared.generated.resources.weather_city_los_angeles_region
import homesafe.shared.generated.resources.weather_city_miami
import homesafe.shared.generated.resources.weather_city_miami_region
import homesafe.shared.generated.resources.weather_city_new_york
import homesafe.shared.generated.resources.weather_city_new_york_region
import homesafe.shared.generated.resources.weather_city_paris
import homesafe.shared.generated.resources.weather_city_paris_region
import homesafe.shared.generated.resources.weather_city_san_francisco
import homesafe.shared.generated.resources.weather_city_san_francisco_region
import homesafe.shared.generated.resources.weather_city_seattle
import homesafe.shared.generated.resources.weather_city_seattle_region
import homesafe.shared.generated.resources.weather_city_sydney
import homesafe.shared.generated.resources.weather_city_sydney_region
import homesafe.shared.generated.resources.weather_city_tokyo
import homesafe.shared.generated.resources.weather_city_tokyo_region
import homesafe.shared.generated.resources.weather_high_low
import homesafe.shared.generated.resources.weather_place_current
import homesafe.shared.generated.resources.weather_places_add
import homesafe.shared.generated.resources.weather_places_added
import homesafe.shared.generated.resources.weather_places_clear_search
import homesafe.shared.generated.resources.weather_places_edit
import homesafe.shared.generated.resources.weather_places_locating
import homesafe.shared.generated.resources.weather_places_location_body
import homesafe.shared.generated.resources.weather_places_location_denied
import homesafe.shared.generated.resources.weather_places_move_down
import homesafe.shared.generated.resources.weather_places_move_up
import homesafe.shared.generated.resources.weather_places_no_results
import homesafe.shared.generated.resources.weather_places_popular
import homesafe.shared.generated.resources.weather_places_remove
import homesafe.shared.generated.resources.weather_places_search_hint
import homesafe.shared.generated.resources.weather_places_searching
import homesafe.shared.generated.resources.weather_places_use_location
import homesafe.shared.generated.resources.weather_places_yours
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A city worth offering before anyone has typed: its name and region are copy here (shown in the reader's language), and become the saved place's data once it is added. */
private class PopularCity(val name: StringResource, val region: StringResource, val latitude: Double, val longitude: Double)

private val PopularCities = listOf(
    PopularCity(Res.string.weather_city_new_york, Res.string.weather_city_new_york_region, 40.7128, -74.0060),
    PopularCity(Res.string.weather_city_los_angeles, Res.string.weather_city_los_angeles_region, 34.0522, -118.2437),
    PopularCity(Res.string.weather_city_chicago, Res.string.weather_city_chicago_region, 41.8781, -87.6298),
    PopularCity(Res.string.weather_city_seattle, Res.string.weather_city_seattle_region, 47.6062, -122.3321),
    PopularCity(Res.string.weather_city_san_francisco, Res.string.weather_city_san_francisco_region, 37.7749, -122.4194),
    PopularCity(Res.string.weather_city_denver, Res.string.weather_city_denver_region, 39.7392, -104.9903),
    PopularCity(Res.string.weather_city_miami, Res.string.weather_city_miami_region, 25.7617, -80.1918),
    PopularCity(Res.string.weather_city_austin, Res.string.weather_city_austin_region, 30.2672, -97.7431),
    PopularCity(Res.string.weather_city_boston, Res.string.weather_city_boston_region, 42.3601, -71.0589),
    PopularCity(Res.string.weather_city_honolulu, Res.string.weather_city_honolulu_region, 21.3069, -157.8583),
    PopularCity(Res.string.weather_city_london, Res.string.weather_city_london_region, 51.5072, -0.1276),
    PopularCity(Res.string.weather_city_paris, Res.string.weather_city_paris_region, 48.8566, 2.3522),
    PopularCity(Res.string.weather_city_tokyo, Res.string.weather_city_tokyo_region, 35.6762, 139.6503),
    PopularCity(Res.string.weather_city_sydney, Res.string.weather_city_sydney_region, -33.8688, 151.2093),
)

/**
 * Places: where the weather app looks. A search box over the top; under it, until something is
 * typed, the phone's own position (or the way to turn that on), the saved cities as cards each
 * under its own sky, and a handful of cities to add with one tap. Typing turns the page over to
 * what the search finds.
 *
 * A tap on a place turns the app to it ([onSelect]); a tap on a result or a suggestion saves it
 * and turns to it ([onAdd]). "Edit" brings out each saved city's remove and reorder buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlacesScreen(
    state: WeatherUiState,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onAdd: (Place) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onUseLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    var editing by rememberSaveable { mutableStateOf(false) }
    val searching = state.search.query.trim().length >= 2
    val saved = state.places.filter { !it.place.isCurrentLocation }
    LazyColumn(
        modifier.fillMaxSize().background(colors.surface).testTag("weather_places"),
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "search") { SearchField(state.search.query, onQueryChange, Modifier.cardWidth()) }

        if (searching) {
            searchResults(state.search, state, onAdd)
            return@LazyColumn
        }

        item(key = "here") { HereRow(state, onSelect, onUseLocation, Modifier.cardWidth()) }

        if (saved.isNotEmpty()) {
            item(key = "saved-title") {
                Row(Modifier.cardWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardLabel(stringResource(Res.string.weather_places_yours), modifier = Modifier.weight(1f))
                    Text(
                        stringResource(if (editing) Res.string.common_done else Res.string.weather_places_edit),
                        style = type.bodyStrong,
                        color = colors.accent,
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { editing = !editing }.testTag("weather_places_edit").padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            items(saved.size, key = { saved[it].place.id }, contentType = { "place" }) { i ->
                val entry = saved[i]
                PlaceCard(
                    entry = entry,
                    name = entry.place.name,
                    selected = entry.place.id == state.selected?.place?.id,
                    nowEpochSeconds = state.nowEpochSeconds,
                    onClick = { onSelect(entry.place.id) },
                    modifier = Modifier.cardWidth(),
                    trailing = if (!editing) {
                        null
                    } else {
                        {
                            Row {
                                if (i > 0) RoundAction(Icons.Filled.ArrowUpward, stringResource(Res.string.weather_places_move_up, entry.place.name)) { onMove(entry.place.id, true) }
                                if (i < saved.lastIndex) RoundAction(Icons.Filled.ArrowDownward, stringResource(Res.string.weather_places_move_down, entry.place.name)) { onMove(entry.place.id, false) }
                                RoundAction(Icons.Filled.DeleteOutline, stringResource(Res.string.weather_places_remove, entry.place.name)) { onRemove(entry.place.id) }
                            }
                        }
                    },
                )
            }
        }

        item(key = "popular") {
            Column(Modifier.cardWidth().padding(top = 8.dp)) {
                CardLabel(stringResource(Res.string.weather_places_popular), modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PopularCities.forEach { city ->
                        val id = Place.idFor(city.latitude, city.longitude)
                        if (state.places.none { it.place.id == id }) {
                            val name = stringResource(city.name)
                            val region = stringResource(city.region)
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .background(colors.surfaceRaised)
                                    .border(1.dp, colors.hairline, CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.weather_places_add, name)) {
                                        onAdd(Place(id, name, region, city.latitude, city.longitude))
                                    }
                                    .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(name, style = type.label, color = colors.onSky)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(search: PlaceSearch, state: WeatherUiState, onAdd: (Place) -> Unit) {
    when {
        search.error != null -> item(key = "search-error") {
            Text(search.error.resolve(), style = WeatherTheme.type.body, color = WeatherTheme.colors.watch, modifier = Modifier.cardWidth().padding(8.dp))
        }

        search.loading && search.results.isEmpty() -> item(key = "search-loading") {
            Text(stringResource(Res.string.weather_places_searching), style = WeatherTheme.type.body, color = WeatherTheme.colors.onSkyMuted, modifier = Modifier.cardWidth().padding(8.dp))
        }

        search.results.isEmpty() -> item(key = "search-empty") {
            Text(stringResource(Res.string.weather_places_no_results, search.query.trim()), style = WeatherTheme.type.body, color = WeatherTheme.colors.onSkyMuted, modifier = Modifier.cardWidth().padding(8.dp))
        }

        else -> items(search.results.size, key = { "result-${search.results[it].id}" }, contentType = { "result" }) { i ->
            val place = search.results[i]
            val colors = WeatherTheme.colors
            val added = state.isSaved(place)
            Row(
                Modifier
                    .cardWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceRaised)
                    .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.weather_places_add, place.name)) { onAdd(place) }
                    .testTag("weather_place_result")
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = WeatherTheme.type.bodyStrong, color = colors.onSky)
                    if (place.region.isNotBlank()) Text(place.region, style = WeatherTheme.type.label, color = colors.onSkyMuted)
                }
                if (added) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(Res.string.weather_places_added), tint = colors.good, modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val focus = LocalFocusManager.current
    val hint = stringResource(Res.string.weather_places_search_hint)
    Row(
        modifier.clip(CircleShape).background(colors.surfaceRaised).border(1.dp, colors.hairline, CircleShape).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = colors.onSkyFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (query.isEmpty()) Text(hint, style = WeatherTheme.type.body, color = colors.onSkyFaint)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = WeatherTheme.type.body.copy(color = colors.onSky),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().testTag("weather_places_search").semantics { contentDescription = hint },
            )
        }
        if (query.isNotEmpty()) {
            RoundAction(Icons.Filled.Close, stringResource(Res.string.weather_places_clear_search)) { onQueryChange("") }
        }
    }
}

/** The phone's own position: its card when it's known, otherwise why it isn't and what to do about it. */
@Composable
private fun HereRow(state: WeatherUiState, onSelect: (String) -> Unit, onUseLocation: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val here = state.places.firstOrNull { it.place.isCurrentLocation }
    val granted = state.locationAccess == LocationAccess.WHILE_IN_USE || state.locationAccess == LocationAccess.ALWAYS
    when {
        here != null -> PlaceCard(
            entry = here,
            name = here.place.name.ifBlank { stringResource(Res.string.weather_place_current) },
            selected = here.place.id == state.selected?.place?.id,
            nowEpochSeconds = state.nowEpochSeconds,
            onClick = { onSelect(here.place.id) },
            modifier = modifier,
            badge = Icons.Filled.NearMe,
            trailing = if (granted || !state.locationSupported) {
                null
            } else {
                { RoundAction(Icons.Filled.MyLocation, stringResource(Res.string.weather_places_use_location), onUseLocation) }
            },
        )

        state.locating -> Text(stringResource(Res.string.weather_places_locating), style = WeatherTheme.type.body, color = colors.onSkyMuted, modifier = modifier.padding(8.dp))

        state.locationSupported && !granted -> Column(
            modifier.clip(WeatherCardShape).background(colors.surfaceRaised).border(1.dp, colors.hairline, WeatherCardShape).padding(16.dp),
        ) {
            Text(
                stringResource(if (state.locationAccess == LocationAccess.DENIED) Res.string.weather_places_location_denied else Res.string.weather_places_location_body),
                style = WeatherTheme.type.body,
                color = colors.onSkyMuted,
            )
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(Res.string.weather_places_use_location), onUseLocation, Modifier.testTag("weather_use_location"), icon = Icons.Filled.MyLocation)
        }

        else -> Unit
    }
}

/**
 * A place as a card under its own sky (held still: a list of these is a list of pictures, not
 * of animations): its name, what it's doing there, the temperature and the day's range.
 */
@Composable
private fun PlaceCard(
    entry: PlaceWeather,
    name: String,
    selected: Boolean,
    nowEpochSeconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = WeatherTheme.units
    val report = entry.report
    val scene = remember(report?.current, entry.place, nowEpochSeconds / 600) {
        report?.let { SkyScene.of(it.current, entry.place.latitude, entry.place.longitude, nowEpochSeconds) }
            ?: SkyScene.of(WeatherKind.PARTLY_CLOUDY, null, 10.0, 270, null, entry.place.latitude, entry.place.longitude, nowEpochSeconds)
    }
    Box(
        modifier
            .height(92.dp)
            .clip(WeatherCardShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.cardBorder, WeatherCardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("weather_place_${entry.place.id}"),
    ) {
        WeatherSky(scene, Modifier.fillMaxSize(), glass = false, freeze = SkyFreeze(time = 11f + (entry.place.latitude % 7).toFloat()))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0x8C000000), Color(0x33000000)))))
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (badge != null) {
                        Icon(badge, contentDescription = null, tint = colors.onSky, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(name, style = type.title, color = colors.onSky, maxLines = 1)
                }
                val line = when {
                    report != null -> stringResource(report.current.kind.label(report.current.isDay))
                    entry.error != null -> entry.error.resolve()
                    else -> entry.place.region
                }
                Text(line, style = type.label, color = colors.onSkyMuted, maxLines = 1)
            }
            if (trailing != null) {
                trailing()
            } else if (report != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(WeatherFormat.degrees(report.current.temperatureC, units), style = type.heroSmall, color = colors.onSky)
                    report.today(nowEpochSeconds)?.let { today ->
                        Text(stringResource(Res.string.weather_high_low, WeatherFormat.degrees(today.highC, units), WeatherFormat.degrees(today.lowC, units)), style = type.label, color = colors.onSkyMuted)
                    }
                }
            }
        }
    }
}

/** A small round button with only an icon, named by [label] for anyone not looking at it. */
@Composable
internal fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = WeatherTheme.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = colors.onSky, modifier = Modifier.size(20.dp))
    }
}
