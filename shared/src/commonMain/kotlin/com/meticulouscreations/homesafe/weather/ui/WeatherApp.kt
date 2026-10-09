package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import com.meticulouscreations.homesafe.weather.PlaceWeather
import com.meticulouscreations.homesafe.weather.RadarLoad
import com.meticulouscreations.homesafe.weather.WeatherUiState
import com.meticulouscreations.homesafe.weather.WeatherViewModel
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherStory
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import com.meticulouscreations.homesafe.weather.ui.radar.RadarScreen
import com.meticulouscreations.homesafe.weather.ui.shader.WeatherShaderWarmUp
import com.meticulouscreations.homesafe.weather.ui.sky.SkyFreeze
import com.meticulouscreations.homesafe.weather.ui.sky.SkyScene
import com.meticulouscreations.homesafe.weather.ui.sky.WeatherSky
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.weather_close
import homesafe.shared.generated.resources.weather_open_places
import homesafe.shared.generated.resources.weather_open_settings
import homesafe.shared.generated.resources.weather_place_current
import homesafe.shared.generated.resources.weather_place_position
import homesafe.shared.generated.resources.weather_places_use_location
import homesafe.shared.generated.resources.weather_tab_forecast
import homesafe.shared.generated.resources.weather_tab_radar
import homesafe.shared.generated.resources.weather_tab_today
import homesafe.shared.generated.resources.weather_title_alert
import homesafe.shared.generated.resources.weather_title_places
import homesafe.shared.generated.resources.weather_title_settings
import homesafe.shared.generated.resources.weather_welcome_add
import homesafe.shared.generated.resources.weather_welcome_body
import homesafe.shared.generated.resources.weather_welcome_title
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The weather app's three tabs, in nav order. */
enum class WeatherTab(val label: StringResource, val icon: ImageVector) {
    TODAY(Res.string.weather_tab_today, Icons.Filled.WbSunny),
    FORECAST(Res.string.weather_tab_forecast, Icons.Filled.CalendarMonth),
    RADAR(Res.string.weather_tab_radar, Icons.Filled.Radar),
}

/** A page pushed over the tabs. */
@Immutable
internal sealed interface WeatherPage {
    data object Places : WeatherPage

    data object Settings : WeatherPage

    /** A government warning in full, by its id. */
    data class Alert(val id: String) : WeatherPage
}

/** Everything the weather screens can ask for, as one thing to hand down: the view model's side of the app. */
@Stable
internal class WeatherActions(
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onAdd: (Place) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onMove: (String, Boolean) -> Unit = { _, _ -> },
    val onSearch: (String) -> Unit = {},
    val onUseLocation: () -> Unit = {},
    val onUnitsChange: (WeatherUnits) -> Unit = {},
    val onNoticesChange: (WeatherNoticeSettings) -> Unit = {},
    val onStillSkyChange: (Boolean) -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onRadarVisible: (Boolean) -> Unit = {},
)

/**
 * The weather app: an app of its own inside PercySafe, opened from the drawer, as Finance is.
 * Its background is the sky over the place in view, drawn live by shaders; on it, Today (now,
 * the next hours, the details), Forecast (the ten days) and Radar, with a swipe between places
 * and Places and Settings pushed over the top. Back pops a page, then leaves the app ([onClose]).
 */
@Composable
fun WeatherApp(onClose: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true) {
    // The activity's instance, the one the drawer's card shares.
    val viewModel: WeatherViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val actions = remember(viewModel) {
        WeatherActions(
            onClose = { close() },
            onRefresh = viewModel::refresh,
            onSelect = viewModel::select,
            onAdd = viewModel::addPlace,
            onRemove = viewModel::removePlace,
            onMove = viewModel::movePlace,
            onSearch = { query -> if (query.isEmpty()) viewModel.clearSearch() else viewModel.search(query) },
            onUseLocation = viewModel::requestLocation,
            onUnitsChange = viewModel::setUnits,
            onNoticesChange = viewModel::setNotices,
            onStillSkyChange = viewModel::setStillSky,
            onAllowNotifications = viewModel::requestNotificationPermission,
            onRadarVisible = viewModel::setRadarVisible,
        )
    }
    WeatherAppContent(state, viewModel.tiles, actions, modifier, active)
}

