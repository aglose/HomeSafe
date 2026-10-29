package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.BalanceSave
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.BalanceAge
import com.meticulouscreations.homesafe.finance.domain.BalanceFreshness
import com.meticulouscreations.homesafe.finance.domain.Owner
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.SheetCell
import com.meticulouscreations.homesafe.finance.domain.TypedBalance
import com.meticulouscreations.homesafe.finance.domain.TypedBalanceKind
import com.meticulouscreations.homesafe.finance.domain.typedBalances
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_save
import homesafe.shared.generated.resources.fin_balance_editor_bank_note
import homesafe.shared.generated.resources.fin_balance_editor_held
import homesafe.shared.generated.resources.fin_balance_editor_owed
import homesafe.shared.generated.resources.fin_balance_editor_saving
import homesafe.shared.generated.resources.fin_balance_editor_still_right
import homesafe.shared.generated.resources.fin_balance_editor_where
import homesafe.shared.generated.resources.fin_balance_editor_worth
import homesafe.shared.generated.resources.fin_balance_error_changed
import homesafe.shared.generated.resources.fin_balance_fine_print
import homesafe.shared.generated.resources.fin_balance_intro
import homesafe.shared.generated.resources.fin_balance_never
import homesafe.shared.generated.resources.fin_balance_open_action
import homesafe.shared.generated.resources.fin_balance_section_bank
import homesafe.shared.generated.resources.fin_balance_section_bank_note
import homesafe.shared.generated.resources.fin_balance_section_due
import homesafe.shared.generated.resources.fin_balance_section_fresh
import homesafe.shared.generated.resources.fin_balance_summary_due
import homesafe.shared.generated.resources.fin_balance_summary_fresh
import homesafe.shared.generated.resources.fin_balance_synced
import homesafe.shared.generated.resources.fin_balance_teaser_due
import homesafe.shared.generated.resources.fin_balance_teaser_fresh
import homesafe.shared.generated.resources.fin_balance_teaser_never
import homesafe.shared.generated.resources.fin_balance_teaser_oldest
import homesafe.shared.generated.resources.fin_balance_teaser_title
import homesafe.shared.generated.resources.fin_balance_updated
import homesafe.shared.generated.resources.fin_balance_updated_by
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** The colour a balance's age is shown in: green within a month, amber after, red past two months. */
@Composable
internal fun BalanceFreshness.color(): Color = when (this) {
    BalanceFreshness.FRESH -> FinanceTheme.colors.gain
    BalanceFreshness.AGING, BalanceFreshness.UNKNOWN -> FinanceTheme.colors.watch
    BalanceFreshness.STALE -> FinanceTheme.colors.loss
}

/** How the most out-of-date of [balances] stands, for a summary's colour: stale if any is, worth a look if any is, else fresh. */
internal fun worstFreshness(balances: List<TypedBalance>, nowEpochSeconds: Long): BalanceFreshness {
    val all = balances.map { it.freshness(nowEpochSeconds) }
    return when {
        BalanceFreshness.STALE in all -> BalanceFreshness.STALE
        all.any { it.wantsUpdate } -> BalanceFreshness.AGING
        else -> BalanceFreshness.FRESH
    }
}

/** "Updated 5 weeks ago", "Synced today", or that no update has been recorded. */
internal fun balanceAgeText(age: BalanceAge?, nowEpochSeconds: Long, withWho: Boolean = false): UiText {
    age ?: return UiText.of(Res.string.fin_balance_never)
    val ago = FinanceFormat.relativeDays(nowEpochSeconds, age.atEpochSeconds)
    return when {
        age.fromBank -> UiText.of(Res.string.fin_balance_synced, ago)
        withWho && age.by != null -> UiText.of(Res.string.fin_balance_updated_by, ago, UiText.verbatim(age.by))
        else -> UiText.of(Res.string.fin_balance_updated, ago)
    }
}

/**
 * The balances typed into the sheet by hand, with how long ago each was updated: those worth
 * updating first (oldest first), then those up to date, then those the bank sync fills in. Tapping
 * one opens [BalanceEditor], which types a new number into the sheet through [onSave].
 */
@Composable
internal fun TypedBalancesScreen(
    state: FinanceUiState,
    contentPadding: PaddingValues,
    onSave: (TypedBalance, Double) -> Unit,
    onClearSave: () -> Unit,
) {
    val finance = state.finance
    val now = LocalFinanceClock.current.now().epochSeconds
    val balances = remember(finance) { finance?.let(::typedBalances).orEmpty() }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.testTag("finance_typed_balances")) {
        if (finance == null) {
            item(key = "loading") { WalletSkeleton() }
        } else {
            typedBalanceItems(balances, now, onOpen = { editing = it.cell.id })
        }
    }
    editing?.let { id -> BalanceEditorFor(id, state, onSave, onClearSave, onDismiss = { editing = null }) }
}

