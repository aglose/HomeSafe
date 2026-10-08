package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.finance.BudgetUiState
import com.meticulouscreations.homesafe.finance.BudgetViewModel
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BucketSource
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetBucket
import com.meticulouscreations.homesafe.finance.domain.BudgetCard
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetPace
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.PaceStatus
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.Purchase
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.components.AuroraBackground
import com.meticulouscreations.homesafe.finance.ui.components.Bar
import com.meticulouscreations.homesafe.finance.ui.components.BarChart
import com.meticulouscreations.homesafe.finance.ui.components.BudgetTank
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.ChartRule
import com.meticulouscreations.homesafe.finance.ui.components.DonutChart
import com.meticulouscreations.homesafe.finance.ui.components.DonutSlice
import com.meticulouscreations.homesafe.finance.ui.components.HeatMeter
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.finance.ui.components.Meter
import com.meticulouscreations.homesafe.finance.ui.components.RollingNumber
import com.meticulouscreations.homesafe.finance.ui.components.Shimmer
import com.meticulouscreations.homesafe.finance.ui.components.heatColor
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_try_again
import homesafe.shared.generated.resources.fin_bank_problem_signed_out_body
import homesafe.shared.generated.resources.fin_bank_problem_signed_out_title
import homesafe.shared.generated.resources.fin_budget_action_settings
import homesafe.shared.generated.resources.fin_budget_action_sync
import homesafe.shared.generated.resources.fin_budget_action_syncing
import homesafe.shared.generated.resources.fin_budget_back_to_now
import homesafe.shared.generated.resources.fin_budget_bucket_family
import homesafe.shared.generated.resources.fin_budget_bucket_unsorted
import homesafe.shared.generated.resources.fin_budget_card_masked
import homesafe.shared.generated.resources.fin_budget_card_named
import homesafe.shared.generated.resources.fin_budget_card_no_purchases
import homesafe.shared.generated.resources.fin_budget_card_relink
import homesafe.shared.generated.resources.fin_budget_category_entertainment
import homesafe.shared.generated.resources.fin_budget_category_fees
import homesafe.shared.generated.resources.fin_budget_category_food
import homesafe.shared.generated.resources.fin_budget_category_government
import homesafe.shared.generated.resources.fin_budget_category_home
import homesafe.shared.generated.resources.fin_budget_category_loans
import homesafe.shared.generated.resources.fin_budget_category_medical
import homesafe.shared.generated.resources.fin_budget_category_other
import homesafe.shared.generated.resources.fin_budget_category_personal_care
import homesafe.shared.generated.resources.fin_budget_category_rest
import homesafe.shared.generated.resources.fin_budget_category_services
import homesafe.shared.generated.resources.fin_budget_category_shopping
import homesafe.shared.generated.resources.fin_budget_category_transport
import homesafe.shared.generated.resources.fin_budget_category_travel
import homesafe.shared.generated.resources.fin_budget_category_utilities
import homesafe.shared.generated.resources.fin_budget_filter_all
import homesafe.shared.generated.resources.fin_budget_flow_bills
import homesafe.shared.generated.resources.fin_budget_flow_cards
import homesafe.shared.generated.resources.fin_budget_flow_from_savings
import homesafe.shared.generated.resources.fin_budget_flow_left
import homesafe.shared.generated.resources.fin_budget_flow_note
import homesafe.shared.generated.resources.fin_budget_flow_take_home
import homesafe.shared.generated.resources.fin_budget_flow_title
import homesafe.shared.generated.resources.fin_budget_fresh_never
import homesafe.shared.generated.resources.fin_budget_fresh_read
import homesafe.shared.generated.resources.fin_budget_fresh_read_changed
import homesafe.shared.generated.resources.fin_budget_fresh_reading
import homesafe.shared.generated.resources.fin_budget_hero_day
import homesafe.shared.generated.resources.fin_budget_hero_limit
import homesafe.shared.generated.resources.fin_budget_hero_month
import homesafe.shared.generated.resources.fin_budget_hero_no_limit
import homesafe.shared.generated.resources.fin_budget_how
import homesafe.shared.generated.resources.fin_budget_link_body
import homesafe.shared.generated.resources.fin_budget_link_title
import homesafe.shared.generated.resources.fin_budget_merchant_times
import homesafe.shared.generated.resources.fin_budget_months_subtitle
import homesafe.shared.generated.resources.fin_budget_months_title
import homesafe.shared.generated.resources.fin_budget_not_ready
import homesafe.shared.generated.resources.fin_budget_open_linked_accounts
import homesafe.shared.generated.resources.fin_budget_pace_caption
import homesafe.shared.generated.resources.fin_budget_pace_chart_description
import homesafe.shared.generated.resources.fin_budget_pace_legend_limit
import homesafe.shared.generated.resources.fin_budget_pace_legend_savings
import homesafe.shared.generated.resources.fin_budget_pace_scrub
import homesafe.shared.generated.resources.fin_budget_pace_stat_allowance
import homesafe.shared.generated.resources.fin_budget_pace_stat_limit_day
import homesafe.shared.generated.resources.fin_budget_pace_stat_not_this_month
import homesafe.shared.generated.resources.fin_budget_pace_stat_projected
import homesafe.shared.generated.resources.fin_budget_pace_stat_rate
import homesafe.shared.generated.resources.fin_budget_pace_title
import homesafe.shared.generated.resources.fin_budget_pending_note
import homesafe.shared.generated.resources.fin_budget_problem_other_title
import homesafe.shared.generated.resources.fin_budget_problem_outdated_title
import homesafe.shared.generated.resources.fin_budget_purchase_pending
import homesafe.shared.generated.resources.fin_budget_purchase_refund
import homesafe.shared.generated.resources.fin_budget_purchases_count
import homesafe.shared.generated.resources.fin_budget_purchases_none
import homesafe.shared.generated.resources.fin_budget_purchases_none_here
import homesafe.shared.generated.resources.fin_budget_purchases_title
import homesafe.shared.generated.resources.fin_budget_role_family
import homesafe.shared.generated.resources.fin_budget_role_ignore
import homesafe.shared.generated.resources.fin_budget_role_person
import homesafe.shared.generated.resources.fin_budget_role_split
import homesafe.shared.generated.resources.fin_budget_role_unset
import homesafe.shared.generated.resources.fin_budget_roles_body
import homesafe.shared.generated.resources.fin_budget_roles_title
import homesafe.shared.generated.resources.fin_budget_sandbox
import homesafe.shared.generated.resources.fin_budget_setup_body
import homesafe.shared.generated.resources.fin_budget_setup_title
import homesafe.shared.generated.resources.fin_budget_sort_count
import homesafe.shared.generated.resources.fin_budget_sort_more
import homesafe.shared.generated.resources.fin_budget_sort_subtitle
import homesafe.shared.generated.resources.fin_budget_sort_title
import homesafe.shared.generated.resources.fin_budget_source_account
import homesafe.shared.generated.resources.fin_budget_source_bank
import homesafe.shared.generated.resources.fin_budget_source_manual
import homesafe.shared.generated.resources.fin_budget_source_none
import homesafe.shared.generated.resources.fin_budget_source_rule
import homesafe.shared.generated.resources.fin_budget_tag_clear
import homesafe.shared.generated.resources.fin_budget_tag_remember
import homesafe.shared.generated.resources.fin_budget_tag_remember_note
import homesafe.shared.generated.resources.fin_budget_tag_remembered_note
import homesafe.shared.generated.resources.fin_budget_tank_no_limit
import homesafe.shared.generated.resources.fin_budget_tank_pace_mark
import homesafe.shared.generated.resources.fin_budget_tank_share
import homesafe.shared.generated.resources.fin_budget_teaser_of_limit
import homesafe.shared.generated.resources.fin_budget_teaser_title
import homesafe.shared.generated.resources.fin_budget_what_title
import homesafe.shared.generated.resources.fin_budget_where_title
import homesafe.shared.generated.resources.fin_budget_who_left
import homesafe.shared.generated.resources.fin_budget_who_of_limit
import homesafe.shared.generated.resources.fin_budget_who_over
import homesafe.shared.generated.resources.fin_budget_who_subtitle
import homesafe.shared.generated.resources.fin_budget_who_title
import homesafe.shared.generated.resources.fin_budget_who_unsorted_note
import homesafe.shared.generated.resources.fin_sheet_setup_not_allowed_body
import homesafe.shared.generated.resources.fin_sheet_setup_not_allowed_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How many of the purchases nobody has been put to are offered at the top of the page; the rest are in the list. */
private const val TO_SORT_SHOWN = 4

