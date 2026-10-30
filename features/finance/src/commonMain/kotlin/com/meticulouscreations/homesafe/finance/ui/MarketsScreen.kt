package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.Position
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.ui.components.AuroraBackground
import com.meticulouscreations.homesafe.finance.ui.components.CascadeIn
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.finance.ui.components.RangeSelector
import com.meticulouscreations.homesafe.finance.ui.components.Shimmer
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_callout_for_you
import homesafe.shared.generated.resources.finance_change_at
import homesafe.shared.generated.resources.finance_change_with_percent
import homesafe.shared.generated.resources.finance_markets_52w_high
import homesafe.shared.generated.resources.finance_markets_52w_low
import homesafe.shared.generated.resources.finance_markets_about
import homesafe.shared.generated.resources.finance_markets_chart_description
import homesafe.shared.generated.resources.finance_markets_closed
import homesafe.shared.generated.resources.finance_markets_day_high
import homesafe.shared.generated.resources.finance_markets_day_low
import homesafe.shared.generated.resources.finance_markets_fine_print
import homesafe.shared.generated.resources.finance_markets_fine_print_error
import homesafe.shared.generated.resources.finance_markets_how_to_read_day
import homesafe.shared.generated.resources.finance_markets_how_to_read_range
import homesafe.shared.generated.resources.finance_markets_macro_subtitle
import homesafe.shared.generated.resources.finance_markets_macro_title
import homesafe.shared.generated.resources.finance_markets_open_closes
import homesafe.shared.generated.resources.finance_markets_opens
import homesafe.shared.generated.resources.finance_markets_prev_close
import homesafe.shared.generated.resources.finance_markets_price
import homesafe.shared.generated.resources.finance_markets_quote_fine_print
import homesafe.shared.generated.resources.finance_markets_range_day
import homesafe.shared.generated.resources.finance_markets_range_five_years
import homesafe.shared.generated.resources.finance_markets_range_max
import homesafe.shared.generated.resources.finance_markets_range_month
import homesafe.shared.generated.resources.finance_markets_range_three_months
import homesafe.shared.generated.resources.finance_markets_range_week
import homesafe.shared.generated.resources.finance_markets_range_year
import homesafe.shared.generated.resources.finance_markets_range_ytd
import homesafe.shared.generated.resources.finance_markets_symbol
import homesafe.shared.generated.resources.finance_markets_volume
import homesafe.shared.generated.resources.finance_markets_watchlist_title
import homesafe.shared.generated.resources.finance_markets_what_is_it
import homesafe.shared.generated.resources.finance_markets_why_it_matters
import homesafe.shared.generated.resources.finance_stats_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val IndexSymbols = MarketCatalog.indices.map { it.symbol }

/** The ranges the Markets tab and a quote's page offer. */
internal val PriceRanges = listOf(
    ChartRange.DAY,
    ChartRange.WEEK,
    ChartRange.MONTH,
    ChartRange.THREE_MONTHS,
    ChartRange.YEAR_TO_DATE,
    ChartRange.YEAR,
    ChartRange.FIVE_YEARS,
    ChartRange.MAX,
)

