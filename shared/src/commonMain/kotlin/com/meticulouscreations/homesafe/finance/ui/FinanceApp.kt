package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.finance.BudgetViewModel
import com.meticulouscreations.homesafe.finance.ChartStyleViewModel
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.FinanceViewModel
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.SymbolMatch
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.fin_bank_title
import homesafe.shared.generated.resources.fin_budget_settings_title
import homesafe.shared.generated.resources.finance_close
import homesafe.shared.generated.resources.finance_refresh
import homesafe.shared.generated.resources.finance_refreshing
import homesafe.shared.generated.resources.finance_tab_budget
import homesafe.shared.generated.resources.finance_tab_economy
import homesafe.shared.generated.resources.finance_tab_markets
import homesafe.shared.generated.resources.finance_tab_risk
import homesafe.shared.generated.resources.finance_tab_wallet
import homesafe.shared.generated.resources.finance_title_chart_settings
import homesafe.shared.generated.resources.finance_title_connections
import homesafe.shared.generated.resources.finance_title_glossary
import homesafe.shared.generated.resources.finance_title_sheet_sync
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The finance app's five tabs, in bottom-nav order. */
enum class FinanceTab(val label: StringResource, val icon: ImageVector) {
    WALLET(Res.string.finance_tab_wallet, Icons.Filled.AccountBalanceWallet),
    BUDGET(Res.string.finance_tab_budget, Icons.Filled.CreditCard),
    MARKETS(Res.string.finance_tab_markets, Icons.AutoMirrored.Filled.ShowChart),
    ECONOMY(Res.string.finance_tab_economy, Icons.Filled.Public),
    RISK(Res.string.finance_tab_risk, Icons.Filled.Radar),
}

/** A page pushed over the tabs. */
@Immutable
internal sealed interface FinanceDetail {
    data class QuotePage(val symbol: String) : FinanceDetail

    data class IndicatorPage(val id: String) : FinanceDetail

    /** How it all connects: the cause-and-effect map. */
    data object Connections : FinanceDetail

    /** The jargon buster. */
    data object Glossary : FinanceDetail

    /** How the budget sheet's last sync went, part by part. */
    data object SheetSync : FinanceDetail

    /** The banks and brokerages linked through Plaid, whose balances fill the sheet in. */
    data object BankSync : FinanceDetail

    /** The month's limits, what each card is, and how its purchases are sorted. */
    data object BudgetSettings : FinanceDetail

    /** How every chart looks and feels. */
    data object ChartSettings : FinanceDetail
}