/** What a bucket goes by in saved state and in the filter: its wire name, or this for the unsorted. */
private const val UNSORTED_KEY = "unsorted"

private val BucketId.key: String get() = wire ?: UNSORTED_KEY

/** The Budget tab with its view model: reads the month while it is on screen, and reports the sheet's take-home and bills to the relay. */
@Composable
internal fun BudgetRoute(
    finance: PersonalFinance?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onOpenLinkedAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: BudgetViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Only while the page is in front of someone: not behind another page, nor with the app in the background.
    LifecycleStartEffect(viewModel) {
        viewModel.setActive(true)
        onStopOrDispose { viewModel.setActive(false) }
    }
    val budget = state.budget
    LaunchedEffect(finance, budget?.config?.cardPaidLines, budget?.config?.sheet, budget?.isCurrentMonth) { viewModel.onSheet(finance) }
    BudgetScreen(
        state = state,
        listState = listState,
        contentPadding = contentPadding,
        onTag = viewModel::tag,
        onSetRole = { card, role -> viewModel.save(BudgetConfigPatch(roles = mapOf(card to role))) },
        onShowMonth = viewModel::showMonth,
        onSyncNow = viewModel::syncNow,
        onRetry = viewModel::refresh,
        onOpenLinkedAccounts = onOpenLinkedAccounts,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * The month's card spending against the budget: how full the month is, how fast it is filling
 * and where that leads, who spent what, where take-home goes, and every purchase, each of which
 * can be put to the person it belongs to.
 */
@Composable
internal fun BudgetScreen(
    state: BudgetUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onTag: (purchaseId: String, bucket: BucketId, remember: Boolean) -> Unit,
    onSetRole: (cardKey: String, role: CardRole) -> Unit,
    onShowMonth: (String?) -> Unit,
    onSyncNow: () -> Unit,
    onRetry: () -> Unit,
    onOpenLinkedAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val budget = state.budget
    // Which bucket's purchases the list shows (by key), and the purchase whose sheet is up (by id).
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var tagging by rememberSaveable { mutableStateOf<String?>(null) }
    val pace = remember(budget) { budget?.let(BudgetPace::of) }
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.testTag("finance_budget")) {
        when {
            budget == null && state.loading -> item(key = "loading") { BudgetSkeleton() }

            budget == null -> item(key = "problem") { ProblemCard(state.problem, state.problemText, onRetry) }

            !budget.configured -> item(key = "setup") {
                BudgetMessageCard(stringResource(Res.string.fin_budget_setup_title), stringResource(Res.string.fin_budget_setup_body), stringResource(Res.string.common_try_again), onRetry)
            }

            budget.cards.isEmpty() -> item(key = "link") {
                BudgetMessageCard(
                    stringResource(Res.string.fin_budget_link_title),
                    stringResource(Res.string.fin_budget_link_body),
                    stringResource(Res.string.fin_budget_open_linked_accounts),
                    onOpenLinkedAccounts,
                    Modifier.testTag("finance_budget_link"),
                )
            }

            pace != null -> {
                val unset = budget.cards.filter { it.role == CardRole.Unset }
                if (budget.budgetCards.isNotEmpty()) {
                    item(key = "hero") { BudgetHero(budget, pace, onBackToNow = { onShowMonth(null) }) }
                }
                item(key = "status") { StatusBlock(state, budget, onSyncNow, onOpenSettings, onOpenLinkedAccounts) }
                if (unset.isNotEmpty()) {
                    item(key = "roles") { RoleSetupCard(unset, saving = state.saving, onSetRole = onSetRole) }
                }
                if (budget.budgetCards.isNotEmpty()) {
                    val toSort = budget.unassigned
                    if (toSort.isNotEmpty()) {
                        item(key = "sort-h") {
                            SectionHeader(
                                stringResource(Res.string.fin_budget_sort_title),
                                trailing = pluralStringResource(Res.plurals.fin_budget_sort_count, toSort.size, toSort.size),
                                subtitle = stringResource(Res.string.fin_budget_sort_subtitle),
                            )
                        }
                        items(toSort.take(TO_SORT_SHOWN), key = { "sort-${it.id}" }) { purchase ->
                            SortRow(purchase, budget.config.people, busy = purchase.id in state.tagging, onPick = { onTag(purchase.id, it, false) }, onOpen = { tagging = purchase.id })
                        }
                        if (toSort.size > TO_SORT_SHOWN) {
                            item(key = "sort-more") {
                                Text(
                                    pluralStringResource(Res.plurals.fin_budget_sort_more, toSort.size - TO_SORT_SHOWN, toSort.size - TO_SORT_SHOWN),
                                    style = FinanceTheme.type.bodyStrong,
                                    color = FinanceTheme.colors.accent,
                                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 10.dp).clickable(role = Role.Button) { filter = UNSORTED_KEY },
                                )
                            }
                        }
                    }
                    item(key = "pace-h") { SectionHeader(stringResource(Res.string.fin_budget_pace_title), info = "budgetpace") }
                    item(key = "pace") { PaceBlock(budget, pace) }
                    item(key = "who-h") { SectionHeader(stringResource(Res.string.fin_budget_who_title), subtitle = stringResource(Res.string.fin_budget_who_subtitle)) }
                    items(budget.buckets.filter { it.id != BucketId.Unassigned || it.count > 0 }, key = { "who-${it.id.key}" }) { bucket ->
                        BucketCard(bucket, budget, selected = filter == bucket.id.key, onClick = { filter = if (filter == bucket.id.key) null else bucket.id.key })
                    }
                    if (budget.config.sheet.takeHome != null) {
                        item(key = "flow-h") { SectionHeader(stringResource(Res.string.fin_budget_flow_title), info = "savingsline") }
                        item(key = "flow") { TakeHomeFlow(budget, pace) }
                    }
                    if (budget.categories.isNotEmpty()) {
                        item(key = "what-h") { SectionHeader(stringResource(Res.string.fin_budget_what_title)) }
                        item(key = "what") { CategoryBlock(budget) }
                    }
                    if (budget.merchants.isNotEmpty()) {
                        item(key = "where-h") { SectionHeader(stringResource(Res.string.fin_budget_where_title)) }
                        item(key = "where") { MerchantBlock(budget) }
                    }
                    if (budget.history.any { it.spent > 0 }) {
                        item(key = "months-h") { SectionHeader(stringResource(Res.string.fin_budget_months_title), subtitle = stringResource(Res.string.fin_budget_months_subtitle)) }
                        item(key = "months") { MonthsBlock(budget, onShowMonth) }
                    }
                    val shown = budget.purchases.filter { filter == null || it.bucket.key == filter }
                    item(key = "list-h") {
                        SectionHeader(stringResource(Res.string.fin_budget_purchases_title), trailing = pluralStringResource(Res.plurals.fin_budget_purchases_count, shown.size, shown.size))
                    }
                    item(key = "list-filter") { FilterChips(budget, filter, onSelect = { filter = it }) }
                    if (shown.isEmpty()) {
                        item(key = "list-none") { FinePrint(stringResource(if (budget.purchases.isEmpty()) Res.string.fin_budget_purchases_none else Res.string.fin_budget_purchases_none_here)) }
                    }
                    shown.groupBy { it.date }.forEach { (day, purchases) ->
                        item(key = "day-$day") {
                            Text(
                                FinanceFormat.dayOfMonth(day),
                                style = FinanceTheme.type.label,
                                color = FinanceTheme.colors.textTertiary,
                                modifier = Modifier.padding(horizontal = PageGutter).padding(top = 14.dp, bottom = 2.dp),
                            )
                        }
                        items(purchases, key = { "p-${it.id}" }) { purchase ->
                            PurchaseRow(purchase, budget, busy = purchase.id in state.tagging, onClick = { tagging = purchase.id })
                        }
                    }
                    item(key = "how") { FinePrint(stringResource(Res.string.fin_budget_how), Modifier.padding(top = 20.dp)) }
                }
            }
        }
    }

    val picked = budget?.purchases?.firstOrNull { it.id == tagging }
    if (picked != null) {
        TagSheet(
            purchase = picked,
            card = budget.cards.firstOrNull { it.key == picked.cardKey },
            people = budget.config.people,
            onPick = { bucket, remember ->
                tagging = null
                onTag(picked.id, bucket, remember)
            },
            onDismiss = { tagging = null },
        )
    }
}

