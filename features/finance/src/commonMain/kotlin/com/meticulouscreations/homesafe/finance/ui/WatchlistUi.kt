package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.SymbolSearch
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.Holdings
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.LongRunStats
import com.meticulouscreations.homesafe.finance.domain.Position
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.SymbolMatch
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_save
import homesafe.shared.generated.resources.longrun_all_time_high
import homesafe.shared.generated.resources.longrun_at_record
import homesafe.shared.generated.resources.longrun_data_from
import homesafe.shared.generated.resources.longrun_fine_print
import homesafe.shared.generated.resources.longrun_from_high
import homesafe.shared.generated.resources.longrun_history_failed
import homesafe.shared.generated.resources.longrun_per_year
import homesafe.shared.generated.resources.longrun_set_on
import homesafe.shared.generated.resources.longrun_since_start
import homesafe.shared.generated.resources.longrun_since_year
import homesafe.shared.generated.resources.longrun_title
import homesafe.shared.generated.resources.longrun_week_of
import homesafe.shared.generated.resources.longrun_years
import homesafe.shared.generated.resources.watchlist_add
import homesafe.shared.generated.resources.watchlist_add_click
import homesafe.shared.generated.resources.watchlist_editor_amount_held
import homesafe.shared.generated.resources.watchlist_editor_cost_per_coin
import homesafe.shared.generated.resources.watchlist_editor_cost_per_share
import homesafe.shared.generated.resources.watchlist_editor_note
import homesafe.shared.generated.resources.watchlist_editor_title
import homesafe.shared.generated.resources.watchlist_holdings_note
import homesafe.shared.generated.resources.watchlist_holdings_since_bought
import homesafe.shared.generated.resources.watchlist_holdings_since_bought_percent
import homesafe.shared.generated.resources.watchlist_holdings_title
import homesafe.shared.generated.resources.watchlist_holdings_title_currency
import homesafe.shared.generated.resources.watchlist_holdings_today
import homesafe.shared.generated.resources.watchlist_holdings_today_percent
import homesafe.shared.generated.resources.watchlist_position_add
import homesafe.shared.generated.resources.watchlist_position_average_cost
import homesafe.shared.generated.resources.watchlist_position_cost_basis
import homesafe.shared.generated.resources.watchlist_position_edit
import homesafe.shared.generated.resources.watchlist_position_entered
import homesafe.shared.generated.resources.watchlist_position_held
import homesafe.shared.generated.resources.watchlist_position_invite
import homesafe.shared.generated.resources.watchlist_position_market_value
import homesafe.shared.generated.resources.watchlist_position_not_entered
import homesafe.shared.generated.resources.watchlist_position_own_some
import homesafe.shared.generated.resources.watchlist_position_return_percent
import homesafe.shared.generated.resources.watchlist_position_shares
import homesafe.shared.generated.resources.watchlist_position_title
import homesafe.shared.generated.resources.watchlist_position_today
import homesafe.shared.generated.resources.watchlist_position_today_percent
import homesafe.shared.generated.resources.watchlist_position_total_return
import homesafe.shared.generated.resources.watchlist_search_add_symbol
import homesafe.shared.generated.resources.watchlist_search_added
import homesafe.shared.generated.resources.watchlist_search_clear
import homesafe.shared.generated.resources.watchlist_search_description
import homesafe.shared.generated.resources.watchlist_search_failed
import homesafe.shared.generated.resources.watchlist_search_hint
import homesafe.shared.generated.resources.watchlist_search_no_matches
import homesafe.shared.generated.resources.watchlist_search_searching
import homesafe.shared.generated.resources.watchlist_search_subtitle
import homesafe.shared.generated.resources.watchlist_search_title
import homesafe.shared.generated.resources.watchlist_status_added
import homesafe.shared.generated.resources.watchlist_status_in_sheet
import homesafe.shared.generated.resources.watchlist_status_not_added
import homesafe.shared.generated.resources.watchlist_status_remove
import homesafe.shared.generated.resources.watchlist_subtitle_app
import homesafe.shared.generated.resources.watchlist_subtitle_sheet
import homesafe.shared.generated.resources.watchlist_subtitle_sheet_and_app
import homesafe.shared.generated.resources.watchlist_subtitle_suggestions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** "+ Add", the watchlist header's button. */
@Composable
internal fun AddSymbolButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(colors.gain.copy(alpha = 0.14f))
            .clickable(onClickLabel = stringResource(Res.string.watchlist_add_click), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("finance_add_symbol"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = colors.gain, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(Res.string.watchlist_add), style = FinanceTheme.type.label, color = colors.gain)
    }
}

