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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.finance.BudgetUiState
import com.meticulouscreations.homesafe.finance.BudgetViewModel
import com.meticulouscreations.homesafe.finance.domain.AmazonItem
import com.meticulouscreations.homesafe.finance.domain.AmazonNeed
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BucketSource
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetBucket
import com.meticulouscreations.homesafe.finance.domain.BudgetCard
import com.meticulouscreations.homesafe.finance.domain.BudgetCompare
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetLine
import com.meticulouscreations.homesafe.finance.domain.BudgetPace
import com.meticulouscreations.homesafe.finance.domain.CardHolder
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.LinePick
import com.meticulouscreations.homesafe.finance.domain.LineSource
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
import com.meticulouscreations.homesafe.ui.WithTooltip
import com.meticulouscreations.homesafe.ui.handCursor
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_try_again
import homesafe.shared.generated.resources.fin_amazon_filter
import homesafe.shared.generated.resources.fin_amazon_subtitle
import homesafe.shared.generated.resources.fin_amazon_title
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
import homesafe.shared.generated.resources.fin_budget_category_filter_click
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
import homesafe.shared.generated.resources.fin_budget_compare_less
import homesafe.shared.generated.resources.fin_budget_compare_level
import homesafe.shared.generated.resources.fin_budget_compare_more
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
import homesafe.shared.generated.resources.fin_budget_headline_over
import homesafe.shared.generated.resources.fin_budget_hero_day
import homesafe.shared.generated.resources.fin_budget_hero_limit
import homesafe.shared.generated.resources.fin_budget_hero_month
import homesafe.shared.generated.resources.fin_budget_hero_no_limit
import homesafe.shared.generated.resources.fin_budget_holder_ending
import homesafe.shared.generated.resources.fin_budget_holders_body
import homesafe.shared.generated.resources.fin_budget_holders_title
import homesafe.shared.generated.resources.fin_budget_how
import homesafe.shared.generated.resources.fin_budget_kpi_left
import homesafe.shared.generated.resources.fin_budget_kpi_of_limit
import homesafe.shared.generated.resources.fin_budget_kpi_of_take_home
import homesafe.shared.generated.resources.fin_budget_kpi_per_day
import homesafe.shared.generated.resources.fin_budget_line_source_auto
import homesafe.shared.generated.resources.fin_budget_line_source_manual
import homesafe.shared.generated.resources.fin_budget_line_source_manual_none
import homesafe.shared.generated.resources.fin_budget_line_source_none
import homesafe.shared.generated.resources.fin_budget_line_source_rule
import homesafe.shared.generated.resources.fin_budget_line_source_rule_none
import homesafe.shared.generated.resources.fin_budget_lines_heading
import homesafe.shared.generated.resources.fin_budget_lines_of_planned
import homesafe.shared.generated.resources.fin_budget_lines_outside
import homesafe.shared.generated.resources.fin_budget_lines_outside_note
import homesafe.shared.generated.resources.fin_budget_lines_setup_action
import homesafe.shared.generated.resources.fin_budget_lines_setup_body
import homesafe.shared.generated.resources.fin_budget_lines_setup_title
import homesafe.shared.generated.resources.fin_budget_lines_subtitle
import homesafe.shared.generated.resources.fin_budget_lines_title
import homesafe.shared.generated.resources.fin_budget_lines_usual
import homesafe.shared.generated.resources.fin_budget_lines_usual_less
import homesafe.shared.generated.resources.fin_budget_lines_usual_more
import homesafe.shared.generated.resources.fin_budget_link_body
import homesafe.shared.generated.resources.fin_budget_link_title
import homesafe.shared.generated.resources.fin_budget_merchant_times
import homesafe.shared.generated.resources.fin_budget_months_subtitle
import homesafe.shared.generated.resources.fin_budget_months_title
import homesafe.shared.generated.resources.fin_budget_not_ready
import homesafe.shared.generated.resources.fin_budget_note_hint
import homesafe.shared.generated.resources.fin_budget_note_remove
import homesafe.shared.generated.resources.fin_budget_note_save
import homesafe.shared.generated.resources.fin_budget_note_title
import homesafe.shared.generated.resources.fin_budget_open_linked_accounts
import homesafe.shared.generated.resources.fin_budget_pace_caption
import homesafe.shared.generated.resources.fin_budget_pace_caption_compare
import homesafe.shared.generated.resources.fin_budget_pace_chart_description
import homesafe.shared.generated.resources.fin_budget_pace_legend_current
import homesafe.shared.generated.resources.fin_budget_pace_legend_limit
import homesafe.shared.generated.resources.fin_budget_pace_legend_savings
import homesafe.shared.generated.resources.fin_budget_pace_scrub
import homesafe.shared.generated.resources.fin_budget_pace_stat_allowance
import homesafe.shared.generated.resources.fin_budget_pace_stat_limit_day
import homesafe.shared.generated.resources.fin_budget_pace_stat_not_this_month
import homesafe.shared.generated.resources.fin_budget_pace_stat_projected
import homesafe.shared.generated.resources.fin_budget_pace_stat_rate
import homesafe.shared.generated.resources.fin_budget_pace_stat_usual
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
import homesafe.shared.generated.resources.fin_budget_search_clear
import homesafe.shared.generated.resources.fin_budget_search_description
import homesafe.shared.generated.resources.fin_budget_search_hint
import homesafe.shared.generated.resources.fin_budget_setup_body
import homesafe.shared.generated.resources.fin_budget_setup_title
import homesafe.shared.generated.resources.fin_budget_sort_count
import homesafe.shared.generated.resources.fin_budget_sort_more
import homesafe.shared.generated.resources.fin_budget_sort_subtitle
import homesafe.shared.generated.resources.fin_budget_sort_title
import homesafe.shared.generated.resources.fin_budget_source_account
import homesafe.shared.generated.resources.fin_budget_source_amazon
import homesafe.shared.generated.resources.fin_budget_source_bank
import homesafe.shared.generated.resources.fin_budget_source_manual
import homesafe.shared.generated.resources.fin_budget_source_none
import homesafe.shared.generated.resources.fin_budget_source_rule
import homesafe.shared.generated.resources.fin_budget_table_amount
import homesafe.shared.generated.resources.fin_budget_table_card
import homesafe.shared.generated.resources.fin_budget_table_line
import homesafe.shared.generated.resources.fin_budget_table_purchase
import homesafe.shared.generated.resources.fin_budget_table_whose
import homesafe.shared.generated.resources.fin_budget_tag_clear
import homesafe.shared.generated.resources.fin_budget_tag_line_auto
import homesafe.shared.generated.resources.fin_budget_tag_line_none
import homesafe.shared.generated.resources.fin_budget_tag_line_title
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
import homesafe.shared.generated.resources.fin_recurring_subtitle
import homesafe.shared.generated.resources.fin_recurring_title
import homesafe.shared.generated.resources.fin_sheet_setup_not_allowed_body
import homesafe.shared.generated.resources.fin_sheet_setup_not_allowed_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** How many of the purchases nobody has been put to are offered at the top of the page; the rest are in the list. */
private const val TO_SORT_SHOWN = 4

/** What a bucket goes by in saved state and in the filter: its wire name, or this for the unsorted. */
private const val UNSORTED_KEY = "unsorted"

private val BucketId.key: String get() = wire ?: UNSORTED_KEY

/** The purchases' header, which picking a filter scrolls to. */
private const val LIST_HEADER_KEY = "list-h"

/** A category in the filter: this before Plaid's name for it (`cat:FOOD_AND_DRINK`). */
private const val CATEGORY_FILTER = "cat:"

/** A budget line in the filter: this before the line's name, or alone for the purchases in none (`line:Groceries`). */
private const val LINE_FILTER = "line:"

/** The list's filter for Amazon's charges. */
private const val AMAZON_FILTER = "amazon"

/** How far a line's usual month has to be from its plan before the page says so: a tenth of the plan, and never under this. */
private const val USUAL_GAP_MIN = 25.0

/** The days into a month before a line says where its pace is heading: sooner, one shop's trip is the whole pace. */
private const val LINE_HEADING_FROM_DAY = 7

/** What the relay counts a purchase with no category as, in the month's categories. */
private const val OTHER_CATEGORY = "OTHER"

/** The category a purchase is counted under, as the relay counts it: one Plaid gave no name is "OTHER". */
private val Purchase.categoryKey: String get() = category?.takeIf { it.isNotEmpty() } ?: OTHER_CATEGORY

/**
 * Whether [purchase] is in the list under [filter]: everything, one bucket's (by key), one
 * category's (`cat:` and its name), one budget line's (`line:` and its name, or nothing for none),
 * or Amazon's ([AMAZON_FILTER]). An Amazon charge shared out is in each bucket a share of it is.
 */
private fun matchesFilter(purchase: Purchase, filter: String?): Boolean = when {
    filter == null -> true
    filter == AMAZON_FILTER -> purchase.fromAmazon
    filter.startsWith(CATEGORY_FILTER) -> purchase.categoryKey == filter.removePrefix(CATEGORY_FILTER)
    filter.startsWith(LINE_FILTER) -> purchase.line.orEmpty() == filter.removePrefix(LINE_FILTER)
    else -> if (purchase.shares.isEmpty()) purchase.bucket.key == filter else purchase.shares.any { it.bucket.key == filter }
}