/**
 * The finance app: an app of its own inside PercySafe, opened from the drawer. Robinhood's dark
 * look on true black — Wallet (the household's money, from the budget sheet), Budget (the month's
 * card spending against its limits), Markets (indices and the watchlist, live), Economy (inflation, the Treasury curve, rates) and Risk (the warning
 * lights for a downturn, blended into one gauge) — with quote and indicator pages pushed over
 * the tabs. Back pops a page, then leaves the app ([onClose]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun FinanceApp(onClose: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true) {
    // The activity's instance, the one the drawer's teaser shares.
    val viewModel: FinanceViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chartStyleViewModel: ChartStyleViewModel = metroViewModel()
    val chartStyle by chartStyleViewModel.style.collectAsStateWithLifecycle()
    // The Budget tab's own, here for the Wallet's line about the month and so a pull refreshes it too.
    val budgetViewModel: BudgetViewModel = metroViewModel()
    val budgetState by budgetViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { budgetViewModel.refresh() }
    var tab by rememberSaveable { mutableStateOf(FinanceTab.WALLET) }
    val details = remember { mutableStateListOf<FinanceDetail>() }
    val listStates = FinanceTab.entries.associateWith { rememberLazyListState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // The explainer sheet up, by explainer id.
    var explaining by rememberSaveable { mutableStateOf<String?>(null) }
    // An opening of the app, counted toward the "tap ⓘ" tip's showings once: saved, so a
    // recreated activity around the same opening doesn't count it again.
    var openingCounted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!openingCounted) {
            openingCounted = true
            viewModel.onAppOpened()
        }
    }
    // The add-a-ticker search, up over whichever tab opened it.
    var addingSymbol by rememberSaveable { mutableStateOf(false) }
    val openAddSymbol: () -> Unit = remember { { addingSymbol = true } }
    fun push(detail: FinanceDetail) {
        if (details.lastOrNull() != detail) details += detail
    }

    // Off while the app animates closed ([active] false), so that Back reaches what's beneath.
    BackHandler(enabled = active) {
        if (details.isNotEmpty()) details.removeAt(details.lastIndex) else onClose()
    }

    val palette = remember { FinancePalette() }
    // Remembered: a new instance through the static local would recompose the whole tree on
    // every state change (a quote poll, each economic reading as it arrives).
    val fontFamily = albertSansFontFamily()
    val type = remember(fontFamily) { FinanceTypography(fontFamily) }
    val openExplainer: (String) -> Unit = remember { { id -> explaining = id } }
    CompositionLocalProvider(
        LocalFinancePalette provides palette,
        LocalFinanceTypography provides type,
        LocalExplainer provides openExplainer,
        LocalChartStyle provides chartStyle,
        LocalEconomyTone provides state.tone,
    ) {
        val status = WindowInsets.statusBars.asPaddingValues()
        val nav = WindowInsets.navigationBars.asPaddingValues()
        // On a phone on its side the tabs' nav stands at the start edge instead of floating over
        // the foot of the page (as the camera shell's does; see showsNavRail there), and the tabs'
        // pages start clear of it.
        val sideNav = isCompactLandscape()
        val padding = PaddingValues(
            start = if (sideNav) FINANCE_SIDE_NAV_CLEARANCE else 0.dp,
            top = status.calculateTopPadding() + 60.dp,
            bottom = nav.calculateBottomPadding() + if (sideNav) 32.dp else 104.dp,
        )
        val detailPadding = PaddingValues(top = status.calculateTopPadding() + 60.dp, bottom = nav.calculateBottomPadding() + 32.dp)
        val pullState = rememberPullToRefreshState()

        Box(modifier.fillMaxSize().background(palette.background).testTag("finance_app")) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    viewModel.refresh()
                    budgetViewModel.refresh()
                },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.refreshing,
                        containerColor = palette.surfaceRaised,
                        color = palette.gain,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = status.calculateTopPadding() + 56.dp),
                    )
                },
            ) {
                val target: Any = details.lastOrNull() ?: tab
                AnimatedContent(
                    targetState = target,
                    transitionSpec = { financeTransition(initialState, targetState) },
                    label = "financePage",
                    contentKey = { it },
                ) { page ->
                    when (page) {
                        FinanceTab.WALLET -> WalletScreen(
                            state,
                            listStates.getValue(FinanceTab.WALLET),
                            padding,
                            onOpenQuote = { push(FinanceDetail.QuotePage(it)) },
                            onRetrySheet = viewModel::retrySheet,
                            onOpenSync = { push(FinanceDetail.SheetSync) },
                            onAddSymbol = openAddSymbol,
                            budget = budgetState.budget,
                            onOpenBudget = { tab = FinanceTab.BUDGET },
                        )

                        FinanceTab.BUDGET -> BudgetRoute(
                            state.finance,
                            listStates.getValue(FinanceTab.BUDGET),
                            padding,
                            onOpenLinkedAccounts = { push(FinanceDetail.BankSync) },
                            onOpenSettings = { push(FinanceDetail.BudgetSettings) },
                        )

                        FinanceTab.MARKETS -> MarketsScreen(
                            state,
                            listStates.getValue(FinanceTab.MARKETS),
                            padding,
                            viewModel::requestHistory,
                            onAddSymbol = openAddSymbol,
                        ) { push(FinanceDetail.QuotePage(it)) }

                        FinanceTab.ECONOMY -> EconomyScreen(
                            state,
                            listStates.getValue(FinanceTab.ECONOMY),
                            padding,
                            onOpenIndicator = { push(FinanceDetail.IndicatorPage(it)) },
                            onOpenConnections = { push(FinanceDetail.Connections) },
                            onToneChange = viewModel::setTone,
                        )

                        FinanceTab.RISK -> RiskScreen(state, listStates.getValue(FinanceTab.RISK), padding) { push(FinanceDetail.IndicatorPage(it)) }

                        is FinanceDetail.QuotePage -> QuoteDetailScreen(
                            page.symbol,
                            state,
                            detailPadding,
                            viewModel::requestHistory,
                            onFollow = { symbol ->
                                val meta = state.meta(symbol)
                                viewModel.addSymbol(SymbolMatch(symbol, state.nameToSave(symbol), meta.kind, typeLabel = "", exchange = null))
                            },
                            onUnfollow = viewModel::removeSymbol,
                            onSetPosition = viewModel::setPosition,
                        )

                        is FinanceDetail.IndicatorPage -> IndicatorDetailScreen(page.id, state, detailPadding)

                        FinanceDetail.Connections -> ConnectionsScreen(state, detailPadding)

                        FinanceDetail.Glossary -> GlossaryScreen(detailPadding)

                        FinanceDetail.SheetSync -> SheetSyncScreen(state, detailPadding, onSyncNow = viewModel::refresh, onOpenBankSync = { push(FinanceDetail.BankSync) })

                        FinanceDetail.BankSync -> BankSyncRoute(detailPadding)

                        FinanceDetail.BudgetSettings -> BudgetSettingsRoute(state.finance, detailPadding)

                        FinanceDetail.ChartSettings -> ChartSettingsScreen(chartStyle, detailPadding, onChange = chartStyleViewModel::update)
                    }
                }
            }

            FinanceTopBar(
                title = details.lastOrNull()?.let { titleOf(it) } ?: stringResource(tab.label),
                isDetail = details.isNotEmpty(),
                refreshing = state.refreshing,
                onBack = { if (details.isNotEmpty()) details.removeAt(details.lastIndex) else onClose() },
                onRefresh = viewModel::refresh,
                onGlossary = { push(FinanceDetail.Glossary) },
                onChartSettings = { push(FinanceDetail.ChartSettings) },
            )

            // The first-openings nudge, floating above the tabs' nav until it's put away.
            ExplainTip(
                visible = state.explainTipVisible && details.isEmpty(),
                onDismiss = viewModel::dismissExplainTip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // Above the nav when it floats along the bottom; beside it when it is at the side.
                    .padding(start = if (sideNav) FINANCE_SIDE_NAV_CLEARANCE else 0.dp, bottom = if (sideNav) 16.dp else 84.dp),
            )

            if (addingSymbol) {
                AddSymbolSheet(
                    search = state.search,
                    sheetSymbols = state.sheetSymbols,
                    addedSymbols = remember(state.watched) { state.watched.mapTo(HashSet()) { it.symbol } },
                    onQuery = viewModel::searchSymbols,
                    onAdd = viewModel::addSymbol,
                    onDismiss = {
                        addingSymbol = false
                        viewModel.clearSearch()
                    },
                )
            }

            explaining?.let { id ->
                ExplainSheet(
                    id = id,
                    state = state,
                    onDismiss = { explaining = null },
                    onNavigate = { explaining = it },
                    onOpenChart = {
                        explaining = null
                        push(FinanceDetail.IndicatorPage(it))
                    },
                )
            }

            val onPickTab: (FinanceTab) -> Unit = { picked ->
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (picked == tab) {
                    scope.launch { listStates.getValue(picked).animateScrollToItem(0) }
                } else {
                    tab = picked
                }
            }
            AnimatedVisibility(
                visible = details.isEmpty() && !sideNav,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            ) {
                FinanceBottomNav(tab, onPickTab)
            }
            AnimatedVisibility(
                visible = details.isEmpty() && sideNav,
                // Centred in the height the top bar leaves.
                modifier = Modifier.align(Alignment.CenterStart).padding(top = status.calculateTopPadding() + 48.dp),
                enter = fadeIn(tween(220)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it },
                exit = fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it },
            ) {
                FinanceSideNav(tab, onPickTab)
            }
        }
    }
}

@Composable
private fun titleOf(detail: FinanceDetail): String = when (detail) {
    is FinanceDetail.QuotePage -> MarketCatalog.lookup(detail.symbol).shortName.resolve()
    is FinanceDetail.IndicatorPage -> IndicatorCatalog.byId(detail.id)?.let { Narrator.plainTitle(it.id).resolve() } ?: ""
    FinanceDetail.Connections -> stringResource(Res.string.finance_title_connections)
    FinanceDetail.Glossary -> stringResource(Res.string.finance_title_glossary)
    FinanceDetail.SheetSync -> stringResource(Res.string.finance_title_sheet_sync)
    FinanceDetail.BankSync -> stringResource(Res.string.fin_bank_title)
    FinanceDetail.BudgetSettings -> stringResource(Res.string.fin_budget_settings_title)
    FinanceDetail.ChartSettings -> stringResource(Res.string.finance_title_chart_settings)
}

/**
 * Pages pushed over the tabs slide in from the right and back out; tabs hand over sideways in
 * the direction of the tab tapped, with a quick fade, the way the camera shell's tabs do.
 */