/** [WeatherApp] without its view model: everything on screen from one [state], for previews and tests as much as for the app. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun WeatherAppContent(
    state: WeatherUiState,
    tiles: MapTileSource,
    actions: WeatherActions,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    initialTab: WeatherTab = WeatherTab.TODAY,
    initialPage: WeatherPage? = null,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val pages = remember { mutableStateListOf<WeatherPage>().apply { initialPage?.let(::add) } }
    // The hour a finger is holding on the hourly strip: the whole sky turns to it.
    var scrub by remember { mutableStateOf<Long?>(null) }
    val dim = remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current

    fun pop() {
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else actions.onClose()
    }
    // Off while the app animates closed ([active] false), so that Back reaches what's beneath.
    BackHandler(enabled = active) { pop() }

    val selected = state.selected
    val report = selected?.report
    val scrubbed = remember(scrub, report) { scrub?.let { at -> report?.hourly?.firstOrNull { it.epochSeconds == at } } }
    val scene = remember(selected?.place, report?.current, scrubbed, state.nowEpochSeconds / 120) {
        val latitude = selected?.place?.latitude ?: 40.0
        val longitude = selected?.place?.longitude ?: -100.0
        when {
            scrubbed != null -> SkyScene.of(scrubbed, latitude, longitude)
            report != null -> SkyScene.of(report.current, latitude, longitude, state.nowEpochSeconds)
            else -> SkyScene.of(WeatherKind.MOSTLY_CLEAR, null, 8.0, 270, null, latitude, longitude, state.nowEpochSeconds)
        }
    }
    val onSky = pages.isEmpty() && tab != WeatherTab.RADAR
    // Today or Forecast, whichever the pager of places was last showing.
    var lastPlaceTab by rememberSaveable { mutableStateOf(if (initialTab == WeatherTab.FORECAST) WeatherTab.FORECAST else WeatherTab.TODAY) }
    LaunchedEffect(tab) { if (tab != WeatherTab.RADAR) lastPlaceTab = tab }
    LaunchedEffect(active, pages.isEmpty(), tab, selected?.place?.id) {
        // The radar loop is wanted by the Radar tab and by Today's small map of it.
        actions.onRadarVisible(active && pages.isEmpty() && tab != WeatherTab.FORECAST && selected != null)
    }
    // Gone from the screen by any road (a tab switch can take the overlay away without closing it): nobody wants the loop.
    DisposableEffect(actions) { onDispose { actions.onRadarVisible(false) } }

    val palette = remember { WeatherPalette() }
    // Remembered: a new instance through the static local would recompose the whole tree on every state change.
    val fontFamily = albertSansFontFamily()
    val type = remember(fontFamily) { WeatherTypography(fontFamily) }
    CompositionLocalProvider(LocalWeatherPalette provides palette, LocalWeatherTypography provides type, LocalWeatherUnits provides state.units) {
        val status = WindowInsets.statusBars.asPaddingValues()
        val nav = WindowInsets.navigationBars.asPaddingValues()
        val sideNav = isCompactLandscape()
        val top = status.calculateTopPadding() + 58.dp
        val padding = PaddingValues(
            start = if (sideNav) SIDE_NAV_CLEARANCE else 0.dp,
            top = top,
            bottom = nav.calculateBottomPadding() + if (sideNav) 28.dp else 104.dp,
        )
        val pagePadding = PaddingValues(top = top + 6.dp, bottom = nav.calculateBottomPadding() + 28.dp)

        Box(modifier.fillMaxSize().background(Color(0xFF05080E)).testTag("weather_app")) {
            // Under the sky, which covers it: drawn, and never seen.
            if (active) WeatherShaderWarmUp()
            WeatherSky(
                scene = scene,
                modifier = Modifier.fillMaxSize(),
                running = active && onSky,
                freeze = if (state.preferences.stillSky) SkyFreeze() else null,
            )
            // A little shade always, so white lettering reads on the brightest sky; more as the cards scroll up over it.
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = 0.1f + dim.floatValue * 0.5f) })

            val target: Any = pages.lastOrNull() ?: tab
            AnimatedContent(
                targetState = target,
                transitionSpec = { weatherTransition(initialState, targetState) },
                label = "weatherPage",
                // Today and Forecast are one pager of places, which stays where it is as they swap inside it.
                contentKey = { if (it == WeatherTab.TODAY || it == WeatherTab.FORECAST) "places" else it },
            ) { shown ->
                when (shown) {
                    WeatherTab.TODAY, WeatherTab.FORECAST -> PlacePager(
                        state = state,
                        // Its own tab while it is on its way out to the radar, not whatever came next.
                        tab = if (tab == WeatherTab.RADAR) lastPlaceTab else tab,
                        scrubbedEpochSeconds = scrub,
                        tiles = tiles,
                        padding = padding,
                        actions = actions,
                        onScrub = { scrub = it },
                        onDim = { dim.floatValue = it },
                        onOpenAlert = { pages += WeatherPage.Alert(it) },
                        onOpenRadar = { tab = WeatherTab.RADAR },
                        onOpenPlaces = { pages += WeatherPage.Places },
                        animated = active && !state.preferences.stillSky,
                    )

                    WeatherTab.RADAR -> RadarScreen(
                        place = selected?.place,
                        radar = state.radar,
                        tiles = tiles,
                        utcOffsetSeconds = report?.offsetAt(state.nowEpochSeconds) ?: localUtcOffsetSeconds(state.nowEpochSeconds.toDouble()),
                        nowEpochSeconds = state.nowEpochSeconds,
                        headline = remember(report, state.nowEpochSeconds) {
                            report?.let { WeatherStory.nearTermPhrase(WeatherStory.nearTerm(it, state.nowEpochSeconds), state.nowEpochSeconds) }
                        },
                        padding = PaddingValues(start = if (sideNav) SIDE_NAV_CLEARANCE else 0.dp, top = top, bottom = nav.calculateBottomPadding() + if (sideNav) 12.dp else 92.dp),
                        animated = active && !state.preferences.stillSky,
                    )

                    WeatherPage.Places -> PlacesScreen(
                        state = state,
                        padding = pagePadding,
                        onQueryChange = actions.onSearch,
                        onAdd = { place ->
                            actions.onAdd(place)
                            actions.onSearch("")
                            pages.clear()
                        },
                        onSelect = { id ->
                            actions.onSelect(id)
                            pages.clear()
                        },
                        onRemove = actions.onRemove,
                        onMove = actions.onMove,
                        onUseLocation = actions.onUseLocation,
                    )

                    WeatherPage.Settings -> WeatherSettingsScreen(
                        state = state,
                        padding = pagePadding,
                        onUnitsChange = actions.onUnitsChange,
                        onNoticesChange = actions.onNoticesChange,
                        onStillSkyChange = actions.onStillSkyChange,
                        onAllowNotifications = actions.onAllowNotifications,
                    )

                    is WeatherPage.Alert -> {
                        val alert = report?.alerts?.firstOrNull { it.id == shown.id }
                        if (alert != null) {
                            AlertScreen(alert, report::offsetAt, pagePadding)
                        } else {
                            // The warning lapsed while its page was open.
                            Box(Modifier.fillMaxSize().background(palette.surface))
                        }
                    }
                }
            }

            WeatherTopBar(
                state = state,
                page = pages.lastOrNull(),
                onSky = onSky,
                onBack = ::pop,
                onOpenPlaces = { if (pages.lastOrNull() != WeatherPage.Places) pages += WeatherPage.Places },
                onOpenSettings = { if (pages.lastOrNull() != WeatherPage.Settings) pages += WeatherPage.Settings },
            )

            val onPickTab: (WeatherTab) -> Unit = { picked ->
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                scrub = null
                tab = picked
            }
            // With nowhere to forecast for there is nothing behind the tabs to go to.
            val navigable = pages.isEmpty() && state.places.isNotEmpty()
            AnimatedVisibility(
                visible = navigable && !sideNav,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            ) {
                WeatherNav(tab, onPickTab, vertical = false)
            }
            AnimatedVisibility(
                visible = navigable && sideNav,
                modifier = Modifier.align(Alignment.CenterStart).padding(top = status.calculateTopPadding() + 48.dp),
                enter = fadeIn(tween(220)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it },
                exit = fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it },
            ) {
                WeatherNav(tab, onPickTab, vertical = true)
            }
        }
    }
}

/**
 * The places, side by side, to swipe between: each a page showing Today or the Forecast for it,
 * whichever tab is up. The app's selected place and the page in view are kept the same: a swipe
 * selects, and a selection made elsewhere (the Places page) turns the pager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacePager(
    state: WeatherUiState,
    tab: WeatherTab,
    scrubbedEpochSeconds: Long?,
    tiles: MapTileSource,
    padding: PaddingValues,
    actions: WeatherActions,
    onScrub: (Long?) -> Unit,
    onDim: (Float) -> Unit,
    onOpenAlert: (String) -> Unit,
    onOpenRadar: () -> Unit,
    onOpenPlaces: () -> Unit,
    animated: Boolean,
    modifier: Modifier = Modifier,
) {
    if (state.places.isEmpty()) {
        Welcome(state, padding, actions.onUseLocation, onOpenPlaces, modifier)
        return
    }
    val colors = WeatherTheme.colors
    val places by rememberUpdatedState(state.places)
    val selectedId by rememberUpdatedState(state.selected?.place?.id)
    val pagerState = rememberPagerState(initialPage = state.selectedIndex) { state.places.size }
    LaunchedEffect(state.selectedIndex, state.places.size) {
        if (state.selectedIndex != pagerState.currentPage && state.selectedIndex < pagerState.pageCount) pagerState.animateScrollToPage(state.selectedIndex)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            places.getOrNull(page)?.place?.id?.let { id -> if (id != selectedId) actions.onSelect(id) }
        }
    }
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        state = pullState,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = state.refreshing,
                containerColor = colors.surfaceRaised,
                color = colors.accent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
            )
        },
    ) {
        HorizontalPager(pagerState, Modifier.fillMaxSize(), key = { state.places.getOrNull(it)?.place?.id ?: it }, beyondViewportPageCount = 0) { page ->
            val entry = state.places.getOrNull(page) ?: return@HorizontalPager
            PlacePage(
                entry = entry,
                current = page == pagerState.currentPage,
                tab = tab,
                state = state,
                scrubbedEpochSeconds = scrubbedEpochSeconds.takeIf { page == pagerState.currentPage },
                tiles = tiles,
                padding = padding,
                onScrub = onScrub,
                onDim = onDim,
                onOpenAlert = onOpenAlert,
                onOpenRadar = onOpenRadar,
                onRetry = actions.onRefresh,
                animated = animated,
            )
        }
    }
}

/** One place's page: Today and Forecast, faded one into the other as the tab changes, each keeping its own scroll. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlacePage(
    entry: PlaceWeather,
    current: Boolean,
    tab: WeatherTab,
    state: WeatherUiState,
    scrubbedEpochSeconds: Long?,
    tiles: MapTileSource,
    padding: PaddingValues,
    onScrub: (Long?) -> Unit,
    onDim: (Float) -> Unit,
    onOpenAlert: (String) -> Unit,
    onOpenRadar: () -> Unit,
    onRetry: () -> Unit,
    animated: Boolean,
) {
    val todayList = rememberLazyListState(cacheWindow = PageCacheWindow)
    val forecastList = rememberLazyListState(cacheWindow = PageCacheWindow)
    val list = if (tab == WeatherTab.FORECAST) forecastList else todayList
    val dimmed by rememberUpdatedState(onDim)
    // How far the cards have come up over the sky, for the page in view: the sky dims behind them.
    LaunchedEffect(list, current) {
        if (!current) return@LaunchedEffect
        snapshotFlow { if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / 520f).coerceIn(0f, 1f) }.collect { dimmed(it) }
    }
    val scrubbed = remember(scrubbedEpochSeconds, entry.report) {
        scrubbedEpochSeconds?.let { at -> entry.report?.hourly?.firstOrNull { it.epochSeconds == at } }
    }
    // Both tabs stay composed, the one not on show as a layer drawn at nothing: a change of tab is
    // then a fade between two things already there, where composing the other from nothing (ten
    // days of rows, or twenty-six hours of columns) took the frame it was asked for and three more.
    // The first of them is put off until the one on show has been up a moment and is at rest.
    val forecastUp = tab == WeatherTab.FORECAST
    var both by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(OTHER_TAB_AFTER_MS)
        snapshotFlow { todayList.isScrollInProgress || forecastList.isScrollInProgress }.first { !it }
        both = true
    }
    val fade = animateFloatAsState(if (forecastUp) 1f else 0f, tween(300), label = "placeTab")
    // The tab behind follows the forecast and the clock only while the lists are at rest, so a
    // refresh or the minute's tick landing in the middle of a scroll recomposes the tab on show,
    // as it always did, and not both of them in the one frame.
    val live = TabInputs(entry, state.nowEpochSeconds)
    var rested by remember { mutableStateOf(live) }
    LaunchedEffect(live) {
        snapshotFlow { todayList.isScrollInProgress || forecastList.isScrollInProgress }.first { !it }
        rested = live
    }
    val today = if (forecastUp) rested else live
    val forecast = if (forecastUp) live else rested
    Box(Modifier.fillMaxSize()) {
        if (!forecastUp || both) {
            Box(Modifier.tabLayer(shown = !forecastUp) { 1f - fade.value }) {
                TodayScreen(
                    entry = today.entry,
                    nowEpochSeconds = today.nowEpochSeconds,
                    scrubbed = scrubbed,
                    radar = state.radar.takeIf { current } ?: RadarLoad(),
                    tiles = tiles,
                    listState = todayList,
                    padding = padding,
                    onScrub = onScrub,
                    onOpenAlert = onOpenAlert,
                    onOpenRadar = onOpenRadar,
                    onRetry = onRetry,
                    // Nothing in a tab that isn't on show is worth a frame.
                    animated = animated && !forecastUp,
                )
            }
        }
        if (forecastUp || both) {
            Box(Modifier.tabLayer(shown = forecastUp) { fade.value }) {
                ForecastScreen(forecast.entry, forecast.nowEpochSeconds, forecastList, padding, onRetry)
            }
        }
    }
}

/**
 * One of a place's two tabs as a layer: drawn at [alpha], which is read only when it is drawn,
 * and while it isn't the one [shown], under the other (so no finger reaches it) and unsaid to
 * screen readers and tests.
 */