/** Whose a purchase is, as its chip says it: a charge shared out names each bucket, the largest share first. */
@Composable
private fun Purchase.whoseLabel(): String =
    if (shares.size > 1) shares.map { it.bucket.label() }.joinToString(stringResource(Res.string.common_dot_separator)) else bucket.label()

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
        onTagLine = viewModel::tagLine,
        onSayNeed = viewModel::sayNeed,
        onNote = viewModel::note,
        onSetRole = { card, role -> viewModel.save(BudgetConfigPatch(roles = mapOf(card to role))) },
        onSetHolder = { card, mark, person -> viewModel.save(BudgetConfigPatch(holders = mapOf(card to mapOf(mark to person)))) },
        onShowMonth = viewModel::showMonth,
        onSyncNow = viewModel::syncNow,
        onRetry = viewModel::refresh,
        onOpenLinkedAccounts = onOpenLinkedAccounts,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * The month's card spending against the budget: how full the month is, how fast it is filling
 * and where that leads, each card-paid line of the budget sheet against its plan, who spent what,
 * where take-home goes, and every purchase, each of which can be put to the person it belongs to
 * and in the line it belongs in.
 */
// Public for another module to place; it fills whatever it is given, so it takes no Modifier.
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun BudgetScreen(
    state: BudgetUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onTag: (purchaseId: String, bucket: BucketId, remember: Boolean) -> Unit,
    onTagLine: (purchaseId: String, line: LinePick, remember: Boolean) -> Unit,
    onSetRole: (cardKey: String, role: CardRole) -> Unit,
    onSetHolder: (cardKey: String, mark: String, person: String?) -> Unit,
    onShowMonth: (String?) -> Unit,
    onSyncNow: () -> Unit,
    onRetry: () -> Unit,
    onOpenLinkedAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onSayNeed: (purchaseId: String, orderId: String, item: AmazonItem, need: AmazonNeed?) -> Unit = { _, _, _, _ -> },
    onNote: (purchaseId: String, note: String?) -> Unit = { _, _ -> },
) {
    val budget = state.budget
    // Which bucket's purchases the list shows (by key), and the purchase whose sheet is up (by id).
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var tagging by rememberSaveable { mutableStateOf<String?>(null) }
    // What the purchases are searched for, by name.
    var query by rememberSaveable { mutableStateOf("") }
    val pace = remember(budget) { budget?.let(BudgetPace::of) }
    val scope = rememberCoroutineScope()

    // The list sits well below the things that filter it, so picking a filter brings the list up to show it took.
    val showInList: (String?) -> Unit = { key ->
        filter = key
        if (key != null) scope.launch { listState.scrollToKey(LIST_HEADER_KEY) }
    }
    val purchases = budget?.purchases.orEmpty()
    // Worked out once per change, not on every recomposition of the list.
    val shown = remember(purchases, filter, query) {
        val needle = query.trim()
        purchases.filter { matchesFilter(it, filter) && (needle.isEmpty() || it.name.contains(needle, ignoreCase = true) || it.note?.contains(needle, ignoreCase = true) == true) }
    }
    val shownByDay = remember(shown) { shown.groupBy { it.date } }
    val desk = LocalFinanceDesk.current
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

            pace != null && desk -> {
                budgetDeskItems(
                    state,
                    budget,
                    pace,
                    filter,
                    query,
                    shown.size,
                    shownByDay,
                    showInList = showInList,
                    onFilter = { filter = it },
                    onQuery = { query = it },
                    onOpenTag = { tagging = it },
                    onTag = onTag,
                    onSetRole = onSetRole,
                    onSetHolder = onSetHolder,
                    onShowMonth = onShowMonth,
                    onSyncNow = onSyncNow,
                    onOpenLinkedAccounts = onOpenLinkedAccounts,
                    onOpenSettings = onOpenSettings,
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
                // A shared card whose bank marks each purchase with the card that made it: said once, they sort themselves.
                val unsaid = budget.cards.filter { it.unsaidHolders.isNotEmpty() }
                if (unsaid.isNotEmpty() && budget.config.people.isNotEmpty()) {
                    item(key = "holders") { HoldersCard(unsaid, budget, state.saving, onSetHolder) }
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
                        items(toSort.take(TO_SORT_SHOWN), key = { "sort-${it.id}" }, contentType = { "sort" }) { purchase ->
                            SortRow(purchase, budget.config.people, busy = purchase.id in state.tagging, onPick = { onTag(purchase.id, it, false) }, onOpen = { tagging = purchase.id })
                        }
                        if (toSort.size > TO_SORT_SHOWN) {
                            item(key = "sort-more") {
                                Text(
                                    pluralStringResource(Res.plurals.fin_budget_sort_more, toSort.size - TO_SORT_SHOWN, toSort.size - TO_SORT_SHOWN),
                                    style = FinanceTheme.type.bodyStrong,
                                    color = FinanceTheme.colors.accent,
                                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 10.dp).clickable(role = Role.Button) { showInList(UNSORTED_KEY) },
                                )
                            }
                        }
                    }
                    // The month under way, kept while an earlier one is up: the bars stay on the same months, and the two are set side by side.
                    val current = state.current?.takeIf { it.isCurrentMonth } ?: budget
                    val compareTo = current.takeIf { it.month != budget.month }
                    if (current.history.any { it.spent > 0 }) {
                        item(key = "months-h") { SectionHeader(stringResource(Res.string.fin_budget_months_title), subtitle = stringResource(Res.string.fin_budget_months_subtitle)) }
                        item(key = "months") { MonthsBlock(current, showing = state.month ?: current.month, shown = budget, onShowMonth = onShowMonth) }
                    }
                    item(key = "pace-h") { SectionHeader(stringResource(Res.string.fin_budget_pace_title), info = "budgetpace") }
                    item(key = "pace") { PaceBlock(budget, pace, compareTo) }
                    if (budget.config.cardPaidLines.isEmpty()) {
                        // Only for the month under way: an earlier one is looked back at, not set up.
                        if (budget.isCurrentMonth) {
                            item(key = "lines-setup") {
                                BudgetMessageCard(
                                    stringResource(Res.string.fin_budget_lines_setup_title),
                                    stringResource(Res.string.fin_budget_lines_setup_body),
                                    stringResource(Res.string.fin_budget_lines_setup_action),
                                    onOpenSettings,
                                    Modifier.testTag("finance_budget_lines_setup"),
                                )
                            }
                        }
                    } else {
                        item(key = "lines-h") { SectionHeader(stringResource(Res.string.fin_budget_lines_title), subtitle = stringResource(Res.string.fin_budget_lines_subtitle)) }
                        itemsIndexed(budget.lines, key = { _, line -> "line-${line.name}" }, contentType = { _, _ -> "line" }) { index, line ->
                            val key = LINE_FILTER + line.name
                            LineCard(line, index, budget, selected = filter == key, onClick = { showInList(if (filter == key) null else key) })
                        }
                        if (budget.outsideLines.count > 0) {
                            item(key = "lines-outside") {
                                OutsideLinesRow(budget, selected = filter == LINE_FILTER, onClick = { showInList(if (filter == LINE_FILTER) null else LINE_FILTER) })
                            }
                        }
                    }
                    val recurring = budget.recurring
                    if (recurring != null && budget.showsRecurring) {
                        item(key = "recurring-h") { SectionHeader(stringResource(Res.string.fin_recurring_title), subtitle = stringResource(Res.string.fin_recurring_subtitle)) }
                        item(key = "recurring") { RecurringBlock(recurring, budget) }
                    }
                    val amazon = budget.amazon
                    if (amazon != null && amazon.configured && amazon.charges > 0) {
                        item(key = "amazon-h") { SectionHeader(stringResource(Res.string.fin_amazon_title), subtitle = stringResource(Res.string.fin_amazon_subtitle)) }
                        item(key = "amazon") {
                            AmazonBlock(amazon, selected = filter == AMAZON_FILTER, onClick = { showInList(if (filter == AMAZON_FILTER) null else AMAZON_FILTER) })
                        }
                    }
                    item(key = "who-h") { SectionHeader(stringResource(Res.string.fin_budget_who_title), subtitle = stringResource(Res.string.fin_budget_who_subtitle)) }
                    items(budget.buckets.filter { it.id != BucketId.Unassigned || it.count > 0 }, key = { "who-${it.id.key}" }, contentType = { "bucket" }) { bucket ->
                        BucketCard(bucket, budget, selected = filter == bucket.id.key, onClick = { showInList(if (filter == bucket.id.key) null else bucket.id.key) })
                    }
                    if (budget.config.sheet.takeHome != null) {
                        item(key = "flow-h") { SectionHeader(stringResource(Res.string.fin_budget_flow_title), info = "savingsline") }
                        item(key = "flow") { TakeHomeFlow(budget, pace) }
                    }
                    if (budget.categories.isNotEmpty()) {
                        item(key = "what-h") { SectionHeader(stringResource(Res.string.fin_budget_what_title)) }
                        item(key = "what") { CategoryBlock(budget, selected = filter, onSelect = { showInList(it) }) }
                    }
                    if (budget.merchants.isNotEmpty()) {
                        item(key = "where-h") { SectionHeader(stringResource(Res.string.fin_budget_where_title)) }
                        item(key = "where") { MerchantBlock(budget) }
                    }
                    item(key = LIST_HEADER_KEY) {
                        SectionHeader(stringResource(Res.string.fin_budget_purchases_title), trailing = pluralStringResource(Res.plurals.fin_budget_purchases_count, shown.size, shown.size))
                    }
                    item(key = "list-filter") { FilterChips(budget, filter, onSelect = { filter = it }) }
                    if (budget.purchases.isNotEmpty()) {
                        item(key = "list-search") { PurchaseSearch(query, onQuery = { query = it }) }
                    }
                    if (shown.isEmpty()) {
                        item(key = "list-none") { FinePrint(stringResource(if (budget.purchases.isEmpty()) Res.string.fin_budget_purchases_none else Res.string.fin_budget_purchases_none_here)) }
                    }
                    shownByDay.forEach { (day, onDay) ->
                        item(key = "day-$day", contentType = "day") {
                            Text(
                                FinanceFormat.dayOfMonth(day).resolve(),
                                style = FinanceTheme.type.label,
                                color = FinanceTheme.colors.textTertiary,
                                modifier = Modifier.padding(horizontal = PageGutter).padding(top = 14.dp, bottom = 2.dp),
                            )
                        }
                        items(onDay, key = { "p-${it.id}" }, contentType = { "purchase" }) { purchase ->
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
            lines = budget.lines.map { it.name },
            busy = picked.id in state.tagging,
            onSayNeed = { orderId, item, need -> onSayNeed(picked.id, orderId, item, need) },
            onNote = { note -> onNote(picked.id, note) },
            onPick = { bucket, remember ->
                tagging = null
                onTag(picked.id, bucket, remember)
            },
            onPickLine = { line, remember ->
                tagging = null
                onTagLine(picked.id, line, remember)
            },
            onDismiss = { tagging = null },
        )
    }
}

/** For a shared card whose bank marks each purchase with the card that made it: whose each mark is, said once. */
@Composable
private fun HoldersCard(unsaid: List<BudgetCard>, budget: Budget, saving: Boolean, onSetHolder: (cardKey: String, mark: String, person: String?) -> Unit) {
    FinanceCard(Modifier.padding(top = 16.dp).testTag("finance_budget_holders")) {
        Text(stringResource(Res.string.fin_budget_holders_title), style = FinanceTheme.type.section, color = FinanceTheme.colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.fin_budget_holders_body), style = FinanceTheme.type.body, color = FinanceTheme.colors.textSecondary)
        unsaid.forEach { card ->
            Spacer(Modifier.height(14.dp))
            Text(cardLabel(card), style = FinanceTheme.type.bodyStrong, color = FinanceTheme.colors.textPrimary)
            CardHolders(card, budget.config.people, enabled = !saving, onSay = { mark, person -> onSetHolder(card.key, mark, person) })
        }
    }
}

