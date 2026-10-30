package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.AmazonItem
import com.meticulouscreations.homesafe.finance.domain.AmazonMatch
import com.meticulouscreations.homesafe.finance.domain.AmazonNeed
import com.meticulouscreations.homesafe.finance.domain.AmazonOrder
import com.meticulouscreations.homesafe.finance.domain.AmazonSummary
import com.meticulouscreations.homesafe.finance.domain.NeedSource
import com.meticulouscreations.homesafe.finance.domain.Purchase
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.fin_amazon_charge_split
import homesafe.shared.generated.resources.fin_amazon_charge_split_unsorted
import homesafe.shared.generated.resources.fin_amazon_discretionary
import homesafe.shared.generated.resources.fin_amazon_essential
import homesafe.shared.generated.resources.fin_amazon_failed
import homesafe.shared.generated.resources.fin_amazon_headline
import homesafe.shared.generated.resources.fin_amazon_item_price
import homesafe.shared.generated.resources.fin_amazon_items_more
import homesafe.shared.generated.resources.fin_amazon_let_it_sort
import homesafe.shared.generated.resources.fin_amazon_match_order
import homesafe.shared.generated.resources.fin_amazon_match_shipment
import homesafe.shared.generated.resources.fin_amazon_match_split
import homesafe.shared.generated.resources.fin_amazon_open
import homesafe.shared.generated.resources.fin_amazon_sheet_order
import homesafe.shared.generated.resources.fin_amazon_sheet_title
import homesafe.shared.generated.resources.fin_amazon_sheet_unmatched
import homesafe.shared.generated.resources.fin_amazon_sorted_model
import homesafe.shared.generated.resources.fin_amazon_sorted_none
import homesafe.shared.generated.resources.fin_amazon_sorted_rule
import homesafe.shared.generated.resources.fin_amazon_sorted_user
import homesafe.shared.generated.resources.fin_amazon_unmatched
import homesafe.shared.generated.resources.fin_amazon_unsorted
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A need's colour, the same wherever it is drawn: cool for essentials, amber for wants. */
@Composable
internal fun needColor(need: AmazonNeed?): Color = when (need) {
    AmazonNeed.ESSENTIAL -> FinanceTheme.colors.cool
    AmazonNeed.DISCRETIONARY -> FinanceTheme.colors.watch
    null -> FinanceTheme.colors.textTertiary
}

private fun needLabel(need: AmazonNeed): StringResource = when (need) {
    AmazonNeed.ESSENTIAL -> Res.string.fin_amazon_essential
    AmazonNeed.DISCRETIONARY -> Res.string.fin_amazon_discretionary
}

/** An order's items in a few words: the first one's title, and how many more there were. */
@Composable
internal fun amazonItemsLine(order: AmazonOrder): String? {
    val first = order.items.firstOrNull() ?: return null
    val more = order.items.size - 1
    return if (more == 0) first.title else pluralStringResource(Res.plurals.fin_amazon_items_more, more, first.title, more)
}

/**
 * The month's Amazon charges as one bar: what the order emails say was essential, what was
 * discretionary, what nobody has sorted yet, and the charges no order was found for. Tapping it
 * lists the Amazon purchases, each of whose items can be sorted from its sheet.
 */
@Composable
internal fun AmazonBlock(summary: AmazonSummary, selected: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val whole = summary.total.takeIf { it > 0 } ?: return
    val parts = listOf(
        Triple(Res.string.fin_amazon_essential, summary.essential, needColor(AmazonNeed.ESSENTIAL)),
        Triple(Res.string.fin_amazon_discretionary, summary.discretionary, needColor(AmazonNeed.DISCRETIONARY)),
        Triple(Res.string.fin_amazon_unsorted, summary.unsorted, colors.textTertiary),
        // No order is not nothing: a dim grey of its own, still visible against the card.
        Triple(Res.string.fin_amazon_unmatched, summary.unmatched, colors.textTertiary.copy(alpha = 0.4f)),
    ).filter { it.second > 0 }
    FinanceCard(Modifier.padding(bottom = 10.dp).testTag("finance_budget_amazon"), onClick = onClick, padding = 14.dp) {
        Text(
            stringResource(Res.string.fin_amazon_headline, FinanceFormat.money(summary.essential, 0), FinanceFormat.money(whole, 0)),
            style = FinanceTheme.type.bodyStrong,
            color = if (selected) colors.accent else colors.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(colors.surface)) {
            parts.forEach { (_, amount, color) -> Box(Modifier.weight((amount / whole).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(color)) }
        }
        Spacer(Modifier.height(6.dp))
        parts.forEach { (label, amount, color) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label), style = FinanceTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text(FinanceFormat.money(amount, 0), style = FinanceTheme.type.label, color = colors.textPrimary)
            }
        }
        Spacer(Modifier.height(4.dp))
        val failed = summary.failedMailboxes
        Text(
            if (failed.isNotEmpty()) stringResource(Res.string.fin_amazon_failed, failed.joinToString(stringResource(Res.string.common_dot_separator))) else stringResource(Res.string.fin_amazon_open),
            style = FinanceTheme.type.label,
            color = if (failed.isNotEmpty()) colors.watch else colors.textTertiary,
        )
    }
}

