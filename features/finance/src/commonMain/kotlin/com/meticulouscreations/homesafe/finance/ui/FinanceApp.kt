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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.finance.BudgetViewModel
import com.meticulouscreations.homesafe.finance.ChartStyleViewModel
import com.meticulouscreations.homesafe.finance.FinanceLockViewModel
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.FinanceViewModel
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.SymbolMatch
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.APP_PAGE_MAX_WIDTH
import com.meticulouscreations.homesafe.ui.AppSection
import com.meticulouscreations.homesafe.ui.WithTooltip
import com.meticulouscreations.homesafe.ui.appPageGutter
import com.meticulouscreations.homesafe.ui.assistant.AssistantScreenContext
import com.meticulouscreations.homesafe.ui.assistant.LocalAssistant
import com.meticulouscreations.homesafe.ui.assistant.assistantTrigger
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.publishAppSections
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.ui.rememberPredictiveBackTransition
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.fin_balance_title
import homesafe.shared.generated.resources.fin_bank_title
import homesafe.shared.generated.resources.fin_budget_settings_title
import homesafe.shared.generated.resources.finance_close
import homesafe.shared.generated.resources.finance_offline_note
import homesafe.shared.generated.resources.finance_refresh
import homesafe.shared.generated.resources.finance_refreshing
import homesafe.shared.generated.resources.finance_title_chart_settings
import homesafe.shared.generated.resources.finance_title_connections
import homesafe.shared.generated.resources.finance_title_glossary
import homesafe.shared.generated.resources.finance_title_sheet_sync
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Whether [this] page is a dashboard on a desk: the tabs and the pages of figures and charts, not the forms, maps and lists read down. */
private fun Any.isDashboard(): Boolean = this is FinanceTab ||
    this is FinanceDetail.QuotePage ||
    this is FinanceDetail.IndicatorPage ||
    this is FinanceDetail.WalletPage

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

    /** The balances typed into the sheet by hand, how old each is, and typing a new one in. */
    data object TypedBalances : FinanceDetail

    /** A part of the Wallet on a page of its own; [focus] is a section a checkup link asked for. */
    data class WalletPage(val topic: WalletTopic, val focus: WalletSection? = null) : FinanceDetail
}

/**
 * The finance app: an app of its own inside PercySafe, opened from the drawer. Robinhood's dark
 * look on true black — Wallet (the household's money, from the budget sheet), Budget (the month's
 * card spending against its limits), Markets (indices and the watchlist, live), Economy (inflation, the Treasury curve, rates) and Risk (the warning
 * lights for a downturn, blended into one gauge) — with quote and indicator pages pushed over
 * the tabs. Back pops a page, scrubbed under a back swipe; with no page up, Back is the drawer
 * overlay's around it, which closes the app (InnerAppOverlay). [requestedTab] turns it to a tab
 * from outside (a notification's tap), and [onShowRequestedTab] says it has.
 *
 * With no connection to the server it opens on what the device kept, behind a lock: the
 * fingerprint or face prompt comes up with it, and none of the app is composed until that is
 * passed (see `FinanceAccess`). Closing the app locks it again.
 */
@Composable
fun FinanceApp(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    requestedTab: FinanceTab? = null,
    onShowRequestedTab: () -> Unit = {},
) {
    val lockViewModel: FinanceLockViewModel = metroViewModel()
    val lock by lockViewModel.uiState.collectAsStateWithLifecycle()
    // Once the app has gone from the screen, not as it starts to close: the lock must not flash up
    // through the overlay as it drains away.
    DisposableEffect(lockViewModel) { onDispose { lockViewModel.lock() } }
    // A question asked over the app goes with a picture of it, so the assistant takes it back when the app locks.
    val assistant = LocalAssistant.current
    DisposableEffect(assistant, lockViewModel) {
        val release = assistant?.showsBehind(lockViewModel.locked)
        onDispose { release?.invoke() }
    }
    if (lock.locked) {
        val fontFamily = albertSansFontFamily()
        val palette = remember { FinancePalette() }
        val type = remember(fontFamily) { FinanceTypography(fontFamily) }
        // The prompt comes up with the lock rather than waiting for a tap; put away, the button asks again.
        LaunchedEffect(lockViewModel) { lockViewModel.unlock() }
        CompositionLocalProvider(LocalFinancePalette provides palette, LocalFinanceTypography provides type) {
            FinanceLockScreen(lock, onUnlock = lockViewModel::unlock, onClose = onClose, modifier = modifier)
        }
    } else {
        FinanceAppContent(onClose, active, requestedTab, onShowRequestedTab, offline = lock.offline, modifier = modifier)
    }
}