/**
 * Opens [BalanceEditor] on the typed balance with this cell id. Provided by [FinanceApp], so an
 * account or debt row anywhere in the app can update its balance; null elsewhere (previews).
 */
val LocalBalanceEditor = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * [BalanceEditor] on the typed balance in the cell [cellId]. Found again by its cell on every read
 * of the sheet, so the editor always works from the number the sheet holds now; nothing is shown
 * while the sheet isn't read, or once it no longer has that balance.
 */
@Composable
internal fun BalanceEditorFor(cellId: String, state: FinanceUiState, onSave: (TypedBalance, Double) -> Unit, onClearSave: () -> Unit, onDismiss: () -> Unit) {
    val finance = state.finance ?: return
    val balances = remember(finance) { typedBalances(finance) }
    val open = balances.firstOrNull { it.cell.id == cellId } ?: return
    BalanceEditor(
        balance = open,
        save = state.balanceSave?.takeIf { it.cellId == open.cell.id },
        now = LocalFinanceClock.current.now().epochSeconds,
        onSave = { onSave(open, it) },
        onClear = onClearSave,
        onDismiss = {
            onDismiss()
            onClearSave()
        },
    )
}

private fun LazyListScope.typedBalanceItems(balances: List<TypedBalance>, now: Long, onOpen: (TypedBalance) -> Unit) {
    val typed = balances.filterNot { it.fromBank }
    val due = typed.filter { it.freshness(now).wantsUpdate }
    val fresh = typed.filterNot { it.freshness(now).wantsUpdate }
    val bank = balances.filter { it.fromBank }
    item(key = "intro") {
        FinanceCard(Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tone = worstFreshness(due, now)
                Box(Modifier.size(10.dp).clip(CircleShape).background(tone.color()))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (due.isEmpty()) stringResource(Res.string.fin_balance_summary_fresh) else pluralStringResource(Res.plurals.fin_balance_summary_due, due.size, due.size),
                    style = FinanceTheme.type.bodyStrong,
                    color = FinanceTheme.colors.textPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.fin_balance_intro), style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary)
        }
    }
    listOf(
        Triple("due", Res.string.fin_balance_section_due, due),
        Triple("fresh", Res.string.fin_balance_section_fresh, fresh),
        Triple("bank", Res.string.fin_balance_section_bank, bank),
    ).filter { it.third.isNotEmpty() }.forEach { (key, title, rows) ->
        item(key = "$key-h") {
            SectionHeader(stringResource(title), subtitle = if (key == "bank") stringResource(Res.string.fin_balance_section_bank_note) else null)
        }
        rows.forEachIndexed { i, balance ->
            item(key = "$key-${balance.cell.id}") {
                BalanceRow(balance, now, onClick = { onOpen(balance) })
                if (i < rows.lastIndex) Hairline(Modifier.padding(horizontal = PageGutter))
            }
        }
    }
    item(key = "fine") { FinePrint(stringResource(Res.string.fin_balance_fine_print), Modifier.padding(top = 12.dp)) }
}

