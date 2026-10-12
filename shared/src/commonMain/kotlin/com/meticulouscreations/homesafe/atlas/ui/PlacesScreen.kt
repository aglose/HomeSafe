package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.AtlasAnalyzer
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.domain.PlaceKind
import com.meticulouscreations.homesafe.atlas.domain.PlaceStat
import com.meticulouscreations.homesafe.atlas.domain.VisitRole
import com.meticulouscreations.homesafe.atlas.domain.localMinute
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_day_span
import homesafe.shared.generated.resources.atlas_day_visit_detail
import homesafe.shared.generated.resources.atlas_place_gap
import homesafe.shared.generated.resources.atlas_place_recent_title
import homesafe.shared.generated.resources.atlas_place_rhythm_description
import homesafe.shared.generated.resources.atlas_place_rhythm_title
import homesafe.shared.generated.resources.atlas_place_stat_arrive
import homesafe.shared.generated.resources.atlas_place_stat_first
import homesafe.shared.generated.resources.atlas_place_stat_gap
import homesafe.shared.generated.resources.atlas_place_stat_last
import homesafe.shared.generated.resources.atlas_place_stat_time
import homesafe.shared.generated.resources.atlas_place_stat_visits
import homesafe.shared.generated.resources.atlas_place_years_title
import homesafe.shared.generated.resources.atlas_places_none
import homesafe.shared.generated.resources.atlas_places_row_detail
import homesafe.shared.generated.resources.atlas_places_row_last
import homesafe.shared.generated.resources.atlas_places_row_where
import homesafe.shared.generated.resources.atlas_places_search
import homesafe.shared.generated.resources.atlas_places_sort_missed
import homesafe.shared.generated.resources.atlas_places_sort_recent
import homesafe.shared.generated.resources.atlas_places_sort_time
import homesafe.shared.generated.resources.atlas_places_sort_visits
import homesafe.shared.generated.resources.atlas_places_visits
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How the list of places is ordered. */
internal enum class PlaceSort(val label: StringResource) {
    TIME(Res.string.atlas_places_sort_time),
    VISITS(Res.string.atlas_places_sort_visits),
    RECENT(Res.string.atlas_places_sort_recent),

    /** Places gone to more than a few times, longest unvisited first. */
    MISSED(Res.string.atlas_places_sort_missed),
}

/**
 * Every place there is a stay on record at, by the time spent there, the visits made, how
 * lately, or (of those gone to more than a handful of times) how long it has been. The field at
 * the top narrows the list by name, town or address.
 */
