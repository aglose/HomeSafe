package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.components.Meter
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.finance_wallet_accounts_title
import homesafe.shared.generated.resources.finance_wallet_charts_title
import homesafe.shared.generated.resources.finance_wallet_debt_title
import homesafe.shared.generated.resources.finance_wallet_home_owned
import homesafe.shared.generated.resources.finance_wallet_home_title
import homesafe.shared.generated.resources.finance_wallet_per_month
import homesafe.shared.generated.resources.finance_wallet_topic_accounts_note
import homesafe.shared.generated.resources.finance_wallet_topic_cash_flow
import homesafe.shared.generated.resources.finance_wallet_topic_charts_note
import homesafe.shared.generated.resources.finance_wallet_topic_debt_note
import homesafe.shared.generated.resources.finance_wallet_topic_flow_note
import homesafe.shared.generated.resources.finance_wallet_topic_mortgage_note
import homesafe.shared.generated.resources.finance_wallet_topic_tax_note
import homesafe.shared.generated.resources.finance_wallet_topic_vest_note
import homesafe.shared.generated.resources.finance_wallet_topic_vesting_taxes
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** A part of the household's sheet that has a page of its own, opened from a row of the Wallet's "Your money". */
internal enum class WalletTopic(val title: StringResource) {
    ACCOUNTS(Res.string.finance_wallet_accounts_title),
    CASH_FLOW(Res.string.finance_wallet_topic_cash_flow),
    DEBT(Res.string.finance_wallet_debt_title),
    HOME(Res.string.finance_wallet_home_title),
    VESTING_TAXES(Res.string.finance_wallet_topic_vesting_taxes),
    SHEET_CHARTS(Res.string.finance_wallet_charts_title),
}

/** The topics [finance] has anything for, in the order the Wallet lists them. */
internal fun walletTopics(finance: PersonalFinance): List<WalletTopic> = WalletTopic.entries.filter { topic ->
    when (topic) {
        WalletTopic.ACCOUNTS -> finance.accounts.isNotEmpty()
        WalletTopic.CASH_FLOW -> finance.expenses.isNotEmpty() || flowHistory(finance).size > 2
        WalletTopic.DEBT -> finance.debts.isNotEmpty()
        WalletTopic.HOME -> finance.home != null || finance.mortgagePlan != null || finance.oldHouse?.soldPrice != null
        WalletTopic.VESTING_TAXES -> finance.vesting.isNotEmpty() || finance.taxYears.isNotEmpty()
        WalletTopic.SHEET_CHARTS -> finance.charts.isNotEmpty()
    }
}

/** How much of the home is paid for: one less what's owed over what it's worth. */
internal fun homeOwnedFraction(finance: PersonalFinance): Double? {
    val home = finance.home ?: return null
    val value = home.value?.takeIf { it > 0 } ?: return null
    val owed = home.unpaidPrincipal ?: finance.mortgageBalance.takeIf { it > 0 } ?: return null
    return (1 - owed / value).coerceIn(0.0, 1.0)
}

/** "Your money": a row per topic, its figure and a small picture of it, each opening the topic's page. */
@Composable
internal fun WalletHub(finance: PersonalFinance, topics: List<WalletTopic>, onOpen: (WalletTopic) -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape),
    ) {
        topics.forEachIndexed { i, topic ->
            if (i > 0) Hairline(Modifier.padding(start = 62.dp))
            TopicRow(finance, topic, onClick = { onOpen(topic) })
        }
    }
}