private fun Modifier.tabLayer(shown: Boolean, alpha: () -> Float): Modifier =
    zIndex(if (shown) 1f else 0f)
        .graphicsLayer { this.alpha = alpha() }
        .then(if (shown) Modifier else Modifier.clearAndSetSemantics { })

/** What a place's tab is drawn from: the place's forecast, and the time. */
@Immutable
private data class TabInputs(val entry: PlaceWeather, val nowEpochSeconds: Long)

/** How long a place's page has been up before its other tab is composed behind it. */
private const val OTHER_TAB_AFTER_MS = 700L

/**
 * How much of a place's page is kept composed beyond what is on screen: two screens' worth either
 * way, which is all of it. A page is a dozen cards and no more, and some of them are a great deal
 * to compose (the details are nine tiles in one item, the hourly strip twenty-six columns): with
 * the list's own window such a card can arrive under a dragging finger half built, and the rest
 * of it is fifty milliseconds in one frame. Kept, it is built once, ahead of the scroll and in
 * the time between frames.
 */
@OptIn(ExperimentalFoundationApi::class)
private val PageCacheWindow = LazyLayoutCacheWindow(aheadFraction = 2f, behindFraction = 2f)

/** Nowhere to forecast for yet: say what the app does, and offer the two ways to give it somewhere. */
@Composable
private fun Welcome(state: WeatherUiState, padding: PaddingValues, onUseLocation: () -> Unit, onOpenPlaces: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    Column(
        modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp).testTag("weather_welcome"),
        verticalArrangement = Arrangement.Center,
    ) {
        // Until the first look for places is over, an empty list isn't yet "none".
        if (!state.settled) return@Column
        Text(stringResource(Res.string.weather_welcome_title), style = WeatherTheme.type.title.copy(fontSize = WeatherTheme.type.heroSmall.fontSize, lineHeight = WeatherTheme.type.heroSmall.lineHeight), color = colors.onSky)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(Res.string.weather_welcome_body), style = WeatherTheme.type.headline, color = colors.onSkyMuted)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.locationSupported) PillButton(stringResource(Res.string.weather_places_use_location), onUseLocation, Modifier.testTag("weather_welcome_locate"), icon = Icons.Filled.MyLocation)
            PillButton(stringResource(Res.string.weather_welcome_add), onOpenPlaces, Modifier.testTag("weather_welcome_add"), icon = Icons.Filled.Search, filled = !state.locationSupported)
        }
    }
}

