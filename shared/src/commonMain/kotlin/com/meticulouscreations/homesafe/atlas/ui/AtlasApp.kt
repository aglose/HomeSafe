package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.AtlasViewModel
import com.meticulouscreations.homesafe.finance.FinanceLockViewModel
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.ui.rememberPredictiveBackTransition
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_close
import homesafe.shared.generated.resources.atlas_loading
import homesafe.shared.generated.resources.atlas_sync_open
import homesafe.shared.generated.resources.atlas_tab_days
import homesafe.shared.generated.resources.atlas_tab_map
import homesafe.shared.generated.resources.atlas_tab_patterns
import homesafe.shared.generated.resources.atlas_tab_places
import homesafe.shared.generated.resources.atlas_tab_trips
import homesafe.shared.generated.resources.atlas_title
import homesafe.shared.generated.resources.atlas_title_day
import homesafe.shared.generated.resources.atlas_title_place
import homesafe.shared.generated.resources.atlas_title_sync
import homesafe.shared.generated.resources.atlas_title_trip
import homesafe.shared.generated.resources.common_back
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The atlas app's five tabs, in nav order. */
enum class AtlasTab(val label: StringResource, val icon: ImageVector) {
    MAP(Res.string.atlas_tab_map, Icons.Filled.Public),
    DAYS(Res.string.atlas_tab_days, Icons.Filled.CalendarMonth),
    PLACES(Res.string.atlas_tab_places, Icons.Filled.Place),
    TRIPS(Res.string.atlas_tab_trips, Icons.Filled.Luggage),
    PATTERNS(Res.string.atlas_tab_patterns, Icons.Filled.Insights),
}

/** A page pushed over the tabs. */
@Immutable
internal sealed interface AtlasPage {
    /** One day, counted from 1970-01-01. */
    data class Day(val day: Int) : AtlasPage

    data class PlaceDetail(val placeKey: String) : AtlasPage

    /** A trip, by its place in the list (newest first). */
    data class TripDetail(val index: Int) : AtlasPage

    /** Where the history comes from, and how that is going. */
    data object Sync : AtlasPage
}

/** Everything the atlas screens can ask for, as one thing to hand down: the view model's side of the app. */
@Stable
internal class AtlasActions(
    val onClose: () -> Unit = {},
    val onCheckNow: () -> Unit = {},
)

/**
 * The atlas: an app of its own inside PercySafe, opened from the drawer, as Finance, Weather and
 * Fitness are. Everywhere its owner has been, from the Google Maps Timeline history the relay
 * keeps: the map of it all, any day replayed, every place and trip, and what the patterns in it
 * say. Back pops a page, then leaves the app ([onClose]).
 *
 * With no connection to the server it opens on what the device kept, behind the same lock as the
 * finance app's: where someone has been is no more for whoever picks the phone up than what they
 * own is.
 */
@Composable
fun AtlasApp(onClose: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true) {
    val lockViewModel: FinanceLockViewModel = metroViewModel()
    val lock by lockViewModel.uiState.collectAsStateWithLifecycle()
    // Once the app has gone from the screen, not as it starts to close: the lock must not flash up
    // through the overlay as it drains away.
    DisposableEffect(lockViewModel) { onDispose { lockViewModel.lock() } }
    val fontFamily = albertSansFontFamily()
    val palette = remember { AtlasPalette() }
    val type = remember(fontFamily) { AtlasTypography(fontFamily) }
    CompositionLocalProvider(LocalAtlasPalette provides palette, LocalAtlasTypography provides type) {
        if (lock.locked) {
            // The prompt comes up with the lock rather than waiting for a tap; put away, the button asks again.
            LaunchedEffect(lockViewModel) { lockViewModel.unlock() }
            Box(modifier.fillMaxSize()) {
                AtlasLockScreen(lock, onUnlock = lockViewModel::unlock)
                AtlasTopBar(stringResource(Res.string.atlas_title), paged = false, onBack = onClose, onOpenSync = null)
            }
        } else {
            // The activity's instance, the one the drawer's card shares.
            val viewModel: AtlasViewModel = metroViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val close by rememberUpdatedState(onClose)
            val actions = remember(viewModel) { AtlasActions(onClose = { close() }, onCheckNow = { viewModel.refresh(askDrive = true) }) }
            AtlasAppContent(state, viewModel.tiles, actions, modifier, active)
        }
    }
}

