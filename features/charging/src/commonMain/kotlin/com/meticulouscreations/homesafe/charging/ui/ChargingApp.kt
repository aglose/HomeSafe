package com.meticulouscreations.homesafe.charging.ui

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.charging.ChargingUiState
import com.meticulouscreations.homesafe.charging.ChargingViewModel
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.AppSection
import com.meticulouscreations.homesafe.ui.LocalAppSections
import com.meticulouscreations.homesafe.ui.WithTooltip
import com.meticulouscreations.homesafe.ui.appPageGutter
import com.meticulouscreations.homesafe.ui.assistant.AssistantScreenContext
import com.meticulouscreations.homesafe.ui.assistant.LocalAssistant
import com.meticulouscreations.homesafe.ui.assistant.assistantTrigger
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.ui.publishAppSections
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.ui.rememberPredictiveBackTransition
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_close
import homesafe.shared.generated.resources.charging_loading
import homesafe.shared.generated.resources.charging_refresh
import homesafe.shared.generated.resources.charging_tab_charges
import homesafe.shared.generated.resources.charging_tab_overview
import homesafe.shared.generated.resources.charging_tab_rates
import homesafe.shared.generated.resources.charging_title
import homesafe.shared.generated.resources.charging_title_charge
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.common_retry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The charging app's tabs, in nav order. */
enum class ChargingTab(val label: StringResource, val icon: ImageVector) {
    OVERVIEW(Res.string.charging_tab_overview, Icons.Filled.Insights),
    CHARGES(Res.string.charging_tab_charges, Icons.AutoMirrored.Filled.List),
    RATES(Res.string.charging_tab_rates, Icons.Filled.Schedule),
}

/** A page pushed over the tabs. */
@Immutable
sealed interface ChargingPage {
    /** One charge, hour by hour. */
    data class Charge(val id: Long) : ChargingPage

    /** One car, month by month. */
    data class Car(val id: String) : ChargingPage
}

/** Everything the charging screens can ask for, as one thing to hand down: the view model's side of the app. */
@Stable
class ChargingActions(
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onOpenCharge: (Long) -> Unit = {},
    val onSaveCharge: (Long, Double?, String?) -> Unit = { _, _, _ -> },
    val onChoosePlan: (String) -> Unit = {},
)

/**
 * The charging app: an app of its own inside PercySafe, opened from the drawer as the others are.
 * What the household's Teslas cost to charge: each month and each car, every charge, and the rates
 * it was priced at. The relay holds it all. Back pops a page, then leaves the app ([onClose]).
 */
@Composable
fun ChargingApp(onClose: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true) {
    // The activity's instance, the one the drawer's card shares.
    val viewModel: ChargingViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val actions = remember(viewModel) {
        ChargingActions(
            onClose = { close() },
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onOpenCharge = viewModel::openSession,
            onSaveCharge = viewModel::saveSession,
            onChoosePlan = viewModel::choosePlan,
        )
    }
    val askedCar by ChargingLink.car.collectAsStateWithLifecycle()
    ChargingAppContent(state, actions, modifier, active, askedCar = askedCar, onShowAskedCar = ChargingLink::shown)
}