/**
 * The part of a purchase's sheet about its Amazon order: which order it was and how the charge
 * was matched to it, the charge shared out by need, and each item with a choice of essential or
 * discretionary (and, once someone has said, letting it sort itself again).
 */
@Composable
internal fun AmazonOrderSection(purchase: Purchase, busy: Boolean, onSayNeed: (orderId: String, item: AmazonItem, need: AmazonNeed?) -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val order = purchase.amazon
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("finance_budget_amazon_order")) {
        Text(stringResource(Res.string.fin_amazon_sheet_title), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
        Spacer(Modifier.height(2.dp))
        if (order == null) {
            Text(stringResource(Res.string.fin_amazon_sheet_unmatched), style = FinanceTheme.type.label, color = colors.textTertiary)
            return@Column
        }
        Text(stringResource(Res.string.fin_amazon_sheet_order, order.orderId, FinanceFormat.dayOfMonth(order.ordered).resolve()), style = FinanceTheme.type.label, color = colors.textSecondary)
        Text(
            stringResource(
                when (order.match) {
                    AmazonMatch.SHIPMENT -> Res.string.fin_amazon_match_shipment
                    AmazonMatch.ORDER -> Res.string.fin_amazon_match_order
                    AmazonMatch.SPLIT -> Res.string.fin_amazon_match_split
                },
            ),
            style = FinanceTheme.type.label,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (order.unsorted > 0) {
                stringResource(Res.string.fin_amazon_charge_split_unsorted, FinanceFormat.money(order.essential), FinanceFormat.money(order.discretionary), FinanceFormat.money(order.unsorted))
            } else {
                stringResource(Res.string.fin_amazon_charge_split, FinanceFormat.money(order.essential), FinanceFormat.money(order.discretionary))
            },
            style = FinanceTheme.type.label,
            color = colors.textSecondary,
        )
        order.items.forEach { item ->
            Spacer(Modifier.height(12.dp))
            AmazonItemRow(item, busy, onSay = { need -> onSayNeed(order.orderId, item, need) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmazonItemRow(item: AmazonItem, busy: Boolean, onSay: (AmazonNeed?) -> Unit) {
    val colors = FinanceTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(needColor(item.need)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = FinanceTheme.type.body, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val about = listOfNotNull(
                item.price?.let { stringResource(Res.string.fin_amazon_item_price, item.quantity, FinanceFormat.money(it)) },
                stringResource(
                    when (item.sortedBy) {
                        NeedSource.USER -> Res.string.fin_amazon_sorted_user
                        NeedSource.RULE -> Res.string.fin_amazon_sorted_rule
                        NeedSource.MODEL -> Res.string.fin_amazon_sorted_model
                        NeedSource.NONE -> Res.string.fin_amazon_sorted_none
                    },
                ),
            )
            Text(about.joinToString(stringResource(Res.string.common_dot_separator)), style = FinanceTheme.type.label, color = colors.textTertiary)
            Spacer(Modifier.height(6.dp))
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Once someone has said, a third choice takes it back; null is that choice.
                val picks: List<AmazonNeed?> = AmazonNeed.entries + if (item.sortedBy == NeedSource.USER) listOf(null) else emptyList()
                picks.forEach { pick ->
                    val isSelected = pick != null && pick == item.need
                    val tint = if (pick == null) colors.textSecondary else needColor(pick)
                    Text(
                        if (pick == null) stringResource(Res.string.fin_amazon_let_it_sort) else stringResource(needLabel(pick)),
                        style = FinanceTheme.type.label,
                        color = if (isSelected) tint else colors.textSecondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) tint.copy(alpha = 0.18f) else colors.surface)
                            .border(1.dp, if (isSelected) tint.copy(alpha = 0.6f) else colors.hairline, CircleShape)
                            .selectable(selected = isSelected, enabled = !busy, role = Role.RadioButton) { if (!isSelected) onSay(pick) }
                            .testTag("finance_budget_amazon_need_${item.position}_${pick?.wire ?: "auto"}")
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Preview(name = "Budget · Amazon this month", widthDp = 412, heightDp = 600)
@Composable
private fun FinanceBudgetAmazonPreview() {
    FinanceStage {
        Column(Modifier.padding(top = 12.dp)) {
            AmazonBlock(BudgetFixtures.amazonSummary, selected = false, onClick = {})
            AmazonBlock(BudgetFixtures.amazonSummary.copy(failedMailboxes = listOf("sam")), selected = true, onClick = {})
        }
    }
}

@Preview(name = "Budget · an Amazon purchase's order", widthDp = 412, heightDp = 520)
@Composable
private fun FinanceBudgetAmazonOrderPreview() {
    FinanceStage {
        Column(Modifier.padding(top = 16.dp)) {
            AmazonOrderSection(BudgetFixtures.amazonPurchases.first(), busy = false, onSayNeed = { _, _, _ -> })
            Spacer(Modifier.height(24.dp))
            AmazonOrderSection(BudgetFixtures.amazonPurchases.last(), busy = false, onSayNeed = { _, _, _ -> })
        }
    }
}
