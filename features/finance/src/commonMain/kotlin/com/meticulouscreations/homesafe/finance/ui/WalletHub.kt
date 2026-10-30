package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import com.meticulouscreations.homesafe.ui.handCursor
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
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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

/**
 * "Your money": a row per topic, its figure and a small picture of it, each opening the topic's
 * page; on a desk, a grid of tiles, [columns] across.
 */
@Composable
internal fun WalletHub(finance: PersonalFinance, topics: List<WalletTopic>, onOpen: (WalletTopic) -> Unit, modifier: Modifier = Modifier, columns: Int = 1) {
    if (columns > 1) {
        DeskGrid(topics, columns, modifier, gap = 14.dp) { topic -> TopicRow(finance, topic, onClick = { onOpen(topic) }, tile = true) }
        return
    }
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
private fun TopicRow(finance: PersonalFinance, topic: WalletTopic, onClick: () -> Unit, tile: Boolean = false) {
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
                tile = tile,
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
                tile = tile,
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
            // Every mortgage entry: the sheet splits one by owner column, and a household may carry two.
            val mortgage = finance.mortgageBalance.takeIf { it > 0 }
            val open = finance.debts.count { !it.isMortgage && !it.isPaidOff }
            val history = Series.of(finance.history.mapNotNull { s -> s.debt?.let { s.epochSeconds to it } })
            TopicRowLayout(
                Icons.Outlined.CreditCard,
                topic,
                figure = FinanceFormat.compactMoney(finance.consumerDebt),
                note = listOfNotNull(
                    open.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.finance_wallet_topic_debt_note, it, it) },
                    mortgage?.let { stringResource(Res.string.finance_wallet_topic_mortgage_note, FinanceFormat.compactMoney(it)) },
                ).joinToString(dot),
                onClick = onClick,
                tile = tile,
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
                tile = tile,
            ) {
                owned?.let { Meter(it.toFloat(), colors.gain, height = 6.dp) }
            }
        }

        WalletTopic.VESTING_TAXES -> {
            val now = LocalFinanceClock.current.now().epochSeconds
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
                tile = tile,
                // A tile has the room for when each vest falls against today.
                picture = if (tile && finance.vesting.any { it.epochSeconds != null }) {
                    { VestStrip(finance, now) }
                } else {
                    null
                },
            )
        }

        WalletTopic.SHEET_CHARTS -> TopicRowLayout(
            Icons.Outlined.InsertChart,
            topic,
            figure = null,
            note = pluralStringResource(Res.plurals.finance_wallet_topic_charts_note, finance.charts.size, finance.charts.size),
            onClick = onClick,
            tile = tile,
            // A tile has the room for a glance at the first chart the sheet has.
            picture = finance.charts.firstNotNullOfOrNull { c -> c.series.firstOrNull()?.values?.filterNotNull()?.takeIf { it.size > 2 } }?.takeIf { tile }?.let { values ->
                { MiniBars(values) }
            },
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
    tile: Boolean = false,
    picture: (@Composable () -> Unit)? = null,
) {
    if (tile) {
        TopicTile(icon, topic, figure, note, onClick, figureColor, picture)
        return
    }
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

/**
 * [TopicRowLayout] as a desk's tile: the icon and title over the figure in large type, the note,
 * and the picture along the foot, every tile the same height so a grid of them lines up.
 */
@Composable
private fun TopicTile(
    icon: ImageVector,
    topic: WalletTopic,
    figure: String?,
    note: String?,
    onClick: () -> Unit,
    figureColor: Color,
    picture: (@Composable () -> Unit)?,
) {
    val colors = FinanceTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(TOPIC_TILE_HEIGHT)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.surfaceRaised.copy(alpha = 0.55f), colors.surface)))
            .border(1.dp, colors.hairline, shape)
            .testTag("wallet_topic_${topic.name.lowercase()}")
            .handCursor()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(colors.surfaceRaised), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(topic.title), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(14.dp))
        if (figure != null) Text(figure, style = FinanceTheme.type.title, color = figureColor, maxLines = 1)
        if (!note.isNullOrEmpty()) Text(note, style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        picture?.invoke()
    }
}

private val TOPIC_TILE_HEIGHT = 172.dp

/** The vests along a line from the first to the last, today marked across it: those behind dimmed, those to come lit. */
@Composable
private fun VestStrip(finance: PersonalFinance, now: Long) {
    val colors = FinanceTheme.colors
    val times = finance.vesting.mapNotNull { it.epochSeconds }
    val from = minOf(times.min(), now)
    val to = maxOf(times.max(), now)
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val span = (to - from).coerceAtLeast(1).toFloat()
        fun x(t: Long) = 5.dp.toPx() + (size.width - 10.dp.toPx()) * ((t - from) / span)
        val mid = size.height / 2
        drawLine(colors.hairline, Offset(0f, mid), Offset(size.width, mid), 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(colors.accent.copy(alpha = 0.5f), Offset(x(now), mid), Offset(size.width, mid), 2.dp.toPx(), cap = StrokeCap.Round)
        times.forEach { t -> drawCircle(if (t >= now) colors.accent else colors.textTertiary, 5.dp.toPx(), Offset(x(t), mid)) }
        drawLine(colors.textPrimary, Offset(x(now), 0f), Offset(x(now), size.height), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** [values] as a row of small bars on a shared zero: a chart's shape at a glance. */
@Composable
private fun MiniBars(values: List<Double>) {
    val colors = FinanceTheme.colors
    val shown = values.takeLast(24)
    val top = shown.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: return
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        val slot = size.width / shown.size
        val barW = (slot * 0.62f).coerceAtLeast(2f)
        shown.forEachIndexed { i, v ->
            val h = (size.height * (kotlin.math.abs(v) / top)).toFloat().coerceAtLeast(2f)
            drawRoundRect(
                colors.cool.copy(alpha = 0.35f + 0.65f * (i + 1) / shown.size),
                topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 3),
            )
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
            // A page just pushed may not be measured yet; with no viewport the search below can't step.
            snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            jumped = true
            listState.scrollToKey(focus.key)
            flash = focus
            delay(1_400)
            flash = null
        }
    }
    val desk = LocalFinanceDesk.current
    LazyColumn(modifier.testTag("wallet_topic_page"), state = listState, contentPadding = contentPadding) {
        if (finance == null) {
            item(key = "loading") { WalletSkeleton() }
        } else {
            if (desk) walletTopicDeskItems(topic, finance, state, flash) else walletTopicItems(topic, finance, state, flash)
            item(key = "source") { SourceFooter(finance) }
        }
    }
}

/**
 * Brings the item with [key] to the top of the list. The list only knows the keys of what it has
 * laid out, so it glides down a screen at a time until the item is among them (or the list ends).
 */
internal suspend fun LazyListState.scrollToKey(key: Any) {
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
