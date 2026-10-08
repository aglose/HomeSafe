package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.ChartLoad
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.SheetIssue
import com.meticulouscreations.homesafe.finance.domain.Account
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.AffordabilityPoint
import com.meticulouscreations.homesafe.finance.domain.ChartDomain
import com.meticulouscreations.homesafe.finance.domain.ChartHaptics
import com.meticulouscreations.homesafe.finance.domain.ChartHealth
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.ChartShader
import com.meticulouscreations.homesafe.finance.domain.ChartStacking
import com.meticulouscreations.homesafe.finance.domain.ChartStyle
import com.meticulouscreations.homesafe.finance.domain.ChartValueFormat
import com.meticulouscreations.homesafe.finance.domain.Contribution
import com.meticulouscreations.homesafe.finance.domain.ContributionYear
import com.meticulouscreations.homesafe.finance.domain.Debt
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.ExpenseLine
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.HomeEquity
import com.meticulouscreations.homesafe.finance.domain.HouseSale
import com.meticulouscreations.homesafe.finance.domain.IncomeLine
import com.meticulouscreations.homesafe.finance.domain.Indicator
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.LineSharpness
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.MortgagePlan
import com.meticulouscreations.homesafe.finance.domain.Owner
import com.meticulouscreations.homesafe.finance.domain.ParseNote
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.Position
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.SectionHealth
import com.meticulouscreations.homesafe.finance.domain.SectionStatus
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SheetChart
import com.meticulouscreations.homesafe.finance.domain.SheetChartKind
import com.meticulouscreations.homesafe.finance.domain.SheetChartSeries
import com.meticulouscreations.homesafe.finance.domain.SheetHealth
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetSection
import com.meticulouscreations.homesafe.finance.domain.Snapshot
import com.meticulouscreations.homesafe.finance.domain.TaxYear
import com.meticulouscreations.homesafe.finance.domain.VestEvent
import com.meticulouscreations.homesafe.finance.domain.WatchedSymbol
import com.meticulouscreations.homesafe.finance.domain.YieldCurve
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_chart_quoted_tab
import homesafe.shared.generated.resources.fin_data_chart_tabs_gone
import homesafe.shared.generated.resources.fin_data_found_read
import homesafe.shared.generated.resources.fin_data_found_snapshots
import homesafe.shared.generated.resources.fin_data_note_history_undated
import homesafe.shared.generated.resources.finance_markets_watchlist_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.sin

/**
 * Made-up data for the finance screens' previews: a deterministic random walk for every price
 * and reading, and an invented household. Nothing here is anyone's real money.
 */
internal object FinanceFixtures {
    private const val NOW = 1_790_800_000L
    private const val DAY = Series.DAY_SECONDS