/**
 * Pages pushed over the tabs slide in from the right and back out; the tabs fade through one
 * another, since what moves between them is the sky's own business.
 */
private fun weatherTransition(from: Any, to: Any): ContentTransform {
    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
    return when {
        to is WeatherPage ->
            (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))

        from is WeatherPage ->
            (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))

        else -> fadeIn(tween(260)) togetherWith fadeOut(tween(200))
    }
}

/**
 * The bar across the top. Over the sky it is the place in view (its name, a mark when it's where
 * the phone is, and a dot for each place to show which of them this is), with Places and
 * Settings beside it; over a pushed page it is that page's title and a way back.
 */
@Composable
private fun WeatherTopBar(
    state: WeatherUiState,
    page: WeatherPage?,
    onSky: Boolean,
    onBack: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                when {
                    page != null -> Modifier.background(Brush.verticalGradient(0f to colors.surface, 0.8f to colors.surface.copy(alpha = 0.94f), 1f to Color.Transparent))
                    onSky -> Modifier.background(Brush.verticalGradient(listOf(Color(0x4D000000), Color.Transparent)))
                    else -> Modifier
                },
            )
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(
            if (page != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
            stringResource(if (page != null) Res.string.common_back else Res.string.weather_close),
            onBack,
            Modifier.testTag("weather_back"),
        )
        if (page != null) {
            Text(
                when (page) {
                    WeatherPage.Places -> stringResource(Res.string.weather_title_places)
                    WeatherPage.Settings -> stringResource(Res.string.weather_title_settings)
                    is WeatherPage.Alert -> stringResource(Res.string.weather_title_alert)
                },
                style = type.bodyStrong,
                color = colors.onSky,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            return@Row
        }
        val selected = state.selected
        val name = selected?.place?.let { it.name.ifBlank { stringResource(Res.string.weather_place_current) } }.orEmpty()
        val position = if (state.places.size > 1) stringResource(Res.string.weather_place_position, name, state.selectedIndex + 1, state.places.size) else ""
        val openPlaces = stringResource(Res.string.weather_open_places)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClickLabel = openPlaces, onClick = onOpenPlaces)
                .semantics(mergeDescendants = true) { if (position.isNotEmpty()) contentDescription = position }
                .testTag("weather_place_title")
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected?.place?.isCurrentLocation == true) {
                    Icon(Icons.Filled.NearMe, contentDescription = null, tint = colors.onSky, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(name, style = type.title, color = colors.onSky, maxLines = 1)
            }
            if (state.places.size > 1) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.places.forEachIndexed { i, _ ->
                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == state.selectedIndex) colors.onSky else colors.onSkyFaint))
                    }
                }
            } else if (selected?.place?.region?.isNotBlank() == true) {
                Text(selected.place.region, style = type.label, color = colors.onSkyMuted, maxLines = 1)
            }
        }
        BarButton(Icons.AutoMirrored.Filled.FormatListBulleted, openPlaces, onOpenPlaces, Modifier.testTag("weather_places_button"))
        BarButton(Icons.Filled.Tune, stringResource(Res.string.weather_open_settings), onOpenSettings, Modifier.testTag("weather_settings_button"))
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = WeatherTheme.colors.onSky, modifier = Modifier.size(22.dp))
    }
}