/** The app itself, once it may be shown. [offline] says so under the title: what is up is what the device kept. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun FinanceAppContent(
    onClose: () -> Unit,
    active: Boolean,
    requestedTab: FinanceTab?,
    onShowRequestedTab: () -> Unit,
    offline: Boolean,
    modifier: Modifier = Modifier,
) {
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
    // Here rather than in the bars, which go while a page is open.
    AssistantScreenContext { "Finance app, ${tab.name.lowercase()} tab" + (details.lastOrNull()?.let { ", $it page open" } ?: "") }
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
    // A typed balance being updated from an account or debt row, by its cell id.
    var editingBalance by rememberSaveable { mutableStateOf<String?>(null) }
    val openBalanceEditor: (String) -> Unit = remember { { id -> editingBalance = id } }
    fun push(detail: FinanceDetail) {
        if (details.lastOrNull() != detail) details += detail
    }
    // A notification's tap asked for a tab (a budget alert's: Budget): whatever page or sheet was up gives way to it.
    val requestShown by rememberUpdatedState(onShowRequestedTab)
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            details.clear()
            explaining = null
            addingSymbol = false
            editingBalance = null
            tab = requestedTab
            requestShown()
        }
    }

    // Only while a page is up: with none, Back leaves the app, which the overlay around it drains
    // predictively (InnerAppOverlay). Off while the app animates closed ([active] false), so that
    // Back reaches what's beneath.
    val back = rememberPredictiveBack(enabled = active && details.isNotEmpty()) {
        if (details.isNotEmpty()) details.removeAt(details.lastIndex)
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
        LocalBalanceEditor provides openBalanceEditor,
        LocalChartStyle provides chartStyle,
        LocalEconomyTone provides state.tone,
    ) {
        val status = WindowInsets.statusBars.asPaddingValues()
        val nav = WindowInsets.navigationBars.asPaddingValues()
        // On a phone on its side the tabs' nav stands at the start edge instead of floating over
        // the foot of the page (as the camera shell's does; see showsNavRail there), and the tabs'
        // pages start clear of it.
        val sideNav = isCompactLandscape()
        // Beside the shell's sidebar the tabs are listed in it, under Finance's row, and the app
        // draws no nav of its own (see publishAppSections).
        val inSidebar = publishAppSections(FINANCE_SECTIONS, selected = if (details.isEmpty()) FinanceTab.entries.indexOf(tab) else -1) { index ->
            val picked = FinanceTab.entries[index]
            if (details.isEmpty() && picked == tab) scope.launch { listStates.getValue(picked).animateScrollToItem(0) }
            details.clear()
            tab = picked
        }
        BoxWithConstraints(modifier.fillMaxSize().background(palette.background).testTag("finance_app")) {
            // A wide window (a desktop's, a tablet's on its side) lays the tabs out as dashboards
            // (see FinanceDesk), centred at a dashboard's width; the pages that are a form or a list
            // to read down stay centred at a readable width (see appPageGutter).
            val desk = !sideNav && maxWidth >= DESK_MIN_WIDTH
            val gutter = appPageGutter(maxWidth, reserved = if (sideNav) FINANCE_SIDE_NAV_CLEARANCE else 0.dp)
            val deskGutter = appPageGutter(maxWidth, reserved = DESK_MARGIN * 2, maxWidth = DESK_MAX_WIDTH) + DESK_MARGIN
            val top = status.calculateTopPadding() + if (desk) 68.dp else 60.dp
            val padding = if (desk) {
                // Clear of the floating tabs where they still show: an iPad's host lists no app sections in a sidebar.
                PaddingValues(start = deskGutter, end = deskGutter, top = top, bottom = nav.calculateBottomPadding() + if (inSidebar) 40.dp else 104.dp)
            } else {
                PaddingValues(
                    start = (if (sideNav) FINANCE_SIDE_NAV_CLEARANCE else 0.dp) + gutter,
                    end = gutter,
                    top = top,
                    bottom = nav.calculateBottomPadding() + if (sideNav || inSidebar) 32.dp else 104.dp,
                )
            }
            val detailPadding = PaddingValues(start = gutter, end = gutter, top = top, bottom = nav.calculateBottomPadding() + 32.dp)
            val deskDetailPadding = if (desk) padding else detailPadding
            val pullState = rememberPullToRefreshState()

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
                val previous: Any? = if (details.isEmpty()) null else details.getOrNull(details.lastIndex - 1) ?: tab
                // Which way the stack last moved, so a page given back leaves the way it came; outside
                // the snapshot system since it only steers an animation.
                val depth = remember { intArrayOf(details.size, 1) }
                if (details.size != depth[0]) {
                    depth[1] = if (details.size > depth[0]) 1 else -1
                    depth[0] = details.size
                }
                // The pages end at the keyboard while it is up, so the field being typed in scrolls up above
                // it; the nav stays put, under the keyboard.
                rememberPredictiveBackTransition(target, previous, back, label = "financePage").AnimatedContent(
                    modifier = Modifier.imePadding(),
                    transitionSpec = { financeTransition(initialState, targetState, pop = back.inProgress || depth[1] < 0) },
                    contentKey = { it },
                ) { page ->
                    // A dashboard keeps its margin itself, so what stands on its page (a setup card, the sync line) lines up with its panels.
                    val dashboard = desk && page.isDashboard()
                    CompositionLocalProvider(LocalFinanceDesk provides dashboard, LocalPageGutter provides if (dashboard) 0.dp else LocalPageGutter.current) {
                        when (page) {
                            FinanceTab.WALLET -> WalletScreen(
                                state,
                                listStates.getValue(FinanceTab.WALLET),
                                padding,
                                onRetrySheet = viewModel::retrySheet,
                                onOpenSync = { push(FinanceDetail.SheetSync) },
                                onOpenTopic = { topic, focus -> push(FinanceDetail.WalletPage(topic, focus)) },
                                budget = budgetState.budget,
                                onOpenBudget = { tab = FinanceTab.BUDGET },
                                onOpenBalances = { push(FinanceDetail.TypedBalances) },
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
                                deskDetailPadding,
                                viewModel::requestHistory,
                                onFollow = { symbol ->
                                    val meta = state.meta(symbol)
                                    viewModel.addSymbol(SymbolMatch(symbol, state.nameToSave(symbol), meta.kind, typeLabel = "", exchange = null))
                                },
                                onUnfollow = viewModel::removeSymbol,
                                onSetPosition = viewModel::setPosition,
                            )

                            is FinanceDetail.IndicatorPage -> IndicatorDetailScreen(page.id, state, deskDetailPadding)

                            FinanceDetail.Connections -> ConnectionsScreen(state, detailPadding)

                            FinanceDetail.Glossary -> GlossaryScreen(detailPadding)

                            FinanceDetail.SheetSync -> SheetSyncScreen(state, detailPadding, onSyncNow = viewModel::refresh, onOpenBankSync = { push(FinanceDetail.BankSync) })

                            FinanceDetail.BankSync -> BankSyncRoute(detailPadding)

                            FinanceDetail.BudgetSettings -> BudgetSettingsRoute(state.finance, detailPadding)

                            FinanceDetail.ChartSettings -> ChartSettingsScreen(chartStyle, detailPadding, onChange = chartStyleViewModel::update)

                            is FinanceDetail.WalletPage -> WalletTopicScreen(page.topic, page.focus, state, deskDetailPadding)

                            FinanceDetail.TypedBalances -> TypedBalancesScreen(state, detailPadding, onSave = viewModel::updateBalance, onClearSave = viewModel::clearBalanceSave)
                        }
                    }
                }
            }

            FinanceTopBar(
                title = details.lastOrNull()?.let { titleOf(it) } ?: stringResource(tab.label),
                isDetail = details.isNotEmpty(),
                refreshing = state.refreshing,
                offline = offline,
                onBack = { if (details.isNotEmpty()) details.removeAt(details.lastIndex) else onClose() },
                closable = !inSidebar,
                onRefresh = viewModel::refresh,
                onGlossary = { push(FinanceDetail.Glossary) },
                onChartSettings = { push(FinanceDetail.ChartSettings) },
                desk = desk,
                inset = if (desk) deskGutter else gutter,
            )

            // The first-openings nudge, floating above the tabs' nav until it's put away.
            ExplainTip(
                visible = state.explainTipVisible && details.isEmpty(),
                onDismiss = viewModel::dismissExplainTip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // Above the nav when it floats along the bottom; beside it when it is at the side.
                    .padding(start = (if (sideNav) FINANCE_SIDE_NAV_CLEARANCE else 0.dp) + gutter, end = gutter, bottom = if (sideNav || inSidebar) 16.dp else 84.dp)
                    .widthIn(max = APP_PAGE_MAX_WIDTH),
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

            editingBalance?.let { id ->
                BalanceEditorFor(id, state, viewModel::updateBalance, viewModel::clearBalanceSave, onDismiss = { editingBalance = null })
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
                visible = details.isEmpty() && !sideNav && !inSidebar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            ) {
                FinanceBottomNav(tab, onPickTab)
            }
            AnimatedVisibility(
                visible = details.isEmpty() && sideNav && !inSidebar,
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
    FinanceDetail.TypedBalances -> stringResource(Res.string.fin_balance_title)
    is FinanceDetail.WalletPage -> stringResource(detail.topic.title)
}

/**
 * Pages pushed over the tabs slide in from the right and back out; tabs hand over sideways in
 * the direction of the tab tapped, with a quick fade, the way the camera shell's tabs do. [pop]
 * is a page being given back (or a back swipe scrubbing that), which leaves the way it came even
 * onto another page.
 */
