package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.Account
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.HomeEquity
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.MortgagePlan
import com.meticulouscreations.homesafe.finance.domain.Owner
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.components.AuroraBackground
import com.meticulouscreations.homesafe.finance.ui.components.Bar
import com.meticulouscreations.homesafe.finance.ui.components.BarChart
import com.meticulouscreations.homesafe.finance.ui.components.CascadeIn
import com.meticulouscreations.homesafe.finance.ui.components.ChartAxis
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.DonutChart
import com.meticulouscreations.homesafe.finance.ui.components.DonutSlice
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.finance.ui.components.Meter
import com.meticulouscreations.homesafe.finance.ui.components.RangeSelector
import com.meticulouscreations.homesafe.finance.ui.components.RollingNumber
import com.meticulouscreations.homesafe.finance.ui.components.Shimmer
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.finance_change_at
import homesafe.shared.generated.resources.finance_change_since
import homesafe.shared.generated.resources.finance_change_with_percent
import homesafe.shared.generated.resources.finance_range_all
import homesafe.shared.generated.resources.finance_wallet_accounts_title
import homesafe.shared.generated.resources.finance_wallet_affordability_fits
import homesafe.shared.generated.resources.finance_wallet_affordability_over
import homesafe.shared.generated.resources.finance_wallet_affordability_title
import homesafe.shared.generated.resources.finance_wallet_after_tax
import homesafe.shared.generated.resources.finance_wallet_allocation_subtitle
import homesafe.shared.generated.resources.finance_wallet_allocation_title
import homesafe.shared.generated.resources.finance_wallet_allocation_total
import homesafe.shared.generated.resources.finance_wallet_apr
import homesafe.shared.generated.resources.finance_wallet_before_tax
import homesafe.shared.generated.resources.finance_wallet_bought_for
import homesafe.shared.generated.resources.finance_wallet_caption_cash_flow
import homesafe.shared.generated.resources.finance_wallet_cash_flow_title
import homesafe.shared.generated.resources.finance_wallet_cash_received
import homesafe.shared.generated.resources.finance_wallet_chart_description
import homesafe.shared.generated.resources.finance_wallet_charts_subtitle
import homesafe.shared.generated.resources.finance_wallet_charts_title
import homesafe.shared.generated.resources.finance_wallet_debt_excl_mortgage
import homesafe.shared.generated.resources.finance_wallet_debt_fine_print
import homesafe.shared.generated.resources.finance_wallet_debt_history
import homesafe.shared.generated.resources.finance_wallet_debt_secured
import homesafe.shared.generated.resources.finance_wallet_debt_title
import homesafe.shared.generated.resources.finance_wallet_down_payment
import homesafe.shared.generated.resources.finance_wallet_earned
import homesafe.shared.generated.resources.finance_wallet_effective_rate
import homesafe.shared.generated.resources.finance_wallet_equity
import homesafe.shared.generated.resources.finance_wallet_explain_card
import homesafe.shared.generated.resources.finance_wallet_flow_history_selected
import homesafe.shared.generated.resources.finance_wallet_flow_history_subtitle
import homesafe.shared.generated.resources.finance_wallet_flow_history_title
import homesafe.shared.generated.resources.finance_wallet_flow_left_over
import homesafe.shared.generated.resources.finance_wallet_hero_how_to_read
import homesafe.shared.generated.resources.finance_wallet_home_owned
import homesafe.shared.generated.resources.finance_wallet_home_title
import homesafe.shared.generated.resources.finance_wallet_home_value
import homesafe.shared.generated.resources.finance_wallet_improvements
import homesafe.shared.generated.resources.finance_wallet_invested
import homesafe.shared.generated.resources.finance_wallet_metric_assets
import homesafe.shared.generated.resources.finance_wallet_metric_cash_flow
import homesafe.shared.generated.resources.finance_wallet_metric_debt
import homesafe.shared.generated.resources.finance_wallet_monthly_payment
import homesafe.shared.generated.resources.finance_wallet_mortgage_subtitle
import homesafe.shared.generated.resources.finance_wallet_mortgage_title
import homesafe.shared.generated.resources.finance_wallet_not_enough_history
import homesafe.shared.generated.resources.finance_wallet_of_take_home
import homesafe.shared.generated.resources.finance_wallet_old_house_title
import homesafe.shared.generated.resources.finance_wallet_open_sheet
import homesafe.shared.generated.resources.finance_wallet_owed
import homesafe.shared.generated.resources.finance_wallet_owner_all
import homesafe.shared.generated.resources.finance_wallet_owner_chip
import homesafe.shared.generated.resources.finance_wallet_paid_off
import homesafe.shared.generated.resources.finance_wallet_paid_out
import homesafe.shared.generated.resources.finance_wallet_payment_parts
import homesafe.shared.generated.resources.finance_wallet_payment_parts_hoa
import homesafe.shared.generated.resources.finance_wallet_per_month
import homesafe.shared.generated.resources.finance_wallet_price
import homesafe.shared.generated.resources.finance_wallet_quick_debt
import homesafe.shared.generated.resources.finance_wallet_quick_left_over
import homesafe.shared.generated.resources.finance_wallet_quick_left_over_rate
import homesafe.shared.generated.resources.finance_wallet_quick_net_worth
import homesafe.shared.generated.resources.finance_wallet_quick_runway
import homesafe.shared.generated.resources.finance_wallet_quick_runway_months
import homesafe.shared.generated.resources.finance_wallet_quick_spending
import homesafe.shared.generated.resources.finance_wallet_quick_take_home
import homesafe.shared.generated.resources.finance_wallet_rate
import homesafe.shared.generated.resources.finance_wallet_return
import homesafe.shared.generated.resources.finance_wallet_share_of_income
import homesafe.shared.generated.resources.finance_wallet_short_year
import homesafe.shared.generated.resources.finance_wallet_show_all
import homesafe.shared.generated.resources.finance_wallet_show_less
import homesafe.shared.generated.resources.finance_wallet_sold_for
import homesafe.shared.generated.resources.finance_wallet_synced_from
import homesafe.shared.generated.resources.finance_wallet_take_home
import homesafe.shared.generated.resources.finance_wallet_take_home_amount
import homesafe.shared.generated.resources.finance_wallet_taxes
import homesafe.shared.generated.resources.finance_wallet_taxes_subtitle
import homesafe.shared.generated.resources.finance_wallet_taxes_title
import homesafe.shared.generated.resources.finance_wallet_total_profit
import homesafe.shared.generated.resources.finance_wallet_use_market_rate
import homesafe.shared.generated.resources.finance_wallet_value_added
import homesafe.shared.generated.resources.finance_wallet_vesting_title
import homesafe.shared.generated.resources.finance_wallet_watchlist_title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * What the Wallet's big chart plots. There's no net-worth line: the sheet's history counted a
 * past house's mortgage in "Debt" without counting the house in "Total Assets", so assets less
 * debt swings by a house's worth across the sale. Total assets is the series the sheet keeps
 * consistently; today's net worth leads the cards under the chart instead.
 */