@Composable
private fun BucketId.label(): String = when (this) {
    is BucketId.Person -> name
    BucketId.Family -> stringResource(Res.string.fin_budget_bucket_family)
    BucketId.Unassigned -> stringResource(Res.string.fin_budget_bucket_unsorted)
}

/** Each person their own colour, by their place among the budget's people; the family's is the page's accent. */
@Composable
private fun bucketColor(bucket: BucketId, people: List<String>): Color {
    val colors = FinanceTheme.colors
    return when (bucket) {
        is BucketId.Person -> listOf(colors.cool, colors.violet, colors.categorical[5], colors.categorical[6])[people.indexOf(bucket.name).coerceAtLeast(0) % 4]
        BucketId.Family -> colors.accent
        BucketId.Unassigned -> colors.textTertiary
    }
}

@Composable
private fun statusColor(status: PaceStatus): Color = when (status) {
    PaceStatus.NO_LIMIT -> FinanceTheme.colors.textSecondary
    PaceStatus.UNDER -> FinanceTheme.colors.gain
    PaceStatus.PROJECTED_OVER, PaceStatus.CLOSE -> FinanceTheme.colors.watch
    PaceStatus.OVER, PaceStatus.DIPPING -> FinanceTheme.colors.loss
}

/** Plaid's primary categories, in the app's words; one it doesn't know is "Everything else". */
private fun categoryLabel(category: String?): StringResource = when (category) {
    "FOOD_AND_DRINK" -> Res.string.fin_budget_category_food
    "GENERAL_MERCHANDISE" -> Res.string.fin_budget_category_shopping
    "TRANSPORTATION" -> Res.string.fin_budget_category_transport
    "TRAVEL" -> Res.string.fin_budget_category_travel
    "ENTERTAINMENT" -> Res.string.fin_budget_category_entertainment
    "RENT_AND_UTILITIES" -> Res.string.fin_budget_category_utilities
    "HOME_IMPROVEMENT" -> Res.string.fin_budget_category_home
    "MEDICAL" -> Res.string.fin_budget_category_medical
    "PERSONAL_CARE" -> Res.string.fin_budget_category_personal_care
    "GENERAL_SERVICES" -> Res.string.fin_budget_category_services
    "GOVERNMENT_AND_NON_PROFIT" -> Res.string.fin_budget_category_government
    "BANK_FEES" -> Res.string.fin_budget_category_fees
    "LOAN_PAYMENTS" -> Res.string.fin_budget_category_loans
    else -> Res.string.fin_budget_category_other
}

