package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.Account
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
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
private enum class WalletMetric(val label: String) {
    ASSETS("Total assets"),
    DEBT("Debt"),
    CASH_FLOW("Monthly profit"),
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
) {
    val finance = state.finance
    LazyColumn(state = listState, contentPadding = contentPadding) {
        if (finance != null) item(key = "sync") { SheetSyncLine(state, onOpenSync, Modifier.padding(top = 4.dp)) }
        when {
            finance != null -> walletItems(finance, state)
            state.financeLoading -> item(key = "loading") { WalletSkeleton() }
            else -> item(key = "setup") { state.sheetIssue?.let { SheetSetupCard(it, onRetrySheet) } }
        }
        item(key = "watch-h") {
            SectionHeader("Your watchlist", subtitle = watchlistSubtitle(state), action = onAddSymbol?.let { add -> { AddSymbolButton(add) } })
        }
        watchlistItems(state, "w", onOpenQuote)
        if (finance != null) item(key = "source") { SourceFooter(finance) }
    }
}

private fun LazyListScope.walletItems(finance: PersonalFinance, state: FinanceUiState) {
    item(key = "hero") { NetWorthHero(finance) }
    item(key = "quick") { QuickStats(finance) }
    item(key = "checkup") { CascadeIn(1) { MoneyCheckup(finance, state.readings[IndicatorCatalog.fedFunds.id]?.latest, Modifier.padding(top = 16.dp)) } }
    if (finance.charts.isNotEmpty()) {
        item(key = "charts-h") { SectionHeader("Sheet charts", subtitle = "The charts in “${finance.title}”, live from the sheet") }
        items(finance.charts.size, key = { "chart-${finance.charts[it].id}" }) { i ->
            CascadeIn(i) { SheetChartCard(finance.charts[i], Modifier.padding(bottom = 12.dp)) }
        }
    }
    if (finance.accounts.isNotEmpty()) {
        item(key = "acct-h") { SectionHeader("Accounts", trailing = finance.totalAssets?.let(FinanceFormat::compactMoney), info = "totalassets") }
        item(key = "acct") { CascadeIn(0) { AccountsBlock(finance) } }
        item(key = "alloc-h") { SectionHeader("Where your money sits", subtitle = "Tap a row to highlight it", info = "allocation") }
        item(key = "alloc") { CascadeIn(1) { AllocationBlock(finance.accounts) } }
    }
    if (finance.expenses.isNotEmpty()) {
        item(key = "flow-h") { SectionHeader("Monthly cash flow", trailing = finance.netMonthly?.let { FinanceFormat.signedMoney(it, 0) + " / mo" }, info = "cashflow") }
        item(key = "flow") { CascadeIn(2) { CashFlowBlock(finance) } }
    }
    val flowHistory = finance.history.filter { it.monthlyExpenses != null && it.monthlyIncome != null }.takeLast(12)
    if (flowHistory.size > 2) {
        item(key = "flowhist-h") { SectionHeader("Income vs spending", subtitle = "Green: what came in · orange: what went out, at each check-in in your sheet", info = "savingsrate") }
        item(key = "flowhist") {
            CascadeIn(3) {
                val colors = FinanceTheme.colors
                var sel by remember { mutableStateOf<Int?>(null) }
                Column {
                    val s = sel?.let { flowHistory.getOrNull(it) } ?: flowHistory.last()
                    Text(
                        "${FinanceFormat.monthYear(s.epochSeconds)}: ${FinanceFormat.money(s.monthlyIncome!!, 0)} in, ${FinanceFormat.money(s.monthlyExpenses!!, 0)} out",
                        style = FinanceTheme.type.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = PageGutter),
                    )
                    Spacer(Modifier.height(8.dp))
                    BarChart(
                        bars = flowHistory.map { snap ->
                            Bar(
                                label = FinanceFormat.shortYear(snap.epochSeconds),
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
        item(key = "debt-h") { SectionHeader("Debt", trailing = FinanceFormat.compactMoney(finance.consumerDebt) + " excl. mortgage", info = "debt") }
        item(key = "debt") { CascadeIn(4) { DebtBlock(finance) } }
    }
    finance.home?.let { home ->
        item(key = "home-h") { SectionHeader("Home", info = "homeequity") }
        item(key = "home") { CascadeIn(5) { HomeBlock(home, finance.mortgageBalance) } }
    }
    finance.mortgagePlan?.let { plan ->
        item(key = "mort-h") { SectionHeader("Mortgage planner", subtitle = "Seeded from the sheet's New House tab: drag to try a different house", info = "mortgageplanner") }
        item(key = "mort") { CascadeIn(6) { MortgagePlanner(plan, state.readings[IndicatorCatalog.mortgage.id]?.latest) } }
    }
    if (finance.vesting.isNotEmpty()) {
        item(key = "vest-h") { SectionHeader("Vesting", trailing = finance.vesting.sumOf { it.postTax ?: it.amount }.let { FinanceFormat.compactMoney(it) + " after tax" }, info = "vesting") }
        item(key = "vest") { CascadeIn(7) { VestingBlock(finance) } }
    }
    if (finance.taxYears.isNotEmpty()) {
        item(key = "tax-h") { SectionHeader("Income & taxes", subtitle = "Combined, by year — tap a year", info = "taxes") }
        item(key = "tax") { CascadeIn(8) { TaxBlock(finance) } }
    }
    finance.oldHouse?.takeIf { it.soldPrice != null }?.let { sale ->
        item(key = "old-h") { SectionHeader("Last house sale") }
        item(key = "old") {
            FinanceCard {
                StatPairRow("Bought for", sale.purchasePrice?.let { FinanceFormat.money(it, 0) } ?: "—", "Sold for", FinanceFormat.money(sale.soldPrice!!, 0))
                StatPairRow(
                    "Total profit",
                    sale.profit?.let { FinanceFormat.signedMoney(it, 0) } ?: "—",
                    "Return",
                    sale.roi?.let { FinanceFormat.fractionPercent(it) } ?: "—",
                    rightColor = FinanceTheme.colors.gain,
                )
                sale.cashReceived?.let { StatPairRow("Cash received", FinanceFormat.money(it, 0), "", "") }
            }
        }
    }
}

/** The headline: total assets over the sheet's history, scrubbable, with the metric and range chosen below it. */
@Composable
private fun NetWorthHero(finance: PersonalFinance) {
    val colors = FinanceTheme.colors
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
    val changeText = when {
        change == null -> " "
        i != null -> "${FinanceFormat.signedMoney(change, 0)}${pct?.let { " (" + FinanceFormat.signedPercent(it) + ")" } ?: ""}  ${FinanceFormat.monthYear(series.times[i])}"
        else -> "${FinanceFormat.signedMoney(change, 0)}${pct?.let { " (" + FinanceFormat.signedPercent(it) + ")" } ?: ""}  since ${series.times.firstOrNull()?.let { FinanceFormat.monthYear(it) } ?: ""}"
    }
    Box(Modifier.fillMaxWidth()) {
        AuroraBackground(color, Modifier.matchParentSize(), secondary = colors.accent)
        Column(Modifier.padding(top = 12.dp)) {
            HeroNumber(
                caption = metric.label + if (metric == WalletMetric.CASH_FLOW) " · per month" else "",
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
                    axis = ChartAxis({ FinanceFormat.compactMoney(it) }, { FinanceFormat.monthYear(it) }),
                    contentDescription = "${metric.label} history",
                    onScrub = { scrub = it },
                    modifier = Modifier.fillMaxWidth().height(230.dp),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("Not enough history in this range", style = FinanceTheme.type.label, color = colors.textSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            RangeSelector(WalletRanges, range, { if (it == EconRange.MAX) "ALL" else it.label }, color, { range = it }, Modifier.padding(horizontal = PageGutter - 4.dp))
            Spacer(Modifier.height(10.dp))
            ChipRow(WalletMetric.entries, metric, { it.label }, color, { metric = it })
            HowToRead(
                "Each point is a check-in recorded in your budget sheet, joined into a line, with today's figure at the end. " +
                    "Drag along it to see any check-in, pick a range above, and switch between total assets, debt and monthly profit with the buttons.",
            )
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
        finance.netWorth?.let { QuickCard("Net worth", FinanceFormat.compactMoney(it), colors.accent, "networth") },
        finance.monthlyIncome?.let { QuickCard("Take-home / mo", FinanceFormat.money(it, 0), colors.gain, "cashflow") },
        finance.monthlyExpenses?.let { QuickCard("Spending / mo", FinanceFormat.money(it, 0), colors.loss, "cashflow") },
        finance.netMonthly?.let { net ->
            QuickCard("Left over" + (finance.savingsRate?.let { " · " + FinanceFormat.fractionPercent(it) } ?: ""), FinanceFormat.signedMoney(net, 0), colors.direction(net), "savingsrate")
        },
        finance.runwayMonths?.let { QuickCard("Cash runway", FinanceFormat.grouped(it, 1) + " months", if (it >= 3) colors.gain else colors.watch, "runway") },
        QuickCard("Debt excl. mortgage", FinanceFormat.compactMoney(finance.consumerDebt), colors.textPrimary, "debt"),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 20.dp),
    ) {
        items(cards.size) { i ->
            val (label, value, color, explainerId) = cards[i]
            CascadeIn(i) {
                Column(
                    Modifier
                        .width(150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(FinanceTheme.colors.surface)
                        .border(1.dp, FinanceTheme.colors.hairline, RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = "Explain $label") { open(explainerId) }
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
            { o -> if (o == null) "All" else "${o.label} ${FinanceFormat.compactMoney(finance.accountsTotal(o))}" },
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
                Text(account.name, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${account.owner.label} · ${account.category.label}", style = FinanceTheme.type.label, color = colors.textSecondary)
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
    val slices = byCategory.map { (cat, v) -> DonutSlice(cat.label, v, categoryColor(cat, colors)) }
    val total = slices.sumOf { it.value }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            DonutChart(slices, Modifier.matchParentSize(), highlighted = highlighted)
            val h = highlighted?.let { slices.getOrNull(it) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(h?.label ?: "Total", style = FinanceTheme.type.micro, color = colors.textSecondary)
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
                Text("${FinanceFormat.money(income, 0)} take-home", style = FinanceTheme.type.micro, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text("Left over", style = FinanceTheme.type.micro, color = colors.textPrimary)
            }
            if (finance.income.size > 1) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    finance.income.forEach { line ->
                        StatTile(line.person, FinanceFormat.money(line.monthly, 0), Modifier.weight(1f), note = FinanceFormat.fractionPercent(line.monthly / income) + " of income")
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
                if (expanded) "Show less" else "Show all ${expenses.size}",
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
    val debtHistory = Series.of(finance.history.mapNotNull { s -> s.debt?.let { s.epochSeconds to it } })
    Column {
        if (debtHistory.size > 2) {
            LineChart(
                lines = listOf(ChartLine(debtHistory, colors.loss, fill = true)),
                timeAxis = true,
                axis = ChartAxis({ FinanceFormat.compactMoney(it) }, { FinanceFormat.monthYear(it) }),
                contentDescription = "Debt history",
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
                            debt.owner.label,
                            debt.apr?.let { FinanceFormat.grouped(it, 2) + "% APR" },
                            debt.note,
                            if (debt.isMortgage) "secured by the home" else null,
                        ).joinToString(" · "),
                        style = FinanceTheme.type.label,
                        color = colors.textSecondary,
                    )
                }
                if (debt.isPaidOff) {
                    Text("Paid off", style = FinanceTheme.type.bodyStrong, color = colors.gain)
                } else {
                    Text(FinanceFormat.money(debt.balance, 0), style = FinanceTheme.type.bodyStrong, color = if (debt.isMortgage) colors.textSecondary else colors.textPrimary)
                }
            }
        }
        FinePrint("Net worth counts the house as the equity built in it, so the mortgage against its full value is listed but not subtracted — as the sheet does.")
    }
}

@Composable
private fun HomeBlock(home: HomeEquity, mortgage: Double) {
    val colors = FinanceTheme.colors
    var showImprovements by remember { mutableStateOf(false) }
    FinanceCard {
        val value = home.value
        StatPairRow("Home value", value?.let { FinanceFormat.money(it, 0) } ?: "—", "Equity", home.equity?.let { FinanceFormat.money(it, 0) } ?: "—", rightColor = colors.gain)
        val owed = home.unpaidPrincipal ?: mortgage.takeIf { it > 0 }
        StatPairRow("Owed", owed?.let { FinanceFormat.money(it, 0) } ?: "—", "Value added", home.valueAdded?.let { FinanceFormat.money(it, 0) } ?: "—")
        if (value != null && owed != null && value > 0) {
            val owned = (1 - owed / value).coerceIn(0.0, 1.0)
            Spacer(Modifier.height(10.dp))
            Text("You own ${FinanceFormat.fractionPercent(owned)} of the house", style = FinanceTheme.type.label, color = colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            Meter(owned.toFloat(), colors.gain, height = 8.dp)
        }
        if (home.improvements.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Improvements · ${FinanceFormat.money(home.improvements.sumOf { it.monthly }, 0)} ${if (showImprovements) "▴" else "▾"}",
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
        Text("Monthly payment", style = FinanceTheme.type.label, color = colors.textSecondary)
        RollingNumber(FinanceFormat.money(total, 0), FinanceTheme.type.title, colors.textPrimary)
        Text(
            "${FinanceFormat.money(pi, 0)} loan · ${FinanceFormat.money(tax, 0)} tax · ${FinanceFormat.money(ins, 0)} insurance" + if (plan.hoaMonthly > 0) " · ${FinanceFormat.money(plan.hoaMonthly, 0)} HOA" else "",
            style = FinanceTheme.type.label,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        SliderRow("Price", FinanceFormat.money(price.toDouble(), 0))
        Slider(price, { price = (it / 5000).roundToInt() * 5000f }, valueRange = 500_000f..3_000_000f, colors = sliderColors)
        SliderRow("Down payment", "${FinanceFormat.fractionPercent(down.toDouble(), 0)} · ${FinanceFormat.money((price * down).toDouble(), 0)}")
        Slider(down, { down = (it * 100).roundToInt() / 100f }, valueRange = 0.05f..0.5f, colors = sliderColors)
        SliderRow("Rate", FinanceFormat.fractionPercent(rate.toDouble(), 2))
        Slider(rate, { rate = (it * 2000).roundToInt() / 2000f }, valueRange = 0.03f..0.10f, colors = sliderColors)
        if (marketRate != null) {
            Text(
                "Use today's 30-year average: ${FinanceFormat.grouped(marketRate, 2)}%",
                style = FinanceTheme.type.label,
                color = colors.accent,
                modifier = Modifier.clickable { rate = (marketRate / 100).toFloat() }.padding(vertical = 6.dp),
            )
        }
        if (plan.affordability.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Against what the budget can carry", style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            plan.affordability.forEach { p ->
                val ok = total <= p.affordableMortgage
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row {
                        Text(p.label, style = FinanceTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(
                            (if (ok) "Fits · " else "Over by ${FinanceFormat.money(total - p.affordableMortgage, 0)} · ") + FinanceFormat.money(p.affordableMortgage, 0),
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
                    Text("${v.type} · ${v.label}", style = FinanceTheme.type.bodyStrong, color = if (past) colors.textSecondary else colors.textPrimary)
                    Text(
                        v.epochSeconds?.let { if (past) "Paid out" else FinanceFormat.relativeDays(now, it) } ?: "",
                        style = FinanceTheme.type.label,
                        color = if (past) colors.textTertiary else colors.accent,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(FinanceFormat.money(v.amount, 0), style = FinanceTheme.type.bodyStrong, color = if (past) colors.textSecondary else colors.textPrimary)
                    v.postTax?.let { Text("${FinanceFormat.money(it, 0)} after tax", style = FinanceTheme.type.label, color = colors.textSecondary) }
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
                Bar("'" + (y.year % 100).toString().padStart(2, '0'), y.takeHome ?: 0.0, colors.gain, secondary = y.incomePreTax, secondaryColor = colors.textTertiary.copy(alpha = 0.35f))
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
                StatPairRow("Earned", y.incomePreTax?.let { FinanceFormat.money(it, 0) } ?: "—", "Take-home", y.takeHome?.let { FinanceFormat.money(it, 0) } ?: "—", rightColor = colors.gain)
                StatPairRow("Taxes", y.taxes?.let { FinanceFormat.money(it, 0) } ?: "—", "Effective rate", y.effectiveRate?.let { FinanceFormat.fractionPercent(it) } ?: "—", leftColor = colors.loss)
                if (y.invested != null) {
                    StatPairRow("Invested", FinanceFormat.money(y.invested, 0), "Of take-home", y.investedRate?.let { FinanceFormat.fractionPercent(it) } ?: "—", leftColor = colors.accent)
                }
            }
        }
        Legend(listOf(Triple("Take-home", "", colors.gain), Triple("Before tax", "", colors.textTertiary)))
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
            "Synced from “${finance.title}” · ${FinanceFormat.relativeDays(now, finance.fetchedAtEpochSeconds).let { if (it == "today") "today" else it }}",
            style = FinanceTheme.type.micro,
            color = FinanceTheme.colors.textTertiary,
        )
        finance.sourceUrl?.let { url ->
            Text(
                "Open the sheet",
                style = FinanceTheme.type.bodyStrong,
                color = FinanceTheme.colors.accent,
                modifier = Modifier.padding(top = 8.dp).clickable { uriHandler.openUri(url) },
            )
        }
    }
}
