package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.domain.PlaceKind
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.atlas.domain.VisitRole
import com.meticulouscreations.homesafe.atlas.domain.YearsCovered
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.ui.radar.MAP_TILE_DP
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_map_all_years
import homesafe.shared.generated.resources.atlas_map_everywhere
import homesafe.shared.generated.resources.atlas_map_home
import homesafe.shared.generated.resources.atlas_map_pause
import homesafe.shared.generated.resources.atlas_map_play
import homesafe.shared.generated.resources.atlas_map_reading_paths
import homesafe.shared.generated.resources.atlas_map_stat_places
import homesafe.shared.generated.resources.atlas_map_stat_regions
import homesafe.shared.generated.resources.atlas_map_stat_travelled
import homesafe.shared.generated.resources.atlas_map_zoom_in
import homesafe.shared.generated.resources.atlas_map_zoom_out
import homesafe.shared.generated.resources.atlas_place_home
import homesafe.shared.generated.resources.atlas_place_work
import homesafe.shared.generated.resources.weather_radar_credit_map
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** How many places the map draws: the ones most time was spent at. More would be a rash, not a map. */
private const val MAP_PLACES = 60
private const val YEAR_STEP_MS = 1100L

/**
 * The atlas itself: the whole map, glowing where life has gone. The years along the foot, newest
 * first, pick how much of it to show: all of it, one year alone, or any few together, each tap
 * adding a year or taking it away. The play button walks through them from the first, so the
 * footprint grows year on year.
 */