private fun financeTransition(from: Any, to: Any, pop: Boolean): ContentTransform {
    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
    return when {
        from is FinanceDetail && (pop || to !is FinanceDetail) ->
            (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))

        to is FinanceDetail ->
            (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))

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
    offline: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGlossary: () -> Unit,
    onChartSettings: () -> Unit,
    closable: Boolean = true,
    desk: Boolean = false,
    inset: Dp = 0.dp,
) {
    if (desk) {
        FinanceDeskBar(title, isDetail, refreshing, offline, onBack, onRefresh, onGlossary, onChartSettings, closable, inset)
        return
    }
    val colors = FinanceTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            // Fades from black so content scrolling under it slips away rather than cutting off.
            .background(Brush.verticalGradient(0f to colors.background, 0.75f to colors.background.copy(alpha = 0.92f), 1f to Color.Transparent))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Beside the shell's sidebar there is nothing to close: the sidebar is the way out, so only Back is offered.
        if (isDetail || closable) {
            val label = stringResource(if (isDetail) Res.string.common_back else Res.string.finance_close)
            WithTooltip(label, modifier = Modifier.align(Alignment.CenterStart)) {
                IconButton(onClick = onBack, modifier = Modifier.handCursor().testTag("finance_back")) {
                    AnimatedContent(isDetail, label = "backIcon") { detail ->
                        Icon(if (detail) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, contentDescription = label, tint = colors.textPrimary)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(title, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "title") { t ->
                Text(t, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
            }
            // Short enough to sit between the buttons at either end, and within their height.
            if (offline) Text(stringResource(Res.string.finance_offline_note), style = FinanceTheme.type.micro, color = colors.watch, maxLines = 1, modifier = Modifier.testTag("finance_offline"))
        }
        // On the tabs only: their titles are short enough to leave room for a third button.
        if (!isDetail) {
            val chartSettings = stringResource(Res.string.finance_title_chart_settings)
            WithTooltip(chartSettings, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 88.dp)) {
                IconButton(onClick = onChartSettings, modifier = Modifier.handCursor().testTag("finance_chart_settings")) {
                    Icon(Icons.Filled.Tune, contentDescription = chartSettings, tint = colors.textSecondary)
                }
            }
        }
        val glossary = stringResource(Res.string.finance_title_glossary)
        WithTooltip(glossary, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 44.dp)) {
            IconButton(onClick = onGlossary, modifier = Modifier.handCursor().testTag("finance_glossary")) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = glossary, tint = colors.textSecondary)
            }
        }
        RefreshButton(refreshing, onRefresh, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * [FinanceTopBar] on a desk: a toolbar over the dashboard, its title at the dashboard's start edge
 * in the large type a window's page has, and the buttons at its end edge, all [inset] from the
 * window's sides as the dashboard is.
 */
@Composable
private fun FinanceDeskBar(
    title: String,
    isDetail: Boolean,
    refreshing: Boolean,
    offline: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGlossary: () -> Unit,
    onChartSettings: () -> Unit,
    closable: Boolean,
    inset: Dp,
) {
    val colors = FinanceTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to colors.background, 0.8f to colors.background.copy(alpha = 0.94f), 1f to Color.Transparent))
            .statusBarsPadding()
            // The buttons' own padding brings their icons to the dashboard's edges.
            .padding(start = (inset - 12.dp).coerceAtLeast(8.dp), end = (inset - 12.dp).coerceAtLeast(8.dp), top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isDetail || closable) {
            val label = stringResource(if (isDetail) Res.string.common_back else Res.string.finance_close)
            WithTooltip(label) {
                IconButton(onClick = onBack, modifier = Modifier.handCursor().testTag("finance_back")) {
                    AnimatedContent(isDetail, label = "backIcon") { detail ->
                        Icon(if (detail) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, contentDescription = label, tint = colors.textPrimary)
                    }
                }
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            AnimatedContent(title, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "title") { t ->
                Text(t, style = FinanceTheme.type.title, color = colors.textPrimary, maxLines = 1)
            }
            if (offline) Text(stringResource(Res.string.finance_offline_note), style = FinanceTheme.type.micro, color = colors.watch, maxLines = 1, modifier = Modifier.testTag("finance_offline"))
        }
        val chartSettings = stringResource(Res.string.finance_title_chart_settings)
        WithTooltip(chartSettings) {
            IconButton(onClick = onChartSettings, modifier = Modifier.handCursor().testTag("finance_chart_settings")) {
                Icon(Icons.Filled.Tune, contentDescription = chartSettings, tint = colors.textSecondary)
            }
        }
        val glossary = stringResource(Res.string.finance_title_glossary)
        WithTooltip(glossary) {
            IconButton(onClick = onGlossary, modifier = Modifier.handCursor().testTag("finance_glossary")) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = glossary, tint = colors.textSecondary)
            }
        }
        RefreshButton(refreshing, onRefresh)
    }
}