/** What the watchlist's heading says about where its rows come from. */
internal fun watchlistSubtitle(state: FinanceUiState): StringResource {
    val entries = state.watchEntries
    val fromSheet = entries.any { it.inSheet }
    val fromApp = entries.any { it.addedInApp }
    return when {
        fromSheet && fromApp -> Res.string.watchlist_subtitle_sheet_and_app
        fromSheet -> Res.string.watchlist_subtitle_sheet
        fromApp -> Res.string.watchlist_subtitle_app
        else -> Res.string.watchlist_subtitle_suggestions
    }
}

/**
 * The watchlist's rows, with what's held summed up above them when anything is: the same on the
 * Markets and Wallet tabs.
 */
internal fun LazyListScope.watchlistItems(state: FinanceUiState, keyPrefix: String, onOpenQuote: (String) -> Unit) {
    state.holdings.forEach { holdings -> item(key = "$keyPrefix-holdings-${holdings.currency}") { HoldingsCard(holdings) } }
    items(state.watchEntries, key = { "$keyPrefix-${it.symbol}" }) { entry ->
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

/** What the positions entered are worth together, today's move, and the gain since buying. */
@Composable
internal fun HoldingsCard(holdings: Holdings, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    FinanceCard(modifier.padding(bottom = 6.dp)) {
        Text(
            if (holdings.isUsd) {
                stringResource(Res.string.watchlist_holdings_title)
            } else {
                stringResource(Res.string.watchlist_holdings_title_currency, holdings.currency.uppercase())
            },
            style = FinanceTheme.type.micro,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(FinanceFormat.money(holdings.value, currency = holdings.currency), style = FinanceTheme.type.title, color = colors.textPrimary)
        Spacer(Modifier.height(2.dp))
        val day = FinanceFormat.signedMoney(holdings.dayChange, currency = holdings.currency)
        Text(
            holdings.dayChangePercent?.let { stringResource(Res.string.watchlist_holdings_today_percent, day, FinanceFormat.signedPercent(it)) }
                ?: stringResource(Res.string.watchlist_holdings_today, day),
            style = FinanceTheme.type.label,
            color = colors.direction(holdings.dayChange),
        )
        val gain = holdings.totalGain
        if (gain != null) {
            val gainText = FinanceFormat.signedMoney(gain, currency = holdings.currency)
            Text(
                holdings.totalGainPercent?.let { stringResource(Res.string.watchlist_holdings_since_bought_percent, gainText, FinanceFormat.signedPercent(it)) }
                    ?: stringResource(Res.string.watchlist_holdings_since_bought, gainText),
                style = FinanceTheme.type.label,
                color = colors.direction(gain),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            pluralStringResource(Res.plurals.watchlist_holdings_note, holdings.count, holdings.count),
            style = FinanceTheme.type.micro,
            color = colors.textTertiary,
        )
    }
}

/**
 * Search Yahoo for a stock, ETF, fund, coin or index and add it to the watchlist. The sheet's own
 * tickers say so instead of offering to add them again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddSymbolSheet(
    search: SymbolSearch,
    sheetSymbols: List<String>,
    addedSymbols: Set<String>,
    onQuery: (String) -> Unit,
    onAdd: (SymbolMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(stringResource(Res.string.watchlist_search_title), style = type.title, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(Res.string.watchlist_search_subtitle), style = type.label, color = colors.textSecondary)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (search.query.isEmpty()) Text(stringResource(Res.string.watchlist_search_hint), style = type.body, color = colors.textTertiary)
                        val searchDescription = stringResource(Res.string.watchlist_search_description)
                        BasicTextField(
                            value = search.query,
                            onValueChange = onQuery,
                            singleLine = true,
                            textStyle = type.body.copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .testTag("finance_symbol_search")
                                .semantics { contentDescription = searchDescription },
                        )
                    }
                    if (search.query.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.watchlist_search_clear),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onQuery("") },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // A fixed height, so the sheet doesn't resize under the finger as results come and go.
            Box(Modifier.fillMaxWidth().height(380.dp)) {
                when {
                    search.results.isNotEmpty() -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(search.results, key = { it.symbol }) { match ->
                            val status = when (match.symbol) {
                                in sheetSymbols -> MatchStatus.IN_SHEET
                                in addedSymbols -> MatchStatus.ADDED
                                else -> MatchStatus.NEW
                            }
                            SymbolMatchRow(match, status) { onAdd(match) }
                        }
                    }

                    search.loading -> Text(stringResource(Res.string.watchlist_search_searching), style = type.label, color = colors.textSecondary, modifier = Modifier.padding(24.dp))

                    search.error != null -> Text(stringResource(Res.string.watchlist_search_failed, search.error.resolve()), style = type.label, color = colors.loss, modifier = Modifier.padding(24.dp))

                    search.query.isNotBlank() -> Text(stringResource(Res.string.watchlist_search_no_matches, search.query.trim()), style = type.label, color = colors.textSecondary, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

private enum class MatchStatus { NEW, ADDED, IN_SHEET }

@Composable
private fun SymbolMatchRow(match: SymbolMatch, status: MatchStatus, onAdd: () -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val addLabel = stringResource(Res.string.watchlist_search_add_symbol, match.symbol)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = status == MatchStatus.NEW, onClickLabel = addLabel, onClick = onAdd)
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(match.symbol, style = type.bodyStrong, color = colors.textPrimary, maxLines = 1)
            Text(match.name, style = type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(match.typeLabel.takeIf { it.isNotBlank() }, match.exchange).joinToString(stringResource(Res.string.common_dot_separator)), style = type.micro, color = colors.textTertiary, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        when (status) {
            MatchStatus.IN_SHEET -> SheetBadge()

            MatchStatus.ADDED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.gain, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(Res.string.watchlist_search_added), style = type.label, color = colors.gain)
            }

            MatchStatus.NEW -> Box(
                Modifier.size(32.dp).clip(CircleShape).background(colors.gain.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = addLabel, tint = colors.gain, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Whether a kind can be owned in shares or coins (an index or a yield can't). */
internal fun InstrumentKind.isHoldable(): Boolean = this == InstrumentKind.EQUITY || this == InstrumentKind.CRYPTO

/**
 * Where a quote's page stands with the watchlist: in the budget sheet, added in the app (with a
 * way to take it off), or neither (with a way to add it).
 */
@Composable
internal fun WatchStatusRow(symbol: String, state: FinanceUiState, onFollow: () -> Unit, onUnfollow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val inSheet = symbol in state.sheetSymbols
    val added = state.watchedSymbol(symbol) != null && !inSheet
    Row(modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            inSheet -> {
                SheetBadge()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.watchlist_status_in_sheet), style = type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
            }

            added -> {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.gain, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.watchlist_status_added), style = type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                TextButton(onClick = onUnfollow) { Text(stringResource(Res.string.watchlist_status_remove), style = type.label, color = colors.loss) }
            }

            else -> {
                Text(stringResource(Res.string.watchlist_status_not_added), style = type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                AddSymbolButton(onFollow)
            }
        }
    }
}

/**
 * The quote page's "Your position": shares held, what they're worth, and the gain today and since
 * buying — or an invitation to enter them.
 */
@Composable
internal fun PositionSection(symbol: String, state: FinanceUiState, onSetPosition: (Position?) -> Unit) {
    val colors = FinanceTheme.colors
    val quote = state.quotes[symbol]
    val meta = state.meta(symbol)
    val shortName = meta.shortName.resolve()
    val crypto = meta.kind == InstrumentKind.CRYPTO
    val position = state.watchedSymbol(symbol)?.position
    var editing by rememberSaveable(symbol) { mutableStateOf(false) }
    Column {
        SectionHeader(
            stringResource(Res.string.watchlist_position_title),
            subtitle = if (position == null) null else stringResource(Res.string.watchlist_position_entered),
            action = if (position != null) {
                { TextButton(onClick = { editing = true }) { Text(stringResource(Res.string.watchlist_position_edit), style = FinanceTheme.type.label, color = colors.gain) } }
            } else {
                null
            },
        )
        if (position == null) {
            FinanceCard(onClick = { editing = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.watchlist_position_own_some, shortName), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                        Text(stringResource(Res.string.watchlist_position_invite), style = FinanceTheme.type.label, color = colors.textSecondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.watchlist_position_add), tint = colors.gain)
                }
            }
        } else {
            val price = quote?.price
            StatGrid(
                listOf(
                    stringResource(if (crypto) Res.string.watchlist_position_held else Res.string.watchlist_position_shares) to
                        FinanceFormat.held(position.shares, meta.kind, shortName).resolve(),
                    stringResource(Res.string.watchlist_position_market_value) to (price?.let { FinanceFormat.money(position.value(it), currency = meta.currency) } ?: "—"),
                    stringResource(Res.string.watchlist_position_average_cost) to
                        (position.costPerShare?.let { FinanceFormat.price(it, meta.kind, meta.currency) } ?: stringResource(Res.string.watchlist_position_not_entered)),
                    stringResource(Res.string.watchlist_position_cost_basis) to
                        (position.costPerShare?.let { FinanceFormat.money(it * position.shares, currency = meta.currency) } ?: "—"),
                    stringResource(Res.string.watchlist_position_today) to
                        (quote?.let { FinanceFormat.signedMoney(position.dayChange(it), currency = meta.currency) } ?: "—"),
                    stringResource(Res.string.watchlist_position_today_percent) to (quote?.let { FinanceFormat.signedPercent(it.changePercent) } ?: "—"),
                    stringResource(Res.string.watchlist_position_total_return) to
                        (price?.let { position.totalGain(it) }?.let { FinanceFormat.signedMoney(it, currency = meta.currency) } ?: "—"),
                    stringResource(Res.string.watchlist_position_return_percent) to
                        (price?.let { position.totalGainPercent(it) }?.let { FinanceFormat.signedPercent(it) } ?: "—"),
                ),
            )
        }
    }
    if (editing) {
        PositionEditor(
            symbol = shortName,
            unitLabel = if (crypto) stringResource(Res.string.watchlist_editor_amount_held, shortName) else stringResource(Res.string.watchlist_position_shares),
            costLabel = stringResource(if (crypto) Res.string.watchlist_editor_cost_per_coin else Res.string.watchlist_editor_cost_per_share),
            initial = position,
            onSave = {
                onSetPosition(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/** Shares (or coins) and the average price paid for them; clearing the shares removes the position. */
@Composable
private fun PositionEditor(symbol: String, unitLabel: String, costLabel: String, initial: Position?, onSave: (Position?) -> Unit, onDismiss: () -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    // Written out in full, never rounded: saving without an edit must leave the position as it was.
    var shares by rememberSaveable { mutableStateOf(initial?.shares?.let(FinanceFormat::plainDecimal).orEmpty()) }
    var cost by rememberSaveable { mutableStateOf(initial?.costPerShare?.let(FinanceFormat::plainDecimal).orEmpty()) }
    val sharesValue = parseAmount(shares)
    val costValue = parseAmount(cost)
    val sharesOk = shares.isBlank() || (sharesValue != null && sharesValue > 0)
    val costOk = cost.isBlank() || (costValue != null && costValue >= 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceRaised,
        title = { Text(stringResource(Res.string.watchlist_editor_title, symbol), style = type.title, color = colors.textPrimary) },
        text = {
            Column {
                NumberField(unitLabel, shares, { shares = it }, error = !sharesOk, testTag = "finance_position_shares")
                Spacer(Modifier.height(12.dp))
                NumberField(costLabel, cost, { cost = it }, error = !costOk, prefix = "$", testTag = "finance_position_cost")
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.watchlist_editor_note), style = type.micro, color = colors.textTertiary)
            }
        },
        confirmButton = {
            TextButton(
                enabled = sharesOk && costOk,
                onClick = { onSave(sharesValue?.let { Position(it, costValue?.takeIf { cost.isNotBlank() }) }) },
            ) { Text(stringResource(Res.string.common_save), style = type.bodyStrong, color = if (sharesOk && costOk) colors.gain else colors.textTertiary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel), style = type.bodyStrong, color = colors.textSecondary) } },
    )
}

@Composable
internal fun NumberField(label: String, value: String, onChange: (String) -> Unit, error: Boolean, testTag: String, prefix: String? = null) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    Column {
        Text(label, style = type.label, color = if (error) colors.loss else colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, if (error) colors.loss else colors.hairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prefix != null) Text(prefix, style = type.body, color = colors.textSecondary)
            BasicTextField(
                value = value,
                onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }) },
                singleLine = true,
                textStyle = type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(testTag).semantics { contentDescription = label },
            )
        }
    }
}

