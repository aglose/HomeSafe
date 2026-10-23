package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.Era
import com.meticulouscreations.homesafe.atlas.domain.Place
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.atlas.domain.Trip
import com.meticulouscreations.homesafe.atlas.domain.VisitRole
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.WebMercator
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_day_summary
import homesafe.shared.generated.resources.atlas_era_detail
import homesafe.shared.generated.resources.atlas_era_now
import homesafe.shared.generated.resources.atlas_era_span
import homesafe.shared.generated.resources.atlas_eras_title
import homesafe.shared.generated.resources.atlas_trip_days_title
import homesafe.shared.generated.resources.atlas_trip_detail
import homesafe.shared.generated.resources.atlas_trip_earlier
import homesafe.shared.generated.resources.atlas_trip_later
import homesafe.shared.generated.resources.atlas_trip_stops
import homesafe.shared.generated.resources.atlas_trip_stops_title
import homesafe.shared.generated.resources.atlas_trips_cities
import homesafe.shared.generated.resources.atlas_trips_countries
import homesafe.shared.generated.resources.atlas_trips_coverage_title
import homesafe.shared.generated.resources.atlas_trips_day_trip
import homesafe.shared.generated.resources.atlas_trips_flew
import homesafe.shared.generated.resources.atlas_trips_from_home
import homesafe.shared.generated.resources.atlas_trips_nights
import homesafe.shared.generated.resources.atlas_trips_none
import homesafe.shared.generated.resources.atlas_trips_regions
import homesafe.shared.generated.resources.atlas_trips_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What a trip goes by: the town most of it was spent in, or the place where there is no town. */
@Composable
internal fun tripTitle(trip: Trip, state: AtlasUiState): String = localityText(trip.locality) ?: placeTitle(trip.mainPlace, state.history)

/**
 * Away from home: how much of the world the record reaches, every trip on it with the newest
 * first, and the places that have been home.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripsScreen(state: AtlasUiState, padding: PaddingValues, onOpenTrip: (Long) -> Unit, onOpenPlace: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    val analysis = state.analysis
    LazyColumn(modifier.fillMaxSize().testTag("atlas_trips"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "coverage") {
            AtlasCard(Modifier.padding(horizontal = AtlasGutter), title = stringResource(Res.string.atlas_trips_coverage_title)) {
                Row(Modifier.fillMaxWidth()) {
                    AtlasStat(analysis.countries.size.toString(), pluralStringResource(Res.plurals.atlas_trips_countries, analysis.countries.size), Modifier.weight(1f), tint = colors.air)
                    AtlasStat(analysis.regions.size.toString(), pluralStringResource(Res.plurals.atlas_trips_regions, analysis.regions.size), Modifier.weight(1f), tint = colors.trail)
                    AtlasStat(AtlasFormat.grouped(analysis.cities.toLong()), pluralStringResource(Res.plurals.atlas_trips_cities, analysis.cities), Modifier.weight(1f), tint = colors.place)
                }
                if (analysis.regions.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // "US-IL" reads as the state; a country with no states to its name as itself.
                        analysis.regions.forEach { region ->
                            Text(
                                region.code.substringAfter('-'),
                                style = AtlasTheme.type.micro,
                                color = colors.text,
                                modifier = Modifier.clip(CircleShape).background(colors.surfaceRaised).border(1.dp, colors.hairline, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        item(key = "trips_title") { SectionTitle(stringResource(Res.string.atlas_trips_title)) }
        if (analysis.trips.isEmpty()) {
            item(key = "no_trips") {
                Text(stringResource(Res.string.atlas_trips_none), style = AtlasTheme.type.body, color = colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(28.dp))
            }
        }
        itemsIndexed(analysis.trips, key = { _, trip -> trip.start }) { index, trip -> TripCard(trip, state, onClick = { onOpenTrip(trip.start) }, modifier = Modifier.testTag("atlas_trip_$index")) }
        if (analysis.eras.isNotEmpty()) {
            item(key = "eras_title") { SectionTitle(stringResource(Res.string.atlas_eras_title)) }
            itemsIndexed(analysis.eras.asReversed(), key = { _, era -> era.startDay }) { index, era -> EraCard(era, state, current = index == 0) { onOpenPlace(era.home.key) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = AtlasTheme.type.title, color = AtlasTheme.colors.text, modifier = Modifier.padding(start = AtlasGutter + 4.dp, top = 10.dp).semantics { heading() })
}

@Composable
private fun TripCard(trip: Trip, state: AtlasUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    Row(
        modifier
            .padding(horizontal = AtlasGutter)
            .fillMaxWidth()
            .clip(AtlasCardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AtlasCardShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteSketch(listOfNotNull(state.analysis.homeAt(trip.startDay)?.home) + trip.stops, if (trip.flew) colors.air else colors.trail, Modifier.size(64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tripTitle(trip, state), style = AtlasTheme.type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (trip.flew) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Flight, contentDescription = stringResource(Res.string.atlas_trips_flew), tint = colors.air, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                if (trip.nights > 0) stringResource(Res.string.atlas_era_span, dateText(trip.startDay, year = false), dateText(trip.endDay)) else dateText(trip.startDay),
                style = AtlasTheme.type.label,
                color = colors.textMuted,
                maxLines = 1,
            )
            Text(
                stringResource(
                    Res.string.atlas_trip_detail,
                    if (trip.nights > 0) pluralStringResource(Res.plurals.atlas_trips_nights, trip.nights, trip.nights) else stringResource(Res.string.atlas_trips_day_trip),
                    stringResource(Res.string.atlas_trips_from_home, distanceText(trip.farthestKm * 1000)),
                ),
                style = AtlasTheme.type.label,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The shape of a route, with nothing under it: the stops joined in order, fitted to the square. */
