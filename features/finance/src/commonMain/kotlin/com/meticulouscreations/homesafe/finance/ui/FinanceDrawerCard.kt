package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_drawer_change_today
import homesafe.shared.generated.resources.shell_drawer_finance
import homesafe.shared.generated.resources.shell_drawer_finance_locked
import homesafe.shared.generated.resources.shell_drawer_finance_subtitle
import homesafe.shared.generated.resources.shell_drawer_open_finance
import org.jetbrains.compose.resources.stringResource

/**
 * The Finance app's door: its name, then what Settings chose for it ([FinanceUiState.drawerItems]):
 * markets, each with today's price, change and sparkline (one across the card, more two to a row),
 * and the household's figures from the sheet once it is in. While the app is [locked] those
 * figures give way to a line saying so: the markets are anyone's to see, the household's money isn't.
 */
@Composable
fun FinanceDrawerCard(finance: FinanceUiState, locked: Boolean, onOpen: (Offset) -> Unit, modifier: Modifier = Modifier) {
    val palette = remember { FinancePalette() }
    var center by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(22.dp)
    val markets = finance.drawerItems.filter { it.isMarket }
    val money = finance.drawerItems.filterNot { it.isMarket }
    Column(
        modifier
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
        if (markets.size == 1) {
            Spacer(Modifier.height(14.dp))
            WideMarket(finance, markets.single(), palette)
        } else if (markets.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Pairs(markets) { item, tile -> MarketTile(finance, item, palette, tile) }
        }
        if (money.isEmpty()) return@Column
        val sheet = finance.finance
        if (locked) {
            Spacer(Modifier.height(if (markets.isEmpty()) 14.dp else 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("drawer_finance_locked")) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(14.dp))
                Text(stringResource(Res.string.shell_drawer_finance_locked), style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
            }
        } else if (sheet != null) {
            // Only what the sheet has an answer for: a figure it doesn't give is left out, not shown as a dash.
            val shown = money.mapNotNull { item -> moneyValue(sheet, item)?.let { item to it } }
            if (shown.isEmpty()) return@Column
            Spacer(Modifier.height(if (markets.isEmpty()) 14.dp else 12.dp))
            Pairs(shown) { (item, value), tile ->
                Column(tile.testTag("drawer_finance_${item.name.lowercase()}")) {
                    Text(stringResource(item.label), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        FinanceFormat.compactMoney(value),
                        style = MaterialTheme.typography.titleSmall,
                        // Owing isn't the good news the rest of the household's figures are.
                        color = if (item == FinanceDrawerItem.DEBT) Color.White else palette.accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** [items] two to a row, each [tile] half the card; an odd one out keeps to its half. */
@Composable
private fun <T> Pairs(items: List<T>, modifier: Modifier = Modifier, tile: @Composable (T, Modifier) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The one market chosen, as the card has always shown the S&P: name, price and change, with the day's sparkline beside them. */
@Composable
private fun WideMarket(finance: FinanceUiState, item: FinanceDrawerItem, palette: FinancePalette, modifier: Modifier = Modifier) {
    val symbol = item.symbol ?: return
    val meta = MarketCatalog.lookup(symbol)
    val quote = finance.quotes[symbol]
    val dir = palette.direction(quote?.change ?: 0.0)
    Row(modifier.testTag("drawer_finance_${item.name.lowercase()}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(meta.shortName.resolve(), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            Text(quote?.let { FinanceFormat.price(it.price, meta.kind, it.currency) } ?: "—", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(quote?.let { stringResource(Res.string.shell_drawer_change_today, FinanceFormat.signedPercent(it.changePercent)) } ?: " ", style = MaterialTheme.typography.labelSmall, color = dir)
        }
        if (quote != null && quote.intraday.size > 1) {
            Sparkline(quote.intraday, dir, Modifier.width(96.dp).height(40.dp), baseline = quote.previousClose)
        }
    }
}

/** One of several markets, in half the card: name and change on a line, the price, and the day's sparkline under them. */
@Composable
private fun MarketTile(finance: FinanceUiState, item: FinanceDrawerItem, palette: FinancePalette, modifier: Modifier = Modifier) {
    val symbol = item.symbol ?: return
    val meta = MarketCatalog.lookup(symbol)
    val quote = finance.quotes[symbol]
    val dir = palette.direction(quote?.change ?: 0.0)
    Column(modifier.testTag("drawer_finance_${item.name.lowercase()}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(meta.shortName.resolve(), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Text(quote?.let { FinanceFormat.signedPercent(it.changePercent) } ?: " ", style = MaterialTheme.typography.labelSmall, color = dir, maxLines = 1)
        }
        Text(quote?.let { FinanceFormat.price(it.price, meta.kind, it.currency) } ?: "—", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
        // The line's room is kept before the day has two points, so the card doesn't grow when it does.
        Box(Modifier.fillMaxWidth().height(26.dp).padding(top = 4.dp)) {
            if (quote != null && quote.intraday.size > 1) Sparkline(quote.intraday, dir, Modifier.matchParentSize(), baseline = quote.previousClose)
        }
    }
}

/** [item]'s amount from the sheet, or null where the sheet doesn't give it (or [item] is a market). */
internal fun moneyValue(finance: PersonalFinance, item: FinanceDrawerItem): Double? = when (item) {
    FinanceDrawerItem.NET_WORTH -> finance.netWorth
    FinanceDrawerItem.ASSETS -> finance.totalAssets
    FinanceDrawerItem.INVESTMENTS -> finance.investmentsTotal
    FinanceDrawerItem.CASH -> finance.liquidCash
    FinanceDrawerItem.DEBT -> finance.consumerDebt
    else -> null
}