private fun financeTransition(from: Any, to: Any): ContentTransform {
    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
    return when {
        to is FinanceDetail ->
            (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))

        from is FinanceDetail ->
            (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))

        from is FinanceTab && to is FinanceTab -> {
            val forward = to.ordinal > from.ordinal
            (slideInHorizontally(spec) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(240)) + scaleIn(tween(320), initialScale = 0.985f)) togetherWith
                (slideOutHorizontally(spec) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(160)))
        }

        else -> fadeIn(tween(200)) togetherWith fadeOut(tween(200))
    }
}

@Composable
private fun FinanceTopBar(
    title: String,
    isDetail: Boolean,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGlossary: () -> Unit,
    onChartSettings: () -> Unit,
) {
    val colors = FinanceTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            // Fades from black so content scrolling under it slips away rather than cutting off.
            .background(Brush.verticalGradient(0f to colors.background, 0.75f to colors.background.copy(alpha = 0.92f), 1f to Color.Transparent))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).testTag("finance_back")) {
            AnimatedContent(isDetail, label = "backIcon") { detail ->
                Icon(
                    if (detail) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                    contentDescription = stringResource(if (detail) Res.string.common_back else Res.string.finance_close),
                    tint = colors.textPrimary,
                )
            }
        }
        AnimatedContent(title, modifier = Modifier.align(Alignment.Center), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "title") { t ->
            Text(t, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
        }
        // On the tabs only: their titles are short enough to leave room for a third button.
        if (!isDetail) {
            IconButton(onClick = onChartSettings, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 88.dp).testTag("finance_chart_settings")) {
                Icon(Icons.Filled.Tune, contentDescription = stringResource(Res.string.finance_title_chart_settings), tint = colors.textSecondary)
            }
        }
        IconButton(onClick = onGlossary, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 44.dp).testTag("finance_glossary")) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(Res.string.finance_title_glossary), tint = colors.textSecondary)
        }
        IconButton(onClick = onRefresh, modifier = Modifier.align(Alignment.CenterEnd)) {
            if (refreshing) {
                // Only while refreshing, and turned in the layer: no frames spent when idle, and no
                // recomposition per frame when not.
                val spin = rememberInfiniteTransition(label = "refreshSpin")
                val angle = spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.finance_refreshing), tint = colors.gain, modifier = Modifier.graphicsLayer { rotationZ = angle.value })
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.finance_refresh), tint = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun FinanceBottomNav(selected: FinanceTab, onSelect: (FinanceTab) -> Unit) {
    val colors = FinanceTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth(0.92f)
            .clip(CircleShape)
            .background(colors.surfaceRaised.copy(alpha = 0.94f))
            .border(1.dp, colors.hairline, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Five tabs share the bar evenly: at their own widths they would run past a narrow phone's edge.
        FinanceTab.entries.forEach { t -> FinanceNavItem(t, isSelected = t == selected, onSelect = onSelect, modifier = Modifier.weight(1f), horizontalPadding = 4.dp) }
    }
}