/**
 * The Budget tab on a desk: the month's headline and pace side by side; the budget lines and who
 * spent what as cards two across beside a rail of what to sort and where the money went; then
 * every purchase as a table, a column each for what it was, its line, its card, whose it is and
 * what it cost, in one panel that stays a lazy list however long the month's gets.
 */
private fun LazyListScope.budgetDeskItems(
    state: BudgetUiState,
    budget: Budget,
    pace: BudgetPace,
    filter: String?,
    query: String,
    shownCount: Int,
    shownByDay: Map<String, List<Purchase>>,
    showInList: (String?) -> Unit,
    onFilter: (String?) -> Unit,
    onQuery: (String) -> Unit,
    onOpenTag: (String) -> Unit,
    onTag: (purchaseId: String, bucket: BucketId, remember: Boolean) -> Unit,
    onSetRole: (cardKey: String, role: CardRole) -> Unit,
    onSetHolder: (cardKey: String, mark: String, person: String?) -> Unit,
    onShowMonth: (String?) -> Unit,
    onSyncNow: () -> Unit,
    onOpenLinkedAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spending = budget.budgetCards.isNotEmpty()
    // Where the reading stands and the two things to do about it, on one line over the page.
    item(key = "desk-status") { DeskPage { BudgetDeskStatus(state, budget, onSyncNow, onOpenSettings, onOpenLinkedAccounts, onBackToNow = { onShowMonth(null) }) } }
    // The month in four figures, each read at a glance.
    if (spending) item(key = "desk-figures") { DeskPage { BudgetFigures(budget, pace, Modifier.padding(top = 12.dp)) } }
    // What needs saying before the month can be read right, the width of the page.
    val unset = budget.cards.filter { it.role == CardRole.Unset }
    if (unset.isNotEmpty()) {
        item(key = "roles") { DeskPage { RoleSetupCard(unset, saving = state.saving, onSetRole = onSetRole) } }
    }
    val unsaid = budget.cards.filter { it.unsaidHolders.isNotEmpty() }
    if (unsaid.isNotEmpty() && budget.config.people.isNotEmpty()) {
        item(key = "holders") { DeskPage { HoldersCard(unsaid, budget, state.saving, onSetHolder) } }
    }
    if (!spending) return
    item(key = "desk") {
        DeskPage {
            DeskRow(Modifier.padding(top = DeskGap)) {
                DeskColumn(DESK_MAIN_WEIGHT) {
                    // The month under way, kept while an earlier one is up: the bars stay on the same months, and a month
                    // tapped there is drawn over this one in the pace chart, so the two panels sit together.
                    val current = state.current?.takeIf { it.isCurrentMonth } ?: budget
                    DeskPanel(title = stringResource(Res.string.fin_budget_pace_title), info = "budgetpace", bottom = 8.dp) {
                        PaceBlock(budget, pace, current.takeIf { it.month != budget.month })
                    }
                    if (current.history.any { it.spent > 0 }) {
                        DeskPanel(title = stringResource(Res.string.fin_budget_months_title), subtitle = stringResource(Res.string.fin_budget_months_subtitle)) {
                            MonthsBlock(current, showing = state.month ?: current.month, shown = budget, onShowMonth = onShowMonth)
                        }
                    }
                    if (budget.config.cardPaidLines.isEmpty()) {
                        if (budget.isCurrentMonth) {
                            BudgetMessageCard(
                                stringResource(Res.string.fin_budget_lines_setup_title),
                                stringResource(Res.string.fin_budget_lines_setup_body),
                                stringResource(Res.string.fin_budget_lines_setup_action),
                                onOpenSettings,
                                Modifier.testTag("finance_budget_lines_setup"),
                            )
                        }
                    } else {
                        DeskPanel(title = stringResource(Res.string.fin_budget_lines_title), bottom = 8.dp) {
                            budget.lines.forEachIndexed { index, line ->
                                val key = LINE_FILTER + line.name
                                LinePlanRow(line, lineColor(index), budget, selected = filter == key, onClick = { showInList(if (filter == key) null else key) })
                            }
                            if (budget.outsideLines.count > 0) {
                                PlanRow(
                                    color = FinanceTheme.colors.textTertiary,
                                    name = stringResource(Res.string.fin_budget_lines_outside),
                                    spent = budget.outsideLines.spent,
                                    plan = null,
                                    share = if (budget.spent > 0) (budget.outsideLines.spent / budget.spent).toFloat() else 0f,
                                    status = pluralStringResource(Res.plurals.fin_budget_purchases_count, budget.outsideLines.count, budget.outsideLines.count),
                                    statusColor = FinanceTheme.colors.textSecondary,
                                    selected = filter == LINE_FILTER,
                                    onClick = { showInList(if (filter == LINE_FILTER) null else LINE_FILTER) },
                                    modifier = Modifier.testTag("finance_budget_line_outside"),
                                )
                            }
                        }
                    }
                    DeskPanel(title = stringResource(Res.string.fin_budget_who_title), bottom = 8.dp) {
                        budget.buckets.filter { it.id != BucketId.Unassigned || it.count > 0 }.forEach { bucket ->
                            BucketPlanRow(bucket, budget, selected = filter == bucket.id.key, onClick = { showInList(if (filter == bucket.id.key) null else bucket.id.key) })
                        }
                    }
                }
                DeskColumn(1f) {
                    val toSort = budget.unassigned
                    if (toSort.isNotEmpty()) {
                        DeskPanel(
                            title = stringResource(Res.string.fin_budget_sort_title),
                            trailing = pluralStringResource(Res.plurals.fin_budget_sort_count, toSort.size, toSort.size),
                            bottom = 8.dp,
                        ) {
                            toSort.take(TO_SORT_SHOWN).forEach { purchase ->
                                key(purchase.id) {
                                    SortRow(purchase, budget.config.people, busy = purchase.id in state.tagging, onPick = { onTag(purchase.id, it, false) }, onOpen = { onOpenTag(purchase.id) })
                                }
                            }
                            if (toSort.size > TO_SORT_SHOWN) {
                                Text(
                                    pluralStringResource(Res.plurals.fin_budget_sort_more, toSort.size - TO_SORT_SHOWN, toSort.size - TO_SORT_SHOWN),
                                    style = FinanceTheme.type.bodyStrong,
                                    color = FinanceTheme.colors.accent,
                                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 10.dp).handCursor().clickable(role = Role.Button) { showInList(UNSORTED_KEY) },
                                )
                            }
                        }
                    }
                    if (budget.categories.isNotEmpty()) {
                        DeskPanel(title = stringResource(Res.string.fin_budget_what_title)) { CategoryBlock(budget, selected = filter, onSelect = { showInList(it) }) }
                    }
                    if (budget.merchants.isNotEmpty()) {
                        DeskPanel(title = stringResource(Res.string.fin_budget_where_title), bottom = 8.dp) { MerchantBlock(budget) }
                    }
                    val recurring = budget.recurring
                    if (recurring != null && budget.showsRecurring) {
                        DeskPanel(title = stringResource(Res.string.fin_recurring_title), subtitle = stringResource(Res.string.fin_recurring_subtitle), bottom = 4.dp) {
                            RecurringBlock(recurring, budget)
                        }
                    }
                    val amazon = budget.amazon
                    if (amazon != null && amazon.configured && amazon.charges > 0) {
                        DeskPanel(title = stringResource(Res.string.fin_amazon_title), info = null) {
                            AmazonBlock(amazon, selected = filter == AMAZON_FILTER, onClick = { showInList(if (filter == AMAZON_FILTER) null else AMAZON_FILTER) })
                        }
                    }
                }
            }
        }
    }
    item(key = LIST_HEADER_KEY) {
        DeskPage {
            Column(Modifier.padding(top = 28.dp).deskPanelBand(PanelBand.TOP).padding(bottom = 4.dp)) {
                CompositionLocalProvider(LocalPageGutter provides TABLE_GUTTER) {
                    SectionHeader(
                        stringResource(Res.string.fin_budget_purchases_title),
                        trailing = pluralStringResource(Res.plurals.fin_budget_purchases_count, shownCount, shownCount),
                        top = 18.dp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { FilterChips(budget, filter, onSelect = onFilter) }
                        if (budget.purchases.isNotEmpty()) PurchaseSearch(query, onQuery, Modifier.width(320.dp).padding(bottom = 10.dp))
                    }
                    PurchaseTableHeader(Modifier.padding(top = 12.dp))
                }
            }
        }
    }
    if (shownCount == 0) {
        item(key = "list-none") {
            DeskPage {
                FinePrint(
                    stringResource(if (budget.purchases.isEmpty()) Res.string.fin_budget_purchases_none else Res.string.fin_budget_purchases_none_here),
                    Modifier.deskPanelBand(PanelBand.MIDDLE).padding(horizontal = TABLE_GUTTER, vertical = 12.dp),
                )
            }
        }
    }
    shownByDay.forEach { (day, onDay) ->
        item(key = "day-$day", contentType = "day") {
            Text(
                FinanceFormat.dayOfMonth(day).resolve(),
                style = FinanceTheme.type.label,
                color = FinanceTheme.colors.textTertiary,
                modifier = Modifier.fillMaxWidth().deskPanelBand(PanelBand.MIDDLE).padding(horizontal = TABLE_GUTTER).padding(top = 14.dp, bottom = 4.dp),
            )
        }
        items(onDay, key = { "p-${it.id}" }, contentType = { "purchase" }) { purchase ->
            PurchaseTableRow(purchase, budget, busy = purchase.id in state.tagging, onClick = { onOpenTag(purchase.id) }, Modifier.deskPanelBand(PanelBand.MIDDLE))
        }
    }
    item(key = "how") {
        DeskPage {
            FinePrint(stringResource(Res.string.fin_budget_how), Modifier.deskPanelBand(PanelBand.BOTTOM).padding(horizontal = TABLE_GUTTER - PageGutter, vertical = 12.dp))
        }
    }
}