private enum class WalletMetric(val label: StringResource) {
    ASSETS(Res.string.finance_wallet_metric_assets),
    DEBT(Res.string.finance_wallet_metric_debt),
    CASH_FLOW(Res.string.finance_wallet_metric_cash_flow),
}

private val WalletRanges = listOf(EconRange.Y1, EconRange.Y3, EconRange.Y5, EconRange.MAX)

@Composable
internal fun WalletScreen(
    state: FinanceUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onOpenQuote: (String) -> Unit,
    onRetrySheet: () -> Unit,
    onOpenSync: () -> Unit = {},
    onAddSymbol: (() -> Unit)? = null,
    budget: Budget? = null,
    onOpenBudget: () -> Unit = {},
) {
    val finance = state.finance
    val fedRate = state.readings[IndicatorCatalog.fedFunds.id]?.latest
    val checkup = remember(finance, fedRate) { finance?.let { moneyCheckup(it, fedRate) } }
    // The checkup's breakdown sheet: open or not, and on which page (null: how it's scored).
    var checkupOpen by rememberSaveable { mutableStateOf(false) }
    var checkupPage by rememberSaveable { mutableStateOf<CheckKind?>(null) }
    // A section the checkup just jumped to, lit up briefly so the eye lands on it.
    var flash by remember { mutableStateOf<WalletSection?>(null) }
    val scope = rememberCoroutineScope()
    LazyColumn(state = listState, contentPadding = contentPadding) {
        if (finance != null) item(key = "sync") { SheetSyncLine(state, onOpenSync, Modifier.padding(top = 4.dp)) }
        when {
            finance != null && checkup != null -> walletItems(
                finance,
                state,
                checkup,
                flash,
                budget,
                onOpenBudget,
                onOpenCheckup = { page ->
                    checkupPage = page
                    checkupOpen = true
                },
            )

            state.financeLoading -> item(key = "loading") { WalletSkeleton() }

            else -> item(key = "setup") { state.sheetIssue?.let { SheetSetupCard(it, onRetrySheet) } }
        }
        item(key = "watch-h") {
            SectionHeader(
                stringResource(Res.string.finance_wallet_watchlist_title),
                subtitle = stringResource(watchlistSubtitle(state)),
                action = onAddSymbol?.let { add -> { AddSymbolButton(add) } },
            )
        }
        watchlistItems(state, "w", onOpenQuote)
        if (finance != null) item(key = "source") { SourceFooter(finance) }
    }
    if (checkupOpen && checkup != null) {
        CheckupSheet(
            checkup,
            page = checkupPage,
            onPage = { checkupPage = it },
            onDismiss = { checkupOpen = false },
            onJump = { section ->
                checkupOpen = false
                scope.launch {
                    listState.scrollToKey(section.key)
                    flash = section
                    delay(1_400)
                    if (flash == section) flash = null
                }
            },
        )
    }
}

/**
 * Brings the item with [key] to the top of the list. The list only knows the keys of what it has
 * laid out, so it glides down a screen at a time until the item is among them (or the list ends).
 */
private suspend fun LazyListState.scrollToKey(key: Any) {
    repeat(40) {
        layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let {
            animateScrollToItem(it.index)
            return
        }
        val step = layoutInfo.viewportSize.height * 0.85f
        if (step <= 0f || animateScrollBy(step, tween(220, easing = LinearEasing)) == 0f) return
    }
}

/** A section header that glows for a moment when the checkup jumps to it. */
@Composable
private fun Flashable(lit: Boolean, content: @Composable () -> Unit) {
    val glow by animateFloatAsState(if (lit) 0.16f else 0f, tween(if (lit) 220 else 900), label = "sectionFlash")
    val accent = FinanceTheme.colors.accent
    Box(Modifier.fillMaxWidth().drawBehind { drawRect(accent.copy(alpha = glow)) }) { content() }
}