@Composable
private fun TopicRow(finance: PersonalFinance, topic: WalletTopic, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val dot = stringResource(Res.string.common_dot_separator)
    when (topic) {
        WalletTopic.ACCOUNTS -> {
            val invested = finance.accounts.filter { it.category == AccountCategory.INVESTING || it.category == AccountCategory.RETIREMENT }.sumOf { it.balance }
            TopicRowLayout(
                Icons.Outlined.AccountBalanceWallet,
                topic,
                figure = FinanceFormat.compactMoney(finance.totalAssets ?: finance.accounts.sumOf { it.balance }),
                note = pluralStringResource(Res.plurals.finance_wallet_topic_accounts_note, finance.accounts.size, finance.accounts.size, FinanceFormat.compactMoney(invested)),
                onClick = onClick,
            ) {
                SplitBar(finance.accounts.groupBy { it.category }.map { (cat, list) -> list.sumOf { it.balance } to categoryColor(cat, colors) }.sortedByDescending { it.first })
            }
        }

        WalletTopic.CASH_FLOW -> {
            val net = finance.netMonthly
            val income = finance.monthlyIncome
            TopicRowLayout(
                Icons.Outlined.Payments,
                topic,
                figure = net?.let { stringResource(Res.string.finance_wallet_per_month, FinanceFormat.signedMoney(it, 0)) },
                figureColor = net?.let { colors.direction(it) } ?: colors.textPrimary,
                note = finance.savingsRate?.let { stringResource(Res.string.finance_wallet_topic_flow_note, FinanceFormat.fractionPercent(it, 0)) },
                onClick = onClick,
            ) {
                if (income != null && income > 0 && finance.expenses.isNotEmpty()) {
                    // Take-home cut as the Cash flow page's bar is: the biggest costs, the rest, and what's left (white).
                    val top = finance.expenses.take(4).mapIndexed { i, e -> e.monthly to colors.categorical[(i + 1) % colors.categorical.size] }
                    val rest = finance.expenses.drop(4).sumOf { it.monthly }
                    val left = (income - finance.expenses.sumOf { it.monthly }).coerceAtLeast(0.0)
                    SplitBar(top + (rest to colors.textTertiary) + (left to colors.textPrimary))
                }
            }
        }

        WalletTopic.DEBT -> {
            val mortgage = finance.debts.firstOrNull { it.isMortgage && !it.isPaidOff }
            val open = finance.debts.count { !it.isMortgage && !it.isPaidOff }
            val history = Series.of(finance.history.mapNotNull { s -> s.debt?.let { s.epochSeconds to it } })
            TopicRowLayout(
                Icons.Outlined.CreditCard,
                topic,
                figure = FinanceFormat.compactMoney(finance.consumerDebt),
                note = listOfNotNull(
                    open.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.finance_wallet_topic_debt_note, it, it) },
                    mortgage?.let { stringResource(Res.string.finance_wallet_topic_mortgage_note, FinanceFormat.compactMoney(it.balance)) },
                ).joinToString(dot),
                onClick = onClick,
            ) {
                if (history.size > 2) Sparkline(history, colors.loss, Modifier.fillMaxWidth().height(28.dp))
            }
        }

        WalletTopic.HOME -> {
            val owned = homeOwnedFraction(finance)
            TopicRowLayout(
                Icons.Outlined.Home,
                topic,
                figure = finance.home?.equity?.let(FinanceFormat::compactMoney),
                figureColor = colors.gain,
                note = owned?.let { stringResource(Res.string.finance_wallet_home_owned, FinanceFormat.fractionPercent(it, 0)) },
                onClick = onClick,
            ) {
                owned?.let { Meter(it.toFloat(), colors.gain, height = 6.dp) }
            }
        }

        WalletTopic.VESTING_TAXES -> {
            val now = Clock.System.now().epochSeconds
            val next = finance.vesting.filter { (it.epochSeconds ?: 0) >= now }.minByOrNull { it.epochSeconds ?: Long.MAX_VALUE }
            val year = finance.taxYears.lastOrNull()
            TopicRowLayout(
                Icons.Outlined.Event,
                topic,
                figure = next?.let { FinanceFormat.compactMoney(it.amount) },
                figureColor = colors.accent,
                note = listOfNotNull(
                    next?.epochSeconds?.let { stringResource(Res.string.finance_wallet_topic_vest_note, next.type, FinanceFormat.relativeDays(now, it).resolve()) },
                    year?.effectiveRate?.let { stringResource(Res.string.finance_wallet_topic_tax_note, year.year.toString(), FinanceFormat.fractionPercent(it, 0)) },
                ).joinToString(dot),
                onClick = onClick,
            )
        }

        WalletTopic.SHEET_CHARTS -> TopicRowLayout(
            Icons.Outlined.InsertChart,
            topic,
            figure = null,
            note = pluralStringResource(Res.plurals.finance_wallet_topic_charts_note, finance.charts.size, finance.charts.size),
            onClick = onClick,
        )
    }
}