@Composable
private fun BalanceRow(balance: TypedBalance, now: Long, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val freshness = balance.freshness(now)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fin_balance_open_action), onClick = onClick)
            .padding(horizontal = PageGutter, vertical = 12.dp)
            .testTag("finance_typed_balance_${balance.cell.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(freshness.color()))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(balance.name.resolve(), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val about = listOfNotNull(balance.owner?.label?.resolve(), balanceAgeText(balance.age, now).resolve())
            Text(
                about.joinToString(stringResource(Res.string.common_dot_separator)),
                style = FinanceTheme.type.label,
                color = if (freshness.wantsUpdate && !balance.fromBank) freshness.color() else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(FinanceFormat.money(balance.balance, 0), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
        Text("  ›", style = FinanceTheme.type.label, color = colors.textTertiary)
    }
}

/**
 * A new number for [balance], filled in with the one the sheet holds. Saved unchanged, it says
 * the balance is still right, which counts as updating it. Closes once [save] is done.
 */
@Composable
internal fun BalanceEditor(balance: TypedBalance, save: BalanceSave?, now: Long, onSave: (Double) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val clear by rememberUpdatedState(onClear)
    val dismiss by rememberUpdatedState(onDismiss)
    // What a save before this opening said (it finished out of sight) isn't this one's to show.
    LaunchedEffect(Unit) { if (save != null && !save.saving) clear() }
    LaunchedEffect(save?.done) { if (save?.done == true) dismiss() }
    // Written out in full, never rounded: saving without an edit must leave the number as it was.
    // Started again when the sheet turns out to hold another number (it changed since it was read).
    var text by rememberSaveable(balance.cell.id, balance.balance) { mutableStateOf(FinanceFormat.plainDecimal(balance.balance)) }
    val amount = parseAmount(text)
    // An account can be overdrawn; what's owed and what the home is worth can't go below zero.
    val signed = balance.kind == TypedBalanceKind.ACCOUNT
    val valid = amount != null && (amount >= 0 || signed)
    val unchanged = amount != null && abs(amount - balance.balance) < 0.005
    val saving = save?.saving == true
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceRaised,
        title = { Text(balance.name.resolve(), style = type.title, color = colors.textPrimary) },
        text = {
            Column(Modifier.testTag("finance_balance_editor")) {
                val freshness = balance.freshness(now)
                Text(
                    listOfNotNull(balance.owner?.label?.resolve(), balanceAgeText(balance.age, now, withWho = true).resolve()).joinToString(stringResource(Res.string.common_dot_separator)),
                    style = type.label,
                    color = if (freshness.wantsUpdate) freshness.color() else colors.textSecondary,
                )
                Spacer(Modifier.height(14.dp))
                NumberField(
                    stringResource(
                        when (balance.kind) {
                            TypedBalanceKind.ACCOUNT -> Res.string.fin_balance_editor_held
                            TypedBalanceKind.DEBT -> Res.string.fin_balance_editor_owed
                            TypedBalanceKind.HOME_VALUE -> Res.string.fin_balance_editor_worth
                        },
                    ),
                    text,
                    { text = it },
                    error = !valid,
                    testTag = "finance_balance_amount",
                    prefix = "$",
                    allowNegative = signed,
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.fin_balance_editor_where, balance.cell.label, balance.cell.tab), style = type.micro, color = colors.textTertiary)
                if (balance.fromBank) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.fin_balance_editor_bank_note), style = type.micro, color = colors.watch)
                }
                save?.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it.resolve(), style = type.label, color = colors.loss, modifier = Modifier.testTag("finance_balance_error"))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !saving,
                onClick = { amount?.let(onSave) },
                modifier = Modifier.testTag("finance_balance_save"),
            ) {
                Text(
                    stringResource(
                        when {
                            saving -> Res.string.fin_balance_editor_saving
                            unchanged -> Res.string.fin_balance_editor_still_right
                            else -> Res.string.common_save
                        },
                    ),
                    style = type.bodyStrong,
                    color = if (valid && !saving) colors.gain else colors.textTertiary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel), style = type.bodyStrong, color = colors.textSecondary) } },
    )
}

/** The Wallet's line on the typed balances: how many want updating and how old the oldest is. Off the page when the sheet has none. */
@Composable
internal fun TypedBalancesTeaser(finance: PersonalFinance, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val now = LocalFinanceClock.current.now().epochSeconds
    val typed = remember(finance) { typedBalances(finance).filterNot { it.fromBank } }
    if (typed.isEmpty()) return
    val colors = FinanceTheme.colors
    val due = typed.filter { it.freshness(now).wantsUpdate }
    val never = typed.count { it.age == null }
    FinanceCard(modifier.testTag("finance_typed_balances_teaser"), onClick = onOpen, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.fin_balance_teaser_title), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, modifier = Modifier.weight(1f))
            val tone = worstFreshness(due, now)
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone.color()))
            Spacer(Modifier.width(8.dp))
            Text(
                if (due.isEmpty()) stringResource(Res.string.fin_balance_teaser_fresh) else pluralStringResource(Res.plurals.fin_balance_teaser_due, due.size, due.size),
                style = FinanceTheme.type.bodyStrong,
                color = tone.color(),
            )
            Text("  ›", style = FinanceTheme.type.label, color = colors.textTertiary)
        }
        Spacer(Modifier.height(4.dp))
        // Oldest first, those never updated leading: the first with an age is the oldest known.
        val oldest = typed.firstOrNull { it.age != null }?.age
        Text(
            when {
                never > 0 -> pluralStringResource(Res.plurals.fin_balance_teaser_never, never, never)
                oldest != null -> stringResource(Res.string.fin_balance_teaser_oldest, FinanceFormat.relativeDays(now, oldest.atEpochSeconds).resolve())
                else -> ""
            },
            style = FinanceTheme.type.label,
            color = colors.textSecondary,
        )
    }
}

