package com.meticulouscreations.homesafe.weather.ski.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.WithTooltip
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.ski.SkiRegionLoad
import com.meticulouscreations.homesafe.weather.ski.SkiUiState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOrigin
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPass
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlan
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlanner
import com.meticulouscreations.homesafe.weather.ski.domain.SkiRegion
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import com.meticulouscreations.homesafe.weather.ui.PillButton
import com.meticulouscreations.homesafe.weather.ui.WeatherCard
import com.meticulouscreations.homesafe.weather.ui.WeatherCardShape
import com.meticulouscreations.homesafe.weather.ui.WeatherTheme
import com.meticulouscreations.homesafe.weather.ui.cardWidth
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_find_group_near
import homesafe.shared.generated.resources.ski_find_group_north_america
import homesafe.shared.generated.resources.ski_find_group_passes
import homesafe.shared.generated.resources.ski_follow_resort
import homesafe.shared.generated.resources.ski_following_resort
import homesafe.shared.generated.resources.ski_loading
import homesafe.shared.generated.resources.ski_pass_epic
import homesafe.shared.generated.resources.ski_pass_ikon
import homesafe.shared.generated.resources.ski_pass_indy
import homesafe.shared.generated.resources.ski_pass_mountain_collective
import homesafe.shared.generated.resources.ski_pass_powder_alliance
import homesafe.shared.generated.resources.ski_region_alaska
import homesafe.shared.generated.resources.ski_region_alberta
import homesafe.shared.generated.resources.ski_region_arizona
import homesafe.shared.generated.resources.ski_region_british_columbia
import homesafe.shared.generated.resources.ski_region_california
import homesafe.shared.generated.resources.ski_region_colorado
import homesafe.shared.generated.resources.ski_region_follow
import homesafe.shared.generated.resources.ski_region_following
import homesafe.shared.generated.resources.ski_region_idaho
import homesafe.shared.generated.resources.ski_region_lake_tahoe
import homesafe.shared.generated.resources.ski_region_maine
import homesafe.shared.generated.resources.ski_region_montana
import homesafe.shared.generated.resources.ski_region_nevada
import homesafe.shared.generated.resources.ski_region_new_hampshire
import homesafe.shared.generated.resources.ski_region_new_mexico
import homesafe.shared.generated.resources.ski_region_new_york
import homesafe.shared.generated.resources.ski_region_northern_california
import homesafe.shared.generated.resources.ski_region_oregon
import homesafe.shared.generated.resources.ski_region_row_coming
import homesafe.shared.generated.resources.ski_region_row_outlook
import homesafe.shared.generated.resources.ski_region_row_snow
import homesafe.shared.generated.resources.ski_region_sierra_nevada
import homesafe.shared.generated.resources.ski_region_southern_california
import homesafe.shared.generated.resources.ski_region_utah
import homesafe.shared.generated.resources.ski_region_vermont
import homesafe.shared.generated.resources.ski_region_washington
import homesafe.shared.generated.resources.ski_region_wyoming
import homesafe.shared.generated.resources.ski_setup_adults
import homesafe.shared.generated.resources.ski_setup_children
import homesafe.shared.generated.resources.ski_setup_count
import homesafe.shared.generated.resources.ski_setup_days
import homesafe.shared.generated.resources.ski_setup_decrease
import homesafe.shared.generated.resources.ski_setup_driving
import homesafe.shared.generated.resources.ski_setup_driving_hint
import homesafe.shared.generated.resources.ski_setup_from_city
import homesafe.shared.generated.resources.ski_setup_from_here
import homesafe.shared.generated.resources.ski_setup_from_home
import homesafe.shared.generated.resources.ski_setup_from_title
import homesafe.shared.generated.resources.ski_setup_increase
import homesafe.shared.generated.resources.ski_setup_lodging
import homesafe.shared.generated.resources.ski_setup_party_title
import homesafe.shared.generated.resources.ski_setup_passes_body
import homesafe.shared.generated.resources.ski_setup_passes_title
import homesafe.shared.generated.resources.ski_setup_trip_title
import homesafe.shared.generated.resources.ski_sites_trouble_body
import homesafe.shared.generated.resources.ski_sites_trouble_row
import homesafe.shared.generated.resources.ski_sites_trouble_title
import org.jetbrains.compose.resources.stringResource