// Public for another module to place; it fills whatever it is given, so it takes no Modifier.
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun MarketsScreen(
    state: FinanceUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onRequestHistory: (String, ChartRange) -> Unit,
    onAddSymbol: () -> Unit,
    onOpenQuote: (String) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(MarketCatalog.SP500.symbol) }
    var range by rememberSaveable { mutableStateOf(ChartRange.DAY) }
    // Saved by name, so a sort that's gone after an update falls back to the list's own order.
    var sortName by rememberSaveable { mutableStateOf(WatchSort.LIST.name) }
    // Ordering by what's held is offered only when something is.
    val sorts = if (state.watchEntries.any { it.position != null }) WatchSort.entries else WatchSort.entries - WatchSort.HELD
    val sort = sorts.firstOrNull { it.name == sortName } ?: WatchSort.LIST

    if (LocalFinanceDesk.current) {
        MarketsDesk(state, listState, contentPadding, selected, { selected = it }, range, { range = it }, sorts, sort, { sortName = it.name }, onRequestHistory, onAddSymbol, onOpenQuote)
        return
    }
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
        item(key = "tape") { TickerTape(state.quotes, onOpenQuote) }
        item(key = "hero") {
            PriceHeroAndChart(
                symbol = selected,
                state = state,
                range = range,
                onRange = { range = it },
                onRequestHistory = onRequestHistory,
                verdictPeers = IndexSymbols,
                trailing = {
                    MarketStatus(state.quotes[MarketCatalog.SP500.symbol])
                    Explainers.forSymbol(selected)?.let { InfoButton(it) }
                },
            )
        }
        item(key = "indices") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = PageGutter),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 18.dp),
            ) {
                items(MarketCatalog.indices, key = { it.symbol }) { meta ->
                    IndexCard(meta.symbol, state.quotes[meta.symbol], meta.symbol == selected, onClick = { selected = meta.symbol }, Modifier.width(148.dp))
                }
            }
        }
        item(key = "stats-h") {
            SectionHeader(stringResource(Res.string.finance_stats_title), trailing = MarketCatalog.lookup(selected).shortName.resolve(), info = "prevclose")
        }
        item(key = "stats") { CascadeIn(0) { IndexStats(selected, state) } }
        item(key = "alltime") { LongRunSection(selected, state, onRequestHistory) }
        item(key = "macro-h") {
            SectionHeader(stringResource(Res.string.finance_markets_macro_title), subtitle = stringResource(Res.string.finance_markets_macro_subtitle))
        }
        items(MarketCatalog.macro, key = { "macro-${it.symbol}" }) { meta ->
            QuoteRow(meta.symbol, state.quotes[meta.symbol], onClick = { onOpenQuote(meta.symbol) })
        }
        item(key = "watch-h") {
            SectionHeader(stringResource(Res.string.finance_markets_watchlist_title), subtitle = stringResource(watchlistSubtitle(state)), action = { AddSymbolButton(onAddSymbol) })
        }
        if (state.watchEntries.size > 1) {
            item(key = "watch-sort") {
                ChipRow(sorts, sort, { stringResource(it.label) }, FinanceTheme.colors.accent, { sortName = it.name }, Modifier.padding(bottom = 8.dp))
            }
        }
        watchlistItems(state, "watch", sort, onOpenQuote)
        item(key = "fine") {
            FinePrint(
                state.quotesError?.let { stringResource(Res.string.finance_markets_fine_print_error, it.resolve()) }
                    ?: stringResource(Res.string.finance_markets_fine_print),
            )
        }
    }
}

/** The day's figures for [symbol] and where it sits in its year's range. */
@Composable
private fun IndexStats(symbol: String, state: FinanceUiState) {
    val q = state.quotes[symbol]
    val kind = MarketCatalog.lookup(symbol).kind
    Column {
        StatGrid(
            listOf(
                stringResource(Res.string.finance_markets_prev_close) to (q?.previousClose?.let { FinanceFormat.price(it, kind) } ?: "—"),
                stringResource(Res.string.finance_markets_volume) to (q?.volume?.takeIf { it > 0 }?.let(FinanceFormat::volume) ?: "—"),
                stringResource(Res.string.finance_markets_day_high) to (q?.dayHigh?.let { FinanceFormat.price(it, kind) } ?: "—"),
                stringResource(Res.string.finance_markets_day_low) to (q?.dayLow?.let { FinanceFormat.price(it, kind) } ?: "—"),
            ),
        )
        val hi = q?.fiftyTwoWeekHigh
        val lo = q?.fiftyTwoWeekLow
        if (q != null && hi != null && lo != null) {
            RangeBar(lo, hi, q.price, FinanceFormat.price(lo, kind), FinanceFormat.price(hi, kind))
        }
    }
}

/**
 * The Markets tab on a desk, laid out as a broker's web page is: the chosen index's price and
 * chart with every index side by side under it, its day's stats beside its long run, and the
 * watchlist and the other big signals down a rail at the side, always in view beside the chart.
 */
