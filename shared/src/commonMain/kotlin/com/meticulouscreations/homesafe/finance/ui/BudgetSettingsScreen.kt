package com.meticulouscreations.homesafe.finance.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.finance.BudgetUiState
import com.meticulouscreations.homesafe.finance.BudgetViewModel
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetLimits
import com.meticulouscreations.homesafe.finance.domain.ExpenseLine
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.ui.components.Shimmer
import com.meticulouscreations.homesafe.text.resolve
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.common_save
import homesafe.shared.generated.resources.fin_budget_bucket_family
import homesafe.shared.generated.resources.fin_budget_bucket_unsorted
import homesafe.shared.generated.resources.fin_budget_error_generic
import homesafe.shared.generated.resources.fin_budget_settings_cards_none
import homesafe.shared.generated.resources.fin_budget_settings_cards_subtitle
import homesafe.shared.generated.resources.fin_budget_settings_cards_title
import homesafe.shared.generated.resources.fin_budget_settings_limit_family
import homesafe.shared.generated.resources.fin_budget_settings_limit_suggest
import homesafe.shared.generated.resources.fin_budget_settings_limit_total
import homesafe.shared.generated.resources.fin_budget_settings_limits_subtitle
import homesafe.shared.generated.resources.fin_budget_settings_limits_title
import homesafe.shared.generated.resources.fin_budget_settings_lines_none
import homesafe.shared.generated.resources.fin_budget_settings_lines_subtitle
import homesafe.shared.generated.resources.fin_budget_settings_lines_sum
import homesafe.shared.generated.resources.fin_budget_settings_lines_title
import homesafe.shared.generated.resources.fin_budget_settings_people
import homesafe.shared.generated.resources.fin_budget_settings_people_none
import homesafe.shared.generated.resources.fin_budget_settings_rules_forget
import homesafe.shared.generated.resources.fin_budget_settings_rules_none
import homesafe.shared.generated.resources.fin_budget_settings_rules_subtitle
import homesafe.shared.generated.resources.fin_budget_settings_rules_title
import homesafe.shared.generated.resources.fin_budget_settings_saving
import org.jetbrains.compose.resources.stringResource

/** The budget's settings with the Budget page's own view model, so a change made here is on that page when it comes back. */
@Composable
internal fun BudgetSettingsRoute(finance: PersonalFinance?, contentPadding: PaddingValues) {
    val viewModel: BudgetViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val budget = state.budget
    LaunchedEffect(Unit) { if (budget == null) viewModel.refresh() }
    // Ticking a line as paid by card changes the bills the relay is told about.
    LaunchedEffect(finance, budget?.config?.cardPaidLines, budget?.config?.sheet, budget?.isCurrentMonth) { viewModel.onSheet(finance) }
    BudgetSettingsScreen(state, finance, contentPadding, onSave = viewModel::save, onForgetRule = viewModel::forgetRule)
}

/**
 * What the month is measured against and how its purchases are sorted: the limits, what each
 * card is, which of the budget sheet's lines are paid by card (the rest being the bills that
 * take-home has to cover first), and the merchants whose purchases always go one way.
 */
