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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.ui.components.AuroraBackground
import com.meticulouscreations.homesafe.finance.ui.components.CascadeIn
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.finance.ui.components.RangeSelector
import com.meticulouscreations.homesafe.finance.ui.components.Shimmer
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** The ranges the Markets tab and a quote's page offer. */
internal val PriceRanges = listOf(
    ChartRange.DAY,
    ChartRange.WEEK,
    ChartRange.MONTH,
    ChartRange.THREE_MONTHS,
    ChartRange.YEAR_TO_DATE,
    ChartRange.YEAR,
    ChartRange.FIVE_YEARS,
)

@Composable
internal fun MarketsScreen(
    state: FinanceUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onRequestHistory: (String, ChartRange) -> Unit,
    onOpenQuote: (String) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(MarketCatalog.SP500.symbol) }
    var range by rememberSaveable { mutableStateOf(ChartRange.DAY) }

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
        item(key = "tape") { TickerTape(state.quotes, onOpenQuote) }
        item(key = "hero") {
            PriceHeroAndChart(
                symbol = selected,
                state = state,
                range = range,
                onRange = { range = it },
                onRequestHistory = onRequestHistory,
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
                    IndexCard(meta.symbol, state.quotes[meta.symbol], meta.symbol == selected) { selected = meta.symbol }
                }
            }
        }
        item(key = "stats-h") { SectionHeader("Stats", trailing = MarketCatalog.lookup(selected).shortName, info = "prevclose") }
        item(key = "stats") {
            val q = state.quotes[selected]
            val kind = MarketCatalog.lookup(selected).kind
            CascadeIn(0) {
                Column {
                    StatGrid(
                        listOf(
                            "Prev close" to (q?.previousClose?.let { FinanceFormat.price(it, kind) } ?: "—"),
                            "Volume" to (q?.volume?.takeIf { it > 0 }?.let(FinanceFormat::volume) ?: "—"),
                            "Day high" to (q?.dayHigh?.let { FinanceFormat.price(it, kind) } ?: "—"),
                            "Day low" to (q?.dayLow?.let { FinanceFormat.price(it, kind) } ?: "—"),
                        ),
                    )
                    val hi = q?.fiftyTwoWeekHigh
                    val lo = q?.fiftyTwoWeekLow
                    if (q != null && hi != null && lo != null) {
                        RangeBar(lo, hi, q.price, FinanceFormat.price(lo, kind), FinanceFormat.price(hi, kind))
                    }
                }
            }
        }
        item(key = "macro-h") { SectionHeader("Other big signals", subtitle = "Interest rates, gold, oil, crypto and the dollar — tap any to learn why it matters") }
        items(MarketCatalog.macro, key = { "macro-${it.symbol}" }) { meta ->
            QuoteRow(meta.symbol, state.quotes[meta.symbol], onClick = { onOpenQuote(meta.symbol) })
        }
        item(key = "watch-h") {
            SectionHeader("Watchlist", subtitle = if (state.finance?.watchlist?.isNotEmpty() == true) "From your budget sheet" else null)
        }
        items(state.watchlist, key = { "watch-$it" }) { symbol ->
            QuoteRow(symbol, state.quotes[symbol], onClick = { onOpenQuote(symbol) })
        }
        item(key = "fine") {
            FinePrint(
                (state.quotesError?.let { "Couldn't refresh: $it. " } ?: "") +
                    "Quotes from Yahoo Finance, delayed up to 15 minutes. Updated every 15 seconds while markets are open.",
            )
        }
    }
}

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
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = FinanceTheme.colors
    val meta = MarketCatalog.lookup(symbol)
    val quote = state.quotes[symbol]
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

    val changeText = when {
        change == null || changePct == null -> " "

        shownIndex != null -> {
            val t = series!!.times[shownIndex]
            // Daily and longer bars are stamped at the exchange's midnight in the offset of their
            // own date, which in winter is an hour off today's; written from midday they can't
            // slip back a day.
            val intraday = range == ChartRange.DAY || range == ChartRange.WEEK || range == ChartRange.MONTH
            val whenText = if (intraday) FinanceFormat.dateTime(t, gmt) else FinanceFormat.date(t + 12 * 3600, gmt)
            "${FinanceFormat.priceChange(change, meta.kind)} (${FinanceFormat.signedPercent(changePct)})  $whenText"
        }

        else -> "${FinanceFormat.priceChange(change, meta.kind)} (${FinanceFormat.signedPercent(changePct)})  ${rangeCaption(range)}"
    }

    val extent = if (range == ChartRange.DAY && series != null && series.size > 1) {
        val span = (series.lastTime!! - series.times.first()).toFloat()
        val session = if (meta.kind == InstrumentKind.CRYPTO) 86_400f else 23_400f
        (span / session).coerceIn(0.08f, 1f)
    } else {
        1f
    }
    val now = Clock.System.now().epochSeconds
    val live = range == ChartRange.DAY && quote != null && (meta.kind == InstrumentKind.CRYPTO || quote.isSessionOpen(now)) && scrub == null

    Box(modifier.fillMaxWidth()) {
        AuroraBackground(lineColor, Modifier.matchParentSize(), intensity = if (series != null) 1f else 0.3f)
        Column(Modifier.padding(top = 12.dp)) {
            HeroNumber(
                caption = meta.name,
                value = shownValue?.let { FinanceFormat.price(it, meta.kind) } ?: "—",
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
                    contentDescription = "${meta.name} chart, ${rangeCaption(range)}",
                    onScrub = { scrub = it },
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
            } else if (load?.error != null) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text(load.error, style = FinanceTheme.type.label, color = colors.textSecondary)
                }
            } else {
                Shimmer(Modifier.fillMaxWidth().height(240.dp).padding(horizontal = PageGutter), corner = 14.dp)
            }
            Spacer(Modifier.height(10.dp))
            RangeSelector(PriceRanges, range, { it.label }, lineColor, onRange, Modifier.padding(horizontal = PageGutter - 4.dp))
            // Today's move in a sentence, with a sense of whether it's a big day.
            if (range == ChartRange.DAY && quote != null && scrub == null) {
                Text(
                    Narrator.quoteVerdict(symbol, quote),
                    style = FinanceTheme.type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 6.dp),
                )
            }
        }
    }
}

