package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.ui.FinanceFormat
import com.meticulouscreations.homesafe.finance.ui.FinancePalette
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.ui.FitnessPalette
import com.meticulouscreations.homesafe.fitness.ui.shader.FiberField
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.WeatherUiState
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import com.meticulouscreations.homesafe.weather.domain.WeatherStory
import com.meticulouscreations.homesafe.weather.ui.sky.SkyFreeze
import com.meticulouscreations.homesafe.weather.ui.sky.SkyScene
import com.meticulouscreations.homesafe.weather.ui.sky.WeatherSky
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_drawer_empty
import homesafe.shared.generated.resources.fitness_drawer_open
import homesafe.shared.generated.resources.fitness_drawer_subtitle
import homesafe.shared.generated.resources.fitness_drawer_title
import homesafe.shared.generated.resources.fitness_drawer_week
import homesafe.shared.generated.resources.fitness_today_headline_due
import homesafe.shared.generated.resources.fitness_today_headline_working
import homesafe.shared.generated.resources.fitness_today_resume_detail
import homesafe.shared.generated.resources.shell_app_title
import homesafe.shared.generated.resources.shell_drawer_cameras
import homesafe.shared.generated.resources.shell_drawer_change_today
import homesafe.shared.generated.resources.shell_drawer_close
import homesafe.shared.generated.resources.shell_drawer_finance
import homesafe.shared.generated.resources.shell_drawer_finance_subtitle
import homesafe.shared.generated.resources.shell_drawer_market_disclaimer
import homesafe.shared.generated.resources.shell_drawer_net_worth
import homesafe.shared.generated.resources.shell_drawer_open_finance
import homesafe.shared.generated.resources.shell_drawer_section_apps
import homesafe.shared.generated.resources.shell_drawer_section_home_security
import homesafe.shared.generated.resources.shell_menu
import homesafe.shared.generated.resources.weather_drawer_empty
import homesafe.shared.generated.resources.weather_drawer_open
import homesafe.shared.generated.resources.weather_drawer_subtitle
import homesafe.shared.generated.resources.weather_drawer_title
import homesafe.shared.generated.resources.weather_place_current
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.hypot

private val DrawerWidth = 304.dp