@Composable
private fun BudgetSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(top = 12.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                Shimmer(Modifier.width(140.dp).height(14.dp))
                Spacer(Modifier.height(10.dp))
                Shimmer(Modifier.width(190.dp).height(44.dp))
                Spacer(Modifier.height(10.dp))
                Shimmer(Modifier.fillMaxWidth(0.9f).height(14.dp))
            }
            Shimmer(Modifier.size(width = 104.dp, height = 168.dp), corner = 26.dp)
        }
        Spacer(Modifier.height(24.dp))
        Shimmer(Modifier.fillMaxWidth().height(180.dp), corner = 18.dp)
    }
}

@Composable
private fun ProblemCard(problem: BankProblem?, text: UiText?, onRetry: () -> Unit) {
    val body = text?.resolve().orEmpty()
    val again = stringResource(Res.string.common_try_again)
    when (problem) {
        BankProblem.RELAY_OUTDATED -> BudgetMessageCard(stringResource(Res.string.fin_budget_problem_outdated_title), body, again, onRetry)

        BankProblem.NOT_ALLOWED ->
            BudgetMessageCard(stringResource(Res.string.fin_sheet_setup_not_allowed_title), stringResource(Res.string.fin_sheet_setup_not_allowed_body), again, onRetry)

        BankProblem.SIGNED_OUT ->
            BudgetMessageCard(stringResource(Res.string.fin_bank_problem_signed_out_title), stringResource(Res.string.fin_bank_problem_signed_out_body), again, onRetry)

        BankProblem.NOT_CONFIGURED -> BudgetMessageCard(stringResource(Res.string.fin_budget_setup_title), stringResource(Res.string.fin_budget_setup_body), again, onRetry)

        BankProblem.PLAID, BankProblem.OTHER, null -> BudgetMessageCard(stringResource(Res.string.fin_budget_problem_other_title), body, again, onRetry)
    }
}

/** A card that says why there's no month to show, with the one thing to do about it. */
@Composable
private fun BudgetMessageCard(title: String, body: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    FinanceCard(modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CreditCard, contentDescription = null, tint = colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = FinanceTheme.type.section, color = colors.textPrimary)
        }
        if (body.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(body, style = FinanceTheme.type.body, color = colors.textSecondary)
        }
        Spacer(Modifier.height(14.dp))
        PillButton(action, colors.accent, onClick = onAction)
    }
}

/**
 * The month at a glance: what has been spent, in words how that stands, and the tank filled to
 * that share of the limit. The dashed line across the tank is where an even month would be
 * today; the liquid over it is a month running ahead of itself.
 */