/** The fixtures' sheet with a cell behind each account and debt, and some of them updated lately. */
internal val typedBalanceFinance: PersonalFinance = FinanceFixtures.finance.let { f ->
    val day = 86_400L
    var row = 4
    fun cell(label: String, right: Int = 1, value: Double, negative: Boolean = false) = SheetCell("Home", label, row++, right, value, negative = negative)
    val accounts = f.accounts.map { a ->
        val label = (a.name as? UiText.Verbatim)?.value
        // The home's equity is worked out from its value, so it has no cell of its own.
        if (a.category == AccountCategory.HOME || label == null) a else a.copy(cell = cell(label, if (a.owner == Owner.Person("Sam")) 2 else 1, a.balance))
    }
    val debts = f.debts.map { d -> d.copy(cell = cell(d.name, value = -d.balance, negative = true)) }
    val home = f.home?.copy(valueCell = cell("Home Value", value = f.home.value ?: 0.0))
    val ages = buildMap {
        put(accounts[1].cell!!.id, BalanceAge(FinanceFixtures.NOW - 4 * day, "alex", fromBank = false))
        put(accounts[2].cell!!.id, BalanceAge(FinanceFixtures.NOW - 45 * day, "sam", fromBank = false))
        put(accounts[4].cell!!.id, BalanceAge(FinanceFixtures.NOW - 130 * day, "sam", fromBank = false))
        put(accounts[7].cell!!.id, BalanceAge(FinanceFixtures.NOW - day, null, fromBank = true))
        put(accounts[8].cell!!.id, BalanceAge(FinanceFixtures.NOW - day, null, fromBank = true))
        put(debts[0].cell!!.id, BalanceAge(FinanceFixtures.NOW - day, null, fromBank = true))
        put(debts[3].cell!!.id, BalanceAge(FinanceFixtures.NOW - 20 * day, "alex", fromBank = false))
        put(home!!.valueCell!!.id, BalanceAge(FinanceFixtures.NOW - 70 * day, "alex", fromBank = false))
    }
    f.copy(accounts = accounts, debts = debts, home = home, balanceAges = ages, bankFilled = setOf(accounts[7].cell!!.id, accounts[8].cell!!.id, debts[0].cell!!.id))
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceTypedBalancesPreview() {
    FinanceStage { TypedBalancesScreen(FinanceFixtures.state.copy(finance = typedBalanceFinance), previewPadding, onSave = { _, _ -> }, onClearSave = {}) }
}

@Preview(name = "Wallet · typed balances to update", widthDp = 412, heightDp = 1100)
@Composable
private fun FinanceWalletTypedBalancesPreview() {
    FinanceStage { WalletScreen(FinanceFixtures.state.copy(finance = typedBalanceFinance), rememberLazyListState(), previewPadding, onRetrySheet = {}) }
}

@Preview(name = "Accounts · how old each balance is", widthDp = 412, heightDp = 1500)
@Composable
private fun FinanceWalletAccountsAgesPreview() {
    FinanceStage { WalletTopicScreen(WalletTopic.ACCOUNTS, focus = null, FinanceFixtures.state.copy(finance = typedBalanceFinance), previewPadding) }
}

@Preview(name = "Typed balances · on the Wallet", widthDp = 412, heightDp = 200)
@Composable
private fun FinanceTypedBalancesTeaserPreview() {
    FinanceStage {
        Column(Modifier.padding(vertical = 16.dp)) {
            TypedBalancesTeaser(typedBalanceFinance, onOpen = {})
            Spacer(Modifier.height(12.dp))
            val updated = typedBalanceFinance.copy(balanceAges = typedBalances(typedBalanceFinance).associate { it.cell.id to BalanceAge(FinanceFixtures.NOW - 86_400L * 3, "alex", false) })
            TypedBalancesTeaser(updated, onOpen = {})
        }
    }
}

@Preview(name = "Typed balances · editor", widthDp = 412, heightDp = 520)
@Composable
private fun FinanceBalanceEditorPreview() {
    FinanceStage {
        val balance = typedBalances(typedBalanceFinance).first { it.age?.by == "sam" && it.freshness(FinanceFixtures.NOW) == BalanceFreshness.STALE }
        BalanceEditor(balance, save = null, now = FinanceFixtures.NOW, onSave = {}, onClear = {}, onDismiss = {})
    }
}

@Preview(name = "Typed balances · the sheet changed", widthDp = 412, heightDp = 560)
@Composable
private fun FinanceBalanceEditorErrorPreview() {
    FinanceStage {
        val balance = typedBalances(typedBalanceFinance).first { it.kind == TypedBalanceKind.HOME_VALUE }
        BalanceEditor(
            balance,
            save = BalanceSave(balance.cell.id, saving = false, error = UiText.of(Res.string.fin_balance_error_changed)),
            now = FinanceFixtures.NOW,
            onSave = {},
            onClear = {},
            onDismiss = {},
        )
    }
}