/** [ChargingApp] without its view model: everything on screen from one [state], for previews and tests as much as for the app. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChargingAppContent(
    state: ChargingUiState,
    actions: ChargingActions,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    initialTab: ChargingTab = ChargingTab.OVERVIEW,
    initialPage: ChargingPage? = null,
    askedCar: String? = null,
    onShowAskedCar: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var chargesCar by rememberSaveable { mutableStateOf<String?>(null) }
    val pages = remember { mutableStateListOf<ChargingPage>().apply { initialPage?.let(::add) } }
    val showAskedCar by rememberUpdatedState(onShowAskedCar)
    LaunchedEffect(askedCar) {
        if (askedCar != null) {
            chargesCar = askedCar
            tab = ChargingTab.CHARGES
            pages.clear()
            showAskedCar()
        }
    }
    AssistantScreenContext { "Charging app, ${tab.name.lowercase()} tab" + (pages.lastOrNull()?.let { ", $it page open" } ?: "") }
    val haptics = LocalHapticFeedback.current

    fun pop() {
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else actions.onClose()
    }
    val back = rememberPredictiveBack(enabled = active && pages.isNotEmpty()) { pop() }

    val fontFamily = albertSansFontFamily()
    val palette = remember { ChargingPalette() }
    // Remembered: a new instance through the static local would recompose the whole tree on every state change.
    val type = remember(fontFamily) { ChargingTypography(fontFamily) }
    CompositionLocalProvider(LocalChargingPalette provides palette, LocalChargingTypography provides type) {
        val status = WindowInsets.statusBars.asPaddingValues()
        val nav = WindowInsets.navigationBars.asPaddingValues()
        val top = status.calculateTopPadding() + 62.dp
        BoxWithConstraints(modifier.fillMaxSize().background(palette.background).testTag("charging_app")) {
            // Centred at a readable width in a wide window (a desktop's, a tablet's): see appPageGutter.
            val gutter = appPageGutter(maxWidth).coerceAtLeast(16.dp)
            val tabPadding = PaddingValues(start = gutter, end = gutter, top = top, bottom = nav.calculateBottomPadding() + if (LocalAppSections.current != null) 28.dp else 108.dp)
            val pagePadding = PaddingValues(start = gutter, end = gutter, top = top, bottom = nav.calculateBottomPadding() + 28.dp)
            val page = pages.lastOrNull()
            val inSidebar = publishAppSections(
                sections = if (state.loaded) CHARGING_SECTIONS else emptyList(),
                selected = if (pages.isEmpty()) tab.ordinal else -1,
            ) { index ->
                pages.clear()
                tab = ChargingTab.entries[index]
            }

            if (!state.loaded) {
                ChargingWaiting(state, actions)
            } else {
                val target: Any = page ?: tab
                // Which way the stack last moved, for the slide's direction; outside the snapshot system since it only steers an animation.
                val depth = remember { intArrayOf(pages.size, 1) }
                if (pages.size != depth[0]) {
                    depth[1] = if (pages.size > depth[0]) 1 else -1
                    depth[0] = pages.size
                }
                val previous: Any? = if (pages.isEmpty()) null else pages.getOrNull(pages.lastIndex - 1) ?: tab
                val openCharge: (Long) -> Unit = { id ->
                    actions.onOpenCharge(id)
                    pages += ChargingPage.Charge(id)
                }
                val seeChargesOf: (String) -> Unit = { car ->
                    chargesCar = car
                    tab = ChargingTab.CHARGES
                    pages.clear()
                }
                // The pages end at the keyboard while it is up, so the field being typed in scrolls up above it.
                rememberPredictiveBackTransition(target, previous, back, label = "chargingPage").AnimatedContent(
                    modifier = Modifier.imePadding(),
                    transitionSpec = { chargingTransition(initialState, targetState, forward = depth[1] > 0 && !back.inProgress) },
                ) { shown ->
                    when (shown) {
                        ChargingTab.OVERVIEW -> OverviewScreen(state, tabPadding, onOpenCharge = openCharge, onOpenCar = { pages += ChargingPage.Car(it) }, onSeeRates = { tab = ChargingTab.RATES })
                        ChargingTab.CHARGES -> ChargesScreen(state, chargesCar, tabPadding, onChooseCar = { chargesCar = it }, onOpenCharge = openCharge, onLoadMore = actions.onLoadMore)
                        ChargingTab.RATES -> RatesScreen(state, tabPadding, onChoosePlan = actions.onChoosePlan)
                        is ChargingPage.Charge -> ChargeScreen(state, shown.id, pagePadding, onSave = actions.onSaveCharge)
                        is ChargingPage.Car -> CarScreen(state, shown.id, pagePadding, onSeeCharges = { seeChargesOf(shown.id) })
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }

            // The answer to whatever just failed, over what's still there. A charge's page with nothing to show says so itself.
            val error = state.error
            if (state.loaded && error != null && (page !is ChargingPage.Charge || state.sessions.any { it.id == page.id } || state.detail?.session?.id == page.id)) {
                val retry = {
                    // A charge's page has its own answer to ask for again.
                    if (page is ChargingPage.Charge) actions.onOpenCharge(page.id)
                    actions.onRefresh()
                }
                ChargingErrorBanner(error.resolve(), retry, Modifier.align(Alignment.TopCenter).padding(start = gutter, end = gutter, top = top))
            }

            ChargingTopBar(
                title = when (page) {
                    null -> stringResource(Res.string.charging_title)
                    is ChargingPage.Charge -> stringResource(Res.string.charging_title_charge)
                    is ChargingPage.Car -> state.summary.monthsOf(page.id).firstOrNull()?.cars?.firstOrNull()?.name ?: page.id
                },
                paged = page != null,
                onBack = ::pop,
                onRefresh = if (page == null && state.loaded) actions.onRefresh else null,
                refreshing = state.refreshing,
            )

            AnimatedVisibility(
                visible = pages.isEmpty() && state.loaded && !inSidebar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            ) {
                ChargingNav(tab) { picked ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    tab = picked
                }
            }
        }
    }
}

/** Nothing from the relay yet: that it is being asked, or why it can't be. */
@Composable
private fun ChargingWaiting(state: ChargingUiState, actions: ChargingActions) {
    val colors = ChargingTheme.colors
    Box(Modifier.fillMaxSize()) {
        ChargingWires(running = true, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.background))))
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val error = state.error
            Text(error?.resolve() ?: stringResource(Res.string.charging_loading), style = ChargingTheme.type.body, color = colors.textMuted, textAlign = TextAlign.Center)
            if (error != null) {
                TextButton(onClick = actions.onRefresh, modifier = Modifier.handCursor().testTag("charging_retry")) {
                    Text(stringResource(Res.string.common_retry), color = colors.home)
                }
            }
        }
    }
}