@Composable
internal fun MapScreen(
    state: AtlasUiState,
    tiles: MapTileSource,
    padding: PaddingValues,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    map: AtlasMapState = remember { AtlasMapState() },
) {
    val colors = AtlasTheme.colors
    val analysis = state.analysis
    val history = state.history
    val scope = rememberCoroutineScope()
    val tilePx = with(LocalDensity.current) { MAP_TILE_DP.dp.toPx() }
    val years = remember(analysis) { analysis.years.map { it.year } }
    // The years picked; none is all of them.
    var picked by rememberSaveable(stateSaver = PickedYearsSaver) { mutableStateOf(emptySet<Int>()) }
    var playing by remember { mutableStateOf(false) }

    val home = analysis.eras.lastOrNull()?.home
    // Opens on home, wide enough to take in the town round it. Once: coming back from another tab finds the map where it was left.
    LaunchedEffect(home?.key) {
        if (home != null && map.openedOn != home.key) {
            map.openedOn = home.key
            map.show(MapBounds(home.latitude - 0.22, home.longitude - 0.3, home.latitude + 0.22, home.longitude + 0.3))
        }
    }
    LaunchedEffect(playing, years) {
        if (!playing) return@LaunchedEffect
        years.forEach { through ->
            picked = years.filterTo(HashSet()) { it <= through }
            delay(YEAR_STEP_MS)
        }
        picked = emptySet()
        playing = false
    }

    val shownYears = picked.ifEmpty { years.toSet() }
    val covered = remember(history, picked) { picked.takeIf { it.isNotEmpty() }?.let { YearsCovered.of(history, it) } }
    val homeLabel = stringResource(Res.string.atlas_place_home)
    val workLabel = stringResource(Res.string.atlas_place_work)
    val markers = remember(analysis, covered, homeLabel, workLabel) {
        val shown = analysis.places.asSequence().filter { covered == null || it.place.key in covered.placeKeys }.take(MAP_PLACES).toList()
        val most = shown.firstOrNull()?.seconds?.coerceAtLeast(1) ?: 1
        shown.mapIndexed { index, stat ->
            val place = stat.place
            MapMarker(
                key = place.key,
                latitude = place.latitude,
                longitude = place.longitude,
                role = when (place.kind) {
                    PlaceKind.HOME -> VisitRole.HOME
                    PlaceKind.WORK -> VisitRole.WORK
                    PlaceKind.OTHER -> VisitRole.OTHER
                },
                // By the square root: home is a thousand times the café and shouldn't be drawn a thousand times its size.
                weight = kotlin.math.sqrt(stat.seconds.toFloat() / most),
                // Names for the few that matter most; the rest are a tap away.
                label = when {
                    place.kind == PlaceKind.HOME -> homeLabel
                    place.kind == PlaceKind.WORK -> place.name ?: workLabel
                    index < 8 -> place.name
                    else -> null
                },
            )
        }
    }
    val flights = remember(history, picked) {
        history.moves.filter { it.mode == TravelMode.FLY && (picked.isEmpty() || CivilDate.of(it.startDay).year in picked) }.takeLast(400)
    }
    val paths = state.paths
    val yearBits = remember(paths, picked) { if (picked.isEmpty()) -1 else paths?.density?.yearsIn(picked) ?: -1 }

    Box(modifier.fillMaxSize().testTag("atlas_map")) {
        AtlasMap(
            state = map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            paths = paths,
            years = yearBits,
            markers = markers,
            flights = flights,
            animated = active,
            onTapMarker = { onOpenPlace(it.key) },
        )

        // What the map adds up to, in the years shown.
        Row(
            Modifier
                .padding(top = padding.calculateTopPadding() + 4.dp, start = AtlasGutter, end = AtlasGutter)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.background.copy(alpha = 0.78f))
                .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            val placeCount = covered?.placeKeys?.size ?: analysis.years.sumOf { it.newPlaces }
            val groundMeters = analysis.years.filter { it.year in shownYears }.sumOf { it.groundMeters }
            AtlasStat(AtlasFormat.grouped(placeCount.toLong()), stringResource(Res.string.atlas_map_stat_places), tint = colors.place)
            AtlasStat(distanceText(groundMeters), stringResource(Res.string.atlas_map_stat_travelled), tint = colors.trail)
            AtlasStat((covered?.regions ?: analysis.regions.size).toString(), stringResource(Res.string.atlas_map_stat_regions))
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = AtlasGutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val fill = colors.background.copy(alpha = 0.78f)
            if (home != null) {
                AtlasRoundButton(Icons.Filled.Home, stringResource(Res.string.atlas_map_home), {
                    map.show(MapBounds(home.latitude - 0.22, home.longitude - 0.3, home.latitude + 0.22, home.longitude + 0.3), glide = true)
                }, Modifier.testTag("atlas_map_home"), fill = fill)
            }
            AtlasRoundButton(Icons.Filled.Public, stringResource(Res.string.atlas_map_everywhere), {
                MapBounds.of(history.places.values.map { it.latitude to it.longitude })?.let { map.show(it, glide = true) }
            }, Modifier.testTag("atlas_map_everywhere"), fill = fill)
            AtlasRoundButton(Icons.Filled.Add, stringResource(Res.string.atlas_map_zoom_in), { map.zoomBy(scope, 2f, 0f, 0f, tilePx) }, Modifier.testTag("atlas_map_zoom_in"), fill = fill)
            AtlasRoundButton(Icons.Filled.Remove, stringResource(Res.string.atlas_map_zoom_out), { map.zoomBy(scope, 0.5f, 0f, 0f, tilePx) }, Modifier.testTag("atlas_map_zoom_out"), fill = fill)
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = padding.calculateBottomPadding())) {
            if (paths == null && !history.isEmpty) {
                Text(
                    stringResource(Res.string.atlas_map_reading_paths),
                    style = AtlasTheme.type.label,
                    color = colors.textMuted,
                    modifier = Modifier.testTag("atlas_map_reading_paths").padding(start = AtlasGutter, bottom = 8.dp).clip(CircleShape).background(colors.background.copy(alpha = 0.78f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                stringResource(Res.string.weather_radar_credit_map),
                style = AtlasTheme.type.micro.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
                color = colors.textFaint,
                modifier = Modifier.padding(start = AtlasGutter, bottom = 6.dp),
            )
            if (years.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = AtlasGutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AtlasRoundButton(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (playing) Res.string.atlas_map_pause else Res.string.atlas_map_play),
                        { playing = !playing },
                        Modifier.testTag("atlas_map_play"),
                        size = 40.dp,
                        tint = colors.background,
                        fill = colors.trail,
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier.clip(CircleShape).background(colors.background.copy(alpha = 0.82f)).border(1.dp, colors.hairline, CircleShape).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        YearPill(stringResource(Res.string.atlas_map_all_years), picked.isEmpty()) {
                            playing = false
                            picked = emptySet()
                        }
                        years.asReversed().forEach { each ->
                            YearPill(each.toString(), each in picked) {
                                playing = false
                                // Every year picked one by one is the same as all of them.
                                picked = (if (each in picked) picked - each else picked + each).takeIf { it.size < years.size }.orEmpty()
                            }
                        }
                    }
                }
            }
        }
    }
}

private val PickedYearsSaver = Saver<Set<Int>, IntArray>(save = { it.toIntArray() }, restore = { it.toSet() })

@Composable
private fun YearPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AtlasTheme.colors
    Text(
        label,
        style = AtlasTheme.type.label,
        color = if (selected) colors.background else colors.textMuted,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.trail else androidx.compose.ui.graphics.Color.Transparent)
            .toggleable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