/** [FinanceBottomNav] stood on end, for a phone on its side: the same tabs, with the same test tags. */
@Composable
private fun FinanceSideNav(selected: FinanceTab, onSelect: (FinanceTab) -> Unit) {
    val colors = FinanceTheme.colors
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .padding(start = FINANCE_SIDE_NAV_MARGIN)
            .navigationBarsPadding()
            .width(FINANCE_SIDE_NAV_WIDTH)
            .clip(shape)
            .background(colors.surfaceRaised.copy(alpha = 0.94f))
            .border(1.dp, colors.hairline, shape)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Every item as wide as the rail, so the selected one's highlight doesn't follow its label's length.
        FinanceTab.entries.forEach { t -> FinanceNavItem(t, isSelected = t == selected, onSelect = onSelect, modifier = Modifier.fillMaxWidth(), horizontalPadding = 4.dp) }
    }
}

@Composable
private fun FinanceNavItem(
    tab: FinanceTab,
    isSelected: Boolean,
    onSelect: (FinanceTab) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 14.dp,
) {
    val colors = FinanceTheme.colors
    Column(
        modifier
            .clip(CircleShape)
            .background(if (isSelected) colors.gain.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
            .semantics { this.selected = isSelected }
            .testTag("finance_tab_${tab.name.lowercase()}")
            .padding(horizontal = horizontalPadding, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = null, tint = if (isSelected) colors.gain else colors.textSecondary, modifier = Modifier.size(22.dp))
        Text(stringResource(tab.label), style = FinanceTheme.type.micro, color = if (isSelected) colors.gain else colors.textSecondary, maxLines = 1)
    }
}

private val FINANCE_SIDE_NAV_MARGIN = 12.dp
private val FINANCE_SIDE_NAV_WIDTH = 76.dp

/** How far the tabs' pages start from the edge while the nav is at the side. */
private val FINANCE_SIDE_NAV_CLEARANCE = FINANCE_SIDE_NAV_MARGIN + FINANCE_SIDE_NAV_WIDTH

/** For the drawer: the S&P's quote, if there is one yet. */
internal fun FinanceUiState.spQuote() = quotes[MarketCatalog.SP500.symbol]