    /** A repeatable walk of [n] points ending at [end], [step] seconds apart. */
    fun walk(n: Int, end: Double, volatility: Double, step: Long, seed: Int, drift: Double = 0.0): Series {
        var state = seed.toLong() * 6_364_136_223_846_793_005L + 1
        val raw = DoubleArray(n)
        var v = 0.0
        for (i in 0 until n) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val r = ((state ushr 33) % 10_000) / 10_000.0 - 0.5
            v += r * volatility + drift + sin(i / 9.0) * volatility * 0.15
            raw[i] = v
        }
        val shift = end - raw.last()
        return Series(LongArray(n) { NOW - (n - 1 - it) * step }, DoubleArray(n) { raw[it] + shift })
    }

    private fun quote(symbol: String, price: Double, change: Double, seed: Int): Quote {
        val day = walk(78, price, price * 0.0012, 300, seed, drift = change / 78)
        return Quote(
            symbol = symbol,
            price = price,
            previousClose = price - change,
            dayHigh = day.max(),
            dayLow = day.min(),
            fiftyTwoWeekHigh = price * 1.04,
            fiftyTwoWeekLow = price * 0.82,
            volume = 3.1e9,
            marketTimeEpochSeconds = NOW,
            gmtOffsetSeconds = -14_400,
            sessionStartEpochSeconds = NOW - 3 * 3600,
            sessionEndEpochSeconds = NOW + 3 * 3600,
            intraday = day,
        )
    }

    val quotes: Map<String, Quote> = listOf(
        quote(MarketCatalog.SP500.symbol, 7651.54, 38.2, 1),
        quote(MarketCatalog.DOW.symbol, 50906.05, -121.4, 2),
        quote(MarketCatalog.NASDAQ.symbol, 26861.06, 140.1, 3),
        quote(MarketCatalog.RUSSELL.symbol, 2480.33, -9.1, 4),
        quote(MarketCatalog.VIX.symbol, 16.04, -0.6, 5),
        quote(MarketCatalog.TEN_YEAR.symbol, 5.31, 0.04, 6),
        quote(MarketCatalog.GOLD.symbol, 3420.1, 12.0, 7),
        quote(MarketCatalog.OIL.symbol, 71.4, -1.2, 8),
        quote(MarketCatalog.BITCOIN.symbol, 84204.42, 852.8, 9),
        quote(MarketCatalog.DOLLAR.symbol, 101.2, 0.1, 10),
        quote("MSFT", 512.40, 3.1, 11),
        quote("AMZN", 241.75, -2.6, 12),
        quote("GOOGL", 219.30, 1.4, 13),
        quote("ETH-USD", 4_105.20, -38.5, 14),
        quote("VTI", 318.62, 1.9, 15),
    ).associateBy { it.symbol }

    private val endValues = mapOf(
        "t10y2y" to 0.42, "t10y3m" to 1.09, "sahm" to -0.07, "unrate" to 4.1, "icsa" to 197.0, "gdp" to 2.2, "umcsent" to 51.7,
        "hy" to 3.08, "stlfsi" to -0.81, "vix" to 16.04, "ccdelinq" to 2.85, "debtgdp" to 122.6, "interest" to 33.6, "m2" to 4.1,
        "cpi" to 3.4, "corecpi" to 3.1, "corepce" to 2.9, "mortgage" to 7.03, "homeprices" to 1.8, "dgs10" to 5.31, "dff" to 3.88,
        "dgs2" to 4.89, "dgs30" to 5.59,
    )

    private fun reading(indicator: Indicator, seed: Int): IndicatorReading {
        val end = endValues[indicator.id] ?: 1.0
        val months = 12 * 20
        val vol = (kotlin.math.abs(end) * 0.03).coerceAtLeast(0.04)
        return IndicatorReading(indicator, walk(months, end, vol, 30 * DAY, seed))
    }

    val readings: Map<String, IndicatorReading> = IndicatorCatalog.all.mapIndexed { i, ind -> ind.id to reading(ind, 100 + i) }.toMap()

    val curve: Map<String, Series> = YieldCurve.tenors.mapIndexed { i, t ->
        val end = 4.0 + 1.6 * kotlin.math.ln(1 + t.years) / kotlin.math.ln(31.0)
        t.fredId to walk(800, end, 0.03, DAY, 300 + i, drift = 0.0006)
    }.toMap()

    val finance = PersonalFinance(
        title = "Example Household Budget",
        fetchedAtEpochSeconds = NOW,
        sourceUrl = null,
        people = listOf("Alex", "Sam"),
        income = listOf(IncomeLine("Alex", 6_240.0), IncomeLine("Sam", 7_930.0)),
        monthlyIncome = 14_170.0,
        monthlyExpenses = 11_385.0,
        netMonthly = 2_785.0,
        expenses = listOf(
            ExpenseLine("Mortgage", 4_870.0), ExpenseLine("Daycare", 2_310.0), ExpenseLine("Student loan", 1_140.0),
            ExpenseLine("Car payment", 615.0), ExpenseLine("Groceries", 905.0), ExpenseLine("Utilities", 335.0),
            ExpenseLine("Meal kits", 210.0), ExpenseLine("Insurance", 388.0), ExpenseLine("Phone", 95.0),
            ExpenseLine("Gym", 64.0), ExpenseLine("Streaming", 48.0),
        ).sortedByDescending { it.monthly },
        accounts = listOf(
            Account("Family 529", Owner.Joint, 31_480.0, AccountCategory.EDUCATION),
            Account("Roth IRA", Owner.Person("Alex"), 47_215.0, AccountCategory.RETIREMENT),
            Account("Roth IRA", Owner.Person("Sam"), 52_860.0, AccountCategory.RETIREMENT),
            Account("Joint brokerage", Owner.Joint, 184_330.0, AccountCategory.INVESTING),
            Account("401(k)", Owner.Person("Sam"), 266_940.0, AccountCategory.RETIREMENT),
            Account("Index funds", Owner.Person("Alex"), 98_120.0, AccountCategory.INVESTING),
            Account("401(k)", Owner.Person("Alex"), 143_705.0, AccountCategory.RETIREMENT),
            Account("Checking & savings", Owner.Joint, 18_450.0, AccountCategory.CASH),
            Account("High-yield savings", Owner.Joint, 61_900.0, AccountCategory.CASH),
            Account("Home equity", Owner.Joint, 212_600.0, AccountCategory.HOME),
        ),
        investmentsTotal = 843_100.0,
        totalAssets = 1_117_600.0,
        emergencyTarget = 68_310.0,
        debts = listOf(
            Debt("Credit cards", Owner.Joint, 0.0, null, null),
            Debt("Student loans", Owner.Person("Alex"), 38_420.0, 4.2, "~4 years left"),
            Debt("Car loan", Owner.Person("Sam"), 11_760.0, 3.4, "19 months left"),
            Debt("Mortgage", Owner.Joint, 642_300.0, 6.4, "30 years"),
        ),
        home = HomeEquity(
            855_000.0, 171_000.0, 684_000.0, 642_300.0, 41_700.0, 212_700.0, 212_600.0, 208_000.0,
            listOf(ExpenseLine("Fence", 6_350.0), ExpenseLine("Heat pump", 11_900.0)),
        ),
        vesting = listOf(
            VestEvent(NOW - 41 * DAY, "Aug 20th, 2026", "ESPP", 6_480.0, 5_830.0),
            VestEvent(NOW + 33 * DAY, "Nov 2nd, 2026", "RSU", 7_215.0, 4_330.0),
            VestEvent(NOW + 124 * DAY, "Feb 1st, 2027", "ESPP", 6_480.0, 5_830.0),
        ),
        watchlist = listOf("MSFT", "AMZN", "GOOGL", "BTC-USD", "ETH-USD"),
        history = (0 until 26).map { i ->
            val t = NOW - (25 - i) * 91L * DAY
            val assets = 210_000.0 + i * 35_500.0 + sin(i / 2.0) * 18_000
            Snapshot(t, 9_800.0 + i * 70, 12_400.0 + i * 68, 2_600.0 - i * 9, assets, 72_000.0 - i * 860)
        },
        taxYears = (2018..2025).mapIndexed { i, y ->
            val gross = listOf(118_000.0, 131_500.0, 147_200.0, 163_900.0, 171_400.0, 186_300.0, 194_700.0, 208_100.0)[i]
            TaxYear(y, gross, gross * 0.27, gross * 0.73, 0.27, gross * 0.14, 0.19)
        },
        mortgagePlan = MortgagePlan(
            955_000.0,
            0.15,
            0.064,
            30,
            11_460.0,
            1_850.0,
            120.0,
            listOf(AffordabilityPoint("Spring 2027", 11_100.0, 5_900.0), AffordabilityPoint("Fall 2028", 9_800.0, 7_200.0), AffordabilityPoint("Fall 2031", 8_100.0, 8_900.0)),
        ),
        oldHouse = HouseSale(512_000.0, 689_000.0, 61_400.0, 0.17, 233_000.0),
        contributions = (2023..2025).mapIndexed { i, y ->
            ContributionYear(
                y,
                listOf(
                    Contribution("401k", Owner.Person("Alex"), 9_800.0 + i * 800),
                    Contribution("401k", Owner.Person("Sam"), 12_200.0 + i * 800),
                    Contribution("529", Owner.Joint, 2_400.0),
                    Contribution("Joint brokerage", Owner.Joint, 4_000.0 + i * 500),
                ),
            )
        },
        charts = sheetCharts(),
    )

    /** Made-up stand-ins for the kinds of chart the sheet has: an area over dates, bars, a stack by year, a pie. */
    private fun sheetCharts(): List<SheetChart> {
        val dates = ChartDomain.Dates((0 until 24).map { NOW - (23 - it) * 91L * DAY })
        val years = ChartDomain.Categories((2018..2025).map { it.toString() })
        val assets = (0 until 24).map { 140_000.0 + it * 31_000.0 + sin(it / 2.0) * 15_000 }
        val gross = listOf(118_000.0, 131_500.0, 147_200.0, 163_900.0, 171_400.0, 186_300.0, 194_700.0, 208_100.0)
        fun chart(id: Long, tab: String, title: String, kind: SheetChartKind, domain: ChartDomain, vararg series: SheetChartSeries, stacking: ChartStacking = ChartStacking.NONE) =
            SheetChart(id, tab, title.asUiText(), "", kind, stacking, domain, series.toList(), "https://docs.google.com/spreadsheets/d/example/edit#gid=$id")
        fun money(label: String, values: List<Double?>, kind: SheetChartKind) = SheetChartSeries(label.asUiText(), values, kind, ChartValueFormat.MONEY)
        return listOf(
            chart(1, "Home", "Total Assets", SheetChartKind.AREA, dates, money("Total Assets", assets, SheetChartKind.AREA)),
            chart(2, "Home", "Total Debt", SheetChartKind.COLUMN, dates, money("Debt", (0 until 24).map { -64_000.0 + it * 2_400.0 }, SheetChartKind.COLUMN)),
            chart(3, "Home", "Quarterly Assets Change", SheetChartKind.LINE, dates, money("Net change", listOf<Double?>(null) + (1 until 24).map { assets[it] - assets[it - 1] }, SheetChartKind.LINE)),
            chart(4, "Forecasts", "Income", SheetChartKind.COLUMN, years, money("Alex", gross.map { it * 0.6 }, SheetChartKind.COLUMN), money("Sam", gross.map { it * 0.4 }, SheetChartKind.COLUMN)),
            chart(
                5,
                "Forecasts",
                "Combined Total Income",
                SheetChartKind.AREA,
                years,
                money("Taxes Paid", gross.map { it * 0.27 }, SheetChartKind.AREA),
                money("Take Home", gross.map { it * 0.73 }, SheetChartKind.AREA),
                stacking = ChartStacking.STACKED,
            ),
            chart(
                7,
                "Forecasts",
                "Invested and its share",
                SheetChartKind.COMBO,
                years,
                money("Invested", gross.map { it * 0.14 }, SheetChartKind.COLUMN),
                SheetChartSeries("Of take-home".asUiText(), (0 until 8).map { 0.12 + it * 0.012 }, SheetChartKind.LINE, ChartValueFormat.PERCENT, rightAxis = true),
            ),
            chart(6, "Home", "Spending", SheetChartKind.PIE, ChartDomain.Categories(listOf("Housing", "Food", "Transport", "Childcare", "Other")), money("Monthly", listOf(4_200.0, 1_300.0, 650.0, 1_900.0, 880.0), SheetChartKind.PIE)),
        )
    }

    /** A sync after some reorganising: one title renamed, one block emptied, a row without a date, a chart on a deleted tab. */
    val troubledHealth = SheetHealth(
        sections = SheetSection.entries.map { section ->
            when (section) {
                SheetSection.EXPENSES -> SectionHealth(section, SectionStatus.MISSING, null)
                SheetSection.VESTING -> SectionHealth(section, SectionStatus.EMPTY, null)
                SheetSection.HISTORY -> SectionHealth(section, SectionStatus.OK, UiText.plural(Res.plurals.fin_data_found_snapshots, 25))
                else -> SectionHealth(section, SectionStatus.OK, UiText.of(Res.string.fin_data_found_read))
            }
        },
        charts = listOf(
            ChartHealth("Total Assets".asUiText(), "Home", null),
            ChartHealth("Total Debt".asUiText(), "Home", null),
            ChartHealth("Income".asUiText(), "Forecasts", UiText.of(Res.string.fin_data_chart_tabs_gone, UiText.of(Res.string.fin_data_chart_quoted_tab, "Forecasts"))),
        ),
        notes = listOf(ParseNote(SheetSection.HISTORY, UiText.plural(Res.plurals.fin_data_note_history_undated, 1))),
    )

    val state = FinanceUiState(
        quotes = quotes,
        readings = readings,
        curve = curve,
        finance = finance,
        financeLoading = false,
        quotesUpdatedEpochSeconds = NOW,
        charts = listOf(MarketCatalog.SP500.symbol to 7_651.54, "MSFT" to 512.40).associate { (symbol, end) ->
            val weeks = 52 * 30
            val history = walk(weeks, end, end * 0.02, 7 * DAY, seed = symbol.length, drift = end / weeks / 2)
            FinanceUiState.chartKey(symbol, ChartRange.MAX) to
                ChartLoad(PriceHistory(symbol, ChartRange.MAX, history, null, -14_400, history.max() * 1.01, history.times[history.values.indexOfFirst { it == history.max() }]), loading = false)
        },
        // One ticker added in the app and a position held in one of the sheet's.
        watched = listOf(
            WatchedSymbol("VTI", "Vanguard Total Stock Market Index Fund ETF", InstrumentKind.EQUITY, NOW - 40 * DAY, Position(12.0, 241.30)),
            WatchedSymbol("MSFT", "Microsoft Corporation", InstrumentKind.EQUITY, NOW - 90 * DAY, Position(8.0, 402.15)),
        ),
    )
}

