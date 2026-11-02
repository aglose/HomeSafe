package com.meticulouscreations.homesafe.weather.ski.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.ski.SkiRegionLoad
import com.meticulouscreations.homesafe.weather.ski.SkiUiState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiCost
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDayView
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOpenState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlanner
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiStripDay
import com.meticulouscreations.homesafe.weather.ui.CardLabel
import com.meticulouscreations.homesafe.weather.ui.PillButton
import com.meticulouscreations.homesafe.weather.ui.WeatherCard
import com.meticulouscreations.homesafe.weather.ui.WeatherCardShape
import com.meticulouscreations.homesafe.weather.ui.WeatherTheme
import com.meticulouscreations.homesafe.weather.ui.cardWidth
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_attribution
import homesafe.shared.generated.resources.ski_best_bet_title
import homesafe.shared.generated.resources.ski_chains_on_road
import homesafe.shared.generated.resources.ski_cost_covered
import homesafe.shared.generated.resources.ski_cost_label
import homesafe.shared.generated.resources.ski_cost_label_from
import homesafe.shared.generated.resources.ski_cost_label_listed
import homesafe.shared.generated.resources.ski_day_prompt
import homesafe.shared.generated.resources.ski_day_prompt_trip
import homesafe.shared.generated.resources.ski_drive_label
import homesafe.shared.generated.resources.ski_empty_body
import homesafe.shared.generated.resources.ski_empty_title
import homesafe.shared.generated.resources.ski_find_button
import homesafe.shared.generated.resources.ski_fresh_label
import homesafe.shared.generated.resources.ski_loading
import homesafe.shared.generated.resources.ski_new_label
import homesafe.shared.generated.resources.ski_no_price
import homesafe.shared.generated.resources.ski_no_start
import homesafe.shared.generated.resources.ski_none_open_body
import homesafe.shared.generated.resources.ski_none_open_first
import homesafe.shared.generated.resources.ski_none_open_title
import homesafe.shared.generated.resources.ski_not_open_that_day
import homesafe.shared.generated.resources.ski_placeholder
import homesafe.shared.generated.resources.ski_region_open_count
import homesafe.shared.generated.resources.ski_region_top_snow
import homesafe.shared.generated.resources.ski_regions_title
import homesafe.shared.generated.resources.ski_resorts_all_title
import homesafe.shared.generated.resources.ski_resorts_title
import homesafe.shared.generated.resources.ski_sites_trouble
import homesafe.shared.generated.resources.ski_ticket_kids_left_out
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How many days ahead the strip of days goes: as far as the forecast has any skill. */
internal const val SKI_DAYS_AHEAD = 10