/**
 * [AtlasApp] without its view model: everything on screen from one [state], for previews and
 * tests as much as for the app.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AtlasAppContent(
    state: AtlasUiState,
    tiles: MapTileSource,
    actions: AtlasActions,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    initialTab: AtlasTab = AtlasTab.MAP,
    initialPage: AtlasPage? = null,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val pages = remember { mutableStateListOf<AtlasPage>().apply { initialPage?.let(::add) } }
    val haptics = LocalHapticFeedback.current

    fun pop() {
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else actions.onClose()
    }
    // Back with a page to give back is this stack's, and scrubs that page's pop under the finger;
    // with none it leaves the app, which the overlay around it drains predictively (InnerAppOverlay).
    // Off while the app animates closed ([active] false), so that Back reaches what's beneath.
    val back = rememberPredictiveBack(enabled = active && pages.isNotEmpty()) { pop() }

    val fontFamily = albertSansFontFamily()
    val palette = remember { AtlasPalette() }
    // Remembered: a new instance through the static local would recompose the whole tree on every state change.
    val type = remember(fontFamily) { AtlasTypography(fontFamily) }
    CompositionLocalProvider(LocalAtlasPalette provides palette, LocalAtlasTypography provides type) {
        val status = WindowInsets.statusBars.asPaddingValues()
        val nav = WindowInsets.navigationBars.asPaddingValues()
        val top = status.calculateTopPadding() + 62.dp
        val tabPadding = PaddingValues(top = top, bottom = nav.calculateBottomPadding() + 108.dp)
        val pagePadding = PaddingValues(top = top, bottom = nav.calculateBottomPadding() + 28.dp)
        val page = pages.lastOrNull()
        val showTabs = pages.isEmpty() && state.loaded && !state.empty

        Box(modifier.fillMaxSize().background(palette.background).testTag("atlas_app")) {
            // The map fills the screen itself; everything else has contours running under its head.
            if (page != null || tab != AtlasTab.MAP || !state.loaded || state.empty) {
                AtlasBackdrop(palette.trail, palette.air, Modifier.fillMaxWidth().height(380.dp), seed = 0.2f, energy = 0.45f, running = active)
                Box(Modifier.fillMaxWidth().height(380.dp).background(Brush.verticalGradient(0f to Color.Transparent, 1f to palette.background)))
            }

            when {
                !state.loaded -> Text(
                    stringResource(Res.string.atlas_loading),
                    style = AtlasTheme.type.body,
                    color = palette.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                state.empty && page == null -> AtlasEmpty(state, pagePadding, onSetUp = { pages += AtlasPage.Sync })

                else -> {
                    val target: Any = page ?: tab
                    // Which way the stack last moved, for the slide's direction; outside the snapshot system since it only steers an animation.
                    val depth = remember { intArrayOf(pages.size, 1) }
                    if (pages.size != depth[0]) {
                        depth[1] = if (pages.size > depth[0]) 1 else -1
                        depth[0] = pages.size
                    }
                    val previous: Any? = if (pages.isEmpty()) null else pages.getOrNull(pages.lastIndex - 1) ?: tab
                    // A back swipe scrubs the pop it would make, so it slides the way a pop does.
                    rememberPredictiveBackTransition(target, previous, back, label = "atlasPage").AnimatedContent(
                        transitionSpec = { atlasTransition(initialState, targetState, forward = depth[1] > 0 && !back.inProgress) },
                        // Stepping from one day to the next is the same page with another day on it, not a new page sliding in.
                        contentKey = { if (it is AtlasPage.Day) AtlasPage.Day::class else it },
                    ) { shown ->
                        val openPlace: (String) -> Unit = { pages += AtlasPage.PlaceDetail(it) }
                        val openDay: (Int) -> Unit = { pages += AtlasPage.Day(it) }
                        when (shown) {
                            AtlasTab.MAP -> MapScreen(state, tiles, tabPadding, openPlace, active = active)

                            AtlasTab.DAYS -> DaysScreen(state, tabPadding, openDay)

                            AtlasTab.PLACES -> PlacesScreen(state, tabPadding, openPlace)

                            AtlasTab.TRIPS -> TripsScreen(state, tabPadding, onOpenTrip = { pages += AtlasPage.TripDetail(it) }, onOpenPlace = openPlace)

                            AtlasTab.PATTERNS -> PatternsScreen(state, tabPadding, openPlace, openDay)

                            is AtlasPage.Day -> DayScreen(
                                state = state,
                                tiles = tiles,
                                day = shown.day,
                                padding = pagePadding,
                                // In place: Back from a week of stepping goes back to the calendar, not through the week.
                                onDay = { day -> if (day <= state.today) pages[pages.lastIndex] = AtlasPage.Day(day) },
                                onOpenPlace = openPlace,
                                active = active,
                            )

                            is AtlasPage.PlaceDetail -> PlaceScreen(state, tiles, shown.placeKey, pagePadding, openDay, active = active)

                            is AtlasPage.TripDetail -> state.analysis.trips.getOrNull(shown.index)?.let { trip ->
                                TripScreen(state, tiles, trip, pagePadding, openDay, openPlace, active = active)
                            } ?: Box(Modifier.fillMaxSize())

                            AtlasPage.Sync -> SyncScreen(state, pagePadding, actions.onCheckNow)

                            else -> Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }

            AtlasTopBar(
                title = when (page) {
                    null -> stringResource(Res.string.atlas_title)
                    is AtlasPage.Day -> stringResource(Res.string.atlas_title_day)
                    is AtlasPage.PlaceDetail -> stringResource(Res.string.atlas_title_place)
                    is AtlasPage.TripDetail -> stringResource(Res.string.atlas_title_trip)
                    AtlasPage.Sync -> stringResource(Res.string.atlas_title_sync)
                },
                paged = page != null,
                onBack = ::pop,
                onOpenSync = if (page == null && state.loaded) ({ pages += AtlasPage.Sync }) else null,
                // Something for its owner to do: a setup step missing on the box, or nothing fetched yet.
                syncNeedsAttention = state.sync.problem != null || state.sync.server?.driveProblem != null || state.sync.server?.driveConfigured == false,
                syncing = state.sync.syncing,
            )

            AnimatedVisibility(
                visible = showTabs,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            ) {
                AtlasNav(tab) { picked ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    tab = picked
                }
            }
        }
    }
}

/** Pages pushed over the tabs slide in from the right and back out the way they came; the tabs fade through one another. */
private fun atlasTransition(from: Any, to: Any, forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
    return when {
        from !is AtlasPage && to !is AtlasPage -> fadeIn(tween(260)) togetherWith fadeOut(tween(200))
        forward -> (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))
        else -> (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))
    }
}