@Composable
private fun BudgetHero(budget: Budget, pace: BudgetPace, onBackToNow: () -> Unit) {
    val colors = FinanceTheme.colors
    val tint = heatColor(pace.heat, colors.gain, colors.watch, colors.loss)
    val limit = pace.limit
    Box(Modifier.fillMaxWidth()) {
        AuroraBackground(tint, Modifier.matchParentSize(), intensity = 0.7f)
        Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val month = FinanceFormat.month(budget.month, year = true)
                Text(
                    if (budget.isCurrentMonth) stringResource(Res.string.fin_budget_hero_day, month, budget.day, budget.daysInMonth) else stringResource(Res.string.fin_budget_hero_month, month),
                    style = FinanceTheme.type.label,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(BudgetNarrator.headline(pace.status, budget.isCurrentMonth)), style = FinanceTheme.type.bodyStrong, color = statusColor(pace.status), modifier = Modifier.testTag("finance_budget_headline"))
                RollingNumber(FinanceFormat.money(budget.spent, 0), FinanceTheme.type.hero, colors.textPrimary)
                Text(
                    if (limit != null) stringResource(Res.string.fin_budget_hero_limit, FinanceFormat.money(limit, 0)) else stringResource(Res.string.fin_budget_hero_no_limit),
                    style = FinanceTheme.type.label,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Text(BudgetNarrator.verdict(budget, pace).resolve(), style = FinanceTheme.type.body, color = colors.textPrimary)
                if (!budget.isCurrentMonth) {
                    Spacer(Modifier.height(8.dp))
                    PillButton(stringResource(Res.string.fin_budget_back_to_now), colors.accent, onClick = onBackToNow)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val evenPace = if (limit != null && budget.isCurrentMonth) budget.day.toFloat() / budget.daysInMonth else null
                Box(Modifier.size(width = 104.dp, height = 168.dp)) {
                    BudgetTank(pace.level, pace.heat, Modifier.matchParentSize())
                    if (evenPace != null) {
                        val mark = colors.textPrimary
                        Canvas(Modifier.matchParentSize()) {
                            // The shader's own geometry: a full tank stops a little short of the rim.
                            val y = size.height * (1f - evenPace.coerceIn(0f, 1f) * 0.93f)
                            drawLine(mark, Offset(6.dp.toPx(), y), Offset(size.width - 6.dp.toPx(), y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        limit == null -> stringResource(Res.string.fin_budget_tank_no_limit)
                        evenPace != null -> stringResource(Res.string.fin_budget_tank_pace_mark)
                        else -> stringResource(Res.string.fin_budget_tank_share, FinanceFormat.fractionPercent(pace.level.toDouble(), 0))
                    },
                    style = FinanceTheme.type.micro,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

/** How fresh the month is, what about it needs attention, and the two things to do about either. */
@Composable
private fun StatusBlock(state: BudgetUiState, budget: Budget, onSyncNow: () -> Unit, onOpenSettings: () -> Unit, onOpenLinkedAccounts: () -> Unit) {
    val colors = FinanceTheme.colors
    val now = state.readAtEpochSeconds
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(top = 4.dp)) {
        val read = budget.syncedAtEpochSeconds
        val changed = budget.changedAtEpochSeconds
        val fresh = when {
            state.syncing -> UiText.of(Res.string.fin_budget_fresh_reading)
            read == null -> UiText.of(Res.string.fin_budget_fresh_never)
            changed == null -> UiText.of(Res.string.fin_budget_fresh_read, FinanceFormat.ago(now, read))
            else -> UiText.of(Res.string.fin_budget_fresh_read_changed, FinanceFormat.ago(now, read), FinanceFormat.ago(now, changed))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (budget.cards.any { it.needsRelink }) colors.watch else colors.gain))
            Spacer(Modifier.width(8.dp))
            Text(fresh.resolve(), style = FinanceTheme.type.label, color = colors.textSecondary)
        }
        if (!budget.ready) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.fin_budget_not_ready), style = FinanceTheme.type.label, color = colors.watch)
        }
        if (budget.pending > 0) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.fin_budget_pending_note, FinanceFormat.money(budget.pending, 0)), style = FinanceTheme.type.label, color = colors.textTertiary)
        }
        if (budget.sandbox) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.fin_budget_sandbox), style = FinanceTheme.type.label, color = colors.watch)
        }
        budget.cards.filter { it.needsRelink }.forEach { card ->
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.fin_budget_card_relink, card.institution),
                style = FinanceTheme.type.label,
                color = colors.watch,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenLinkedAccounts),
            )
        }
        budget.cards.filter { !it.readsPurchases }.forEach { card ->
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.fin_budget_card_no_purchases, card.name),
                style = FinanceTheme.type.label,
                color = colors.watch,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenLinkedAccounts),
            )
        }
        state.notice?.let { notice ->
            Spacer(Modifier.height(4.dp))
            Text(notice.text.resolve(), style = FinanceTheme.type.label, color = if (notice.isError) colors.loss else colors.gain)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(if (state.syncing) Res.string.fin_budget_action_syncing else Res.string.fin_budget_action_sync), colors.textSecondary, onClick = onSyncNow)
            PillButton(stringResource(Res.string.fin_budget_action_settings), colors.accent, onClick = onOpenSettings, modifier = Modifier.testTag("finance_budget_settings"))
        }
    }
}

/** For the cards nobody has said anything about yet: what each is, in one tap. A card of one person's own is set on the settings page. */
@Composable
private fun RoleSetupCard(cards: List<BudgetCard>, saving: Boolean, onSetRole: (String, CardRole) -> Unit) {
    val colors = FinanceTheme.colors
    FinanceCard(Modifier.padding(top = 16.dp).testTag("finance_budget_roles")) {
        Text(stringResource(Res.string.fin_budget_roles_title), style = FinanceTheme.type.section, color = colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.fin_budget_roles_body), style = FinanceTheme.type.body, color = colors.textSecondary)
        cards.forEach { card ->
            Spacer(Modifier.height(14.dp))
            Text(cardLabel(card), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            RoleChips(CardRole.Unset, emptyList(), enabled = !saving, onPick = { onSetRole(card.key, it) })
        }
    }
}

@Composable
internal fun cardLabel(card: BudgetCard): String =
    if (card.mask != null) stringResource(Res.string.fin_budget_card_masked, card.institution, card.name, card.mask) else stringResource(Res.string.fin_budget_card_named, card.institution, card.name)

@Composable
internal fun roleLabel(role: CardRole): String = when (role) {
    CardRole.Split -> stringResource(Res.string.fin_budget_role_split)
    CardRole.Family -> stringResource(Res.string.fin_budget_role_family)
    CardRole.Ignore -> stringResource(Res.string.fin_budget_role_ignore)
    is CardRole.Person -> stringResource(Res.string.fin_budget_role_person, role.name)
    CardRole.Unset -> stringResource(Res.string.fin_budget_role_unset)
}