private fun LazyListScope.walletItems(
    finance: PersonalFinance,
    state: FinanceUiState,
    checkup: Checkup,
    flash: WalletSection?,
    budget: Budget?,
    onOpenBudget: () -> Unit,
    onOpenCheckup: (CheckKind?) -> Unit,
) {
    item(key = "hero") { NetWorthHero(finance) }
    item(key = "quick") { QuickStats(finance) }
    item(key = "checkup") { CascadeIn(1) { MoneyCheckup(checkup, onOpenCheckup, Modifier.padding(top = 16.dp)) } }
    if (finance.charts.isNotEmpty()) {
        item(key = "charts-h") { SectionHeader(stringResource(Res.string.finance_wallet_charts_title), subtitle = stringResource(Res.string.finance_wallet_charts_subtitle, finance.title)) }
        items(finance.charts.size, key = { "chart-${finance.charts[it].id}" }) { i ->
            CascadeIn(i) { SheetChartCard(finance.charts[i], Modifier.padding(bottom = 12.dp)) }
        }
    }
    if (finance.accounts.isNotEmpty()) {
        item(key = WalletSection.ACCOUNTS.key) { Flashable(flash == WalletSection.ACCOUNTS) { SectionHeader(stringResource(Res.string.finance_wallet_accounts_title), trailing = finance.totalAssets?.let(FinanceFormat::compactMoney), info = "totalassets") } }
        item(key = "acct") { CascadeIn(0) { AccountsBlock(finance) } }
        item(key = WalletSection.ALLOCATION.key) {
            Flashable(flash == WalletSection.ALLOCATION) {
                SectionHeader(stringResource(Res.string.finance_wallet_allocation_title), subtitle = stringResource(Res.string.finance_wallet_allocation_subtitle), info = "allocation")
            }
        }
        item(key = "alloc") { CascadeIn(1) { AllocationBlock(finance.accounts) } }
    }
    if (finance.expenses.isNotEmpty()) {
        item(key = WalletSection.CASH_FLOW.key) {
            Flashable(flash == WalletSection.CASH_FLOW) {
                SectionHeader(
                    stringResource(Res.string.finance_wallet_cash_flow_title),
                    trailing = finance.netMonthly?.let { stringResource(Res.string.finance_wallet_per_month, FinanceFormat.signedMoney(it, 0)) },
                    info = "cashflow",
                )
            }
        }
        item(key = "flow") { CascadeIn(2) { CashFlowBlock(finance) } }
    }
    // The plan above; what the month is actually costing is the Budget tab's, one line of it here.
    if (budget != null && budget.isCurrentMonth && budget.budgetCards.isNotEmpty()) {
        item(key = "budget") { BudgetTeaser(budget, onOpenBudget, Modifier.padding(top = 12.dp)) }
    }
    val flowHistory = finance.history.filter { it.monthlyExpenses != null && it.monthlyIncome != null }.takeLast(12)
    if (flowHistory.size > 2) {
        item(key = "flowhist-h") {
            SectionHeader(
                stringResource(Res.string.finance_wallet_flow_history_title),
                subtitle = stringResource(Res.string.finance_wallet_flow_history_subtitle),
                info = "savingsrate",
            )
        }
        item(key = "flowhist") {
            CascadeIn(3) {
                val colors = FinanceTheme.colors
                var sel by remember { mutableStateOf<Int?>(null) }
                Column {
                    val s = sel?.let { flowHistory.getOrNull(it) } ?: flowHistory.last()
                    Text(
                        stringResource(
                            Res.string.finance_wallet_flow_history_selected,
                            FinanceFormat.monthYear(s.epochSeconds).resolve(),
                            FinanceFormat.money(s.monthlyIncome!!, 0),
                            FinanceFormat.money(s.monthlyExpenses!!, 0),
                        ),
                        style = FinanceTheme.type.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = PageGutter),
                    )
                    Spacer(Modifier.height(8.dp))
                    BarChart(
                        bars = flowHistory.map { snap ->
                            Bar(
                                label = FinanceFormat.shortYear(snap.epochSeconds).resolve(),
                                value = snap.monthlyExpenses!!,
                                color = colors.loss,
                                secondary = snap.monthlyIncome,
                                secondaryColor = colors.gain.copy(alpha = 0.28f),
                            )
                        },
                        selected = sel,
                        onSelect = { sel = if (sel == it) null else it },
                        modifier = Modifier.fillMaxWidth().height(170.dp).padding(horizontal = PageGutter),
                    )
                }
            }
        }
    }
    if (finance.debts.isNotEmpty()) {
        item(key = WalletSection.DEBT.key) {
            Flashable(flash == WalletSection.DEBT) {
                SectionHeader(
                    stringResource(Res.string.finance_wallet_debt_title),
                    trailing = stringResource(Res.string.finance_wallet_debt_excl_mortgage, FinanceFormat.compactMoney(finance.consumerDebt)),
                    info = "debt",
                )
            }
        }
        item(key = "debt") { CascadeIn(4) { DebtBlock(finance) } }
    }
    finance.home?.let { home ->
        item(key = "home-h") { SectionHeader(stringResource(Res.string.finance_wallet_home_title), info = "homeequity") }
        item(key = "home") { CascadeIn(5) { HomeBlock(home, finance.mortgageBalance) } }
    }
    finance.mortgagePlan?.let { plan ->
        item(key = "mort-h") {
            SectionHeader(
                stringResource(Res.string.finance_wallet_mortgage_title),
                subtitle = stringResource(Res.string.finance_wallet_mortgage_subtitle),
                info = "mortgageplanner",
            )
        }
        item(key = "mort") { CascadeIn(6) { MortgagePlanner(plan, state.readings[IndicatorCatalog.mortgage.id]?.latest) } }
    }
    if (finance.vesting.isNotEmpty()) {
        item(key = "vest-h") {
            SectionHeader(
                stringResource(Res.string.finance_wallet_vesting_title),
                trailing = stringResource(Res.string.finance_wallet_after_tax, FinanceFormat.compactMoney(finance.vesting.sumOf { it.postTax ?: it.amount })),
                info = "vesting",
            )
        }
        item(key = "vest") { CascadeIn(7) { VestingBlock(finance) } }
    }
    if (finance.taxYears.isNotEmpty()) {
        item(key = "tax-h") { SectionHeader(stringResource(Res.string.finance_wallet_taxes_title), subtitle = stringResource(Res.string.finance_wallet_taxes_subtitle), info = "taxes") }
        item(key = "tax") { CascadeIn(8) { TaxBlock(finance) } }
    }
    finance.oldHouse?.takeIf { it.soldPrice != null }?.let { sale ->
        item(key = "old-h") { SectionHeader(stringResource(Res.string.finance_wallet_old_house_title)) }
        item(key = "old") {
            FinanceCard {
                StatPairRow(
                    stringResource(Res.string.finance_wallet_bought_for),
                    sale.purchasePrice?.let { FinanceFormat.money(it, 0) } ?: "—",
                    stringResource(Res.string.finance_wallet_sold_for),
                    FinanceFormat.money(sale.soldPrice!!, 0),
                )
                StatPairRow(
                    stringResource(Res.string.finance_wallet_total_profit),
                    sale.profit?.let { FinanceFormat.signedMoney(it, 0) } ?: "—",
                    stringResource(Res.string.finance_wallet_return),
                    sale.roi?.let { FinanceFormat.fractionPercent(it) } ?: "—",
                    rightColor = FinanceTheme.colors.gain,
                )
                sale.cashReceived?.let { StatPairRow(stringResource(Res.string.finance_wallet_cash_received), FinanceFormat.money(it, 0), "", "") }
            }
        }
    }
}