@Composable
internal fun BudgetSettingsScreen(
    state: BudgetUiState,
    finance: PersonalFinance?,
    contentPadding: PaddingValues,
    onSave: (BudgetConfigPatch) -> Unit,
    onForgetRule: (String) -> Unit,
) {
    val budget = state.budget
    val colors = FinanceTheme.colors
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.testTag("finance_budget_settings_page")) {
        if (budget == null) {
            item(key = "loading") {
                if (state.loading) {
                    Shimmer(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(top = 12.dp).height(160.dp), corner = 18.dp)
                } else {
                    FinePrint(state.problemText?.resolve() ?: stringResource(Res.string.fin_budget_error_generic), Modifier.padding(top = 12.dp))
                }
            }
            return@LazyColumn
        }
        val config = budget.config
        state.notice?.let { notice ->
            item(key = "notice") {
                Text(notice.text.resolve(), style = FinanceTheme.type.label, color = if (notice.isError) colors.loss else colors.gain, modifier = Modifier.padding(horizontal = PageGutter).padding(top = 12.dp))
            }
        }
        item(key = "limits-h") { SectionHeader(stringResource(Res.string.fin_budget_settings_limits_title), subtitle = stringResource(Res.string.fin_budget_settings_limits_subtitle)) }
        item(key = "limits") { LimitsCard(budget, finance, saving = state.saving, onSave = onSave) }

        item(key = "cards-h") { SectionHeader(stringResource(Res.string.fin_budget_settings_cards_title), subtitle = stringResource(Res.string.fin_budget_settings_cards_subtitle)) }
        if (budget.cards.isEmpty()) item(key = "cards-none") { FinePrint(stringResource(Res.string.fin_budget_settings_cards_none)) }
        items(budget.cards, key = { "card-${it.key}" }) { card ->
            Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 10.dp)) {
                Text(cardLabel(card), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                RoleChips(card.role, config.people, enabled = !state.saving, onPick = { onSave(BudgetConfigPatch(roles = mapOf(card.key to it))) })
            }
        }

        item(key = "lines-h") { SectionHeader(stringResource(Res.string.fin_budget_settings_lines_title), subtitle = stringResource(Res.string.fin_budget_settings_lines_subtitle)) }
        val expenses = finance?.expenses.orEmpty()
        if (expenses.isEmpty()) {
            item(key = "lines-none") { FinePrint(stringResource(Res.string.fin_budget_settings_lines_none)) }
        } else {
            // By place, not by name: a sheet may have two lines called the same, and they are ticked together.
            itemsIndexed(expenses, key = { index, _ -> "line-$index" }) { _, line ->
                SheetLineRow(line, onCard = line.name in config.cardPaidLines, enabled = !state.saving) { onCard ->
                    onSave(BudgetConfigPatch(cardPaidLines = if (onCard) config.cardPaidLines + line.name else config.cardPaidLines - line.name))
                }
            }
            item(key = "lines-sum") {
                val bills = expenses.filter { it.name !in config.cardPaidLines }.sumOf { it.monthly }
                FinePrint(stringResource(Res.string.fin_budget_settings_lines_sum, FinanceFormat.money(bills, 0), FinanceFormat.money(expenses.sumOf { it.monthly }, 0)))
            }
        }

        item(key = "rules-h") { SectionHeader(stringResource(Res.string.fin_budget_settings_rules_title), subtitle = stringResource(Res.string.fin_budget_settings_rules_subtitle)) }
        if (config.rules.isEmpty()) item(key = "rules-none") { FinePrint(stringResource(Res.string.fin_budget_settings_rules_none)) }
        items(config.rules, key = { "rule-${it.merchantKey}" }) { rule ->
            Row(Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter - 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.merchantKey, style = FinanceTheme.type.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val whose = when (val bucket = rule.bucket) {
                        is BucketId.Person -> bucket.name
                        BucketId.Family -> stringResource(Res.string.fin_budget_bucket_family)
                        BucketId.Unassigned -> stringResource(Res.string.fin_budget_bucket_unsorted)
                    }
                    Text(whose, style = FinanceTheme.type.label, color = colors.textSecondary)
                }
                PillButton(stringResource(Res.string.fin_budget_settings_rules_forget), colors.textSecondary, onClick = { onForgetRule(rule.merchantKey) })
            }
        }

        item(key = "people") {
            FinePrint(
                if (config.people.isEmpty()) {
                    stringResource(Res.string.fin_budget_settings_people_none)
                } else {
                    stringResource(Res.string.fin_budget_settings_people, config.people.joinToString(stringResource(Res.string.common_list_separator)))
                },
                Modifier.padding(top = 20.dp),
            )
        }
    }
}