/** What a card can be: shared by two, the family's, out of the budget, or with [people] one person's own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoleChips(selected: CardRole, people: List<String>, enabled: Boolean, onPick: (CardRole) -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val roles = listOf(CardRole.Split, CardRole.Family) + people.map { CardRole.Person(it) } + CardRole.Ignore
    FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        roles.forEach { role ->
            val isSelected = role == selected
            Text(
                roleLabel(role),
                style = FinanceTheme.type.label,
                color = if (isSelected) colors.accent else colors.textSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accent.copy(alpha = 0.18f) else colors.surfaceRaised)
                    .border(1.dp, if (isSelected) colors.accent.copy(alpha = 0.6f) else Color.Transparent, CircleShape)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onPick(role) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A purchase nobody has been put to, with each bucket one tap away; the row itself opens the sheet, where its merchant can be remembered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortRow(purchase: Purchase, people: List<String>, busy: Boolean, onPick: (BucketId) -> Unit, onOpen: () -> Unit) {
    val colors = FinanceTheme.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = PageGutter, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(purchase.name, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(FinanceFormat.dayOfMonth(purchase.date), style = FinanceTheme.type.label, color = colors.textTertiary)
            Spacer(Modifier.width(10.dp))
            Text(FinanceFormat.money(purchase.amount), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (people.map { BucketId.Person(it) } + BucketId.Family).forEach { bucket ->
                val color = bucketColor(bucket, people)
                Text(
                    bucket.label(),
                    style = FinanceTheme.type.label,
                    color = if (busy) colors.textTertiary else color,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(color.copy(alpha = if (busy) 0.06f else 0.14f))
                        .clickable(enabled = !busy, role = Role.Button) { onPick(bucket) }
                        .testTag("finance_budget_sort_${purchase.id}_${bucket.key}")
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** The month so far as a line, against the even pace that would end it at the limit and where this pace ends it instead. */
@Composable
private fun PaceBlock(budget: Budget, pace: BudgetPace) {
    val colors = FinanceTheme.colors
    val tint = heatColor(pace.heat, colors.gain, colors.watch, colors.loss)
    val limit = pace.limit
    var scrub by remember { mutableStateOf<Int?>(null) }
    // Day numbers as the chart's time axis: day 0 is the month's start, at nothing spent.
    val soFar = remember(budget.daily) {
        var total = 0.0
        Series.of(
            buildList {
                add(0L to 0.0)
                budget.daily.forEachIndexed { index, spent ->
                    total += spent
                    add(index + 1L to total)
                }
            },
        )
    }
    val lines = buildList {
        add(ChartLine(soFar, tint, fill = true, width = 3f))
        if (limit != null) add(ChartLine(Series.of(listOf(0L to 0.0, budget.daysInMonth.toLong() to limit)), colors.textTertiary, width = 1.5f, dashed = true))
        if (budget.isCurrentMonth && pace.daysLeft > 0) {
            add(ChartLine(Series.of(listOf(budget.day.toLong() to budget.spent, budget.daysInMonth.toLong() to pace.projected)), tint.copy(alpha = 0.7f), width = 2f, dashed = true))
        }
    }
    val top = maxOf(limit ?: 0.0, pace.projected, budget.spent)
    val rules = buildList {
        // Named in the legend under the chart, not on the lines: the two often sit too close for both labels.
        if (limit != null) add(ChartRule(limit, colors.textSecondary, always = true))
        // Kept in view only where it is near enough not to flatten the month under it.
        pace.savingsLine?.let { line -> add(ChartRule(line, colors.loss, always = top > 0 && line <= top * 1.6)) }
    }
    Column(Modifier.fillMaxWidth()) {
        val scrubbed = scrub?.takeIf { it in 1..budget.daily.size }
        Text(
            if (scrubbed != null) {
                stringResource(Res.string.fin_budget_pace_scrub, FinanceFormat.dayOfMonth(budget.month, scrubbed), FinanceFormat.money(soFar.values[scrubbed], 0))
            } else {
                stringResource(Res.string.fin_budget_pace_caption)
            },
            style = FinanceTheme.type.label,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = PageGutter),
        )
        Spacer(Modifier.height(4.dp))
        LineChart(
            lines = lines,
            rules = rules,
            timeAxis = true,
            contentDescription = stringResource(Res.string.fin_budget_pace_chart_description),
            onScrub = { scrub = it },
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        Legend(
            listOfNotNull(
                limit?.let { Triple(stringResource(Res.string.fin_budget_pace_legend_limit), FinanceFormat.money(it, 0), colors.textSecondary) },
                pace.savingsLine?.let { Triple(stringResource(Res.string.fin_budget_pace_legend_savings), FinanceFormat.money(it, 0), colors.loss) },
            ),
        )
        StatGrid(
            buildList {
                add(stringResource(Res.string.fin_budget_pace_stat_rate) to FinanceFormat.money(pace.dailyRate, 0))
                if (budget.isCurrentMonth) add(stringResource(Res.string.fin_budget_pace_stat_projected) to FinanceFormat.money(pace.projected, 0))
                pace.allowancePerDay?.takeIf { budget.isCurrentMonth && pace.daysLeft > 0 }?.let { add(stringResource(Res.string.fin_budget_pace_stat_allowance) to FinanceFormat.money(it, 0)) }
                if (limit != null) {
                    val day = pace.limitDay?.let { FinanceFormat.dayOfMonth(budget.month, it) }
                    add(stringResource(Res.string.fin_budget_pace_stat_limit_day) to (day ?: stringResource(Res.string.fin_budget_pace_stat_not_this_month)))
                }
            },
        )
    }
}

/** One bucket: what it has spent against its limit, as a meter that heats as the limit nears. Tapping it filters the purchases to it. */
@Composable
private fun BucketCard(bucket: BudgetBucket, budget: Budget, selected: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val color = bucketColor(bucket.id, budget.config.people)
    val limit = bucket.limit?.takeIf { it > 0 }
    FinanceCard(Modifier.padding(bottom = 10.dp).testTag("finance_budget_bucket_${bucket.id.key}"), onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(bucket.id.label(), style = FinanceTheme.type.bodyStrong, color = if (selected) colors.accent else colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                if (limit != null) stringResource(Res.string.fin_budget_who_of_limit, FinanceFormat.money(bucket.spent, 0), FinanceFormat.money(limit, 0)) else FinanceFormat.money(bucket.spent, 0),
                style = FinanceTheme.type.bodyStrong,
                color = colors.textPrimary,
            )
        }
        if (limit != null) {
            val level = (bucket.spent / limit).toFloat().coerceAtLeast(0f)
            HeatMeter(level, BudgetPace.heatOf(level))
        } else {
            Spacer(Modifier.height(10.dp))
            Meter(if (budget.spent > 0) (bucket.spent / budget.spent).toFloat() else 0f, color)
        }
        Spacer(Modifier.height(6.dp))
        // How many purchases, then where the bucket stands: two phrases of their own, side by side.
        val standing = when {
            bucket.id == BucketId.Unassigned -> stringResource(Res.string.fin_budget_who_unsorted_note)
            limit == null -> null
            bucket.spent > limit -> stringResource(Res.string.fin_budget_who_over, FinanceFormat.money(bucket.spent - limit, 0))
            else -> stringResource(Res.string.fin_budget_who_left, FinanceFormat.money(limit - bucket.spent, 0))
        }
        Text(
            listOfNotNull(pluralStringResource(Res.plurals.fin_budget_purchases_count, bucket.count, bucket.count), standing).joinToString(stringResource(Res.string.common_dot_separator)),
            style = FinanceTheme.type.label,
            color = if (limit != null && bucket.spent > limit) colors.loss else colors.textSecondary,
        )
    }
}