/** The headline: total assets over the sheet's history, scrubbable, with the metric and range chosen below it. */
@Composable
private fun NetWorthHero(finance: PersonalFinance) {
    val colors = FinanceTheme.colors
    val dates = rememberFinanceDates()
    var metric by rememberSaveable { mutableStateOf(WalletMetric.ASSETS) }
    var range by rememberSaveable { mutableStateOf(EconRange.MAX) }
    var scrub by remember(metric, range) { mutableStateOf<Int?>(null) }

    val full = remember(finance, metric) { metricSeries(finance, metric) }
    val series = remember(full, range) { full.within(range) }
    val current = when (metric) {
        WalletMetric.ASSETS -> finance.totalAssets
        WalletMetric.DEBT -> finance.consumerDebt
        WalletMetric.CASH_FLOW -> finance.netMonthly
    } ?: series.lastValue
    val i = scrub?.takeIf { it in 0 until series.size }
    val shown = i?.let { series.values[it] } ?: current
    val start = series.firstValue
    val change = if (shown != null && start != null) shown - start else null
    val goodWhenUp = metric != WalletMetric.DEBT
    val color = when {
        change == null -> colors.textSecondary
        (change >= 0) == goodWhenUp -> colors.gain
        else -> colors.loss
    }
    val pct = if (change != null && start != null && start != 0.0) change / abs(start) * 100 else null
    val changeText = if (change == null) {
        " "
    } else {
        val amount = FinanceFormat.signedMoney(change, 0)
        val withPct = pct?.let { stringResource(Res.string.finance_change_with_percent, amount, FinanceFormat.signedPercent(it)) } ?: amount
        if (i != null) {
            stringResource(Res.string.finance_change_at, withPct, FinanceFormat.monthYear(series.times[i]).resolve())
        } else {
            stringResource(Res.string.finance_change_since, withPct, series.times.firstOrNull()?.let { FinanceFormat.monthYear(it).resolve() } ?: "")
        }
    }
    Box(Modifier.fillMaxWidth()) {
        AuroraBackground(color, Modifier.matchParentSize(), secondary = colors.accent)
        Column(Modifier.padding(top = 12.dp)) {
            HeroNumber(
                caption = stringResource(if (metric == WalletMetric.CASH_FLOW) Res.string.finance_wallet_caption_cash_flow else metric.label),
                value = shown?.let { FinanceFormat.money(it, 0) } ?: "—",
                change = changeText,
                changeColor = color,
                trailing = {
                    InfoButton(
                        when (metric) {
                            WalletMetric.ASSETS -> "totalassets"
                            WalletMetric.DEBT -> "debt"
                            WalletMetric.CASH_FLOW -> "cashflow"
                        },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            if (series.size > 1) {
                LineChart(
                    lines = listOf(ChartLine(series, color, fill = true, width = 3f)),
                    timeAxis = true,
                    axis = ChartAxis({ FinanceFormat.compactMoney(it) }, { dates.monthYear(it) }),
                    contentDescription = stringResource(Res.string.finance_wallet_chart_description, stringResource(metric.label)),
                    onScrub = { scrub = it },
                    modifier = Modifier.fillMaxWidth().height(230.dp),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.finance_wallet_not_enough_history), style = FinanceTheme.type.label, color = colors.textSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            RangeSelector(WalletRanges, range, { stringResource(if (it == EconRange.MAX) Res.string.finance_range_all else it.label) }, color, { range = it }, Modifier.padding(horizontal = PageGutter - 4.dp))
            Spacer(Modifier.height(10.dp))
            ChipRow(WalletMetric.entries, metric, { stringResource(it.label) }, color, { metric = it })
            HowToRead(stringResource(Res.string.finance_wallet_hero_how_to_read))
        }
    }
}

/** [metric] over the sheet's snapshots, with today's figures as the last point when they're newer. */
private fun metricSeries(finance: PersonalFinance, metric: WalletMetric): Series {
    val points = finance.history.mapNotNull { s ->
        val v = when (metric) {
            WalletMetric.ASSETS -> s.totalAssets
            WalletMetric.DEBT -> s.debt
            WalletMetric.CASH_FLOW -> s.monthlyProfit
        }
        v?.let { s.epochSeconds to it }
    }.toMutableList()
    val now = when (metric) {
        WalletMetric.ASSETS -> finance.totalAssets
        WalletMetric.DEBT -> finance.consumerDebt
        WalletMetric.CASH_FLOW -> finance.netMonthly
    }
    val lastT = points.lastOrNull()?.first ?: 0
    if (now != null && finance.fetchedAtEpochSeconds > lastT + Series.DAY_SECONDS) points += finance.fetchedAtEpochSeconds to now
    return Series.of(points)
}

/** A strip of cards: income, spending, what's left, runway, debt. */
@Composable
private fun QuickStats(finance: PersonalFinance) {
    val colors = FinanceTheme.colors
    val open = LocalExplainer.current
    // Each card is a figure and the explainer a tap on it opens.
    val cards = listOfNotNull(
        finance.netWorth?.let { QuickCard(stringResource(Res.string.finance_wallet_quick_net_worth), FinanceFormat.compactMoney(it), colors.accent, "networth") },
        finance.monthlyIncome?.let { QuickCard(stringResource(Res.string.finance_wallet_quick_take_home), FinanceFormat.money(it, 0), colors.gain, "cashflow") },
        finance.monthlyExpenses?.let { QuickCard(stringResource(Res.string.finance_wallet_quick_spending), FinanceFormat.money(it, 0), colors.loss, "cashflow") },
        finance.netMonthly?.let { net ->
            val label = finance.savingsRate?.let { stringResource(Res.string.finance_wallet_quick_left_over_rate, FinanceFormat.fractionPercent(it)) }
                ?: stringResource(Res.string.finance_wallet_quick_left_over)
            QuickCard(label, FinanceFormat.signedMoney(net, 0), colors.direction(net), "savingsrate")
        },
        finance.runwayMonths?.let {
            QuickCard(
                stringResource(Res.string.finance_wallet_quick_runway),
                stringResource(Res.string.finance_wallet_quick_runway_months, FinanceFormat.grouped(it, 1)),
                if (it >= 3) colors.gain else colors.watch,
                "runway",
            )
        },
        QuickCard(stringResource(Res.string.finance_wallet_quick_debt), FinanceFormat.compactMoney(finance.consumerDebt), colors.textPrimary, "debt"),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 20.dp),
    ) {
        items(cards.size) { i ->
            val (label, value, color, explainerId) = cards[i]
            val explainLabel = stringResource(Res.string.finance_wallet_explain_card, label)
            CascadeIn(i) {
                Column(
                    Modifier
                        .width(150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(FinanceTheme.colors.surface)
                        .border(1.dp, FinanceTheme.colors.hairline, RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = explainLabel) { open(explainerId) }
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = FinanceTheme.type.micro, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(13.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    RollingNumber(value, FinanceTheme.type.bodyStrong, color)
                }
            }
        }
    }
}

private data class QuickCard(val label: String, val value: String, val color: Color, val explainerId: String)

private fun categoryColor(category: AccountCategory, palette: FinancePalette): Color = palette.categorical[category.ordinal % palette.categorical.size]

/** The accounts, filtered by whose they are, each with its share of the total as a meter. */
@Composable
private fun AccountsBlock(finance: PersonalFinance) {
    val colors = FinanceTheme.colors
    val owners: List<Owner?> = listOf(null) + finance.people.map { Owner.Person(it) } + Owner.Joint
    var owner by remember { mutableStateOf<Owner?>(null) }
    val shown = finance.accounts.filter { owner == null || it.owner == owner }.sortedByDescending { it.balance }
    val total = shown.sumOf { it.balance }.takeIf { it > 0 } ?: 1.0
    Column(Modifier.animateContentSize()) {
        ChipRow(
            owners,
            owner,
            { o -> if (o == null) stringResource(Res.string.finance_wallet_owner_all) else stringResource(Res.string.finance_wallet_owner_chip, o.label.resolve(), FinanceFormat.compactMoney(finance.accountsTotal(o))) },
            colors.accent,
            { owner = it },
        )
        Spacer(Modifier.height(8.dp))
        shown.forEachIndexed { i, account ->
            AccountRow(account, account.balance / total, i)
            if (i < shown.lastIndex) Hairline(Modifier.padding(horizontal = PageGutter))
        }
    }
}

@Composable
private fun AccountRow(account: Account, share: Double, index: Int) {
    val colors = FinanceTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(account.category, colors)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(account.name.resolve(), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(account.owner.label.resolve(), stringResource(account.category.label)).joinToString(stringResource(Res.string.common_dot_separator)), style = FinanceTheme.type.label, color = colors.textSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(FinanceFormat.money(account.balance, 0), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                Text(FinanceFormat.fractionPercent(share), style = FinanceTheme.type.label, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Meter(share.toFloat(), categoryColor(account.category, colors), height = 4.dp, delayMillis = index * 40)
    }
}

/** The donut by account category; tapping a legend row lifts its slice. */
@Composable
private fun AllocationBlock(accounts: List<Account>) {
    val colors = FinanceTheme.colors
    val byCategory = accounts.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.balance } }.entries.sortedByDescending { it.value }
    val slices = byCategory.map { (cat, v) -> DonutSlice(stringResource(cat.label), v, categoryColor(cat, colors)) }
    val total = slices.sumOf { it.value }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            DonutChart(slices, Modifier.matchParentSize(), highlighted = highlighted)
            val h = highlighted?.let { slices.getOrNull(it) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(h?.label ?: stringResource(Res.string.finance_wallet_allocation_total), style = FinanceTheme.type.micro, color = colors.textSecondary)
                Text(FinanceFormat.compactMoney(h?.value ?: total), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                if (h != null) Text(FinanceFormat.fractionPercent(h.value / total), style = FinanceTheme.type.micro, color = h.color)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            slices.forEachIndexed { i, s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (highlighted == i) s.color.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { highlighted = if (highlighted == i) null else i }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                    Spacer(Modifier.width(8.dp))
                    Text(s.label, style = FinanceTheme.type.label, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(FinanceFormat.fractionPercent(s.value / total, 0), style = FinanceTheme.type.label, color = colors.textSecondary)
                }
            }
        }
    }
}

/**
 * Where each month's take-home goes: one bar the width of income, cut into the expenses in
 * proportion and what's left over at the end; then the expenses ranked, the long tail folded away.
 */
@Composable
private fun CashFlowBlock(finance: PersonalFinance) {
    val colors = FinanceTheme.colors
    val income = finance.monthlyIncome ?: finance.income.sumOf { it.monthly }
    val expenses = finance.expenses
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (income > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageGutter)
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceRaised),
            ) {
                expenses.take(7).forEachIndexed { i, e ->
                    Box(Modifier.weight((e.monthly / income).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(colors.categorical[(i + 1) % colors.categorical.size]))
                }
                val rest = expenses.drop(7).sumOf { it.monthly }
                if (rest > 0) Box(Modifier.weight((rest / income).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(colors.textTertiary))
                val left = income - expenses.sumOf { it.monthly }
                if (left > 0) Box(Modifier.weight((left / income).toFloat()).fillMaxHeight().background(colors.textPrimary))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter)) {
                Text(stringResource(Res.string.finance_wallet_take_home_amount, FinanceFormat.money(income, 0)), style = FinanceTheme.type.micro, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text(stringResource(Res.string.finance_wallet_flow_left_over), style = FinanceTheme.type.micro, color = colors.textPrimary)
            }
            if (finance.income.size > 1) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    finance.income.forEach { line ->
                        StatTile(line.person, FinanceFormat.money(line.monthly, 0), Modifier.weight(1f), note = stringResource(Res.string.finance_wallet_share_of_income, FinanceFormat.fractionPercent(line.monthly / income)))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val visible = if (expanded) expenses else expenses.take(8)
        val top = expenses.firstOrNull()?.monthly ?: 1.0
        Column(Modifier.animateContentSize()) {
            visible.forEachIndexed { i, e ->
                Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp)) {
                    Row {
                        Text(e.name, style = FinanceTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(FinanceFormat.money(e.monthly, 0), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                    }
                    Spacer(Modifier.height(5.dp))
                    Meter((e.monthly / top).toFloat(), if (i < 7) colors.categorical[(i + 1) % colors.categorical.size] else colors.textTertiary, height = 4.dp, delayMillis = i * 35)
                }
            }
        }
        if (expenses.size > 8) {
            Text(
                if (expanded) stringResource(Res.string.finance_wallet_show_less) else pluralStringResource(Res.plurals.finance_wallet_show_all, expenses.size, expenses.size),
                style = FinanceTheme.type.bodyStrong,
                color = colors.accent,
                modifier = Modifier.padding(horizontal = PageGutter, vertical = 10.dp).clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun DebtBlock(finance: PersonalFinance) {
    val colors = FinanceTheme.colors
    val dates = rememberFinanceDates()
    val debtHistory = Series.of(finance.history.mapNotNull { s -> s.debt?.let { s.epochSeconds to it } })
    Column {
        if (debtHistory.size > 2) {
            LineChart(
                lines = listOf(ChartLine(debtHistory, colors.loss, fill = true)),
                timeAxis = true,
                axis = ChartAxis({ FinanceFormat.compactMoney(it) }, { dates.monthYear(it) }),
                contentDescription = stringResource(Res.string.finance_wallet_debt_history),
                modifier = Modifier.fillMaxWidth().height(130.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        finance.debts.sortedWith(compareBy({ it.isMortgage }, { -it.balance })).forEach { debt ->
            Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(debt.name, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                    Text(
                        listOfNotNull(
                            debt.owner.label.resolve(),
                            debt.apr?.let { stringResource(Res.string.finance_wallet_apr, FinanceFormat.grouped(it, 2)) },
                            debt.note,
                            if (debt.isMortgage) stringResource(Res.string.finance_wallet_debt_secured) else null,
                        ).joinToString(stringResource(Res.string.common_dot_separator)),
                        style = FinanceTheme.type.label,
                        color = colors.textSecondary,
                    )
                }
                if (debt.isPaidOff) {
                    Text(stringResource(Res.string.finance_wallet_paid_off), style = FinanceTheme.type.bodyStrong, color = colors.gain)
                } else {
                    Text(FinanceFormat.money(debt.balance, 0), style = FinanceTheme.type.bodyStrong, color = if (debt.isMortgage) colors.textSecondary else colors.textPrimary)
                }
            }
        }
        FinePrint(stringResource(Res.string.finance_wallet_debt_fine_print))
    }
}

@Composable
private fun HomeBlock(home: HomeEquity, mortgage: Double) {
    val colors = FinanceTheme.colors
    var showImprovements by remember { mutableStateOf(false) }
    FinanceCard {
        val value = home.value
        StatPairRow(
            stringResource(Res.string.finance_wallet_home_value),
            value?.let { FinanceFormat.money(it, 0) } ?: "—",
            stringResource(Res.string.finance_wallet_equity),
            home.equity?.let { FinanceFormat.money(it, 0) } ?: "—",
            rightColor = colors.gain,
        )
        val owed = home.unpaidPrincipal ?: mortgage.takeIf { it > 0 }
        StatPairRow(
            stringResource(Res.string.finance_wallet_owed),
            owed?.let { FinanceFormat.money(it, 0) } ?: "—",
            stringResource(Res.string.finance_wallet_value_added),
            home.valueAdded?.let { FinanceFormat.money(it, 0) } ?: "—",
        )
        if (value != null && owed != null && value > 0) {
            val owned = (1 - owed / value).coerceIn(0.0, 1.0)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.finance_wallet_home_owned, FinanceFormat.fractionPercent(owned)), style = FinanceTheme.type.label, color = colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            Meter(owned.toFloat(), colors.gain, height = 8.dp)
        }
        if (home.improvements.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(Res.string.finance_wallet_improvements, FinanceFormat.money(home.improvements.sumOf { it.monthly }, 0), if (showImprovements) "▴" else "▾"),
                style = FinanceTheme.type.bodyStrong,
                color = colors.accent,
                modifier = Modifier.clickable { showImprovements = !showImprovements },
            )
            if (showImprovements) {
                home.improvements.forEach { imp ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(imp.name, style = FinanceTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(FinanceFormat.money(imp.monthly, 0), style = FinanceTheme.type.label, color = colors.textPrimary)
                    }
                }
            }
        }
    }
}

/** Monthly principal and interest for [loan] at [annualRate] (fraction) over [years]. */
internal fun monthlyPayment(loan: Double, annualRate: Double, years: Int): Double {
    val n = years * 12
    if (n <= 0) return 0.0
    val r = annualRate / 12
    if (r == 0.0) return loan / n
    return loan * r / (1 - (1 + r).pow(-n))
}

/**
 * The sheet's mortgage calculator, live: price, down payment and rate on sliders, the monthly
 * cost rolling as they move, split into its parts, and set against what the sheet says the
 * budget could carry at each point in its plan. One tap swaps in today's average 30-year rate.
 */
@Composable
private fun MortgagePlanner(plan: MortgagePlan, marketRate: Double?) {
    val colors = FinanceTheme.colors
    var price by remember(plan) { mutableFloatStateOf(plan.homePrice.toFloat()) }
    var down by remember(plan) { mutableFloatStateOf(plan.downPaymentFraction.toFloat()) }
    var rate by remember(plan) { mutableFloatStateOf(plan.rate.toFloat()) }
    val loan = price * (1 - down)
    val pi = monthlyPayment(loan.toDouble(), rate.toDouble(), plan.termYears)
    val tax = plan.propertyTaxAnnual / 12
    val ins = plan.insuranceAnnual / 12
    val total = pi + tax + ins + plan.hoaMonthly
    val sliderColors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.hairline)
    FinanceCard {
        Text(stringResource(Res.string.finance_wallet_monthly_payment), style = FinanceTheme.type.label, color = colors.textSecondary)
        RollingNumber(FinanceFormat.money(total, 0), FinanceTheme.type.title, colors.textPrimary)
        Text(
            if (plan.hoaMonthly > 0) {
                stringResource(
                    Res.string.finance_wallet_payment_parts_hoa,
                    FinanceFormat.money(pi, 0),
                    FinanceFormat.money(tax, 0),
                    FinanceFormat.money(ins, 0),
                    FinanceFormat.money(plan.hoaMonthly, 0),
                )
            } else {
                stringResource(Res.string.finance_wallet_payment_parts, FinanceFormat.money(pi, 0), FinanceFormat.money(tax, 0), FinanceFormat.money(ins, 0))
            },
            style = FinanceTheme.type.label,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        SliderRow(stringResource(Res.string.finance_wallet_price), FinanceFormat.money(price.toDouble(), 0))
        Slider(price, { price = (it / 5000).roundToInt() * 5000f }, valueRange = 500_000f..3_000_000f, colors = sliderColors)
        SliderRow(
            stringResource(Res.string.finance_wallet_down_payment),
            listOf(FinanceFormat.fractionPercent(down.toDouble(), 0), FinanceFormat.money((price * down).toDouble(), 0)).joinToString(stringResource(Res.string.common_dot_separator)),
        )
        Slider(down, { down = (it * 100).roundToInt() / 100f }, valueRange = 0.05f..0.5f, colors = sliderColors)
        SliderRow(stringResource(Res.string.finance_wallet_rate), FinanceFormat.fractionPercent(rate.toDouble(), 2))
        Slider(rate, { rate = (it * 2000).roundToInt() / 2000f }, valueRange = 0.03f..0.10f, colors = sliderColors)
        if (marketRate != null) {
            Text(
                stringResource(Res.string.finance_wallet_use_market_rate, FinanceFormat.grouped(marketRate, 2)),
                style = FinanceTheme.type.label,
                color = colors.accent,
                modifier = Modifier.clickable { rate = (marketRate / 100).toFloat() }.padding(vertical = 6.dp),
            )
        }
        if (plan.affordability.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.finance_wallet_affordability_title), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            plan.affordability.forEach { p ->
                val ok = total <= p.affordableMortgage
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row {
                        Text(p.label, style = FinanceTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(
                            if (ok) {
                                stringResource(Res.string.finance_wallet_affordability_fits, FinanceFormat.money(p.affordableMortgage, 0))
                            } else {
                                stringResource(Res.string.finance_wallet_affordability_over, FinanceFormat.money(total - p.affordableMortgage, 0), FinanceFormat.money(p.affordableMortgage, 0))
                            },
                            style = FinanceTheme.type.label,
                            color = if (ok) colors.gain else colors.loss,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Meter((total / p.affordableMortgage).toFloat().coerceIn(0f, 1f), if (ok) colors.gain else colors.loss, height = 5.dp)
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = FinanceTheme.type.label, color = FinanceTheme.colors.textPrimary)
    }
}

/** RSU and ESPP payouts on a timeline: what's coming, when, and how long until. */
@Composable
private fun VestingBlock(finance: PersonalFinance) {
    val colors = FinanceTheme.colors
    val now = Clock.System.now().epochSeconds
    Column(Modifier.padding(horizontal = PageGutter)) {
        finance.vesting.sortedBy { it.epochSeconds ?: Long.MAX_VALUE }.forEachIndexed { i, v ->
            val past = v.epochSeconds != null && v.epochSeconds < now
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (past) colors.textTertiary else colors.accent))
                    if (i < finance.vesting.lastIndex) Box(Modifier.width(2.dp).height(46.dp).background(colors.hairline))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(listOf(v.type, v.label).joinToString(stringResource(Res.string.common_dot_separator)), style = FinanceTheme.type.bodyStrong, color = if (past) colors.textSecondary else colors.textPrimary)
                    Text(
                        v.epochSeconds?.let { if (past) stringResource(Res.string.finance_wallet_paid_out) else FinanceFormat.relativeDays(now, it).resolve() } ?: "",
                        style = FinanceTheme.type.label,
                        color = if (past) colors.textTertiary else colors.accent,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(FinanceFormat.money(v.amount, 0), style = FinanceTheme.type.bodyStrong, color = if (past) colors.textSecondary else colors.textPrimary)
                    v.postTax?.let { Text(stringResource(Res.string.finance_wallet_after_tax, FinanceFormat.money(it, 0)), style = FinanceTheme.type.label, color = colors.textSecondary) }
                }
            }
        }
    }
}

@Composable
private fun TaxBlock(finance: PersonalFinance) {
    val colors = FinanceTheme.colors
    val years = finance.taxYears
    var selected by remember { mutableStateOf<Int?>(years.lastIndex) }
    Column {
        BarChart(
            bars = years.map { y ->
                Bar(stringResource(Res.string.finance_wallet_short_year, (y.year % 100).toString().padStart(2, '0')), y.takeHome ?: 0.0, colors.gain, secondary = y.incomePreTax, secondaryColor = colors.textTertiary.copy(alpha = 0.35f))
            },
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.fillMaxWidth().height(170.dp).padding(horizontal = PageGutter),
        )
        val y = selected?.let { years.getOrNull(it) }
        if (y != null) {
            Spacer(Modifier.height(10.dp))
            FinanceCard {
                Text("${y.year}", style = FinanceTheme.type.section, color = colors.textPrimary)
                StatPairRow(
                    stringResource(Res.string.finance_wallet_earned),
                    y.incomePreTax?.let { FinanceFormat.money(it, 0) } ?: "—",
                    stringResource(Res.string.finance_wallet_take_home),
                    y.takeHome?.let { FinanceFormat.money(it, 0) } ?: "—",
                    rightColor = colors.gain,
                )
                StatPairRow(
                    stringResource(Res.string.finance_wallet_taxes),
                    y.taxes?.let { FinanceFormat.money(it, 0) } ?: "—",
                    stringResource(Res.string.finance_wallet_effective_rate),
                    y.effectiveRate?.let { FinanceFormat.fractionPercent(it) } ?: "—",
                    leftColor = colors.loss,
                )
                if (y.invested != null) {
                    StatPairRow(
                        stringResource(Res.string.finance_wallet_invested),
                        FinanceFormat.money(y.invested, 0),
                        stringResource(Res.string.finance_wallet_of_take_home),
                        y.investedRate?.let { FinanceFormat.fractionPercent(it) } ?: "—",
                        leftColor = colors.accent,
                    )
                }
            }
        }
        Legend(
            listOf(
                Triple(stringResource(Res.string.finance_wallet_take_home), "", colors.gain),
                Triple(stringResource(Res.string.finance_wallet_before_tax), "", colors.textTertiary),
            ),
        )
    }
}

@Composable
private fun StatPairRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
    leftColor: Color = FinanceTheme.colors.textPrimary,
    rightColor: Color = FinanceTheme.colors.textPrimary,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatTile(leftLabel, leftValue, Modifier.weight(1f), valueColor = leftColor)
        if (rightLabel.isNotEmpty()) StatTile(rightLabel, rightValue, Modifier.weight(1f), valueColor = rightColor) else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun WalletSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Shimmer(Modifier.width(120.dp).height(14.dp))
        Shimmer(Modifier.width(240.dp).height(44.dp))
        Shimmer(Modifier.fillMaxWidth().height(230.dp), corner = 14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { Shimmer(Modifier.width(110.dp).height(64.dp), corner = 16.dp) }
        }
    }
}

@Composable
private fun SourceFooter(finance: PersonalFinance) {
    val uriHandler = LocalUriHandler.current
    val now = Clock.System.now().epochSeconds
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 20.dp)) {
        Text(
            stringResource(Res.string.finance_wallet_synced_from, finance.title, FinanceFormat.relativeDays(now, finance.fetchedAtEpochSeconds).resolve()),
            style = FinanceTheme.type.micro,
            color = FinanceTheme.colors.textTertiary,
        )
        finance.sourceUrl?.let { url ->
            Text(
                stringResource(Res.string.finance_wallet_open_sheet),
                style = FinanceTheme.type.bodyStrong,
                color = FinanceTheme.colors.accent,
                modifier = Modifier.padding(top = 8.dp).clickable { uriHandler.openUri(url) },
            )
        }
    }
}