@Composable
internal fun PlacesScreen(state: AtlasUiState, padding: PaddingValues, onOpenPlace: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    var sort by rememberSaveable { mutableStateOf(PlaceSort.TIME) }
    var query by rememberSaveable { mutableStateOf("") }
    val places = state.analysis.places
    val shown = remember(places, sort, query) {
        val wanted = query.trim().lowercase()
        val matching = if (wanted.isEmpty()) {
            places
        } else {
            places.filter { stat -> listOfNotNull(stat.place.name, stat.place.city, stat.place.address, stat.place.region).any { wanted in it.lowercase() } }
        }
        when (sort) {
            PlaceSort.TIME -> matching
            PlaceSort.VISITS -> matching.sortedByDescending { it.visits }
            PlaceSort.RECENT -> matching.sortedByDescending { it.lastDay }
            PlaceSort.MISSED -> matching.filter { it.visits >= 5 && it.place.kind == PlaceKind.OTHER }.sortedBy { it.lastDay }
        }
    }
    val most = remember(shown) { shown.maxOfOrNull { it.seconds }?.coerceAtLeast(1) ?: 1 }

    LazyColumn(modifier.fillMaxSize().testTag("atlas_places"), contentPadding = padding) {
        item(key = "search") {
            Column(Modifier.padding(horizontal = AtlasGutter)) {
                Row(
                    Modifier.fillMaxWidth().clip(CircleShape).background(colors.surfaceRaised).border(1.dp, colors.hairline, CircleShape).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(stringResource(Res.string.atlas_places_search), style = AtlasTheme.type.body, color = colors.textFaint)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = AtlasTheme.type.body.copy(color = colors.text),
                            cursorBrush = SolidColor(colors.trail),
                            modifier = Modifier.fillMaxWidth().testTag("atlas_places_search"),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                AtlasChips(PlaceSort.entries.map { it to stringResource(it.label) }, sort, { sort = it })
                Spacer(Modifier.height(8.dp))
            }
        }
        if (shown.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(Res.string.atlas_places_none),
                    style = AtlasTheme.type.body,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        itemsIndexed(shown, key = { _, stat -> stat.place.key }) { index, stat ->
            PlaceRow(index + 1, stat, state, most, sort) { onOpenPlace(stat.place.key) }
        }
    }
}

@Composable
private fun PlaceRow(rank: Int, stat: PlaceStat, state: AtlasUiState, most: Long, sort: PlaceSort, onClick: () -> Unit) {
    val colors = AtlasTheme.colors
    val tint = when (stat.place.kind) {
        PlaceKind.HOME -> colors.home
        PlaceKind.WORK -> colors.work
        PlaceKind.OTHER -> colors.place
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("atlas_place_row_${stat.place.key}").padding(horizontal = AtlasGutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString(), style = AtlasTheme.type.label, color = colors.textFaint, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(placeTitle(stat.place, state.history), style = AtlasTheme.type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = localityText(state.history.locality(stat.place))
            val counts = stringResource(Res.string.atlas_places_row_detail, pluralStringResource(Res.plurals.atlas_places_visits, stat.visits, AtlasFormat.grouped(stat.visits.toLong())), durationText(stat.seconds))
            Text(
                // Lately and not-lately are about when; the others about how much.
                when {
                    sort == PlaceSort.RECENT || sort == PlaceSort.MISSED -> stringResource(Res.string.atlas_places_row_last, agoText(state.today - stat.lastDay), where ?: counts)
                    where != null -> stringResource(Res.string.atlas_places_row_where, where, counts)
                    else -> counts
                },
                style = AtlasTheme.type.label,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            // How its time stands against the place most time was spent at, by the square root so the long tail shows.
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(colors.surfaceRaised)) {
                Box(Modifier.fillMaxWidth(kotlin.math.sqrt(stat.seconds.toFloat() / most).coerceIn(0.01f, 1f)).height(3.dp).clip(CircleShape).background(tint))
            }
        }
    }
}

/**
 * One place: where it is, how much it has been part of life, when in the week it is gone to, how
 * that has changed year on year, and the last visits made, each opening its day.
 */
@Composable
internal fun PlaceScreen(
    state: AtlasUiState,
    tiles: MapTileSource,
    placeKey: String,
    padding: PaddingValues,
    onOpenDay: (Int) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val colors = AtlasTheme.colors
    val history = state.history
    val stat = remember(state.analysis, placeKey) { state.analysis.places.firstOrNull { it.place.key == placeKey } }
    if (stat == null) {
        Box(modifier.fillMaxSize())
        return
    }
    val place = stat.place
    val role = when (place.kind) {
        PlaceKind.HOME -> VisitRole.HOME
        PlaceKind.WORK -> VisitRole.WORK
        PlaceKind.OTHER -> VisitRole.OTHER
    }
    val map = remember(placeKey) { AtlasMapState() }
    LaunchedEffect(placeKey) { map.show(MapBounds(place.latitude - 0.006, place.longitude - 0.008, place.latitude + 0.006, place.longitude + 0.008)) }
    val visits = remember(history, placeKey) { history.visits.filter { it.placeKey == placeKey } }
    val rhythm = remember(history, placeKey) { AtlasAnalyzer.rhythm(history, placeKey) }
    val byYear = remember(visits) { visits.groupingBy { CivilDate.of(it.startDay).year }.eachCount().toList().sortedBy { it.first } }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).testTag("atlas_place"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.padding(horizontal = AtlasGutter + 4.dp)) {
            Text(placeTitle(place, history), style = AtlasTheme.type.hero, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (place.address ?: localityText(history.locality(place)))?.let { Text(it, style = AtlasTheme.type.body, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Box(Modifier.padding(horizontal = AtlasGutter).fillMaxWidth().height(200.dp).clip(AtlasCardShape).border(1.dp, colors.hairline, AtlasCardShape)) {
            AtlasMap(map, tiles, Modifier.fillMaxSize(), paths = state.paths, markers = listOf(MapMarker(place.key, place.latitude, place.longitude, role, 0.6f)), animated = active)
        }
        AtlasCard(Modifier.padding(horizontal = AtlasGutter)) {
            Row(Modifier.fillMaxWidth()) {
                AtlasStat(AtlasFormat.grouped(stat.visits.toLong()), stringResource(Res.string.atlas_place_stat_visits), Modifier.weight(1f), tint = colors.role(role))
                AtlasStat(durationText(stat.seconds), stringResource(Res.string.atlas_place_stat_time), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                AtlasStat(dateText(stat.firstDay), stringResource(Res.string.atlas_place_stat_first), Modifier.weight(1f))
                AtlasStat(agoText(state.today - stat.lastDay), stringResource(Res.string.atlas_place_stat_last), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                AtlasStat(clockText(stat.typicalArrivalMinute), stringResource(Res.string.atlas_place_stat_arrive), Modifier.weight(1f))
                if (stat.typicalGapDays > 0) {
                    AtlasStat(pluralStringResource(Res.plurals.atlas_place_gap, stat.typicalGapDays, stat.typicalGapDays), stringResource(Res.string.atlas_place_stat_gap), Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        AtlasCard(Modifier.padding(horizontal = AtlasGutter), title = stringResource(Res.string.atlas_place_rhythm_title)) {
            WeekGrid(stringResource(Res.string.atlas_place_rhythm_description)) { weekday, hour -> colors.heat(rhythm[weekday * 24 + hour]) }
        }
        if (byYear.size > 1) {
            AtlasCard(Modifier.padding(horizontal = AtlasGutter), title = stringResource(Res.string.atlas_place_years_title)) {
                val most = byYear.maxOf { it.second }
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    byYear.forEach { (year, count) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(count.toString(), style = AtlasTheme.type.micro, color = colors.textMuted, maxLines = 1)
                            Box(Modifier.fillMaxWidth().height((56 * count.toFloat() / most).coerceAtLeast(2f).dp).clip(RoundedCornerShape(6.dp)).background(colors.role(role)))
                            Text((year % 100).toString().padStart(2, '0'), style = AtlasTheme.type.micro, color = colors.textFaint, maxLines = 1)
                        }
                    }
                }
            }
        }
        AtlasCard(Modifier.padding(horizontal = AtlasGutter), title = stringResource(Res.string.atlas_place_recent_title)) {
            visits.takeLast(25).asReversed().forEach { visit ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenDay(visit.startDay) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateText(visit.startDay, weekday = true), style = AtlasTheme.type.body, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        stringResource(
                            Res.string.atlas_day_visit_detail,
                            stringResource(Res.string.atlas_day_span, clockText(localMinute(visit.start, visit.offsetMinutes)), clockText(localMinute(visit.end, visit.offsetMinutes))),
                            durationText(visit.seconds),
                        ),
                        style = AtlasTheme.type.label,
                        color = colors.textFaint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A week as a grid: a row a day from Monday, a column an hour from midnight, each square the
 * colour [cell] gives it. What it shows is said by [description], for a reader that can't see it.
 */
@Composable
internal fun WeekGrid(description: String, modifier: Modifier = Modifier, cell: (weekday: Int, hour: Int) -> androidx.compose.ui.graphics.Color) {
    val colors = AtlasTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val style = AtlasTheme.type.micro.copy(color = colors.textFaint)
    val days = (0..6).map { stringResource(AtlasFormat.weekday(it)).take(1) }
    val hours = listOf(0, 6, 12, 18).map { hourText(it) }
    Canvas(modifier.fillMaxWidth().aspectRatio(2.6f).semantics { contentDescription = description }) {
        val left = 16.dp.toPx()
        val bottom = 16.dp.toPx()
        val width = (size.width - left) / 24f
        val height = (size.height - bottom) / 7f
        val gap = 1.5.dp.toPx()
        for (weekday in 0..6) {
            drawText(measurer, days[weekday], Offset(0f, weekday * height + height / 2 - 6.dp.toPx()), style)
            for (hour in 0..23) {
                drawRoundRect(cell(weekday, hour), Offset(left + hour * width + gap / 2, weekday * height + gap / 2), Size(width - gap, height - gap), CornerRadius(3.dp.toPx()))
            }
        }
        hours.forEachIndexed { i, label -> drawText(measurer, label, Offset(left + i * 6 * width, size.height - bottom + 3.dp.toPx()), style) }
    }
}