@Composable
private fun MarketsDesk(
    state: FinanceUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    selected: String,
    onSelect: (String) -> Unit,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
    sorts: List<WatchSort>,
    sort: WatchSort,
    onSort: (WatchSort) -> Unit,
    onRequestHistory: (String, ChartRange) -> Unit,
    onAddSymbol: () -> Unit,
    onOpenQuote: (String) -> Unit,
) {
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
        item(key = "tape") { TickerTape(state.quotes, onOpenQuote) }
        item(key = "desk") {
            DeskPage {
                DeskRow(Modifier.padding(top = 16.dp)) {
                    DeskColumn(DESK_MAIN_WEIGHT) {
                        DeskPanel(top = 0.dp) {
                            PriceHeroAndChart(
                                symbol = selected,
                                state = state,
                                range = range,
                                onRange = onRange,
                                onRequestHistory = onRequestHistory,
                                verdictPeers = IndexSymbols,
                                chartHeight = DESK_HERO_CHART,
                                trailing = {
                                    MarketStatus(state.quotes[MarketCatalog.SP500.symbol])
                                    Explainers.forSymbol(selected)?.let { InfoButton(it) }
                                },
                            )
                            Row(Modifier.padding(horizontal = PageGutter).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MarketCatalog.indices.forEach { meta ->
                                    IndexCard(meta.symbol, state.quotes[meta.symbol], meta.symbol == selected, onClick = { onSelect(meta.symbol) }, Modifier.weight(1f))
                                }
                            }
                        }
                        DeskRow {
                            DeskColumn(1f) {
                                DeskPanel(title = stringResource(Res.string.finance_stats_title), trailing = MarketCatalog.lookup(selected).shortName.resolve(), info = "prevclose") {
                                    CascadeIn(0) { IndexStats(selected, state) }
                                }
                            }
                            DeskColumn(1f) {
                                DeskPanel(top = 0.dp, bottom = 4.dp) { LongRunSection(selected, state, onRequestHistory) }
                            }
                        }
                        DeskPanel(title = stringResource(Res.string.finance_markets_macro_title), subtitle = stringResource(Res.string.finance_markets_macro_subtitle), bottom = 8.dp) {
                            DeskGrid(MarketCatalog.macro, columns = 2, gap = 0.dp) { meta -> QuoteRow(meta.symbol, state.quotes[meta.symbol], onClick = { onOpenQuote(meta.symbol) }) }
                        }
                    }
                    DeskColumn(1f) {
                        DeskPanel(
                            title = stringResource(Res.string.finance_markets_watchlist_title),
                            subtitle = stringResource(watchlistSubtitle(state)),
                            action = { AddSymbolButton(onAddSymbol) },
                            bottom = 8.dp,
                        ) {
                            if (state.watchEntries.size > 1) {
                                ChipRow(sorts, sort, { stringResource(it.label) }, FinanceTheme.colors.accent, onSort, Modifier.padding(bottom = 6.dp))
                            }
                            // A list of its own, scrolled in the rail, that composes only the rows in view: the
                            // sheet's tickers and every one added in the app, as many as those come to.
                            val entries = remember(state.watchEntries, state.quotes, sort) { sortWatchEntries(state.watchEntries, state.quotes, sort) }
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = WATCHLIST_RAIL_HEIGHT)) {
                                items(state.holdings, key = { "holdings-${it.currency}" }) { HoldingsCard(it) }
                                items(entries, key = { it.symbol }) { entry ->
                                    QuoteRow(
                                        entry.symbol,
                                        state.quotes[entry.symbol],
                                        onClick = { onOpenQuote(entry.symbol) },
                                        inSheet = entry.inSheet,
                                        position = entry.position,
                                        fallbackName = state.watchedSymbol(entry.symbol)?.name,
                                        fallbackKind = state.watchedSymbol(entry.symbol)?.kind,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "fine") {
            FinePrint(
                state.quotesError?.let { stringResource(Res.string.finance_markets_fine_print_error, it.resolve()) }
                    ?: stringResource(Res.string.finance_markets_fine_print),
                Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** How tall the desk's watchlist grows before it scrolls in the rail: about a laptop window's height, a dozen rows. */
private val WATCHLIST_RAIL_HEIGHT = 860.dp

/**
 * A symbol's price and chart, Robinhood's top of page: the rolling price, the change over the
 * chosen range, the chart under an aurora tinted by direction, and the range row. Scrubbing the
 * chart rolls the price to the point under the finger and shows its time.
 */
@Composable
internal fun PriceHeroAndChart(
    symbol: String,
    state: FinanceUiState,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
    onRequestHistory: (String, ChartRange) -> Unit,
    modifier: Modifier = Modifier,
    /** Symbols this one is switched between in place (the Markets tab's index cards); see the verdict below. */
    verdictPeers: List<String> = emptyList(),
    chartHeight: Dp = 240.dp,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = FinanceTheme.colors
    val quote = state.quotes[symbol]
    val meta = state.meta(symbol)
    val load = state.chart(symbol, range)
    val requestHistory by rememberUpdatedState(onRequestHistory)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(symbol, range, lifecycle) {
        requestHistory(symbol, range)
        // The day's chart keeps growing while the session runs — while the app is on screen.
        if (range == ChartRange.DAY) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(60_000)
                    requestHistory(symbol, range)
                }
            }
        }
    }

    val history = load?.history
    val series = history?.series?.takeIf { it.size > 1 } ?: if (range == ChartRange.DAY) quote?.intraday?.takeIf { it.size > 1 } else null
    val baseline = if (range == ChartRange.DAY) quote?.previousClose ?: history?.baseline else history?.baseline ?: series?.firstValue
    var scrub by remember(symbol, range) { mutableStateOf<Int?>(null) }

    val endValue = if (range == ChartRange.DAY) quote?.price ?: series?.lastValue else series?.lastValue
    val reference = baseline ?: series?.firstValue
    val shownIndex = scrub?.takeIf { series != null && it in 0 until series.size }
    val shownValue = shownIndex?.let { series!!.values[it] } ?: endValue
    val change = if (shownValue != null && reference != null) shownValue - reference else null
    val changePct = if (change != null && reference != null && reference != 0.0) change / reference * 100 else null
    val lineColor = colors.direction(change ?: 0.0)
    val gmt = history?.gmtOffsetSeconds ?: quote?.gmtOffsetSeconds ?: 0

    val name = meta.name.resolve()
    val caption = stringResource(rangeCaption(range))
    val changeText = if (change == null || changePct == null) {
        " "
    } else {
        val amount = stringResource(Res.string.finance_change_with_percent, FinanceFormat.priceChange(change, meta.kind, meta.currency), FinanceFormat.signedPercent(changePct))
        val whenText = if (shownIndex != null) {
            val t = series!!.times[shownIndex]
            // Daily and longer bars are stamped at the exchange's midnight in the offset of their
            // own date, which in winter is an hour off today's; written from midday they can't
            // slip back a day.
            val intraday = range == ChartRange.DAY || range == ChartRange.WEEK || range == ChartRange.MONTH
            (if (intraday) FinanceFormat.dateTime(t, gmt) else FinanceFormat.date(t + 12 * 3600, gmt)).resolve()
        } else {
            caption
        }
        stringResource(Res.string.finance_change_at, amount, whenText)
    }

    val extent = if (range == ChartRange.DAY && series != null && series.size > 1) {
        val span = (series.lastTime!! - series.times.first()).toFloat()
        val session = if (meta.kind == InstrumentKind.CRYPTO) 86_400f else 23_400f
        (span / session).coerceIn(0.08f, 1f)
    } else {
        1f
    }
    val now = LocalFinanceClock.current.now().epochSeconds
    val live = range == ChartRange.DAY && quote != null && (meta.kind == InstrumentKind.CRYPTO || quote.isSessionOpen(now)) && scrub == null

    Box(modifier.fillMaxWidth()) {
        AuroraBackground(lineColor, Modifier.matchParentSize(), intensity = if (series != null) 1f else 0.3f)
        Column(Modifier.padding(top = 12.dp)) {
            HeroNumber(
                caption = name,
                value = shownValue?.let { FinanceFormat.price(it, meta.kind, meta.currency) } ?: "—",
                change = changeText,
                changeColor = lineColor,
                trailing = trailing,
            )
            Spacer(Modifier.height(14.dp))
            if (series != null) {
                LineChart(
                    lines = listOf(ChartLine(series, lineColor, fill = true)),
                    baseline = if (range == ChartRange.DAY) baseline else null,
                    extent = extent,
                    live = live,
                    contentDescription = stringResource(Res.string.finance_markets_chart_description, name, caption),
                    onScrub = { scrub = it },
                    modifier = Modifier.fillMaxWidth().height(chartHeight),
                )
            } else if (load?.error != null) {
                Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
                    Text(load.error.resolve(), style = FinanceTheme.type.label, color = colors.textSecondary)
                }
            } else {
                Shimmer(Modifier.fillMaxWidth().height(chartHeight).padding(horizontal = PageGutter), corner = 14.dp)
            }
            Spacer(Modifier.height(10.dp))
            RangeSelector(PriceRanges, range, { stringResource(it.label) }, lineColor, onRange, Modifier.padding(horizontal = PageGutter - 4.dp))
            // Today's move in a sentence, with a sense of whether it's a big day. Its slot is as
            // tall as the longest of [verdictPeers]' sentences (the other index cards' symbols), so
            // switching cards never changes its height and the page under it stays put; and it only
            // fades while a finger scrubs rather than leaving.
            if (range == ChartRange.DAY && quote != null) {
                Box(
                    Modifier
                        .padding(horizontal = PageGutter, vertical = 6.dp)
                        .graphicsLayer { alpha = if (scrub == null) 1f else 0f },
                ) {
                    verdictPeers.forEach { peer ->
                        val peerQuote = state.quotes[peer]
                        if (peer != symbol && peerQuote != null) {
                            Text(
                                Narrator.quoteVerdict(peer, peerQuote).resolve(),
                                style = FinanceTheme.type.label,
                                color = Color.Transparent,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                    Text(Narrator.quoteVerdict(symbol, quote).resolve(), style = FinanceTheme.type.label, color = colors.textSecondary)
                }
            }
            HowToRead(
                if (range == ChartRange.DAY) {
                    stringResource(Res.string.finance_markets_how_to_read_day)
                } else {
                    stringResource(Res.string.finance_markets_how_to_read_range, caption.lowercase())
                },
            )
        }
    }
}

internal fun rangeCaption(range: ChartRange): StringResource = when (range) {
    ChartRange.DAY -> Res.string.finance_markets_range_day
    ChartRange.WEEK -> Res.string.finance_markets_range_week
    ChartRange.MONTH -> Res.string.finance_markets_range_month
    ChartRange.THREE_MONTHS -> Res.string.finance_markets_range_three_months
    ChartRange.YEAR_TO_DATE -> Res.string.finance_markets_range_ytd
    ChartRange.YEAR -> Res.string.finance_markets_range_year
    ChartRange.FIVE_YEARS -> Res.string.finance_markets_range_five_years
    ChartRange.MAX -> Res.string.finance_markets_range_max
}

/** "Market open" with a live dot, or when it next opens, from the S&P's session times. */
@Composable
private fun MarketStatus(sp: Quote?) {
    if (sp == null) return
    val now = LocalFinanceClock.current.now().epochSeconds
    val open = sp.isSessionOpen(now)
    val colors = FinanceTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color = if (open) colors.gain else colors.textTertiary, size = 6.dp, pulsing = open)
        Spacer(Modifier.width(6.dp))
        val text = when {
            open -> stringResource(Res.string.finance_markets_open_closes, sp.sessionEndEpochSeconds?.let { FinanceFormat.time(it, sp.gmtOffsetSeconds).resolve() } ?: "")
            sp.sessionStartEpochSeconds != null && now < sp.sessionStartEpochSeconds -> stringResource(Res.string.finance_markets_opens, FinanceFormat.time(sp.sessionStartEpochSeconds, sp.gmtOffsetSeconds).resolve())
            else -> stringResource(Res.string.finance_markets_closed)
        }
        Text(text, style = FinanceTheme.type.micro, color = if (open) colors.gain else colors.textSecondary)
    }
}

/** A card per index under the hero: tap to put that index's chart up. */
@Composable
private fun IndexCard(symbol: String, quote: Quote?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val meta = MarketCatalog.lookup(symbol)
    val dir = colors.direction(quote?.change ?: 0.0)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .handCursor()
            .clip(shape)
            .background(if (selected) dir.copy(alpha = 0.10f) else colors.surface)
            .border(1.dp, if (selected) dir.copy(alpha = 0.7f) else colors.hairline, shape)
            .clickable(onClick = onClick)
            .testTag("finance_index_$symbol")
            .padding(12.dp),
    ) {
        val shortName = meta.shortName.resolve()
        Text(shortName, style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1)
        // What it is, in plain words ("Small companies").
        Explainers.forSymbol(symbol)?.let { Explainers.byId(it) }?.title?.let { stringResource(it) }?.takeIf { it != shortName }?.let {
            Text(it, style = FinanceTheme.type.micro, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(2.dp))
        if (quote != null) {
            Text(FinanceFormat.price(quote.price, meta.kind, meta.currency), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1)
            Text(FinanceFormat.signedPercent(quote.changePercent), style = FinanceTheme.type.label, color = dir)
            Spacer(Modifier.height(8.dp))
            Sparkline(quote.intraday, dir, Modifier.fillMaxWidth().height(34.dp), baseline = quote.previousClose)
        } else {
            Shimmer(Modifier.fillMaxWidth().height(70.dp))
        }
    }
}

/** Every followed symbol sliding past along the top, like a trading floor's tape. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TickerTape(quotes: Map<String, Quote>, onOpen: (String) -> Unit) {
    val symbols = MarketCatalog.indices + MarketCatalog.macro
    val colors = FinanceTheme.colors
    if (quotes.isEmpty()) {
        Spacer(Modifier.height(30.dp))
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 0, repeatDelayMillis = 0, velocity = 38.dp),
    ) {
        symbols.forEach { meta ->
            val q = quotes[meta.symbol] ?: return@forEach
            Row(
                Modifier.clickable { onOpen(meta.symbol) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(meta.shortName.resolve(), style = FinanceTheme.type.micro, color = colors.textSecondary)
                Spacer(Modifier.width(6.dp))
                Text(FinanceFormat.price(q.price, meta.kind, meta.currency), style = FinanceTheme.type.micro, color = colors.textPrimary)
                Spacer(Modifier.width(4.dp))
                Text(FinanceFormat.signedPercent(q.changePercent), style = FinanceTheme.type.micro, color = colors.direction(q.change))
            }
            Box(Modifier.size(3.dp).clip(CircleShape).background(colors.hairline).align(Alignment.CenterVertically))
        }
    }
}

/** A quote's own page: its price and chart, what it is, and its stats. */
@Composable
internal fun QuoteDetailScreen(
    symbol: String,
    state: FinanceUiState,
    contentPadding: PaddingValues,
    onRequestHistory: (String, ChartRange) -> Unit,
    onFollow: (String) -> Unit,
    onUnfollow: (String) -> Unit,
    onSetPosition: (String, Position?) -> Unit,
) {
    var range by rememberSaveable(symbol) { mutableStateOf(ChartRange.DAY) }
    val q = state.quotes[symbol]
    val meta = state.meta(symbol)
    val explainer = Explainers.forSymbol(symbol)?.let { Explainers.byId(it) }
    val hero: @Composable (Dp) -> Unit = { height ->
        PriceHeroAndChart(symbol, state, range, { range = it }, onRequestHistory, chartHeight = height, trailing = { Explainers.forSymbol(symbol)?.let { InfoButton(it) } })
    }
    val holding: @Composable () -> Unit = {
        WatchStatusRow(symbol, state, onFollow = { onFollow(symbol) }, onUnfollow = { onUnfollow(symbol) })
        if (meta.kind.isHoldable()) PositionSection(symbol, state) { onSetPosition(symbol, it) }
    }
    val about: (@Composable () -> Unit)? = when {
        explainer != null -> {
            {
                SectionHeader(stringResource(Res.string.finance_markets_what_is_it), info = explainer.id)
                Column(Modifier.padding(horizontal = PageGutter)) {
                    Text(stringResource(explainer.oneLiner), style = FinanceTheme.type.body, color = FinanceTheme.colors.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(Res.string.finance_markets_why_it_matters), style = FinanceTheme.type.micro, color = FinanceTheme.colors.accent)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(explainer.whyYou), style = FinanceTheme.type.body, color = FinanceTheme.colors.textSecondary)
                    Narrator.forYou(explainer.id, state.readings, state.quotes, state.finance)?.let { mine ->
                        Callout(stringResource(Res.string.finance_callout_for_you), mine.resolve(), FinanceTheme.colors.accent, Icons.Filled.Person)
                    }
                }
            }
        }

        meta.about != null -> {
            {
                SectionHeader(stringResource(Res.string.finance_markets_about))
                Text(
                    stringResource(meta.about),
                    style = FinanceTheme.type.body,
                    color = FinanceTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = PageGutter),
                )
            }
        }

        else -> null
    }
    val stats: @Composable () -> Unit = {
        SectionHeader(stringResource(Res.string.finance_stats_title))
        StatGrid(
            listOf(
                stringResource(Res.string.finance_markets_price) to (q?.price?.let { FinanceFormat.price(it, meta.kind, meta.currency) } ?: "—"),
                stringResource(Res.string.finance_markets_prev_close) to (q?.previousClose?.let { FinanceFormat.price(it, meta.kind, meta.currency) } ?: "—"),
                stringResource(Res.string.finance_markets_day_high) to (q?.dayHigh?.let { FinanceFormat.price(it, meta.kind, meta.currency) } ?: "—"),
                stringResource(Res.string.finance_markets_day_low) to (q?.dayLow?.let { FinanceFormat.price(it, meta.kind, meta.currency) } ?: "—"),
                stringResource(Res.string.finance_markets_52w_high) to (q?.fiftyTwoWeekHigh?.let { FinanceFormat.price(it, meta.kind, meta.currency) } ?: "—"),
                stringResource(Res.string.finance_markets_52w_low) to (q?.fiftyTwoWeekLow?.let { FinanceFormat.price(it, meta.kind, meta.currency) } ?: "—"),
                stringResource(Res.string.finance_markets_volume) to (q?.volume?.takeIf { it > 0 }?.let(FinanceFormat::volume) ?: "—"),
                stringResource(Res.string.finance_markets_symbol) to symbol,
            ),
        )
        val hi = q?.fiftyTwoWeekHigh
        val lo = q?.fiftyTwoWeekLow
        if (q != null && hi != null && lo != null) {
            RangeBar(lo, hi, q.price, FinanceFormat.price(lo, meta.kind, meta.currency), FinanceFormat.price(hi, meta.kind, meta.currency))
        }
    }
    if (LocalFinanceDesk.current) {
        // A quote's page on a desk: the chart with its long run under it, and what is held, what
        // it is and the day's stats down a rail beside it.
        LazyColumn(contentPadding = contentPadding) {
            item {
                DeskPage {
                    DeskRow(Modifier.padding(top = 8.dp)) {
                        DeskColumn(DESK_MAIN_WEIGHT) {
                            DeskPanel(top = 0.dp) { hero(DESK_HERO_CHART + 20.dp) }
                            DeskPanel(top = 0.dp, bottom = 4.dp) { LongRunSection(symbol, state, onRequestHistory, deskColumns = 4) }
                        }
                        DeskColumn(1f) {
                            DeskPanel(bottom = 8.dp) { holding() }
                            about?.let { DeskPanel(top = 0.dp) { it() } }
                            DeskPanel(top = 0.dp) { stats() }
                        }
                    }
                }
            }
            item { FinePrint(stringResource(Res.string.finance_markets_quote_fine_print), Modifier.padding(top = 12.dp)) }
        }
        return
    }
    LazyColumn(contentPadding = contentPadding) {
        item { hero(240.dp) }
        item { Column { holding() } }
        about?.let { item { Column { it() } } }
        item { Column { stats() } }
        item { LongRunSection(symbol, state, onRequestHistory) }
        item { FinePrint(stringResource(Res.string.finance_markets_quote_fine_print)) }
    }
}