/** A hub row: the topic's icon, title and note, its figure, a chevron, and [picture] under the words. */
@Composable
private fun TopicRowLayout(
    icon: ImageVector,
    topic: WalletTopic,
    figure: String?,
    note: String?,
    onClick: () -> Unit,
    figureColor: Color = FinanceTheme.colors.textPrimary,
    picture: (@Composable () -> Unit)? = null,
) {
    val colors = FinanceTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("wallet_topic_${topic.name.lowercase()}")
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(colors.surfaceRaised), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(topic.title), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1)
                if (!note.isNullOrEmpty()) Text(note, style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (figure != null) {
                Spacer(Modifier.width(8.dp))
                Text(figure, style = FinanceTheme.type.bodyStrong, color = figureColor, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        }
        if (picture != null) {
            Box(Modifier.padding(start = 46.dp, end = 20.dp, top = 10.dp)) { picture() }
        }
    }
}

/** A thin bar cut into [parts] in proportion: a whole and what it's made of. */
@Composable
private fun SplitBar(parts: List<Pair<Double, Color>>, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val shown = parts.filter { it.first > 0 }
    val total = shown.sumOf { it.first }.takeIf { it > 0 } ?: return
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))) {
        shown.forEachIndexed { i, (v, c) ->
            Box(Modifier.weight((v / total).toFloat()).fillMaxHeight().background(c))
            if (i < shown.lastIndex) Spacer(Modifier.width(2.dp))
        }
    }
}

/**
 * A topic's page, pushed over the Wallet. With [focus] (a checkup ledger row's link) it glides to
 * that section once and lights it up for a moment.
 */
@Composable
internal fun WalletTopicScreen(
    topic: WalletTopic,
    focus: WalletSection?,
    state: FinanceUiState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val finance = state.finance
    var flash by remember { mutableStateOf<WalletSection?>(null) }
    var jumped by rememberSaveable { mutableStateOf(false) }
    if (focus != null && finance != null) {
        LaunchedEffect(focus) {
            if (jumped) return@LaunchedEffect
            jumped = true
            listState.scrollToKey(focus.key)
            flash = focus
            delay(1_400)
            flash = null
        }
    }
    LazyColumn(modifier.testTag("wallet_topic_page"), state = listState, contentPadding = contentPadding) {
        if (finance == null) {
            item(key = "loading") { WalletSkeleton() }
        } else {
            walletTopicItems(topic, finance, state, flash)
            item(key = "source") { SourceFooter(finance) }
        }
    }
}

/**
 * Brings the item with [key] to the top of the list. The list only knows the keys of what it has
 * laid out, so it glides down a screen at a time until the item is among them (or the list ends).
 */
private suspend fun LazyListState.scrollToKey(key: Any) {
    repeat(40) {
        layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let {
            animateScrollToItem(it.index)
            return
        }
        val step = layoutInfo.viewportSize.height * 0.85f
        if (step <= 0f || animateScrollBy(step, tween(220, easing = LinearEasing)) == 0f) return
    }
}

/** A section header that glows for a moment when the checkup jumps to it. */
@Composable
internal fun Flashable(lit: Boolean, content: @Composable () -> Unit) {
    val glow by animateFloatAsState(if (lit) 0.16f else 0f, tween(if (lit) 220 else 900), label = "sectionFlash")
    val accent = FinanceTheme.colors.accent
    Box(Modifier.fillMaxWidth().drawBehind { drawRect(accent.copy(alpha = glow)) }) { content() }
}