/**
 * Where the month's take-home goes: a bar its width, cut into the bills that aren't on a card,
 * the cards so far and what is left. When the first two are more than take-home, nothing is
 * left and the line under it says how much is coming out of savings.
 */
@Composable
private fun TakeHomeFlow(budget: Budget, pace: BudgetPace) {
    val colors = FinanceTheme.colors
    val takeHome = budget.config.sheet.takeHome ?: return
    val bills = budget.config.sheet.billsOffCard ?: 0.0
    val left = takeHome - bills - budget.spent
    val tint = heatColor(pace.heat, colors.gain, colors.watch, colors.loss)
    val whole = maxOf(takeHome, bills + budget.spent).takeIf { it > 0 } ?: return
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter).height(26.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceRaised)) {
            if (bills > 0) Box(Modifier.weight((bills / whole).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(colors.textTertiary))
            if (budget.spent > 0) Box(Modifier.weight((budget.spent / whole).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(tint))
            if (left > 0) Box(Modifier.weight((left / whole).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(colors.textPrimary))
        }
        Spacer(Modifier.height(4.dp))
        FlowLine(colors.textSecondary, stringResource(Res.string.fin_budget_flow_take_home), FinanceFormat.money(takeHome, 0), dot = false)
        FlowLine(colors.textTertiary, stringResource(Res.string.fin_budget_flow_bills), FinanceFormat.money(bills, 0))
        FlowLine(tint, stringResource(Res.string.fin_budget_flow_cards), FinanceFormat.money(budget.spent, 0))
        if (left >= 0) {
            FlowLine(colors.textPrimary, stringResource(Res.string.fin_budget_flow_left), FinanceFormat.money(left, 0), strong = true)
        } else {
            FlowLine(colors.loss, stringResource(Res.string.fin_budget_flow_from_savings), FinanceFormat.money(-left, 0), strong = true)
        }
        FinePrint(stringResource(Res.string.fin_budget_flow_note))
    }
}

@Composable
private fun FlowLine(color: Color, label: String, value: String, dot: Boolean = true, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = if (strong) FinanceTheme.type.bodyStrong else FinanceTheme.type.body, color = if (strong) color else FinanceTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = FinanceTheme.type.bodyStrong, color = if (strong) color else FinanceTheme.colors.textPrimary)
    }
}

/** What the month went on: Plaid's categories as a ring, the five biggest named beside it. */
@Composable
private fun CategoryBlock(budget: Budget) {
    val colors = FinanceTheme.colors
    val spending = budget.categories.filter { it.spent > 0 }
    val named = spending.take(5)
    val rest = spending.drop(5).sumOf { it.spent }
    val slices = named.mapIndexed { i, slice -> DonutSlice(stringResource(categoryLabel(slice.name)), slice.spent, colors.categorical[i % colors.categorical.size]) } +
        listOfNotNull(if (rest > 0) DonutSlice(stringResource(Res.string.fin_budget_category_rest), rest, colors.textTertiary) else null)
    Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter), verticalAlignment = Alignment.CenterVertically) {
        DonutChart(slices, Modifier.size(132.dp), thickness = 18.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            slices.forEach { slice ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(slice.color))
                    Spacer(Modifier.width(8.dp))
                    Text(slice.label, style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(FinanceFormat.money(slice.value, 0), style = FinanceTheme.type.label, color = colors.textPrimary)
                }
            }
        }
    }
}

/** Where the month went: the merchants it spent most at. */
@Composable
private fun MerchantBlock(budget: Budget) {
    val colors = FinanceTheme.colors
    val top = budget.merchants.firstOrNull()?.spent?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.fillMaxWidth()) {
        budget.merchants.take(6).forEachIndexed { i, merchant ->
            Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp)) {
                Row {
                    Text(merchant.name, style = FinanceTheme.type.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(pluralStringResource(Res.plurals.fin_budget_merchant_times, merchant.count, merchant.count), style = FinanceTheme.type.label, color = colors.textTertiary)
                    Spacer(Modifier.width(10.dp))
                    Text(FinanceFormat.money(merchant.spent, 0), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                }
                Spacer(Modifier.height(5.dp))
                Meter((merchant.spent / top).toFloat(), colors.cool, height = 4.dp, delayMillis = i * 35)
            }
        }
    }
}

/** The months before this one as bars, each against the limit behind it. Tapping one looks back at it. */
@Composable
private fun MonthsBlock(budget: Budget, onShowMonth: (String?) -> Unit) {
    val colors = FinanceTheme.colors
    val limit = budget.config.limits.total?.takeIf { it > 0 }
    val months = budget.history.map { it.month to it.spent } + (budget.month to budget.spent)
    BarChart(
        bars = months.map { (month, spent) ->
            Bar(FinanceFormat.month(month), spent, if (limit != null && spent > limit) colors.loss else colors.gain, secondary = limit, secondaryColor = colors.hairline)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).height(150.dp),
        selected = months.lastIndex,
        onSelect = { index -> months.getOrNull(index)?.first?.takeIf { it != budget.month }?.let(onShowMonth) },
    )
}