/**
 * The Ski tab: pick a day, and see the followed resorts compared for it. The one that looks best
 * comes first with why; below it the rest, each with the snow, the drive and the cost at a glance;
 * then the regions being kept an eye on. A tap opens a resort's whole page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkiScreen(
    state: SkiUiState,
    actions: SkiActions,
    padding: PaddingValues,
    onOpenResort: (SkiResortRef) -> Unit,
    onOpenRegion: (String) -> Unit,
    onOpenFind: () -> Unit,
    onDim: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val followed = state.followed
    val views = remember(followed, state.plan, state.tripEpochDay, state.todayEpochDay, state.nowEpochSeconds, units) {
        SkiPlanner.ranked(followed.map { SkiPlanner.view(it, state.plan, state.tripEpochDay, state.todayEpochDay, state.nowEpochSeconds, units) })
    }
    val strip = remember(followed, state.todayEpochDay) { SkiPlanner.dayStrip(followed, state.todayEpochDay, SKI_DAYS_AHEAD) }
    val best = views.firstOrNull()?.takeIf { it.runs }
    val list = rememberLazyListState()
    val dimmed by rememberUpdatedState(onDim)
    LaunchedEffect(list) {
        snapshotFlow { if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / 300f).coerceIn(0f, 1f) }.collect { dimmed(it) }
    }
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        state = pull,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = state.refreshing,
                containerColor = colors.surfaceRaised,
                color = colors.accent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("ski_screen"),
            state = list,
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (followed.isEmpty()) {
                item(key = "empty") { SkiEmpty(Modifier.cardWidth()) }
            } else {
                item(key = "days", contentType = "days") { DaysCard(strip, state.tripEpochDay, state.plan.tripDays, actions.onPickDay, Modifier.cardWidth()) }
                if (!state.hasStart) {
                    item(key = "no_start") { Text(stringResource(Res.string.ski_no_start), style = WeatherTheme.type.label, color = colors.onSkyMuted, modifier = Modifier.cardWidth().padding(horizontal = 8.dp)) }
                }
                // Until a report is in, nobody can say what's open.
                val heard = followed.all { it.latest != null }
                item(key = "best", contentType = "best") {
                    when {
                        best != null -> BestBet(best, strip, state.tripEpochDay, state.nowEpochSeconds, onOpenResort, Modifier.cardWidth())

                        heard -> NoneOpen(views.firstOrNull(), state.tripEpochDay, Modifier.cardWidth())

                        else -> WeatherCard(Modifier.cardWidth().testTag("ski_waiting")) {
                            val error = followed.firstNotNullOfOrNull { it.error }
                            Text(error?.resolve() ?: stringResource(Res.string.ski_loading), style = WeatherTheme.type.body, color = if (error != null) colors.watch else colors.onSkyMuted)
                        }
                    }
                }
                val rest = if (best != null) views.drop(1) else views
                if (rest.isNotEmpty()) {
                    item(key = "rest_label") {
                        CardLabel(stringResource(if (best != null) Res.string.ski_resorts_title else Res.string.ski_resorts_all_title), Modifier.cardWidth().padding(start = 8.dp, top = 4.dp))
                    }
                }
                items(rest, key = { it.entry.ref.slug }, contentType = { "resort" }) { view ->
                    // With nothing open that day the card above has said so, for all of them.
                    ResortCard(view, strip, state.tripEpochDay, state.todayEpochDay, state.nowEpochSeconds, onOpenResort, Modifier.cardWidth(), sayIfShut = best != null)
                }
            }
            if (state.plan.regions.isNotEmpty()) {
                item(key = "regions", contentType = "regions") {
                    RegionsCard(state.plan.regions, state.regions, state.nowEpochSeconds, onOpenRegion, Modifier.cardWidth())
                }
            }
            if (state.siteTrouble.isNotEmpty()) {
                item(key = "site_trouble") {
                    Text(
                        pluralStringResource(Res.plurals.ski_sites_trouble, state.siteTrouble.size, state.siteTrouble.size),
                        style = WeatherTheme.type.label,
                        color = colors.watch,
                        modifier = Modifier.cardWidth().padding(horizontal = 8.dp).testTag("ski_site_trouble"),
                    )
                }
            }
            item(key = "find") {
                PillButton(stringResource(Res.string.ski_find_button), onOpenFind, Modifier.padding(top = 4.dp).testTag("ski_find"), icon = Icons.Filled.Search, filled = false)
            }
            item(key = "footer") {
                Text(stringResource(Res.string.ski_attribution), style = WeatherTheme.type.label, color = colors.onSkyFaint, modifier = Modifier.cardWidth().padding(horizontal = 8.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun DaysCard(strip: List<SkiStripDay>, selected: Int, tripDays: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    WeatherCard(modifier, title = stringResource(if (tripDays > 1) Res.string.ski_day_prompt_trip else Res.string.ski_day_prompt), icon = Icons.Filled.CalendarMonth, padding = 0.dp) {
        SkiDayStrip(strip, selected, onPick, Modifier.padding(bottom = 8.dp), tripDays = tripDays)
    }
}

/** The resort that looks best for the day, larger than the rest, with the reasons. */
@Composable
private fun BestBet(view: SkiDayView, strip: List<SkiStripDay>, selected: Int, now: Long, onOpen: (SkiResortRef) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(Res.string.ski_best_bet_title, stringResource(WeatherFormat.weekdayLong(selected.asInstant(), 0)))
    WeatherCard(modifier.clip(WeatherCardShape).clickable(role = Role.Button) { onOpen(view.entry.ref) }.handCursor().testTag("ski_best_bet"), title = title, icon = Icons.Filled.Star) {
        Text(view.entry.ref.name, style = WeatherTheme.type.title, color = WeatherTheme.colors.onSky, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        Notes(view)
        Spacer(Modifier.height(10.dp))
        Stats(view, now)
        Spacer(Modifier.height(12.dp))
        SkiSnowBars(view.entry, strip, selected, tripDays = view.trip.days)
        ChainsLine(view)
    }
}

/** Out of season, or a day before anyone's open: say so, and who opens first. */
@Composable
private fun NoneOpen(first: SkiDayView?, selected: Int, modifier: Modifier = Modifier) {
    WeatherCard(modifier.testTag("ski_none_open")) {
        Text(stringResource(Res.string.ski_none_open_title, stringResource(WeatherFormat.weekdayLong(selected.asInstant(), 0))), style = WeatherTheme.type.bodyStrong, color = WeatherTheme.colors.onSky)
        Spacer(Modifier.height(4.dp))
        val report = first?.entry?.latest
        val opens = report?.openingEpochDay
        val body = if (report != null && opens != null && opens > selected) {
            stringResource(Res.string.ski_none_open_first, report.name, WeatherFormat.date(opens.asInstant(), 0).resolve())
        } else {
            stringResource(Res.string.ski_none_open_body)
        }
        Text(body, style = WeatherTheme.type.body, color = WeatherTheme.colors.onSkyMuted)
    }
}

@Composable
private fun ResortCard(view: SkiDayView, strip: List<SkiStripDay>, selected: Int, today: Int, now: Long, onOpen: (SkiResortRef) -> Unit, modifier: Modifier = Modifier, sayIfShut: Boolean = true) {
    val report = view.entry.latest
    WeatherCard(modifier.clip(WeatherCardShape).clickable(role = Role.Button) { onOpen(view.entry.ref) }.handCursor().testTag("ski_resort_${view.entry.ref.slug}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.entry.ref.name, style = WeatherTheme.type.headline, color = WeatherTheme.colors.onSky, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (report != null) {
                Spacer(Modifier.width(8.dp))
                SkiPill(report.stateText(today, now).resolve(), report.stateColor(now))
            }
        }
        if (sayIfShut && !view.runs && report != null) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.ski_not_open_that_day, stringResource(WeatherFormat.weekdayLong(selected.asInstant(), 0))), style = WeatherTheme.type.label, color = WeatherTheme.colors.onSkyFaint)
        }
        if (view.notes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Notes(view)
        }
        Spacer(Modifier.height(10.dp))
        Stats(view, now)
        Spacer(Modifier.height(10.dp))
        SkiSnowBars(view.entry, strip, selected, tripDays = view.trip.days)
        ChainsLine(view)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Notes(view: SkiDayView) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        view.notes.forEach { note -> SkiPill(note.text.resolve(), note.tone.color()) }
    }
}