@Composable
internal fun FinanceStage(content: @Composable () -> Unit) {
    FrigateTheme {
        CompositionLocalProvider(LocalFinancePalette provides FinancePalette(), LocalFinanceTypography provides FinanceTypography(albertSansFontFamily())) {
            Box(Modifier.fillMaxSize().background(FinancePalette().background)) { content() }
        }
    }
}

internal val previewPadding = PaddingValues(top = 24.dp, bottom = 24.dp)

@Preview(widthDp = 412, heightDp = 1800)
@Composable
private fun FinanceWalletPreview() {
    FinanceStage { WalletScreen(FinanceFixtures.state, rememberLazyListState(), previewPadding, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 3100)
@Composable
private fun FinanceSheetChartsPreview() {
    FinanceStage {
        Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FinanceFixtures.finance.charts.forEach { SheetChartCard(it) }
        }
    }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceSheetSyncPreview() {
    FinanceStage {
        SheetSyncScreen(
            FinanceFixtures.state.copy(finance = FinanceFixtures.finance.copy(health = FinanceFixtures.troubledHealth)),
            previewPadding,
            onSyncNow = {},
            onOpenBankSync = {},
        )
    }
}

@Preview(widthDp = 412, heightDp = 200)
@Composable
private fun FinanceSheetSyncLinesPreview() {
    FinanceStage {
        Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetSyncLine(FinanceFixtures.state, onOpen = {})
            SheetSyncLine(FinanceFixtures.state.copy(finance = FinanceFixtures.finance.copy(health = FinanceFixtures.troubledHealth)), onOpen = {})
            SheetSyncLine(FinanceFixtures.state.copy(sheetIssue = SheetIssue(SheetProblem.OTHER, "Google didn't answer".asUiText(), null, null)), onOpen = {})
        }
    }
}

@Preview(widthDp = 412, heightDp = 900)
@Composable
private fun FinanceWatchlistPreview() {
    FinanceStage {
        LazyColumn(contentPadding = previewPadding) {
            item {
                SectionHeader(
                    stringResource(Res.string.finance_markets_watchlist_title),
                    subtitle = stringResource(watchlistSubtitle(FinanceFixtures.state)),
                    action = { AddSymbolButton({}) },
                )
            }
            watchlistItems(FinanceFixtures.state, "w") {}
        }
    }
}

@Preview(widthDp = 412, heightDp = 2200)
@Composable
private fun FinanceQuoteWithPositionPreview() {
    FinanceStage { QuoteDetailScreen("MSFT", FinanceFixtures.state, previewPadding, { _, _ -> }, {}, {}, { _, _ -> }) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceMarketsPreview() {
    FinanceStage { MarketsScreen(FinanceFixtures.state, rememberLazyListState(), previewPadding, { _, _ -> }, {}, {}) }
}

@Preview(name = "Economy · straight talk", widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceEconomyPreview() {
    FinanceStage { EconomyScreen(FinanceFixtures.state, rememberLazyListState(), previewPadding, onOpenIndicator = {}) }
}

@Preview(name = "Economy · bright side", widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceEconomyBrightSidePreview() {
    val state = FinanceFixtures.state.copy(tone = EconomyTone.BRIGHT_SIDE)
    FinanceStage {
        CompositionLocalProvider(LocalEconomyTone provides state.tone) {
            EconomyScreen(state, rememberLazyListState(), previewPadding, onOpenIndicator = {})
        }
    }
}

@Preview(name = "Indicator · bright side", widthDp = 412, heightDp = 1200)
@Composable
private fun FinanceIndicatorBrightSidePreview() {
    val state = FinanceFixtures.state.copy(tone = EconomyTone.BRIGHT_SIDE)
    FinanceStage {
        CompositionLocalProvider(LocalEconomyTone provides state.tone) {
            IndicatorDetailScreen(IndicatorCatalog.longTerm.id, state, previewPadding)
        }
    }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceRiskPreview() {
    FinanceStage { RiskScreen(FinanceFixtures.state, rememberLazyListState(), previewPadding) {} }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FinanceIndicatorPreview() {
    FinanceStage { IndicatorDetailScreen(IndicatorCatalog.sahm.id, FinanceFixtures.state, previewPadding) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FinanceSheetSetupPreview() {
    FinanceStage {
        WalletScreen(
            FinanceFixtures.state.copy(finance = null, sheetIssue = SheetIssue(SheetProblem.NOT_SHARED, UiText.Empty, "relay@example.iam.gserviceaccount.com", null)),
            rememberLazyListState(),
            previewPadding,
            {},
            {},
        )
    }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun FinanceConnectionsPreview() {
    FinanceStage { ConnectionsScreen(FinanceFixtures.state, previewPadding) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FinanceGlossaryPreview() {
    FinanceStage { GlossaryScreen(previewPadding) }
}

@Preview(widthDp = 412, heightDp = 1200)
@Composable
private fun FinanceExplainerPreview() {
    FinanceStage {
        Box(Modifier.background(FinancePalette().surfaceRaised)) {
            ExplainerBody(Explainers.byId("cpi")!!, FinanceFixtures.state, {}, {})
        }
    }
}

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun FinanceChartSettingsPreview() {
    FinanceStage { ChartSettingsScreen(ChartStyle.DEFAULT, previewPadding) {} }
}

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun FinanceChartSettingsSharpPreview() {
    val style = ChartStyle(ChartShader.HALFTONE, LineSharpness.POINTS, ChartHaptics.STRONG)
    FinanceStage {
        CompositionLocalProvider(LocalChartStyle provides style) { ChartSettingsScreen(style, previewPadding) {} }
    }
}

/** The fixture household having a harder year: thin cash, a costly card, a smaller monthly gap, little into the 401(k)s. */
internal val strugglingFinance: PersonalFinance = FinanceFixtures.finance.let { f ->
    f.copy(
        monthlyExpenses = 12_960.0,
        netMonthly = 1_210.0,
        accounts = f.accounts.map { if (it.category == AccountCategory.CASH) it.copy(balance = it.balance / 3.2) else it },
        debts = f.debts.map { if (it.name == "Credit cards") it.copy(balance = 8_430.0, apr = 24.9) else it },
        contributions = f.contributions.map { y -> y.copy(lines = y.lines.map { if (it.beforeTakeHome) it.copy(amount = it.amount / 6) else it }) },
    )
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun FinanceCheckupPreview() {
    FinanceStage {
        Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MoneyCheckup(moneyCheckup(strugglingFinance, 4.33), {})
        }
    }
}

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun FinanceCheckupBreakdownPreview() {
    FinanceStage {
        Box(Modifier.background(FinancePalette().surfaceRaised)) {
            CheckupPage(moneyCheckup(strugglingFinance, 4.33), CheckKind.EMERGENCY_FUND, {}, {})
        }
    }
}

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun FinanceCheckupSavingBreakdownPreview() {
    FinanceStage {
        Box(Modifier.background(FinancePalette().surfaceRaised)) {
            CheckupPage(moneyCheckup(FinanceFixtures.finance, 4.33), CheckKind.SAVINGS_RATE, {}, {})
        }
    }
}