/**
 * The desk's line over the Budget page: how fresh the month is and anything about the cards that
 * needs doing, then the page's two actions at its end. The phone's [StatusBlock], laid along a line.
 */
@Composable
private fun BudgetDeskStatus(state: BudgetUiState, budget: Budget, onSyncNow: () -> Unit, onOpenSettings: () -> Unit, onOpenLinkedAccounts: () -> Unit, onBackToNow: () -> Unit) {
    val colors = FinanceTheme.colors
    val now = state.readAtEpochSeconds
    val read = budget.syncedAtEpochSeconds
    val changed = budget.changedAtEpochSeconds
    val fresh = when {
        state.syncing -> UiText.of(Res.string.fin_budget_fresh_reading)
        read == null -> UiText.of(Res.string.fin_budget_fresh_never)
        changed == null -> UiText.of(Res.string.fin_budget_fresh_read, FinanceFormat.ago(now, read))
        else -> UiText.of(Res.string.fin_budget_fresh_read_changed, FinanceFormat.ago(now, read), FinanceFormat.ago(now, changed))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (budget.cards.any { it.needsRelink }) colors.watch else colors.gain))
                Spacer(Modifier.width(8.dp))
                Text(fresh.resolve(), style = FinanceTheme.type.label, color = colors.textSecondary)
            }
            // Only what asks for something: a card to link again, one that reads no purchases, a sandbox, a notice.
            val warnings = buildList<Pair<String, (() -> Unit)?>> {
                if (!budget.ready) add(stringResource(Res.string.fin_budget_not_ready) to null)
                if (budget.sandbox) add(stringResource(Res.string.fin_budget_sandbox) to null)
                budget.cards.filter { it.needsRelink }.forEach { add(stringResource(Res.string.fin_budget_card_relink, it.institution) to onOpenLinkedAccounts) }
                budget.cards.filter { !it.readsPurchases }.forEach { add(stringResource(Res.string.fin_budget_card_no_purchases, it.name) to onOpenLinkedAccounts) }
            }
            warnings.forEach { (text, onClick) ->
                Text(
                    text,
                    style = FinanceTheme.type.label,
                    color = colors.watch,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp).let { m -> if (onClick != null) m.handCursor().clickable(role = Role.Button, onClick = onClick) else m },
                )
            }
            state.notice?.let { notice ->
                Text(notice.text.resolve(), style = FinanceTheme.type.label, color = if (notice.isError) colors.loss else colors.gain, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!budget.isCurrentMonth) PillButton(stringResource(Res.string.fin_budget_back_to_now), colors.accent, onClick = onBackToNow)
            PillButton(stringResource(if (state.syncing) Res.string.fin_budget_action_syncing else Res.string.fin_budget_action_sync), colors.textSecondary, onClick = onSyncNow)
            PillButton(stringResource(Res.string.fin_budget_action_settings), colors.accent, onClick = onOpenSettings, modifier = Modifier.testTag("finance_budget_settings"))
        }
    }
}

/**
 * The month in four figures across the page, each a big number over a few words: what has been
 * spent against the limit, what is left to spend and at how much a day, where the month is
 * heading, and what take-home has left once the bills and the cards are counted.
 */
@Composable
private fun BudgetFigures(budget: Budget, pace: BudgetPace, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val tint = heatColor(pace.heat, colors.gain, colors.watch, colors.loss)
    val limit = pace.limit
    val month = FinanceFormat.month(budget.month, year = true).resolve()
    val figures = buildList {
        add(
            BudgetFigure(
                label = if (budget.isCurrentMonth) stringResource(Res.string.fin_budget_hero_day, month, budget.day, budget.daysInMonth) else stringResource(Res.string.fin_budget_hero_month, month),
                value = FinanceFormat.money(budget.spent, 0),
                note = limit?.let { stringResource(Res.string.fin_budget_kpi_of_limit, FinanceFormat.money(it, 0)) } ?: stringResource(Res.string.fin_budget_hero_no_limit),
                meter = limit?.let { pace.level } to tint,
                testTag = "finance_budget_headline",
            ),
        )
        if (limit != null) {
            val left = limit - budget.spent
            add(
                if (left >= 0) {
                    BudgetFigure(
                        label = stringResource(Res.string.fin_budget_kpi_left),
                        value = FinanceFormat.money(left, 0),
                        note = pace.allowancePerDay?.takeIf { budget.isCurrentMonth && pace.daysLeft > 0 }?.let { stringResource(Res.string.fin_budget_kpi_per_day, FinanceFormat.money(it, 0)) },
                    )
                } else {
                    BudgetFigure(label = stringResource(Res.string.fin_budget_headline_over), value = FinanceFormat.money(-left, 0), valueColor = colors.loss)
                },
            )
        } else {
            add(BudgetFigure(label = stringResource(Res.string.fin_budget_pace_stat_rate), value = FinanceFormat.money(pace.dailyRate, 0)))
        }
        add(
            BudgetFigure(
                label = stringResource(if (budget.isCurrentMonth) Res.string.fin_budget_pace_stat_projected else Res.string.fin_budget_pace_stat_rate),
                value = FinanceFormat.money(if (budget.isCurrentMonth) pace.projected else pace.dailyRate, 0),
                note = stringResource(BudgetNarrator.headline(pace.status, budget.isCurrentMonth)),
                noteColor = statusColor(pace.status),
                info = "budgetpace",
            ),
        )
        budget.config.sheet.takeHome?.let { takeHome ->
            val left = takeHome - (budget.config.sheet.billsOffCard ?: 0.0) - budget.spent
            add(
                BudgetFigure(
                    label = stringResource(if (left >= 0) Res.string.fin_budget_flow_left else Res.string.fin_budget_flow_from_savings),
                    value = FinanceFormat.money(kotlin.math.abs(left), 0),
                    valueColor = if (left >= 0) colors.textPrimary else colors.loss,
                    note = stringResource(Res.string.fin_budget_kpi_of_take_home, FinanceFormat.money(takeHome, 0)),
                    info = "savingsline",
                ),
            )
        }
    }
    DeskGrid(figures, columns = figures.size, modifier, gap = 16.dp, equalHeights = true) { FigureTile(it, Modifier.fillMaxHeight()) }
}