/**
 * The app drawer the top bar's menu button opens: the camera app's own tabs, and below them the
 * apps that live inside PercySafe — Weather, whose card is a window onto the sky outside,
 * Fitness, whose card says which workout is up next, and Finance, whose card carries a live
 * S&P 500 line — so the drawer is worth a glance on its own.
 * Slides in over a scrim; a tap on the scrim, Back, or a swipe to the left closes it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ShellDrawer(
    open: Boolean,
    selectedTab: TopLevelRoute,
    finance: FinanceUiState,
    weather: WeatherUiState,
    fitness: FitnessUiState,
    onSelectTab: (TopLevelRoute) -> Unit,
    onOpenFinance: (Offset) -> Unit,
    onOpenWeather: (Offset) -> Unit,
    onOpenFitness: (Offset) -> Unit,
    onClose: () -> Unit,
) {
    val progress by animateFloatAsState(
        if (open) 1f else 0f,
        if (open) spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow) else tween(220, easing = FastOutSlowInEasing),
        label = "drawer",
    )
    if (!open && progress == 0f) return
    BackHandler(enabled = open) { onClose() }
    val density = LocalDensity.current
    val widthPx = with(density) { DrawerWidth.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    // Reset as it opens, not as it closes: a swipe that closed it would snap back first.
    LaunchedEffect(open) { if (open) drag = 0f }
    val dragState = rememberDraggableState { delta -> drag = (drag + delta).coerceIn(-widthPx, 0f) }

    val menuTitle = stringResource(Res.string.shell_menu)
    Box(Modifier.fillMaxSize().testTag("shell_drawer")) {
        // The scrim: the shell dims and a tap anywhere on it closes the drawer.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress * (1f + drag / widthPx) }
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(Res.string.shell_drawer_close), onClick = onClose),
        )
        Column(
            Modifier
                .fillMaxHeight()
                .width(DrawerWidth)
                .graphicsLayer { translationX = -(1f - progress) * widthPx + drag }
                .draggable(
                    dragState,
                    Orientation.Horizontal,
                    onDragStopped = { velocity -> if (drag < -widthPx / 3 || velocity < -1200f) onClose() else drag = 0f },
                )
                .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                .semantics {
                    paneTitle = menuTitle
                    isTraversalGroup = true
                }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Everything but the small print scrolls: on a phone on its side the drawer is
            // shorter than what is in it, and the Finance card must not be cut off at the fold.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(Res.string.shell_app_title),
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 0.03.em),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 24.dp),
                )
                DrawerLabel(stringResource(Res.string.shell_drawer_section_home_security))
                DrawerRow(Icons.Filled.Home, stringResource(Res.string.shell_drawer_cameras), selectedTab == TopLevelRoute.Home) { onSelectTab(TopLevelRoute.Home) }
                DrawerRow(Icons.Filled.VideoLibrary, stringResource(TopLevelRoute.Moments.label), selectedTab == TopLevelRoute.Moments) { onSelectTab(TopLevelRoute.Moments) }
                DrawerRow(Icons.Filled.Settings, stringResource(TopLevelRoute.Settings.label), selectedTab == TopLevelRoute.Settings) { onSelectTab(TopLevelRoute.Settings) }
                Spacer(Modifier.height(24.dp))
                DrawerLabel(stringResource(Res.string.shell_drawer_section_apps))
                WeatherDrawerCard(weather, animated = open, onOpen = onOpenWeather)
                Spacer(Modifier.height(12.dp))
                FitnessDrawerCard(fitness, animated = open, onOpen = onOpenFitness)
                Spacer(Modifier.height(12.dp))
                FinanceDrawerCard(finance, onOpenFinance)
            }
            Text(
                stringResource(Res.string.shell_drawer_market_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun DrawerLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.12.em),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) tint else MaterialTheme.colorScheme.onSurface)
    }
}

/** The Finance app's door: its name, today's S&P 500 with its sparkline, and net worth once the sheet is in. */
@Composable
private fun FinanceDrawerCard(finance: FinanceUiState, onOpen: (Offset) -> Unit) {
    val palette = remember { FinancePalette() }
    var center by remember { mutableStateOf(Offset.Zero) }
    val sp = finance.quotes[MarketCatalog.SP500.symbol]
    val dir = palette.direction(sp?.change ?: 0.0)
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF07140A), Color(0xFF000000))))
            .border(1.dp, Brush.linearGradient(listOf(palette.gain.copy(alpha = 0.7f), palette.accent.copy(alpha = 0.2f))), shape)
            .clickable(onClickLabel = stringResource(Res.string.shell_drawer_open_finance)) { onOpen(center) }
            .testTag("drawer_finance")
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(palette.gain), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.shell_drawer_finance), style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(stringResource(Res.string.shell_drawer_finance_subtitle), maxLines = 1, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = palette.textSecondary)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(MarketCatalog.SP500.shortName.resolve(), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                Text(sp?.let { FinanceFormat.grouped(it.price) } ?: "—", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(sp?.let { stringResource(Res.string.shell_drawer_change_today, FinanceFormat.signedPercent(it.changePercent)) } ?: " ", style = MaterialTheme.typography.labelSmall, color = dir)
            }
            if (sp != null && sp.intraday.size > 1) {
                Sparkline(sp.intraday, dir, Modifier.width(96.dp).height(40.dp), baseline = sp.previousClose)
            }
        }
        finance.finance?.netWorth?.let { nw ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.shell_drawer_net_worth, FinanceFormat.compactMoney(nw)), style = MaterialTheme.typography.labelMedium, color = palette.accent)
        }
    }
}

/**
 * The weather app's door: the sky over the place it's showing, moving while the drawer is open,
 * with the temperature, what it's doing there, and the day's one sentence written on it.
 */
@Composable
private fun WeatherDrawerCard(weather: WeatherUiState, animated: Boolean, onOpen: (Offset) -> Unit) {
    var center by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(22.dp)
    val entry = weather.selected
    val report = entry?.report
    val scene = remember(entry?.place, report?.current, weather.nowEpochSeconds / 300) {
        val latitude = entry?.place?.latitude ?: 40.0
        val longitude = entry?.place?.longitude ?: -100.0
        report?.let { SkyScene.of(it.current, latitude, longitude, weather.nowEpochSeconds) }
            ?: SkyScene.of(WeatherKind.MOSTLY_CLEAR, null, 8.0, 270, null, latitude, longitude, weather.nowEpochSeconds)
    }
    val secondary = Color(0xD1FFFFFF)
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClickLabel = stringResource(Res.string.weather_drawer_open)) { onOpen(center) }
            .testTag("drawer_weather"),
    ) {
        // Small enough to draw live: the drawer's one moving thing, and stopped when it's shut.
        WeatherSky(scene, Modifier.matchParentSize(), glass = false, running = animated, freeze = if (weather.preferences.stillSky) SkyFreeze() else null)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x80000000)))))
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.WbSunny, contentDescription = null, tint = Color(0xFF1F6BD6), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.weather_drawer_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        entry?.place?.name?.ifBlank { stringResource(Res.string.weather_place_current) } ?: stringResource(Res.string.weather_drawer_subtitle),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                    )
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = secondary)
            }
            Spacer(Modifier.height(14.dp))
            if (report != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(WeatherFormat.degrees(report.current.temperatureC, weather.units), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(report.current.kind.label(report.current.isDay)), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                        Text(
                            remember(report, weather.units, weather.nowEpochSeconds) { WeatherStory.headline(report, weather.units, weather.nowEpochSeconds) }.resolve(),
                            style = MaterialTheme.typography.labelSmall,
                            color = secondary,
                            maxLines = 2,
                        )
                    }
                }
            } else {
                Text(
                    entry?.error?.resolve() ?: stringResource(Res.string.weather_drawer_empty),
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
                )
            }
        }
    }
}