/** The parts of the world the app can look through, and each pass's list of resorts, which has the same shape. */
internal object SkiRegions {
    val near = listOf(
        SkiRegion("northern-california", Res.string.ski_region_northern_california),
        SkiRegion("lake-tahoe", Res.string.ski_region_lake_tahoe),
        SkiRegion("sierra-nevada", Res.string.ski_region_sierra_nevada),
        SkiRegion("california", Res.string.ski_region_california),
        SkiRegion("southern-california", Res.string.ski_region_southern_california),
        SkiRegion("nevada", Res.string.ski_region_nevada),
    )
    val northAmerica = listOf(
        SkiRegion("utah", Res.string.ski_region_utah),
        SkiRegion("colorado", Res.string.ski_region_colorado),
        SkiRegion("wyoming", Res.string.ski_region_wyoming),
        SkiRegion("montana", Res.string.ski_region_montana),
        SkiRegion("idaho", Res.string.ski_region_idaho),
        SkiRegion("oregon", Res.string.ski_region_oregon),
        SkiRegion("washington", Res.string.ski_region_washington),
        SkiRegion("new-mexico", Res.string.ski_region_new_mexico),
        SkiRegion("arizona", Res.string.ski_region_arizona),
        SkiRegion("alaska", Res.string.ski_region_alaska),
        SkiRegion("british-columbia", Res.string.ski_region_british_columbia),
        SkiRegion("alberta", Res.string.ski_region_alberta),
        SkiRegion("vermont", Res.string.ski_region_vermont),
        SkiRegion("new-hampshire", Res.string.ski_region_new_hampshire),
        SkiRegion("maine", Res.string.ski_region_maine),
        SkiRegion("new-york", Res.string.ski_region_new_york),
    )
    val passes = listOf(
        SkiRegion(SkiPass.EPIC.slug, Res.string.ski_pass_epic),
        SkiRegion(SkiPass.IKON.slug, Res.string.ski_pass_ikon),
        SkiRegion(SkiPass.INDY.slug, Res.string.ski_pass_indy),
        SkiRegion(SkiPass.MOUNTAIN_COLLECTIVE.slug, Res.string.ski_pass_mountain_collective),
        SkiRegion(SkiPass.POWDER_ALLIANCE.slug, Res.string.ski_pass_powder_alliance),
    )

    fun of(slug: String): SkiRegion? = (near + northAmerica + passes).firstOrNull { it.slug == slug }
}

/** Where to look for more resorts: the regions, near ones first, and the passes. */
@Composable
internal fun SkiFindScreen(plan: SkiPlan, padding: PaddingValues, onOpenRegion: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize().background(WeatherTheme.colors.surface).testTag("ski_find_page"),
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "near") { RegionGroup(stringResource(Res.string.ski_find_group_near), SkiRegions.near, plan, onOpenRegion, Modifier.cardWidth()) }
        item(key = "na") { RegionGroup(stringResource(Res.string.ski_find_group_north_america), SkiRegions.northAmerica, plan, onOpenRegion, Modifier.cardWidth()) }
        item(key = "passes") { RegionGroup(stringResource(Res.string.ski_find_group_passes), SkiRegions.passes, plan, onOpenRegion, Modifier.cardWidth()) }
    }
}