/** The bar across the top: the way out (or back), where this is, and the way to where the history comes from. */
@Composable
private fun AtlasTopBar(title: String, paged: Boolean, onBack: () -> Unit, onOpenSync: (() -> Unit)?, syncNeedsAttention: Boolean = false, syncing: Boolean = false) {
    val colors = AtlasTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to colors.background.copy(alpha = 0.92f), 0.7f to colors.background.copy(alpha = 0.6f), 1f to Color.Transparent))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtlasRoundButton(
            if (paged) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
            stringResource(if (paged) Res.string.common_back else Res.string.atlas_close),
            onBack,
            Modifier.testTag("atlas_back"),
        )
        Text(title, style = AtlasTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (onOpenSync != null) {
            Box {
                AtlasRoundButton(Icons.Filled.CloudSync, stringResource(Res.string.atlas_sync_open), onOpenSync, Modifier.testTag("atlas_sync_open"), tint = if (syncing) colors.trail else colors.textMuted)
                if (syncNeedsAttention) Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(8.dp).clip(CircleShape).background(colors.trail))
            }
        }
    }
}

/** The tabs, as a dark pill along the foot. */
@Composable
private fun AtlasNav(selected: AtlasTab, onSelect: (AtlasTab) -> Unit) {
    val colors = AtlasTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth(0.94f)
            .clip(CircleShape)
            .background(colors.surfaceRaised.copy(alpha = 0.96f))
            .border(1.dp, colors.hairline, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtlasTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.trail.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                    .semantics { this.selected = isSelected }
                    .testTag("atlas_tab_${tab.name.lowercase()}")
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, contentDescription = null, tint = if (isSelected) colors.trail else colors.textFaint, modifier = Modifier.size(22.dp))
                Text(stringResource(tab.label), style = AtlasTheme.type.micro, color = if (isSelected) colors.text else colors.textFaint, maxLines = 1)
            }
        }
    }
}