internal fun rangeCaption(range: ChartRange): String = when (range) {
    ChartRange.DAY -> "Today"
    ChartRange.WEEK -> "Past week"
    ChartRange.MONTH -> "Past month"
    ChartRange.THREE_MONTHS -> "Past 3 months"
    ChartRange.YEAR_TO_DATE -> "Year to date"
    ChartRange.YEAR -> "Past year"
    ChartRange.FIVE_YEARS -> "Past 5 years"
    ChartRange.MAX -> "All time"
}

/** "Market open" with a live dot, or when it next opens, from the S&P's session times. */
@Composable
private fun MarketStatus(sp: Quote?) {
    if (sp == null) return
    val now = Clock.System.now().epochSeconds
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
            open -> "Market open · closes ${sp.sessionEndEpochSeconds?.let { FinanceFormat.time(it, sp.gmtOffsetSeconds) } ?: ""}"
            sp.sessionStartEpochSeconds != null && now < sp.sessionStartEpochSeconds -> "Opens ${FinanceFormat.time(sp.sessionStartEpochSeconds, sp.gmtOffsetSeconds)}"
            else -> "Market closed"
        }
        Text(text, style = FinanceTheme.type.micro, color = if (open) colors.gain else colors.textSecondary)
    }
}

/** A card per index under the hero: tap to put that index's chart up. */
@Composable
private fun IndexCard(symbol: String, quote: Quote?, selected: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val meta = MarketCatalog.lookup(symbol)
    val dir = colors.direction(quote?.change ?: 0.0)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(148.dp)
            .clip(shape)
            .background(if (selected) dir.copy(alpha = 0.10f) else colors.surface)
            .border(1.dp, if (selected) dir.copy(alpha = 0.7f) else colors.hairline, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(meta.shortName, style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1)
        // What it is, in plain words ("Small companies").
        Explainers.forSymbol(symbol)?.let { Explainers.byId(it) }?.title?.takeIf { it != meta.shortName }?.let {
            Text(it, style = FinanceTheme.type.micro, color = colors.textTertiary, maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        if (quote != null) {
            Text(FinanceFormat.price(quote.price, meta.kind), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1)
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
                Text(meta.shortName, style = FinanceTheme.type.micro, color = colors.textSecondary)
                Spacer(Modifier.width(6.dp))
                Text(FinanceFormat.price(q.price, meta.kind), style = FinanceTheme.type.micro, color = colors.textPrimary)
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
) {
    var range by rememberSaveable(symbol) { mutableStateOf(ChartRange.DAY) }
    val meta = MarketCatalog.lookup(symbol)
    val q = state.quotes[symbol]
    LazyColumn(contentPadding = contentPadding) {
        item {
            PriceHeroAndChart(symbol, state, range, { range = it }, onRequestHistory, trailing = { Explainers.forSymbol(symbol)?.let { InfoButton(it) } })
        }
        val explainer = Explainers.forSymbol(symbol)?.let { Explainers.byId(it) }
        if (explainer != null) {
            item { SectionHeader("What is it?", info = explainer.id) }
            item {
                Column(Modifier.padding(horizontal = PageGutter)) {
                    Text(explainer.oneLiner, style = FinanceTheme.type.body, color = FinanceTheme.colors.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    Text("WHY IT MATTERS TO YOU", style = FinanceTheme.type.micro, color = FinanceTheme.colors.accent)
                    Spacer(Modifier.height(2.dp))
                    Text(explainer.whyYou, style = FinanceTheme.type.body, color = FinanceTheme.colors.textSecondary)
                    Narrator.forYou(explainer.id, state.readings, state.quotes, state.finance)?.let { mine ->
                        Callout("What it means for you", mine, FinanceTheme.colors.accent, Icons.Filled.Person)
                    }
                }
            }
        } else if (meta.about.isNotEmpty()) {
            item { SectionHeader("About") }
            item {
                Text(
                    meta.about,
                    style = FinanceTheme.type.body,
                    color = FinanceTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = PageGutter),
                )
            }
        }
        item { SectionHeader("Stats") }
        item {
            StatGrid(
                listOf(
                    "Price" to (q?.price?.let { FinanceFormat.price(it, meta.kind) } ?: "—"),
                    "Prev close" to (q?.previousClose?.let { FinanceFormat.price(it, meta.kind) } ?: "—"),
                    "Day high" to (q?.dayHigh?.let { FinanceFormat.price(it, meta.kind) } ?: "—"),
                    "Day low" to (q?.dayLow?.let { FinanceFormat.price(it, meta.kind) } ?: "—"),
                    "52W high" to (q?.fiftyTwoWeekHigh?.let { FinanceFormat.price(it, meta.kind) } ?: "—"),
                    "52W low" to (q?.fiftyTwoWeekLow?.let { FinanceFormat.price(it, meta.kind) } ?: "—"),
                    "Volume" to (q?.volume?.takeIf { it > 0 }?.let(FinanceFormat::volume) ?: "—"),
                    "Symbol" to symbol,
                ),
            )
        }
        val hi = q?.fiftyTwoWeekHigh
        val lo = q?.fiftyTwoWeekLow
        if (q != null && hi != null && lo != null) {
            item { RangeBar(lo, hi, q.price, FinanceFormat.price(lo, meta.kind), FinanceFormat.price(hi, meta.kind)) }
        }
        item { FinePrint("From Yahoo Finance; may be delayed up to 15 minutes. Not investment advice.") }
    }
}