@Composable
private fun RouteSketch(stops: List<Place>, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(colors.surfaceRaised)) {
        if (stops.isEmpty()) return@Canvas
        val xs = stops.map { WebMercator.x(it.longitude) }
        val ys = stops.map { WebMercator.y(it.latitude) }
        val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), 1e-9)
        val inset = size.minDimension * 0.2f
        val room = size.minDimension - inset * 2

        fun at(i: Int) = Offset(
            (inset + ((xs[i] - (xs.min() + xs.max()) / 2) / span + 0.5) * room).toFloat(),
            (inset + ((ys[i] - (ys.min() + ys.max()) / 2) / span + 0.5) * room).toFloat(),
        )
        val path = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until stops.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(path, tint, alpha = 0.3f, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, tint, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(colors.home, 3.dp.toPx(), at(0))
        for (i in 1 until stops.size) drawCircle(colors.text, 1.8.dp.toPx(), at(i))
    }
}

@Composable
private fun EraCard(era: Era, state: AtlasUiState, current: Boolean, onClick: () -> Unit) {
    val colors = AtlasTheme.colors
    Column(
        Modifier
            .padding(horizontal = AtlasGutter)
            .fillMaxWidth()
            .clip(AtlasCardShape)
            .background(colors.surface)
            .border(1.dp, if (current) colors.home.copy(alpha = 0.5f) else colors.hairline, AtlasCardShape)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(localityText(era.locality) ?: placeTitle(era.home, state.history), style = AtlasTheme.type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(Res.string.atlas_era_span, monthText(com.meticulouscreations.homesafe.atlas.domain.CivilDate.of(era.startDay).monthIndex), if (current) stringResource(Res.string.atlas_era_now) else monthText(com.meticulouscreations.homesafe.atlas.domain.CivilDate.of(era.endDay).monthIndex)),
            style = AtlasTheme.type.label,
            color = if (current) colors.home else colors.textMuted,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            pluralStringResource(Res.plurals.atlas_era_detail, era.places, AtlasFormat.grouped(era.places.toLong()), distanceText(era.meters)),
            style = AtlasTheme.type.label,
            color = colors.textFaint,
        )
    }
}

/**
 * One trip: the map of it, with the way there and back and around drawn on it, then where it
 * stopped and what each day of it held. [onEarlier] and [onLater] step to the trip before or
 * after, null where there is none.
 */