/** "1,250.5" → 1250.5; null when it isn't a number. */
internal fun parseAmount(text: String): Double? = text.replace(",", "").replace("$", "").trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

/**
 * The long view: the record high and how far below it the price is, what it's done over 1, 5 and
 * 10 years, and since its first price on Yahoo. Laid out with dashes while the whole history
 * loads, so switching between indices never changes the page's height.
 */
@Composable
internal fun LongRunSection(symbol: String, state: FinanceUiState, onRequestHistory: (String, ChartRange) -> Unit) {
    val requestHistory by rememberUpdatedState(onRequestHistory)
    LaunchedEffect(symbol) { requestHistory(symbol, ChartRange.MAX) }
    val quote = state.quotes[symbol]
    val meta = state.meta(symbol)
    val load = state.chart(symbol, ChartRange.MAX)
    val history = load?.history
    val stats = remember(history, quote) { history?.let { LongRunStats.of(it, quote, Clock.System.now().epochSeconds) } }
    val gmt = history?.gmtOffsetSeconds ?: 0
    val dash = "—"
    val since = stats?.let { stringResource(Res.string.longrun_since_year, FinanceFormat.year(it.firstEpochSeconds + 12 * 3600, gmt)) }
        ?: stringResource(Res.string.longrun_since_start)
    val atRecord = stringResource(Res.string.longrun_at_record)
    val (recordLabel, recordValue) = recordWhen(stats, quote, gmt)
    Column {
        SectionHeader(
            stringResource(Res.string.longrun_title),
            trailing = stats?.let { stringResource(Res.string.longrun_data_from, FinanceFormat.monthYear(it.firstEpochSeconds + 12 * 3600, gmt).resolve()) },
        )
        StatGrid(
            listOf(
                stringResource(Res.string.longrun_all_time_high) to (stats?.let { FinanceFormat.price(it.allTimeHigh, meta.kind, meta.currency) } ?: dash),
                stringResource(recordLabel) to recordValue.resolve(),
                stringResource(Res.string.longrun_from_high) to (stats?.let { if (it.fromHighPercent > -0.005) atRecord else FinanceFormat.signedPercent(it.fromHighPercent) } ?: dash),
                stringResource(Res.string.longrun_per_year) to (stats?.perYearPercent?.let { FinanceFormat.signedPercent(it) } ?: dash),
                pluralStringResource(Res.plurals.longrun_years, 1, 1) to (stats?.oneYearPercent?.let(FinanceFormat::longRunPercent) ?: dash),
                pluralStringResource(Res.plurals.longrun_years, 5, 5) to (stats?.fiveYearPercent?.let(FinanceFormat::longRunPercent) ?: dash),
                pluralStringResource(Res.plurals.longrun_years, 10, 10) to (stats?.tenYearPercent?.let(FinanceFormat::longRunPercent) ?: dash),
                since to (stats?.let { FinanceFormat.longRunPercent(it.sinceStartPercent) } ?: dash),
            ),
        )
        val error = load?.error
        FinePrint(
            if (error != null && history == null) {
                stringResource(Res.string.longrun_history_failed, error.resolve())
            } else {
                stringResource(Res.string.longrun_fine_print)
            },
        )
    }
}

/**
 * When the record was set. A record from the history is known only to its week (the bars are
 * weekly, stamped at the week's start, which is written from midday so no offset slips it back a
 * day); one set today is the quote's own moment, written in the exchange's clock as it is.
 */
private fun recordWhen(stats: LongRunStats?, quote: Quote?, historyOffset: Int): Pair<StringResource, UiText> {
    val at = stats?.allTimeHighEpochSeconds ?: return Res.string.longrun_set_on to "—".asUiText()
    return if (stats.allTimeHighToday) {
        Res.string.longrun_set_on to FinanceFormat.date(at, quote?.gmtOffsetSeconds ?: historyOffset)
    } else {
        Res.string.longrun_week_of to FinanceFormat.date(at + 12 * 3600, historyOffset)
    }
}