@Composable
private fun RegionGroup(title: String, regions: List<SkiRegion>, plan: SkiPlan, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    WeatherCard(modifier, title = title, padding = 0.dp) {
        regions.forEach { region ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(region.slug) }.handCursor().testTag("ski_region_${region.slug}").padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(region.name), style = WeatherTheme.type.body, color = colors.onSky, modifier = Modifier.weight(1f))
                if (region.slug in plan.regions) {
                    Icon(Icons.Filled.Star, contentDescription = stringResource(Res.string.ski_region_following), tint = colors.sun, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSkyFaint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Every resort in a region, the ones running first, then the snowiest. Good for looking across a
 * region without following any of it; a star adds a resort to the Ski tab, and a tap opens it.
 */
@Composable
internal fun SkiRegionScreen(
    slug: String,
    load: SkiRegionLoad?,
    state: SkiUiState,
    actions: SkiActions,
    padding: PaddingValues,
    onOpenResort: (SkiResortRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeatherTheme.colors
    LaunchedEffect(slug) { actions.onOpenRegion(slug) }
    val following = slug in state.plan.regions
    val now = state.nowEpochSeconds
    val reports = remember(load?.reports, load?.news, now) {
        load?.reports.orEmpty().map { report -> load?.news?.get(report.slug)?.let(report::merged) ?: report }.sortedWith(
            compareBy<SkiReport> { if (it.runningNow(now)) 0 else 1 }
                .thenByDescending { report -> report.outlook.sumOf { it.cm } + (report.last24Cm ?: 0.0) }
                .thenBy { it.name },
        )
    }
    LazyColumn(
        modifier.fillMaxSize().background(colors.surface).testTag("ski_region_page"),
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "follow") {
            Row(Modifier.cardWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.ski_region_row_outlook), style = WeatherTheme.type.label, color = colors.onSkyMuted, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                PillButton(
                    stringResource(if (following) Res.string.ski_region_following else Res.string.ski_region_follow),
                    { actions.onFollowRegion(slug, !following) },
                    Modifier.testTag("ski_region_follow"),
                    icon = if (following) Icons.Filled.Star else Icons.Filled.StarBorder,
                    filled = !following,
                )
            }
        }
        if (reports.isEmpty()) {
            item(key = "status") {
                val error = load?.error
                Text(error?.resolve() ?: stringResource(Res.string.ski_loading), style = WeatherTheme.type.body, color = if (error != null) colors.watch else colors.onSkyMuted, modifier = Modifier.cardWidth().padding(8.dp))
            }
        }
        items(reports, key = { it.slug }, contentType = { "row" }) { report ->
            RegionRow(report, load?.prices?.get(report.slug), state, actions, onOpenResort, Modifier.cardWidth())
        }
    }
}

@Composable
private fun RegionRow(report: SkiReport, listed: SkiTicketPrice?, state: SkiUiState, actions: SkiActions, onOpenResort: (SkiResortRef) -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val units = WeatherTheme.units
    val ref = report.ref
    val followed = state.plan.follows(report.slug)
    WeatherCard(modifier.clip(WeatherCardShape).clickable(role = Role.Button) { onOpenResort(ref) }.handCursor().testTag("ski_row_${report.slug}"), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(report.name, style = WeatherTheme.type.bodyStrong, color = colors.onSky, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val coming = report.outlook.filter { it.epochDay >= state.todayEpochDay }.sumOf { it.cm }
                Text(
                    (report.last24Cm ?: report.last72Cm)?.takeIf { report.isCurrent(state.nowEpochSeconds) }?.let { fresh ->
                        stringResource(Res.string.ski_region_row_snow, WeatherFormat.snow(fresh, units).resolve(), WeatherFormat.snow(coming, units).resolve())
                    } ?: stringResource(Res.string.ski_region_row_coming, WeatherFormat.snow(coming, units).resolve()),
                    style = WeatherTheme.type.label,
                    color = colors.onSkyMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkiPill(report.stateText(state.todayEpochDay, state.nowEpochSeconds).resolve(), report.stateColor(state.nowEpochSeconds))
                    listed?.adultDay?.let { Text(SkiPlanner.money(it, listed.currency), style = WeatherTheme.type.label, color = colors.onSkyMuted) }
                }
            }
            val label = stringResource(if (followed) Res.string.ski_following_resort else Res.string.ski_follow_resort, report.name)
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label) { actions.onFollow(ref, !followed) }.handCursor().testTag("ski_star_${report.slug}"),
                contentAlignment = Alignment.Center,
            ) {
                WithTooltip(label, Modifier.matchParentSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(if (followed) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = label, tint = if (followed) colors.sun else colors.onSkyFaint, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/** Who's going, from where, and on which passes: what turns the listed numbers into this household's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkiSetupScreen(plan: SkiPlan, actions: SkiActions, padding: PaddingValues, modifier: Modifier = Modifier, canLocate: Boolean = true, startCity: String? = null, siteTrouble: List<Pair<String, Long>> = emptyList()) {
    val colors = WeatherTheme.colors
    LazyColumn(
        modifier.fillMaxSize().background(colors.surface).testTag("ski_setup_page"),
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "party") {
            WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.ski_setup_party_title), icon = Icons.Filled.Group) {
                Stepper(stringResource(Res.string.ski_setup_adults), plan.adults, "ski_adults") { actions.onParty(it, plan.children) }
                Spacer(Modifier.height(10.dp))
                Stepper(stringResource(Res.string.ski_setup_children), plan.children, "ski_children") { actions.onParty(plan.adults, it) }
            }
        }
        item(key = "trip") {
            WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.ski_setup_trip_title), icon = Icons.Filled.CalendarMonth) {
                Stepper(stringResource(Res.string.ski_setup_days), plan.tripDays, "ski_trip_days") { actions.onTrip(it, plan.lodgingPerNight, plan.drivingCentsPerMile) }
                Spacer(Modifier.height(10.dp))
                SkiAmountField(stringResource(Res.string.ski_setup_lodging), plan.lodgingPerNight.takeIf { it > 0 }, "ski_lodging", { actions.onTrip(plan.tripDays, it ?: 0, plan.drivingCentsPerMile) })
                Spacer(Modifier.height(10.dp))
                SkiAmountField(stringResource(Res.string.ski_setup_driving), plan.drivingCentsPerMile, "ski_driving", { actions.onTrip(plan.tripDays, plan.lodgingPerNight, it ?: 0) }, hint = stringResource(Res.string.ski_setup_driving_hint))
            }
        }
        item(key = "passes") {
            WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.ski_setup_passes_title), icon = Icons.Filled.ConfirmationNumber) {
                Text(stringResource(Res.string.ski_setup_passes_body), style = WeatherTheme.type.label, color = colors.onSkyMuted)
                Spacer(Modifier.height(6.dp))
                SkiPass.entries.forEach { pass ->
                    val on = pass in plan.passes
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = { actions.onPass(pass, it) }).handCursor()
                            .testTag("ski_pass_${pass.name.lowercase()}").padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(pass.label(), style = WeatherTheme.type.body, color = colors.onSky, modifier = Modifier.weight(1f))
                        Switch(
                            checked = on,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                        )
                    }
                }
            }
        }
        if (siteTrouble.isNotEmpty()) {
            item(key = "site_trouble") {
                WeatherCard(Modifier.cardWidth().testTag("ski_site_trouble_card"), title = stringResource(Res.string.ski_sites_trouble_title), icon = Icons.Filled.Warning) {
                    Text(stringResource(Res.string.ski_sites_trouble_body), style = WeatherTheme.type.label, color = colors.onSkyMuted)
                    siteTrouble.forEach { (name, since) ->
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(Res.string.ski_sites_trouble_row, name, WeatherFormat.date(since, localUtcOffsetSeconds(since.toDouble())).resolve()), style = WeatherTheme.type.body, color = colors.onSky)
                    }
                }
            }
        }
        item(key = "from") {
            WeatherCard(Modifier.cardWidth(), title = stringResource(Res.string.ski_setup_from_title), icon = Icons.Filled.Home) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A device that can't tell where it is starts from home whatever was chosen elsewhere: nothing to choose.
                    OriginButton(stringResource(Res.string.ski_setup_from_home), Icons.Filled.Home, plan.origin == SkiOrigin.HOME || !canLocate, "ski_from_home") { actions.onOrigin(SkiOrigin.HOME) }
                    if (canLocate) OriginButton(stringResource(Res.string.ski_setup_from_here), Icons.Filled.MyLocation, plan.origin == SkiOrigin.HERE, "ski_from_here") { actions.onOrigin(SkiOrigin.HERE) }
                }
                startCity?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(Res.string.ski_setup_from_city, it), style = WeatherTheme.type.label, color = colors.onSkyMuted, modifier = Modifier.testTag("ski_from_city"))
                }
            }
        }
    }
}

@Composable
private fun OriginButton(label: String, icon: ImageVector, selected: Boolean, tag: String, onClick: () -> Unit) {
    PillButton(label, onClick, Modifier.testTag(tag), icon = icon, filled = selected)
}

@Composable
private fun Stepper(label: String, value: Int, tag: String, onChange: (Int) -> Unit) {
    val colors = WeatherTheme.colors
    val said = stringResource(Res.string.ski_setup_count, label, value)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = WeatherTheme.type.body, color = colors.onSky, modifier = Modifier.weight(1f))
        StepButton(Icons.Filled.Remove, stringResource(Res.string.ski_setup_decrease, label), "${tag}_less") { onChange(value - 1) }
        Text(value.toString(), style = WeatherTheme.type.bodyStrong, color = colors.onSky, modifier = Modifier.width(36.dp).semantics { contentDescription = said }, textAlign = TextAlign.Center)
        StepButton(Icons.Filled.Add, stringResource(Res.string.ski_setup_increase, label), "${tag}_more") { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val colors = WeatherTheme.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceRaised).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).handCursor().testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        WithTooltip(label, Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = colors.onSky, modifier = Modifier.size(20.dp))
            }
        }
    }
}