@Composable
internal fun TripScreen(
    state: AtlasUiState,
    tiles: MapTileSource,
    trip: Trip,
    padding: PaddingValues,
    onOpenDay: (Int) -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEarlier: (() -> Unit)? = null,
    onLater: (() -> Unit)? = null,
    active: Boolean = true,
) {
    val colors = AtlasTheme.colors
    val history = state.history
    val map = remember(trip) { AtlasMapState() }
    val home = state.analysis.homeAt(trip.startDay)?.home
    val tracks = remember(state.paths, trip) { state.paths?.tracks.orEmpty().filter { it.end >= trip.start && it.start <= trip.end } }
    val flights = remember(history, trip) { history.moves.filter { it.mode == TravelMode.FLY && it.start >= trip.start - 60 && it.end <= trip.end + 60 } }
    LaunchedEffect(trip) {
        MapBounds.of((listOfNotNull(home) + trip.stops).map { it.latitude to it.longitude })?.let { map.show(it) }
    }
    val markers = remember(trip, home) {
        listOfNotNull(home?.let { MapMarker(it.key, it.latitude, it.longitude, VisitRole.HOME, 0.4f) }) + trip.stops.map { MapMarker(it.key, it.latitude, it.longitude, VisitRole.OTHER, 0.3f) }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).testTag("atlas_trip"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val stepping = onEarlier != null || onLater != null
        Row(Modifier.fillMaxWidth().padding(horizontal = if (stepping) 8.dp else AtlasGutter + 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stepping) {
                AtlasRoundButton(Icons.Filled.ChevronLeft, stringResource(Res.string.atlas_trip_earlier), { onEarlier?.invoke() }, Modifier.testTag("atlas_trip_earlier"), enabled = onEarlier != null)
            }
            Column(Modifier.weight(1f), horizontalAlignment = if (stepping) Alignment.CenterHorizontally else Alignment.Start) {
                Text(
                    tripTitle(trip, state),
                    style = AtlasTheme.type.hero,
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (stepping) TextAlign.Center else TextAlign.Start,
                )
                Text(
                    if (trip.nights > 0) stringResource(Res.string.atlas_era_span, dateText(trip.startDay, year = false), dateText(trip.endDay)) else dateText(trip.startDay, weekday = true),
                    style = AtlasTheme.type.body,
                    color = colors.textMuted,
                    textAlign = if (stepping) TextAlign.Center else TextAlign.Start,
                )
            }
            if (stepping) {
                AtlasRoundButton(Icons.Filled.ChevronRight, stringResource(Res.string.atlas_trip_later), { onLater?.invoke() }, Modifier.testTag("atlas_trip_later"), enabled = onLater != null)
            }
        }
        Box(Modifier.padding(horizontal = AtlasGutter).fillMaxWidth().height(320.dp).clip(AtlasCardShape).border(1.dp, colors.hairline, AtlasCardShape)) {
            AtlasMap(map, tiles, Modifier.fillMaxSize(), markers = markers, flights = flights, highlight = tracks, animated = active, onTapMarker = { onOpenPlace(it.key) })
        }
        AtlasCard(Modifier.padding(horizontal = AtlasGutter)) {
            Row(Modifier.fillMaxWidth()) {
                AtlasStat(
                    if (trip.nights > 0) pluralStringResource(Res.plurals.atlas_trips_nights, trip.nights, trip.nights) else stringResource(Res.string.atlas_trips_day_trip),
                    stringResource(Res.string.atlas_trips_from_home, distanceText(trip.farthestKm * 1000)),
                    Modifier.weight(1f),
                    tint = if (trip.flew) colors.air else colors.trail,
                )
                AtlasStat(distanceText(trip.meters), pluralStringResource(Res.plurals.atlas_trip_stops, trip.stops.size, trip.stops.size), Modifier.weight(1f))
            }
        }
        AtlasCard(Modifier.padding(horizontal = AtlasGutter), title = stringResource(Res.string.atlas_trip_stops_title)) {
            trip.stops.forEach { stop ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenPlace(stop.key) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.place))
                    Spacer(Modifier.width(12.dp))
                    Text(placeTitle(stop, history), style = AtlasTheme.type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        AtlasCard(Modifier.padding(horizontal = AtlasGutter), title = stringResource(Res.string.atlas_trip_days_title)) {
            (trip.startDay..trip.endDay).forEach { day ->
                val index = state.analysis.days.index(day) ?: return@forEach
                val places = state.analysis.days.places[index]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenDay(day) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateText(day, weekday = true, year = false), style = AtlasTheme.type.body, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        pluralStringResource(Res.plurals.atlas_day_summary, places, distanceText((state.analysis.days.groundMeters[index] + state.analysis.days.airMeters[index]).toDouble()), places),
                        style = AtlasTheme.type.label,
                        color = colors.textFaint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