private data class BudgetFigure(
    val label: String,
    val value: String,
    val note: String? = null,
    val valueColor: Color? = null,
    val noteColor: Color? = null,
    val meter: Pair<Float?, Color>? = null,
    val info: String? = null,
    val testTag: String? = null,
)

@Composable
private fun FigureTile(figure: BudgetFigure, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.surfaceRaised.copy(alpha = 0.55f), colors.surface)))
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        // The label's own height whether or not an ⓘ sits at its end (its touch target spills past it), so every tile's number starts on one line.
        Row(Modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(figure.label, style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            figure.info?.let { InfoButton(it, Modifier.wrapContentHeight(unbounded = true), size = 15.dp) }
        }
        Spacer(Modifier.height(6.dp))
        RollingNumber(figure.value, FinanceTheme.type.title, figure.valueColor ?: colors.textPrimary, Modifier.let { m -> figure.testTag?.let { m.testTag(it) } ?: m })
        figure.note?.let { Text(it, style = FinanceTheme.type.label, color = figure.noteColor ?: colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        figure.meter?.let { (level, color) ->
            if (level != null) {
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(10.dp))
                Meter(level.coerceIn(0f, 1f), color)
            }
        }
    }
}

/**
 * A budget line or a person's spending as one row of a desk's table: its colour, its name, a bar
 * of how far into its plan it is (or its share of the month without one), the figure against the
 * plan, and a word on where it stands. What the phone's card says in sentences under it is in the
 * row's tooltip. Clicking it filters the purchases to it, as the phone's card does.
 */