/** New snow, what the day has by then, the drive, the cost: the four numbers a trip turns on. */
@Composable
private fun Stats(view: SkiDayView, now: Long) {
    val units = WeatherTheme.units
    val report = view.entry.latest
    val placeholder = stringResource(Res.string.ski_placeholder)
    val cost = view.cost
    val tickets = view.trip.tickets
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkiStat(stringResource(Res.string.ski_new_label), report?.takeIf { it.isCurrent(now) }?.last24Cm?.let { WeatherFormat.snow(it, units).resolve() } ?: placeholder, Modifier.weight(1f))
            SkiStat(stringResource(Res.string.ski_fresh_label), WeatherFormat.snow(view.freshCm, units).resolve(), Modifier.weight(1f))
            SkiStat(stringResource(Res.string.ski_drive_label), view.entry.drive?.let { driveText(it.seconds).resolve() } ?: placeholder, Modifier.weight(1.1f))
            val money = when {
                cost.coveredBy != null -> stringResource(Res.string.ski_cost_covered)
                tickets != null -> SkiPlanner.money(tickets, view.entry.price?.currency ?: "US$")
                else -> stringResource(Res.string.ski_no_price)
            }
            val costLabel = when {
                cost.startsAt -> Res.string.ski_cost_label_from
                cost.source == SkiCost.Source.LISTING -> Res.string.ski_cost_label_listed
                else -> Res.string.ski_cost_label
            }
            SkiStat(stringResource(costLabel), money, Modifier.weight(0.9f), color = if (cost.coveredBy != null) WeatherTheme.colors.good else WeatherTheme.colors.onSky)
        }
        if (cost.childrenLeftOut && cost.coveredBy == null && cost.total != null) {
            Text(stringResource(Res.string.ski_ticket_kids_left_out), style = WeatherTheme.type.label, color = WeatherTheme.colors.watch, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A chain control on the road there is the one thing about a drive that can't wait for the resort's page. */
@Composable
private fun ChainsLine(view: SkiDayView) {
    val worst = view.entry.chains.maxByOrNull { it.level.ordinal } ?: return
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = WeatherTheme.colors.watch, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(Res.string.ski_chains_on_road, worst.levelText(), worst.route),
            style = WeatherTheme.type.label,
            color = WeatherTheme.colors.watch,
        )
    }
}

/** The regions being watched: how many resorts are open in each, and who's got the most new snow. */
@Composable
private fun RegionsCard(regions: List<String>, loads: Map<String, SkiRegionLoad>, now: Long, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    WeatherCard(modifier.testTag("ski_regions"), title = stringResource(Res.string.ski_regions_title), icon = Icons.Filled.Map, padding = 0.dp) {
        regions.forEach { slug ->
            val region = SkiRegions.of(slug)
            val load = loads[slug]
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(slug) }.handCursor().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(region?.let { stringResource(it.name) } ?: slug, style = WeatherTheme.type.bodyStrong, color = colors.onSky, maxLines = 1)
                    val reports = load?.reports.orEmpty().map { report -> load?.news?.get(report.slug)?.let(report::merged) ?: report }
                    if (reports.isNotEmpty()) {
                        val open = reports.count { it.runningNow(now) }
                        val top = reports.filter { it.isCurrent(now) && (it.last24Cm ?: 0.0) >= 0.5 }.maxByOrNull { it.last24Cm ?: 0.0 }
                        Text(
                            if (top != null) {
                                stringResource(Res.string.ski_region_top_snow, open, reports.size, top.name, WeatherFormat.snow(top.last24Cm ?: 0.0, units).resolve())
                            } else {
                                stringResource(Res.string.ski_region_open_count, open, reports.size)
                            },
                            style = WeatherTheme.type.label,
                            color = colors.onSkyMuted,
                            maxLines = 2,
                        )
                    }
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSkyFaint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SkiEmpty(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 14.dp, vertical = 40.dp).testTag("ski_empty")) {
        Text(stringResource(Res.string.ski_empty_title), style = WeatherTheme.type.title, color = WeatherTheme.colors.onSky)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.ski_empty_body), style = WeatherTheme.type.headline, color = WeatherTheme.colors.onSkyMuted)
    }
}