/** The tabs as a pane of dark glass: a pill along the foot, or stood on end at the side of a phone on its side. */
@Composable
private fun WeatherNav(selected: WeatherTab, onSelect: (WeatherTab) -> Unit, vertical: Boolean) {
    val colors = WeatherTheme.colors
    if (vertical) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier.padding(start = SIDE_NAV_MARGIN).navigationBarsPadding().width(SIDE_NAV_WIDTH).clip(shape).background(colors.cardSolid).border(1.dp, colors.cardBorder, shape).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WeatherTab.entries.forEach { NavItem(it, it == selected, onSelect, Modifier.fillMaxWidth(), horizontalPadding = 4.dp) }
        }
    } else {
        Row(
            Modifier.navigationBarsPadding().padding(bottom = 16.dp).widthIn(max = 380.dp).fillMaxWidth(0.86f).clip(CircleShape).background(colors.cardSolid).border(1.dp, colors.cardBorder, CircleShape).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WeatherTab.entries.forEach { NavItem(it, it == selected, onSelect) }
        }
    }
}

@Composable
private fun NavItem(tab: WeatherTab, isSelected: Boolean, onSelect: (WeatherTab) -> Unit, modifier: Modifier = Modifier, horizontalPadding: Dp = 18.dp) {
    val colors = WeatherTheme.colors
    Column(
        modifier
            .clip(CircleShape)
            .background(if (isSelected) Color(0x29FFFFFF) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
            .semantics { selected = isSelected }
            .testTag("weather_tab_${tab.name.lowercase()}")
            .padding(horizontal = horizontalPadding, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = null, tint = if (isSelected) colors.onSky else colors.onSkyFaint, modifier = Modifier.size(22.dp))
        Text(stringResource(tab.label), style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing), color = if (isSelected) colors.onSky else colors.onSkyFaint, maxLines = 1)
    }
}

private val SIDE_NAV_MARGIN = 12.dp
private val SIDE_NAV_WIDTH = 76.dp

/** How far the tabs' pages start from the edge while the nav is at the side. */
private val SIDE_NAV_CLEARANCE = SIDE_NAV_MARGIN + SIDE_NAV_WIDTH