@Composable
private fun FilterChips(budget: Budget, selected: String?, onSelect: (String?) -> Unit) {
    val colors = FinanceTheme.colors
    val options: List<Pair<String?, String>> = buildList {
        add(null to stringResource(Res.string.fin_budget_filter_all))
        budget.buckets.forEach { bucket -> if (bucket.id != BucketId.Unassigned || bucket.count > 0) add(bucket.id.key to bucket.id.label()) }
    }
    LazyRow(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first ?: "all" }) { (key, label) ->
            val isSelected = key == selected
            Text(
                label,
                style = FinanceTheme.type.label,
                color = if (isSelected) colors.accent else colors.textSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accent.copy(alpha = 0.18f) else colors.surfaceRaised)
                    .border(1.dp, if (isSelected) colors.accent.copy(alpha = 0.6f) else Color.Transparent, CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(key) }
                    .testTag("finance_budget_filter_${key ?: "all"}")
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PurchaseRow(purchase: Purchase, budget: Budget, busy: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val card = budget.cards.firstOrNull { it.key == purchase.cardKey }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick).testTag("finance_budget_purchase_${purchase.id}").padding(horizontal = PageGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(purchase.name, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val about = listOfNotNull(
                card?.name,
                purchase.category?.let { stringResource(categoryLabel(it)) },
                if (purchase.pending) stringResource(Res.string.fin_budget_purchase_pending) else null,
            )
            Text(about.joinToString(stringResource(Res.string.common_dot_separator)), style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (purchase.isRefund) stringResource(Res.string.fin_budget_purchase_refund, FinanceFormat.money(-purchase.amount)) else FinanceFormat.money(purchase.amount),
                style = FinanceTheme.type.bodyStrong,
                color = if (purchase.isRefund) colors.gain else colors.textPrimary,
            )
            val color = bucketColor(purchase.bucket, budget.config.people)
            Text(
                purchase.bucket.label(),
                style = FinanceTheme.type.micro,
                color = if (busy) colors.textTertiary else color,
                modifier = Modifier.padding(top = 3.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Whose a purchase is: each of the budget's people, the family, or (for one tagged by hand) back
 * to wherever it would fall on its own. With the switch on, the choice is remembered for every
 * purchase from the same merchant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagSheet(purchase: Purchase, card: BudgetCard?, people: List<String>, onPick: (BucketId, Boolean) -> Unit, onDismiss: () -> Unit) {
    val colors = FinanceTheme.colors
    var always by rememberSaveable(purchase.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("finance_budget_tag_sheet")) {
            Column(Modifier.padding(horizontal = PageGutter)) {
                Text(purchase.name, style = FinanceTheme.type.section, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                val about = listOfNotNull(FinanceFormat.dayOfMonth(purchase.date), FinanceFormat.money(purchase.amount), card?.name)
                Text(about.joinToString(stringResource(Res.string.common_dot_separator)), style = FinanceTheme.type.label, color = colors.textSecondary)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(sourceNote(purchase.source)), style = FinanceTheme.type.label, color = colors.textTertiary)
            }
            Spacer(Modifier.height(10.dp))
            val options = people.map { BucketId.Person(it) } + BucketId.Family + listOfNotNull(BucketId.Unassigned.takeIf { purchase.source == BucketSource.MANUAL })
            Column(Modifier.selectableGroup()) {
                options.forEach { bucket ->
                    val isSelected = bucket == purchase.bucket && bucket != BucketId.Unassigned
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = isSelected, role = Role.RadioButton) { onPick(bucket, always) }
                            .testTag("finance_budget_tag_${bucket.key}")
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = PageGutter, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(bucketColor(bucket, people)))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (bucket == BucketId.Unassigned) stringResource(Res.string.fin_budget_tag_clear) else bucket.label(),
                            style = FinanceTheme.type.body,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Hairline(Modifier.padding(horizontal = PageGutter, vertical = 6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = always, role = Role.Switch) { always = it }
                    .testTag("finance_budget_tag_remember")
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = PageGutter, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.fin_budget_tag_remember, purchase.name), style = FinanceTheme.type.body, color = colors.textPrimary)
                    Text(
                        stringResource(if (purchase.remembered) Res.string.fin_budget_tag_remembered_note else Res.string.fin_budget_tag_remember_note),
                        style = FinanceTheme.type.label,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    if (always) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (always) colors.accent else colors.textTertiary,
                )
            }
        }
    }
}

/** The Wallet's line about the month under way: what the cards have cost against the limit, and where that stands. Opens the Budget tab. */
@Composable
internal fun BudgetTeaser(budget: Budget, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val pace = remember(budget) { BudgetPace.of(budget) }
    FinanceCard(modifier.testTag("finance_budget_teaser"), onClick = onOpen, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.fin_budget_teaser_title), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, modifier = Modifier.weight(1f))
            val limit = pace.limit
            Text(
                if (limit != null) stringResource(Res.string.fin_budget_teaser_of_limit, FinanceFormat.money(budget.spent, 0), FinanceFormat.money(limit, 0)) else FinanceFormat.money(budget.spent, 0),
                style = FinanceTheme.type.bodyStrong,
                color = statusColor(pace.status),
            )
        }
        if (pace.limit != null) HeatMeter(pace.level, pace.heat) else Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(4.dp))
        Text(BudgetNarrator.verdict(budget, pace).resolve(), style = FinanceTheme.type.label, color = colors.textSecondary)
    }
}

private fun sourceNote(source: BucketSource): StringResource = when (source) {
    BucketSource.MANUAL -> Res.string.fin_budget_source_manual
    BucketSource.ACCOUNT -> Res.string.fin_budget_source_account
    BucketSource.BANK -> Res.string.fin_budget_source_bank
    BucketSource.RULE -> Res.string.fin_budget_source_rule
    BucketSource.NONE -> Res.string.fin_budget_source_none
}