/**
 * The fitness app's door: muscle fibre in the colours of the workout that is up next (or the one
 * in progress), moving while the drawer is open, with which day that is and how the week has gone.
 */
@Composable
private fun FitnessDrawerCard(fitness: FitnessUiState, animated: Boolean, onOpen: (Offset) -> Unit) {
    var center by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(22.dp)
    val palette = remember { FitnessPalette() }
    val workout = fitness.workout
    val due = fitness.days.firstOrNull { it.due }
    val tints = (workout?.workout?.focus ?: due?.focus)?.let(palette::focus) ?: palette.phase(fitness.phase.kind)
    val secondary = Color(0xD1FFFFFF)
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClickLabel = stringResource(Res.string.fitness_drawer_open)) { onOpen(center) }
            .testTag("drawer_fitness"),
    ) {
        // Small enough to draw live, and stopped when the drawer is shut.
        FiberField(tints.first, tints.second, seed = 0.4f, Modifier.matchParentSize(), energy = if (workout != null) 1f else 0.4f, running = animated)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x4D000000), Color(0x99000000)))))
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FitnessCenter, contentDescription = null, tint = Color(0xFFD9441A), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.fitness_drawer_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(stringResource(Res.string.fitness_drawer_subtitle), maxLines = 1, style = MaterialTheme.typography.labelSmall, color = secondary)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = secondary)
            }
            Spacer(Modifier.height(14.dp))
            when {
                workout != null -> {
                    Text(
                        stringResource(Res.string.fitness_today_headline_working, stringResource(workout.workout.focus.label)),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(pluralStringResource(Res.plurals.fitness_today_resume_detail, workout.setCount, workout.setCount), style = MaterialTheme.typography.labelSmall, color = secondary)
                }

                due != null -> {
                    Text(stringResource(Res.string.fitness_today_headline_due, stringResource(due.focus.label)), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                    Text(
                        pluralStringResource(Res.plurals.fitness_drawer_week, fitness.week.sessions, fitness.week.sessions),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                        maxLines = 1,
                    )
                }

                else -> Text(stringResource(Res.string.fitness_drawer_empty), style = MaterialTheme.typography.labelMedium, color = secondary)
            }
        }
    }
}

/**
 * An app opened from the drawer ([content]: finance, weather, fitness), over the whole shell, opening as
 * a circle that grows from [origin] (the drawer card that was tapped) to cover the screen, and
 * draining back toward the menu button when it closes. [onCovering] reports when it fully covers
 * the screen, so the shell can stop drawing — and stop streaming — the cameras underneath.
 */
@Composable
internal fun InnerAppOverlay(open: Boolean, origin: Offset, onCovering: (Boolean) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val reportCovering by rememberUpdatedState(onCovering)
    var shown by remember { mutableStateOf(open) }
    LaunchedEffect(open) {
        if (open) {
            shown = true
            reveal.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
            reportCovering(true)
        } else {
            reportCovering(false)
            reveal.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            shown = false
        }
    }
    if (!shown) return
    val density = LocalDensity.current
    // Closing, it drains back toward the menu button it was reached from, below the status bar.
    val statusBar = WindowInsets.statusBars.getTop(density)
    val menuPoint = with(density) { Offset(48.dp.toPx(), statusBar + 36.dp.toPx()) }
    // [origin] is in the root's coordinates; the reveal is drawn in this box's, which a display
    // cutout's padding can shift.
    var ownOrigin by remember { mutableStateOf(Offset.Zero) }
    val center = if (open && origin != Offset.Zero) origin - ownOrigin else menuPoint
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { ownOrigin = it.positionInRoot() }
            .graphicsLayer {
                clip = true
                // Rebuilt each frame: the layer re-runs as the reveal animates.
                shape = circleReveal(center, reveal.value)
                val s = 0.94f + 0.06f * reveal.value
                scaleX = s
                scaleY = s
                alpha = (reveal.value * 2.5f).coerceAtMost(1f)
            },
    ) {
        content()
    }
}

/** A circle centred on [center] whose radius at [fraction] 1 reaches the farthest corner. */
private fun circleReveal(center: Offset, fraction: Float) = GenericShape { size, _ ->
    val maxR = maxOf(
        hypot(center.x, center.y),
        hypot(size.width - center.x, center.y),
        hypot(center.x, size.height - center.y),
        hypot(size.width - center.x, size.height - center.y),
    )
    addOval(Rect(center, maxR * fraction))
}