@Composable
private fun PlanRow(
    color: Color,
    name: String,
    spent: Double,
    plan: Double?,
    share: Float,
    status: String?,
    statusColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val colors = FinanceTheme.colors
    val row: @Composable () -> Unit = {
        Row(
            modifier
                .fillMaxWidth()
                .handCursor()
                .clickable(role = Role.Button, onClick = onClick)
                .background(if (selected) colors.accent.copy(alpha = 0.08f) else Color.Transparent)
                .padding(horizontal = PageGutter, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            // The name has the larger share: at a desk's narrowest a long one ("In no budget line") still fits beside its bar.
            Text(name, style = FinanceTheme.type.bodyStrong, color = if (selected) colors.accent else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (plan != null) {
                    val level = (spent / plan).toFloat().coerceAtLeast(0f)
                    Meter(level.coerceAtMost(1f), heatColor(BudgetPace.heatOf(level), colors.gain, colors.watch, colors.loss))
                } else {
                    Meter(share.coerceIn(0f, 1f), color.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                if (plan != null) stringResource(Res.string.fin_budget_lines_of_planned, FinanceFormat.money(spent, 0), FinanceFormat.money(plan, 0)) else FinanceFormat.money(spent, 0),
                style = FinanceTheme.type.bodyStrong,
                color = colors.textPrimary,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(PLAN_FIGURE_WIDTH),
            )
            Spacer(Modifier.width(14.dp))
            Box(Modifier.width(PLAN_STATUS_WIDTH), contentAlignment = Alignment.CenterEnd) {
                if (status != null) {
                    Text(
                        status,
                        style = FinanceTheme.type.micro,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(CircleShape).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
    if (detail != null) WithTooltip(detail) { row() } else row()
}

private val PLAN_FIGURE_WIDTH = 124.dp
private val PLAN_STATUS_WIDTH = 120.dp

/** [LineCard] as a desk's [PlanRow]: over, heading over or what's left as its word, how it usually runs in its tooltip. */
@Composable
private fun LinePlanRow(line: BudgetLine, color: Color, budget: Budget, selected: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val planned = line.planned?.takeIf { it > 0 }
    val heading = if (budget.isCurrentMonth && budget.day >= LINE_HEADING_FROM_DAY) line.spent / budget.day * budget.daysInMonth else null
    val (status, statusColor) = when {
        planned == null -> pluralStringResource(Res.plurals.fin_budget_purchases_count, line.count, line.count) to colors.textSecondary
        line.spent > planned -> stringResource(Res.string.fin_budget_who_over, FinanceFormat.money(line.spent - planned, 0)) to colors.loss
        heading != null && heading > planned -> stringResource(Res.string.fin_budget_lines_heading, FinanceFormat.money(heading, 0)) to colors.watch
        else -> stringResource(Res.string.fin_budget_who_left, FinanceFormat.money(planned - line.spent, 0)) to colors.textSecondary
    }
    val detail = line.usual?.let { usualNote(it, planned).first }
    PlanRow(
        color = color,
        name = line.name,
        spent = line.spent,
        plan = planned,
        share = if (budget.spent > 0) (line.spent / budget.spent).toFloat() else 0f,
        status = status,
        statusColor = statusColor,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.testTag("finance_budget_line_${line.name}"),
        detail = detail,
    )
}

/** How a line usually runs, and whether that is more than its plan: only a gap big enough to be worth changing the plan for is pointed out. */
@Composable
private fun usualNote(usual: Double, planned: Double?): Pair<String, Boolean> {
    val gap = planned?.let { plan -> (usual - plan).takeIf { abs(it) >= maxOf(USUAL_GAP_MIN, plan * 0.1) } }
    val usualText = FinanceFormat.money(usual, 0)
    val plannedText = planned?.let { FinanceFormat.money(it, 0) }.orEmpty()
    val text = when {
        gap == null -> stringResource(Res.string.fin_budget_lines_usual, usualText)
        gap > 0 -> stringResource(Res.string.fin_budget_lines_usual_more, usualText, plannedText)
        else -> stringResource(Res.string.fin_budget_lines_usual_less, usualText, plannedText)
    }
    return text to (gap != null && gap > 0)
}

/** [BucketCard] as a desk's [PlanRow]. */
@Composable
private fun BucketPlanRow(bucket: BudgetBucket, budget: Budget, selected: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val limit = bucket.limit?.takeIf { it > 0 }
    val (status, statusColor) = when {
        limit == null -> pluralStringResource(Res.plurals.fin_budget_purchases_count, bucket.count, bucket.count) to colors.textSecondary
        bucket.spent > limit -> stringResource(Res.string.fin_budget_who_over, FinanceFormat.money(bucket.spent - limit, 0)) to colors.loss
        else -> stringResource(Res.string.fin_budget_who_left, FinanceFormat.money(limit - bucket.spent, 0)) to colors.textSecondary
    }
    PlanRow(
        color = bucketColor(bucket.id, budget.config.people),
        name = bucket.id.label(),
        spent = bucket.spent,
        plan = limit,
        share = if (budget.spent > 0) (bucket.spent / budget.spent).toFloat() else 0f,
        status = status,
        statusColor = statusColor,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.testTag("finance_budget_bucket_${bucket.id.key}"),
        detail = if (bucket.id == BucketId.Unassigned) stringResource(Res.string.fin_budget_who_unsorted_note) else null,
    )
}

/** How far the purchases table keeps its columns from the panel's edges. */
private val TABLE_GUTTER = 22.dp

/** The purchases table's column widths, shared by its heading and its rows. */
private const val TABLE_NAME_WEIGHT = 2.4f
private const val TABLE_LINE_WEIGHT = 1.5f
private const val TABLE_CARD_WEIGHT = 1.2f
private val TABLE_WHOSE_WIDTH = 120.dp
private val TABLE_AMOUNT_WIDTH = 116.dp

/** The purchases table's column headings. */
@Composable
private fun PurchaseTableHeader(modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val style = FinanceTheme.type.micro
    Row(modifier.fillMaxWidth().padding(horizontal = TABLE_GUTTER, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.fin_budget_table_purchase).uppercase(), style = style, color = colors.textTertiary, modifier = Modifier.weight(TABLE_NAME_WEIGHT))
        Text(stringResource(Res.string.fin_budget_table_line).uppercase(), style = style, color = colors.textTertiary, modifier = Modifier.weight(TABLE_LINE_WEIGHT))
        Text(stringResource(Res.string.fin_budget_table_card).uppercase(), style = style, color = colors.textTertiary, modifier = Modifier.weight(TABLE_CARD_WEIGHT))
        Text(stringResource(Res.string.fin_budget_table_whose).uppercase(), style = style, color = colors.textTertiary, modifier = Modifier.width(TABLE_WHOSE_WIDTH))
        Text(stringResource(Res.string.fin_budget_table_amount).uppercase(), style = style, color = colors.textTertiary, textAlign = TextAlign.End, modifier = Modifier.width(TABLE_AMOUNT_WIDTH))
    }
}

/** [PurchaseRow] as a row of the desk's purchases table: what [PurchaseRow] says under the name has a column each. */
@Composable
private fun PurchaseTableRow(purchase: Purchase, budget: Budget, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val card = budget.cards.firstOrNull { it.key == purchase.cardKey }
    Row(
        modifier
            .fillMaxWidth()
            .handCursor()
            .clickable(enabled = !busy, onClick = onClick)
            .testTag("finance_budget_purchase_${purchase.id}")
            .padding(horizontal = TABLE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(TABLE_NAME_WEIGHT).padding(end = 12.dp)) {
            Text(purchase.name, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            purchase.amazon?.let { amazonItemsLine(it) }?.let { Text(it, style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            purchase.note?.let { PurchaseNote(it) }
        }
        Text(
            purchase.line ?: purchase.category?.let { stringResource(categoryLabel(it)) } ?: "",
            style = FinanceTheme.type.label,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(TABLE_LINE_WEIGHT).padding(end = 12.dp),
        )
        Text(card?.name ?: "", style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(TABLE_CARD_WEIGHT).padding(end = 12.dp))
        Box(Modifier.width(TABLE_WHOSE_WIDTH)) {
            val color = bucketColor(purchase.bucket, budget.config.people)
            Text(
                purchase.whoseLabel(),
                style = FinanceTheme.type.micro,
                color = if (busy) colors.textTertiary else color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Column(Modifier.width(TABLE_AMOUNT_WIDTH), horizontalAlignment = Alignment.End) {
            Text(
                if (purchase.isRefund) stringResource(Res.string.fin_budget_purchase_refund, FinanceFormat.money(-purchase.amount)) else FinanceFormat.money(purchase.amount),
                style = FinanceTheme.type.bodyStrong,
                color = if (purchase.isRefund) colors.gain else colors.textPrimary,
                maxLines = 1,
            )
            if (purchase.pending) Text(stringResource(Res.string.fin_budget_purchase_pending), style = FinanceTheme.type.micro, color = colors.textTertiary)
        }
    }
}

@Composable
internal fun BucketId.label(): String = when (this) {
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

        BankProblem.PLAID, BankProblem.TAILSCALE_OFF, BankProblem.UNREACHABLE, BankProblem.OTHER, null -> BudgetMessageCard(stringResource(Res.string.fin_budget_problem_other_title), body, again, onRetry)
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
                val month = FinanceFormat.month(budget.month, year = true).resolve()
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

/** How a cardholder's mark reads: a bare card number as "Card ending 1203", anything else (a name) as the bank wrote it. */
@Composable
private fun holderLabel(holder: CardHolder): String =
    if (holder.mark.length in 3..6 && holder.mark.all { it.isDigit() }) stringResource(Res.string.fin_budget_holder_ending, holder.mark) else holder.mark

/**
 * Whose card is which: each mark the bank puts on [card]'s purchases, with the budget's people
 * to pick from. Picking the person already picked takes the say-so back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardHolders(card: BudgetCard, people: List<String>, enabled: Boolean, onSay: (mark: String, person: String?) -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    Column(modifier.fillMaxWidth()) {
        card.holders.forEach { holder ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(holderLabel(holder), style = FinanceTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Text(pluralStringResource(Res.plurals.fin_budget_purchases_count, holder.count, holder.count), style = FinanceTheme.type.label, color = colors.textTertiary)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                people.forEach { person ->
                    val isSelected = holder.person == person
                    val color = bucketColor(BucketId.Person(person), people)
                    Text(
                        person,
                        style = FinanceTheme.type.label,
                        color = if (isSelected) color else colors.textSecondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) color.copy(alpha = 0.18f) else colors.surfaceRaised)
                            .border(1.dp, if (isSelected) color.copy(alpha = 0.6f) else Color.Transparent, CircleShape)
                            .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSay(holder.mark, if (isSelected) null else person) }
                            .testTag("finance_budget_holder_${holder.mark}_$person")
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
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
            Text(FinanceFormat.dayOfMonth(purchase.date).resolve(), style = FinanceTheme.type.label, color = colors.textTertiary)
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

/**
 * The month so far as a line, against the even pace that would end it at the limit and where this
 * pace ends it instead. An earlier month looked back at has the month under way, [current], drawn
 * over it, day for day.
 */
@Composable
private fun PaceBlock(budget: Budget, pace: BudgetPace, current: Budget?) {
    val colors = FinanceTheme.colors
    val tint = heatColor(pace.heat, colors.gain, colors.watch, colors.loss)
    val limit = pace.limit
    var scrub by remember { mutableStateOf<Int?>(null) }
    val soFar = remember(budget.daily) { runningTotal(budget.daily) }
    val currentLine = remember(current?.daily) { current?.daily?.let(::runningTotal) }
    // Remembered, so a scrub's recomposition hands the chart the same series and it doesn't redraw its geometry.
    val evenLine = remember(limit, budget.daysInMonth) { limit?.let { Series.of(listOf(0L to 0.0, budget.daysInMonth.toLong() to it)) } }
    val projectedLine = remember(budget.isCurrentMonth, pace.daysLeft, budget.day, budget.spent, budget.daysInMonth, pace.projected) {
        if (budget.isCurrentMonth && pace.daysLeft > 0) Series.of(listOf(budget.day.toLong() to budget.spent, budget.daysInMonth.toLong() to pace.projected)) else null
    }
    val lines = buildList {
        add(ChartLine(soFar, tint, fill = true, width = 3f))
        if (evenLine != null) add(ChartLine(evenLine, colors.textTertiary, width = 1.5f, dashed = true))
        if (projectedLine != null) add(ChartLine(projectedLine, tint.copy(alpha = 0.7f), width = 2f, dashed = true))
        if (currentLine != null) add(ChartLine(currentLine, colors.accent, width = 2.5f))
    }
    val top = maxOf(limit ?: 0.0, pace.projected, budget.spent, current?.spent ?: 0.0)
    // What a month has come to on average, from the months before this one that spent anything; one alone is no "usual".
    val usualMonth = remember(budget.history) { budget.history.map { it.spent }.filter { it > 0 }.takeIf { it.size >= 2 }?.average() }
    val rules = buildList {
        // Named in the legend under the chart, not on the lines: the two often sit too close for both labels.
        if (limit != null) add(ChartRule(limit, colors.textSecondary, always = true))
        // Kept in view only where it is near enough not to flatten the month under it.
        pace.savingsLine?.let { line -> add(ChartRule(line, colors.loss, always = top > 0 && line <= top * 1.6)) }
    }
    // On a desk the figures over the page say where the month ends and what is left a day, and the ⓘ what the lines are.
    val desk = LocalFinanceDesk.current
    Column(Modifier.fillMaxWidth()) {
        val scrubbed = scrub?.takeIf { it in 1..budget.daily.size }
        Text(
            when {
                scrubbed != null -> stringResource(Res.string.fin_budget_pace_scrub, FinanceFormat.dayOfMonth(budget.month, scrubbed).resolve(), FinanceFormat.money(soFar.values[scrubbed], 0))

                // Says which month is drawn over which, so it stays on a desk too.
                current != null -> stringResource(Res.string.fin_budget_pace_caption_compare, FinanceFormat.month(budget.month).resolve())

                // Kept as a line's height, so the chart doesn't jump when a hover writes the day in it.
                desk -> " "

                else -> stringResource(Res.string.fin_budget_pace_caption)
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
            modifier = Modifier.fillMaxWidth().height(if (desk) 240.dp else 200.dp),
        )
        Legend(
            listOfNotNull(
                limit?.let { Triple(stringResource(Res.string.fin_budget_pace_legend_limit), FinanceFormat.money(it, 0), colors.textSecondary) },
                pace.savingsLine?.let { Triple(stringResource(Res.string.fin_budget_pace_legend_savings), FinanceFormat.money(it, 0), colors.loss) },
                current?.let { Triple(stringResource(Res.string.fin_budget_pace_legend_current), FinanceFormat.money(it.spent, 0), colors.accent) },
            ),
        )
        StatGrid(
            buildList {
                add(stringResource(Res.string.fin_budget_pace_stat_rate) to FinanceFormat.money(pace.dailyRate, 0))
                if (budget.isCurrentMonth && !desk) add(stringResource(Res.string.fin_budget_pace_stat_projected) to FinanceFormat.money(pace.projected, 0))
                usualMonth?.let { add(stringResource(Res.string.fin_budget_pace_stat_usual) to FinanceFormat.money(it, 0)) }
                pace.allowancePerDay?.takeIf { budget.isCurrentMonth && pace.daysLeft > 0 && !desk }?.let { add(stringResource(Res.string.fin_budget_pace_stat_allowance) to FinanceFormat.money(it, 0)) }
                if (limit != null) {
                    val day = pace.limitDay?.let { FinanceFormat.dayOfMonth(budget.month, it).resolve() }
                    add(stringResource(Res.string.fin_budget_pace_stat_limit_day) to (day ?: stringResource(Res.string.fin_budget_pace_stat_not_this_month)))
                }
            },
            deskColumns = 3,
        )
    }
}

/** Each day's spending added up, as the pace chart's line: day 0 is the month's start, at nothing spent. */
private fun runningTotal(daily: List<Double>): Series {
    var total = 0.0
    return Series.of(
        buildList {
            add(0L to 0.0)
            daily.forEachIndexed { index, spent ->
                total += spent
                add(index + 1L to total)
            }
        },
    )
}

/** One bucket: what it has spent against its limit, as a meter that heats as the limit nears. Tapping it filters the purchases to it. */
@Composable
private fun BucketCard(bucket: BudgetBucket, budget: Budget, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val color = bucketColor(bucket.id, budget.config.people)
    val limit = bucket.limit?.takeIf { it > 0 }
    FinanceCard(modifier.padding(bottom = 10.dp).testTag("finance_budget_bucket_${bucket.id.key}"), onClick = onClick, padding = 14.dp) {
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

/** Each budget line its own colour, by its place among the lines. */
@Composable
private fun lineColor(index: Int): Color = FinanceTheme.colors.categorical[index % FinanceTheme.colors.categorical.size]

/**
 * One of the sheet's card-paid lines: what its purchases have come to against what the sheet
 * plans, as a meter that heats as the plan nears; where this pace takes it by the month's end when
 * that is past the plan; and, when it differs from the plan by enough to matter, what the line
 * usually comes to, which says the plan wants changing. Tapping it filters the purchases to it.
 */
@Composable
private fun LineCard(line: BudgetLine, index: Int, budget: Budget, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val planned = line.planned?.takeIf { it > 0 }
    FinanceCard(modifier.padding(bottom = 10.dp).testTag("finance_budget_line_${line.name}"), onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(lineColor(index)))
            Spacer(Modifier.width(10.dp))
            Text(line.name, style = FinanceTheme.type.bodyStrong, color = if (selected) colors.accent else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                if (planned != null) stringResource(Res.string.fin_budget_lines_of_planned, FinanceFormat.money(line.spent, 0), FinanceFormat.money(planned, 0)) else FinanceFormat.money(line.spent, 0),
                style = FinanceTheme.type.bodyStrong,
                color = colors.textPrimary,
            )
        }
        if (planned != null) {
            val level = (line.spent / planned).toFloat().coerceAtLeast(0f)
            HeatMeter(level, BudgetPace.heatOf(level))
        } else {
            Spacer(Modifier.height(10.dp))
            Meter(if (budget.spent > 0) (line.spent / budget.spent).toFloat().coerceIn(0f, 1f) else 0f, lineColor(index))
        }
        Spacer(Modifier.height(6.dp))
        // A month's pace carried to its end, said only once it is a week in and only when it ends past the plan.
        val heading = if (budget.isCurrentMonth && budget.day >= LINE_HEADING_FROM_DAY) line.spent / budget.day * budget.daysInMonth else null
        val standing = when {
            planned == null -> null
            line.spent > planned -> stringResource(Res.string.fin_budget_who_over, FinanceFormat.money(line.spent - planned, 0))
            heading != null && heading > planned -> stringResource(Res.string.fin_budget_lines_heading, FinanceFormat.money(heading, 0))
            else -> stringResource(Res.string.fin_budget_who_left, FinanceFormat.money(planned - line.spent, 0))
        }
        val warn = planned != null && (line.spent > planned || (heading != null && heading > planned))
        Text(
            listOfNotNull(pluralStringResource(Res.plurals.fin_budget_purchases_count, line.count, line.count), standing).joinToString(stringResource(Res.string.common_dot_separator)),
            style = FinanceTheme.type.label,
            color = when {
                planned != null && line.spent > planned -> colors.loss
                warn -> colors.watch
                else -> colors.textSecondary
            },
        )
        line.usual?.let { usual ->
            val (text, more) = usualNote(usual, planned)
            Text(
                text,
                style = FinanceTheme.type.label,
                color = if (more) colors.watch else colors.textTertiary,
                modifier = Modifier.padding(top = 2.dp).testTag("finance_budget_line_usual_${line.name}"),
            )
        }
    }
}

/** What the purchases in none of the lines come to. Tapping it lists them, from where each can be put in a line. */
@Composable
private fun OutsideLinesRow(budget: Budget, selected: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val outside = budget.outsideLines
    FinanceCard(Modifier.padding(bottom = 10.dp).testTag("finance_budget_line_outside"), onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.textTertiary))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(Res.string.fin_budget_lines_outside), style = FinanceTheme.type.bodyStrong, color = if (selected) colors.accent else colors.textPrimary, modifier = Modifier.weight(1f))
            Text(FinanceFormat.money(outside.spent, 0), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(pluralStringResource(Res.plurals.fin_budget_purchases_count, outside.count, outside.count), stringResource(Res.string.fin_budget_lines_outside_note))
                .joinToString(stringResource(Res.string.common_dot_separator)),
            style = FinanceTheme.type.label,
            color = colors.textSecondary,
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
private fun CategoryBlock(budget: Budget, selected: String?, onSelect: (String) -> Unit) {
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
            slices.forEachIndexed { i, slice ->
                // A named category filters the purchases to it; "the rest" is several, so it doesn't.
                val key = named.getOrNull(i)?.let { CATEGORY_FILTER + it.name.ifEmpty { OTHER_CATEGORY } }
                val isSelected = key != null && key == selected
                val clickLabel = stringResource(Res.string.fin_budget_category_filter_click, slice.label)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (key != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button) { onSelect(key) } else Modifier)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(slice.color))
                    Spacer(Modifier.width(8.dp))
                    Text(slice.label, style = FinanceTheme.type.label, color = if (isSelected) colors.accent else colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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

/**
 * The month under way and the five before it as bars, each against the limit behind it. Tapping
 * one looks back at it, and the same months stay on the chart while it is up; with an earlier
 * month [shown], a line under the bars says how the month under way stands against it.
 */
@Composable
private fun MonthsBlock(current: Budget, showing: String, shown: Budget, onShowMonth: (String?) -> Unit) {
    val colors = FinanceTheme.colors
    val limit = current.config.limits.total?.takeIf { it > 0 }
    val months = current.history.map { it.month to it.spent } + (current.month to current.spent)
    Column(Modifier.fillMaxWidth()) {
        BarChart(
            bars = months.map { (month, spent) ->
                Bar(FinanceFormat.month(month).resolve(), spent, if (limit != null && spent > limit) colors.loss else colors.gain, secondary = limit, secondaryColor = colors.hairline)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).height(150.dp).testTag("finance_budget_months"),
            // The month tapped, straight away: the page catches up once it is read.
            selected = months.indexOfFirst { it.first == showing }.takeIf { it >= 0 },
            onSelect = { index -> months.getOrNull(index)?.first?.let { month -> onShowMonth(month.takeIf { it != current.month }) } },
        )
        // Only once the month tapped is the one read, so the line never speaks of the month before it.
        val compare = remember(shown, current, showing) { shown.takeIf { it.month == showing }?.let { BudgetCompare.of(it, current) } }
        if (compare != null) {
            val day = FinanceFormat.dayOfMonth(current.month, compare.day).resolve()
            val then = FinanceFormat.month(shown.month).resolve()
            val now = FinanceFormat.money(compare.now, 0)
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    compare.level -> stringResource(Res.string.fin_budget_compare_level, day, now, then)
                    compare.difference > 0 -> stringResource(Res.string.fin_budget_compare_more, day, now, FinanceFormat.money(compare.difference, 0), then, FinanceFormat.money(compare.then, 0))
                    else -> stringResource(Res.string.fin_budget_compare_less, day, now, FinanceFormat.money(-compare.difference, 0), then, FinanceFormat.money(compare.then, 0))
                },
                style = FinanceTheme.type.body,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = PageGutter).testTag("finance_budget_compare"),
            )
        }
    }
}

@Composable
private fun FilterChips(budget: Budget, selected: String?, onSelect: (String?) -> Unit) {
    val colors = FinanceTheme.colors
    // A category picked from the donut, or a budget line from its card, shows as a chip of its own after "All", which clears it.
    val category = selected?.takeIf { it.startsWith(CATEGORY_FILTER) }
    val categoryName = category?.let { stringResource(categoryLabel(it.removePrefix(CATEGORY_FILTER))) }
    val line = selected?.takeIf { it.startsWith(LINE_FILTER) }
    val lineName = line?.removePrefix(LINE_FILTER)?.ifEmpty { stringResource(Res.string.fin_budget_lines_outside) }
    val options: List<Pair<String?, String>> = buildList {
        add(null to stringResource(Res.string.fin_budget_filter_all))
        if (category != null && categoryName != null) add(category to categoryName)
        if (line != null && lineName != null) add(line to lineName)
        if (budget.purchases.any { it.fromAmazon } && budget.amazon?.configured == true) add(AMAZON_FILTER to stringResource(Res.string.fin_amazon_filter))
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

/** A box that searches the month's purchases by name, with a clear button once something is typed. */
@Composable
private fun PurchaseSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter)
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceRaised)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(Res.string.fin_budget_search_hint), style = type.body, color = colors.textTertiary)
            val searchDescription = stringResource(Res.string.fin_budget_search_description)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().testTag("finance_budget_search").semantics { contentDescription = searchDescription },
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(Res.string.fin_budget_search_clear),
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onQuery("") },
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
                // What the Amazon order had in it says most; then the budget line it is in, which says
                // more than Plaid's broad category, which is for those in none.
                purchase.amazon?.let { amazonItemsLine(it) } ?: purchase.line ?: purchase.category?.let { stringResource(categoryLabel(it)) },
                if (purchase.pending) stringResource(Res.string.fin_budget_purchase_pending) else null,
            )
            Text(about.joinToString(stringResource(Res.string.common_dot_separator)), style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            purchase.note?.let { PurchaseNote(it) }
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
                purchase.whoseLabel(),
                style = FinanceTheme.type.micro,
                color = if (busy) colors.textTertiary else color,
                modifier = Modifier.padding(top = 3.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Whose a purchase is: each of the budget's people, the family, or (for one tagged by hand) back
 * to wherever it would fall on its own; and, when the sheet has card-paid lines, which of them it
 * is in, none, or (for one put somewhere by hand) wherever it falls on its own. With the switch
 * on, the choice is remembered for every purchase from the same merchant.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagSheet(
    purchase: Purchase,
    card: BudgetCard?,
    people: List<String>,
    lines: List<String>,
    busy: Boolean,
    onSayNeed: (orderId: String, item: AmazonItem, need: AmazonNeed?) -> Unit,
    onNote: (String?) -> Unit,
    onPick: (BucketId, Boolean) -> Unit,
    onPickLine: (LinePick, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FinanceTheme.colors
    var always by rememberSaveable(purchase.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        // Scrolls: with several people and lines, or large text, the remember switch would otherwise fall off its bottom.
        // A sheet is outside the pages that make room for the keyboard, and the note is typed into it.
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp).testTag("finance_budget_tag_sheet")) {
            Column(Modifier.padding(horizontal = PageGutter)) {
                Text(purchase.name, style = FinanceTheme.type.section, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                val about = listOfNotNull(FinanceFormat.dayOfMonth(purchase.date).resolve(), FinanceFormat.money(purchase.amount), card?.name)
                Text(about.joinToString(stringResource(Res.string.common_dot_separator)), style = FinanceTheme.type.label, color = colors.textSecondary)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(sourceNote(purchase.source)), style = FinanceTheme.type.label, color = colors.textTertiary)
                if (purchase.shares.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    purchase.shares.forEach { share ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(bucketColor(share.bucket, people)))
                            Spacer(Modifier.width(8.dp))
                            Text(share.bucket.label(), style = FinanceTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                            Text(FinanceFormat.money(share.amount), style = FinanceTheme.type.label, color = colors.textPrimary)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                NoteField(purchase, busy, onNote)
            }
            if (purchase.fromAmazon) {
                Hairline(Modifier.padding(horizontal = PageGutter, vertical = 10.dp))
                AmazonOrderSection(purchase, busy, onSayNeed)
                Hairline(Modifier.padding(horizontal = PageGutter, vertical = 10.dp))
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
            if (lines.isNotEmpty()) {
                Hairline(Modifier.padding(horizontal = PageGutter, vertical = 6.dp))
                Column(Modifier.padding(horizontal = PageGutter)) {
                    Text(stringResource(Res.string.fin_budget_tag_line_title), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(lineSourceNote(purchase)), style = FinanceTheme.type.label, color = colors.textTertiary)
                    Spacer(Modifier.height(10.dp))
                    val picks = lines.map { LinePick.Line(it) } + LinePick.None + listOfNotNull(LinePick.Automatic.takeIf { purchase.lineSource == LineSource.MANUAL })
                    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        picks.forEach { pick ->
                            val isSelected = when (pick) {
                                is LinePick.Line -> purchase.line == pick.name
                                LinePick.None -> purchase.line == null && purchase.lineSource != LineSource.NONE
                                LinePick.Automatic -> false
                            }
                            Text(
                                when (pick) {
                                    is LinePick.Line -> pick.name
                                    LinePick.None -> stringResource(Res.string.fin_budget_tag_line_none)
                                    LinePick.Automatic -> stringResource(Res.string.fin_budget_tag_line_auto)
                                },
                                style = FinanceTheme.type.label,
                                color = if (isSelected) colors.accent else colors.textSecondary,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) colors.accent.copy(alpha = 0.18f) else colors.surface)
                                    .border(1.dp, if (isSelected) colors.accent.copy(alpha = 0.6f) else colors.hairline, CircleShape)
                                    .selectable(selected = isSelected, role = Role.RadioButton) { onPickLine(pick, always) }
                                    .testTag("finance_budget_tag_line_${pick.wire ?: "auto"}")
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
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
                        stringResource(if (purchase.remembered || purchase.lineRemembered) Res.string.fin_budget_tag_remembered_note else Res.string.fin_budget_tag_remember_note),
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

/** A note someone wrote on a purchase, under what it was: their words, set apart in italics. */
@Composable
private fun PurchaseNote(note: String) {
    Text(note, style = FinanceTheme.type.label.copy(fontStyle = FontStyle.Italic), color = FinanceTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/**
 * The purchase's note, to write, change or take off ("a tire for the Tesla"): typed in, it is
 * saved with Save; one already written can be taken off. Everyone who sees the budget sees it.
 */
@Composable
private fun NoteField(purchase: Purchase, busy: Boolean, onNote: (String?) -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    var draft by rememberSaveable(purchase.id, purchase.note) { mutableStateOf(purchase.note.orEmpty()) }
    val changed = draft.trim() != purchase.note.orEmpty()
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.fin_budget_note_title), style = type.bodyStrong, color = colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (draft.isEmpty()) Text(stringResource(Res.string.fin_budget_note_hint), style = type.body, color = colors.textTertiary)
            val noteDescription = stringResource(Res.string.fin_budget_note_title)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(NOTE_MAX) },
                enabled = !busy,
                maxLines = 4,
                textStyle = type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().testTag("finance_budget_note").semantics { contentDescription = noteDescription },
            )
        }
        if (changed || purchase.note != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (purchase.note != null && !changed) {
                    Text(
                        stringResource(Res.string.fin_budget_note_remove),
                        style = type.bodyStrong,
                        color = colors.textSecondary,
                        modifier = Modifier.handCursor().clickable(enabled = !busy, role = Role.Button) { onNote(null) }.testTag("finance_budget_note_remove").padding(8.dp),
                    )
                }
                if (changed) {
                    Text(
                        stringResource(Res.string.fin_budget_note_save),
                        style = type.bodyStrong,
                        color = colors.accent,
                        modifier = Modifier.handCursor().clickable(enabled = !busy, role = Role.Button) { onNote(draft) }.testTag("finance_budget_note_save").padding(8.dp),
                    )
                }
            }
        }
    }
}

/** The longest note, as the relay takes it (`BUDGET_NOTE_MAX` in relay.py). */
private const val NOTE_MAX = 280

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
    BucketSource.AMAZON -> Res.string.fin_budget_source_amazon
    BucketSource.NONE -> Res.string.fin_budget_source_none
}

/** How a purchase came to be in its budget line, or in none. */
private fun lineSourceNote(purchase: Purchase): StringResource = when (purchase.lineSource) {
    LineSource.MANUAL -> if (purchase.line != null) Res.string.fin_budget_line_source_manual else Res.string.fin_budget_line_source_manual_none
    LineSource.RULE -> if (purchase.line != null) Res.string.fin_budget_line_source_rule else Res.string.fin_budget_line_source_rule_none
    LineSource.AUTO -> Res.string.fin_budget_line_source_auto
    LineSource.NONE -> Res.string.fin_budget_line_source_none
}

@Preview(name = "Budget · filtered to a category", widthDp = 412, heightDp = 420)
@Composable
private fun FinanceBudgetCategoryFilterPreview() {
    FinanceStage {
        Column {
            CategoryBlock(BudgetFixtures.onPace, selected = "cat:FOOD_AND_DRINK", onSelect = {})
            Spacer(Modifier.height(12.dp))
            FilterChips(BudgetFixtures.onPace, selected = "cat:FOOD_AND_DRINK", onSelect = {})
            PurchaseSearch("coffee", onQuery = {})
        }
    }
}

@Preview(name = "Budget · budget lines", widthDp = 412, heightDp = 640)
@Composable
private fun FinanceBudgetLinesPreview() {
    val budget = BudgetFixtures.close
    FinanceStage {
        Column {
            // Over its plan, heading past it, comfortably under with a plan that wants raising, and one the sheet has no amount for.
            listOf(
                BudgetLine("Groceries", 600.0, 642.18, 9, usual = 710.0),
                BudgetLine("Dining out", 250.0, 214.60, 7),
                BudgetLine("Meal kits", 240.0, 119.96, 2, usual = 180.0),
                BudgetLine("Pets", null, 63.40, 2),
            ).forEachIndexed { index, line -> LineCard(line, index, budget, selected = index == 1, onClick = {}) }
            OutsideLinesRow(budget, selected = false, onClick = {})
        }
    }
}

@Preview(name = "Budget · Amazon purchases in the list", widthDp = 412, heightDp = 260)
@Composable
private fun FinanceBudgetAmazonRowsPreview() {
    val budget = BudgetFixtures.withAmazon
    FinanceStage {
        Column(Modifier.padding(top = 8.dp)) {
            FilterChips(budget, selected = AMAZON_FILTER, onSelect = {})
            BudgetFixtures.amazonPurchases.forEach { PurchaseRow(it, budget, busy = false, onClick = {}) }
        }
    }
}