@Composable
private fun RefreshButton(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    WithTooltip(stringResource(if (refreshing) Res.string.finance_refreshing else Res.string.finance_refresh), modifier = modifier) {
        IconButton(onClick = onRefresh, modifier = Modifier.handCursor()) {
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

/** Finance's tabs as the shell's sidebar lists them, under its row (see publishAppSections). */
private val FINANCE_SECTIONS = FinanceTab.entries.map { AppSection(it.label, it.icon) }

@Composable
private fun FinanceBottomNav(selected: FinanceTab, onSelect: (FinanceTab) -> Unit) {
    val colors = FinanceTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth(0.92f)
            // Held, as the shell's own bar is, it opens the assistant.
            .assistantTrigger(LocalAssistant.current)
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
            .assistantTrigger(LocalAssistant.current)
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

@Preview(name = "Desk · the app's toolbar over the Wallet", widthDp = 1200, heightDp = 900)
@Composable
private fun FinanceDeskBarPreview() {
    FinanceDeskStage {
        WalletScreen(
            FinanceFixtures.state,
            rememberLazyListState(),
            PaddingValues(start = DESK_MARGIN, end = DESK_MARGIN, top = 68.dp, bottom = 32.dp),
            onRetrySheet = {},
            budget = BudgetFixtures.month(),
        )
        FinanceTopBar(
            title = stringResource(FinanceTab.WALLET.label),
            isDetail = false,
            refreshing = false,
            offline = false,
            onBack = {},
            onRefresh = {},
            onGlossary = {},
            onChartSettings = {},
            closable = false,
            desk = true,
            inset = DESK_MARGIN,
        )
    }
}