/** A limit as its field shows it: whole dollars, or empty for none. */
private fun fieldText(limit: Double?): String = limit?.let { FinanceFormat.plainDecimal(it) }.orEmpty()

/**
 * The month's limits, typed: the whole month's, each person's and the family card's. An empty
 * field is no limit. While the whole month has none, the sheet offers one: the lines that are
 * paid by card, or failing those what take-home leaves after the bills.
 */
@Composable
private fun LimitsCard(budget: Budget, finance: PersonalFinance?, saving: Boolean, onSave: (BudgetConfigPatch) -> Unit) {
    val colors = FinanceTheme.colors
    val limits = budget.config.limits
    val people = budget.config.people
    var total by rememberSaveable(limits.total) { mutableStateOf(fieldText(limits.total)) }
    var family by rememberSaveable(limits.family) { mutableStateOf(fieldText(limits.family)) }
    // Not saved across a recreated activity: a map of fields is more than the saver takes, and the limits come back from the relay.
    val each = remember(limits.people, people) { mutableStateMapOf<String, String>().apply { people.forEach { put(it, fieldText(limits.people[it])) } } }

    fun valid(text: String) = text.isBlank() || parseAmount(text) != null
    val typed = BudgetLimits(total = parseAmount(total), people = people.associateWith { parseAmount(each[it].orEmpty()) }, family = parseAmount(family))
    val allValid = valid(total) && valid(family) && people.all { valid(each[it].orEmpty()) }
    val changed = typed != BudgetLimits(limits.total, people.associateWith { limits.people[it] }, limits.family)
    val suggestion = remember(finance, budget.config.cardPaidLines, budget.savingsLine) {
        val onCards = finance?.expenses?.filter { it.name in budget.config.cardPaidLines }?.sumOf { it.monthly }?.takeIf { it > 0 }
        onCards ?: budget.savingsLine?.takeIf { it > 0 }
    }

    FinanceCard {
        NumberField(stringResource(Res.string.fin_budget_settings_limit_total), total, { total = it }, error = !valid(total), testTag = "finance_budget_limit_total", prefix = "$")
        if (suggestion != null && total.isBlank()) {
            Spacer(Modifier.height(8.dp))
            PillButton(stringResource(Res.string.fin_budget_settings_limit_suggest, FinanceFormat.money(suggestion, 0)), colors.accent, onClick = { total = fieldText(suggestion) })
        }
        people.forEach { person ->
            Spacer(Modifier.height(12.dp))
            NumberField(
                person,
                each[person].orEmpty(),
                { each[person] = it },
                error = !valid(each[person].orEmpty()),
                testTag = "finance_budget_limit_person_$person",
                prefix = "$",
            )
        }
        Spacer(Modifier.height(12.dp))
        NumberField(stringResource(Res.string.fin_budget_settings_limit_family), family, { family = it }, error = !valid(family), testTag = "finance_budget_limit_family", prefix = "$")
        Spacer(Modifier.height(14.dp))
        val ready = changed && allValid && !saving
        PillButton(
            stringResource(if (saving) Res.string.fin_budget_settings_saving else Res.string.common_save),
            if (ready) colors.accent else colors.textTertiary,
            onClick = { if (ready) onSave(BudgetConfigPatch(limits = typed)) },
            modifier = Modifier.testTag("finance_budget_limits_save"),
        )
    }
}

/** One of the budget sheet's expense lines, ticked when it is paid by card. */
@Composable
private fun SheetLineRow(line: ExpenseLine, onCard: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = FinanceTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = onCard, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .minimumInteractiveComponentSize()
            .padding(horizontal = PageGutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (onCard) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (onCard) colors.accent else colors.textTertiary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(line.name, style = FinanceTheme.type.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(FinanceFormat.money(line.monthly, 0), style = FinanceTheme.type.bodyStrong, color = if (onCard) colors.textPrimary else colors.textSecondary)
    }
}