@Composable
private fun ChargingErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ChargingTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(ChargingCardShape)
            .background(colors.surfaceRaised)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("charging_error"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = ChargingTheme.type.label, color = colors.danger, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.handCursor().testTag("charging_error_retry")) {
            Text(stringResource(Res.string.common_retry), color = colors.home)
        }
    }
}

/** Pages pushed over the tabs slide in from the right and back out the way they came; the tabs fade through one another. */
private fun chargingTransition(from: Any, to: Any, forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
    return when {
        from !is ChargingPage && to !is ChargingPage -> fadeIn(tween(260)) togetherWith fadeOut(tween(200))
        forward -> (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))
        else -> (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))
    }
}

/** A round icon button, with a tooltip and the hand cursor where there's a pointer. */
@Composable
internal fun ChargingRoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = ChargingTheme.colors.text) {
    Box(
        modifier.size(size).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).handCursor(),
        contentAlignment = Alignment.Center,
    ) {
        WithTooltip(label, Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.5f))
            }
        }
    }
}

@Composable
private fun ChargingTopBar(title: String, paged: Boolean, onBack: () -> Unit, onRefresh: (() -> Unit)?, refreshing: Boolean, closable: Boolean = LocalAppSections.current == null) {
    val colors = ChargingTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to colors.background.copy(alpha = 0.94f), 0.7f to colors.background.copy(alpha = 0.7f), 1f to Color.Transparent))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Beside the shell's sidebar there is nothing to close: the sidebar is the way out, so only Back is offered.
        if (paged || closable) {
            ChargingRoundButton(
                if (paged) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                stringResource(if (paged) Res.string.common_back else Res.string.charging_close),
                onBack,
                Modifier.testTag("charging_back"),
            )
        }
        Text(title, style = ChargingTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (onRefresh != null) {
            ChargingRoundButton(Icons.Filled.Refresh, stringResource(Res.string.charging_refresh), onRefresh, Modifier.testTag("charging_refresh"), tint = if (refreshing) colors.home else colors.textMuted)
        }
    }
}

/** The tabs, as a dark pill along the foot. */
@Composable
private fun ChargingNav(selected: ChargingTab, onSelect: (ChargingTab) -> Unit) {
    val colors = ChargingTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth(0.84f)
            // Held, as the shell's own bar is, it opens the assistant.
            .assistantTrigger(LocalAssistant.current)
            .clip(CircleShape)
            .background(colors.surfaceRaised.copy(alpha = 0.96f))
            .border(1.dp, colors.hairline, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChargingTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.home.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                    .handCursor()
                    .semantics { this.selected = isSelected }
                    .testTag("charging_tab_${tab.name.lowercase()}")
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, contentDescription = null, tint = if (isSelected) colors.home else colors.textFaint, modifier = Modifier.size(22.dp))
                Text(stringResource(tab.label), style = ChargingTheme.type.micro, color = if (isSelected) colors.text else colors.textFaint, maxLines = 1)
            }
        }
    }
}

/** The charging app's tabs as the shell's sidebar lists them, under its row (see publishAppSections). */
private val CHARGING_SECTIONS = ChargingTab.entries.map { AppSection(it.label, it.icon) }
