package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.Explainers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The money checkup on the Wallet page: a ring of its lines, passed or flagged, the score beside
 * it, the most pressing flag as "Start here", then each line with its number against the rule's
 * line. Tapping the score opens how it's scored; tapping a line (or its ⓘ) opens that line's
 * breakdown ([CheckupSheet]). Rules of thumb, not advice.
 */
@Composable
internal fun MoneyCheckup(checkup: Checkup, onOpen: (CheckKind?) -> Unit, modifier: Modifier = Modifier) {
    if (checkup.checks.isEmpty()) return
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val all = checkup.passed == checkup.checks.size
    FinanceCard(modifier, padding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "See how the checkup is scored") { onOpen(null) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckupRing(checkup, Modifier.size(64.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("MONEY CHECKUP", style = type.micro, color = colors.textSecondary)
                Text("${checkup.passed} of ${checkup.checks.size} looking healthy", style = type.title, color = if (all) colors.gain else colors.textPrimary)
                Text("How it's scored", style = type.label, color = colors.accent)
            }
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        checkup.toFix.firstOrNull()?.let { first ->
            StartHere(first, onClick = { onOpen(first.kind) }, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))
        }
        checkup.checks.forEachIndexed { i, check ->
            if (i > 0 || checkup.toFix.isNotEmpty()) Hairline(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
            CheckRow(check, onClick = { onOpen(check.kind) })
        }
        Text(
            "Common rules of thumb, not financial advice. Tap a line to see how it's worked out.",
            style = type.micro,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
        )
    }
}

/** The ring of lines, one arc each, green for passed and amber for flagged, drawn in as it appears. */
@Composable
private fun CheckupRing(checkup: Checkup, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val n = checkup.checks.size
    val oks = checkup.checks.map { it.ok }
    Box(
        modifier.clearAndSetSemantics { contentDescription = "${checkup.passed} of $n checks healthy" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = size.minDimension * 0.11f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val gap = if (n > 1) 14f else 0f
            val each = 360f / n
            oks.forEachIndexed { i, ok ->
                val start = -90f + i * each + gap / 2
                drawArc(colors.hairline, start, each - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val drawn = ((sweep.value * n - i).coerceIn(0f, 1f)) * (each - gap)
                if (drawn > 0f) drawArc(if (ok) colors.gain else colors.watch, start, drawn, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text("${checkup.passed}/$n", style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
    }
}

/** The most pressing flag's first step, as a tappable callout at the top of the card. */
@Composable
private fun StartHere(check: Check, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.watch.copy(alpha = 0.10f))
            .border(1.dp, colors.watch.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "See the ${check.title} breakdown", onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Flag, contentDescription = null, tint = colors.watch, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("START HERE · ${check.title.uppercase()}", style = FinanceTheme.type.micro, color = colors.watch)
            Spacer(Modifier.height(2.dp))
            Text(check.steps.firstOrNull() ?: check.tip, style = FinanceTheme.type.label, color = colors.textPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.watch, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CheckRow(check: Check, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "See how ${check.title} is worked out", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusIcon(check.ok, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(check.title, style = type.bodyStrong, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Text(check.figure, style = type.bodyStrong, color = if (check.ok) colors.gain else colors.watch)
            }
            Text(check.detail, style = type.body, color = colors.textPrimary.copy(alpha = 0.88f))
            check.gauge?.let {
                Spacer(Modifier.height(8.dp))
                CheckGaugeBar(it, check.ok, compact = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(check.tip, style = type.label, color = colors.textSecondary)
        }
        // The ⓘ the tip promises: here it opens the working-out, which links on to the plain-English explainer.
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusIcon(ok: Boolean, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    Icon(
        if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
        contentDescription = if (ok) "Healthy" else "Worth a look",
        tint = if (ok) colors.gain else colors.watch,
        modifier = modifier,
    )
}

/**
 * The household's number against the rule's line: the track tinted faintly on the healthy side
 * of the line, filled to the number, with a tick at the line (and a fainter one at the stretch
 * goal). [compact] leaves off the labels under it.
 */
@Composable
internal fun CheckGaugeBar(gauge: CheckGauge, ok: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = FinanceTheme.colors
    val fill = remember { Animatable(0f) }
    val target = (gauge.value / gauge.max).toFloat().coerceIn(0f, 1f)
    LaunchedEffect(target) { fill.animateTo(target, tween(800, easing = FastOutSlowInEasing)) }
    val tick = (gauge.target / gauge.max).toFloat().coerceIn(0f, 1f)
    val stretch = gauge.stretch?.let { (it / gauge.max).toFloat().coerceIn(0f, 1f) }
    val barColor = if (ok) colors.gain else colors.watch
    val good = colors.gain.copy(alpha = 0.12f)
    val track = colors.hairline
    val tickColor = colors.textPrimary
    val stretchColor = colors.textSecondary
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(if (compact) 10.dp else 14.dp)) {
            val h = size.height * 0.6f
            val top = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(track, Offset(0f, top), Size(size.width, h), r)
            if (gauge.higherIsBetter) {
                drawRoundRect(good, Offset(size.width * tick, top), Size(size.width * (1 - tick), h), r)
            } else {
                drawRoundRect(good, Offset(0f, top), Size(size.width * tick, h), r)
            }
            drawRoundRect(barColor, Offset(0f, top), Size(size.width * fill.value, h), r)
            val w = 2.dp.toPx()
            drawRect(tickColor, Offset(size.width * tick - w / 2, 0f), Size(w, size.height))
            if (stretch != null) drawRect(stretchColor.copy(alpha = 0.7f), Offset(size.width * stretch - w / 2, size.height * 0.15f), Size(w, size.height * 0.7f))
        }
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth()) {
                Text("0", style = FinanceTheme.type.micro, color = colors.textTertiary)
                GaugeLabel(gauge.targetLabel, tick, colors.textPrimary)
                if (stretch != null && gauge.stretchLabel != null) GaugeLabel(gauge.stretchLabel, stretch, colors.textSecondary)
            }
        }
    }
}

/** A label ending at [fraction] of the width, under its tick. */
@Composable
private fun GaugeLabel(text: String, fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0.12f, 1f))) {
        Text(text, style = FinanceTheme.type.micro, color = color, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * The checkup's breakdowns as a sheet from the bottom. [page] null is how the score is worked out
 * and where to start; a kind is that line's breakdown: the figure, the rule, the sum that made it
 * with each part opening to the accounts, expenses or loans behind it (and a way to them on the
 * Wallet page), what would move it in dollars, and a what-if slider. [onJump] closes the sheet and
 * scrolls the page to a section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckupSheet(
    checkup: Checkup,
    page: CheckKind?,
    onPage: (CheckKind?) -> Unit,
    onDismiss: () -> Unit,
    onJump: (WalletSection) -> Unit,
) {
    val colors = FinanceTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val jump: (WalletSection) -> Unit = { section ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { onJump(section) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        AnimatedContent(
            page,
            transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 8 }) togetherWith fadeOut(tween(120)) },
            label = "checkupPage",
        ) { current ->
            CheckupPage(checkup, current, onPage, jump, Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding())
        }
    }
}

/** One page of [CheckupSheet], outside the sheet: how it's scored when [page] is null, else that line's breakdown. */
@Composable
internal fun CheckupPage(checkup: Checkup, page: CheckKind?, onPage: (CheckKind?) -> Unit, onJump: (WalletSection) -> Unit, modifier: Modifier = Modifier) {
    val check = page?.let { checkup.byKind(it) }
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        if (check == null) CheckupOverview(checkup, onPage) else CheckDetail(check, checkup, onPage, onJump)
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text.uppercase(), style = FinanceTheme.type.micro, color = FinanceTheme.colors.textSecondary, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
}

/** How the score is made, each line's number against its rule, and the flagged ones in the order to tackle them. */
@Composable
private fun CheckupOverview(checkup: Checkup, onPage: (CheckKind?) -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckupRing(checkup, Modifier.size(56.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Money checkup", style = type.title, color = colors.textPrimary)
                Text("${checkup.passed} of ${checkup.checks.size} looking healthy", style = type.label, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Each line holds one of your numbers against a common rule of thumb, using the figures in your budget sheet. " +
                "A line passes when your number is on the healthy side of its line; the score is how many pass. " +
                "The lines aren't weighted, so the order to work on them is below.",
            style = type.body,
            color = colors.textPrimary.copy(alpha = 0.88f),
        )
        SheetHeading("Your lines")
        checkup.checks.forEach { check ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Open ${check.title}") { onPage(check.kind) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIcon(check.ok, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(check.title, style = type.bodyStrong, color = colors.textPrimary)
                    Text(check.rule, style = type.label, color = colors.textSecondary)
                }
                Spacer(Modifier.width(10.dp))
                Text(check.figure, style = type.bodyStrong, color = if (check.ok) colors.gain else colors.watch)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.padding(start = 6.dp).size(14.dp))
            }
        }
        val toFix = checkup.toFix
        SheetHeading(if (toFix.isEmpty()) "Keeping it healthy" else "Where to start")
        if (toFix.isEmpty()) {
            Text("Every line passes. Open any of them to see how much room you have and what would change it.", style = type.body, color = colors.textPrimary.copy(alpha = 0.88f))
        }
        toFix.forEachIndexed { i, check ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "Open ${check.title}") { onPage(check.kind) }
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(colors.watch.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = type.micro, color = colors.watch)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(check.title, style = type.bodyStrong, color = colors.textPrimary)
                    Text(priorityReason(check.kind), style = type.label, color = colors.textSecondary)
                    check.steps.firstOrNull()?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = type.label, color = colors.textPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Common rules of thumb, not financial advice.", style = type.micro, color = colors.textTertiary)
    }
}

/** One line's breakdown. */
@Composable
private fun CheckDetail(check: Check, checkup: Checkup, onPage: (CheckKind?) -> Unit, onJump: (WalletSection) -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    Column {
        val tone = if (check.ok) colors.gain else colors.watch
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "All checks") { onPage(null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All checks", tint = colors.textSecondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(check.title, style = type.title, color = colors.textPrimary)
                Text(if (check.ok) "Looking healthy" else "Worth a look", style = type.label, color = tone)
            }
            StatusIcon(check.ok, Modifier.size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(check.figure, style = type.hero, color = tone)
        Text(check.detail, style = type.body, color = colors.textPrimary.copy(alpha = 0.88f))
        check.gauge?.let {
            Spacer(Modifier.height(14.dp))
            CheckGaugeBar(it, check.ok)
        }
        Spacer(Modifier.height(10.dp))
        Text(check.rule, style = type.label, color = colors.textSecondary)

        SheetHeading("How it's worked out")
        Text("Tap a line to see what's in it.", style = type.label, color = colors.textTertiary, modifier = Modifier.padding(bottom = 4.dp))
        check.ledger.forEach { row -> LedgerRow(row, check.ok, onJump) }
        check.note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = type.micro, color = colors.textTertiary)
        }

        SheetHeading(if (check.ok) "Keeping it healthy" else "How to improve it")
        check.steps.forEach { step ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = colors.accent, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(step, style = type.body, color = colors.textPrimary)
            }
        }

        check.whatIf?.let { whatIf ->
            SheetHeading("Try it")
            WhatIfCard(whatIf, check.kind)
        }

        SheetHeading("More")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val open = LocalExplainer.current
            Explainers.byId(check.explainerId)?.let { e -> SheetChip("Plain English: ${e.title}", colors.cool) { open(e.id) } }
            check.ledger.mapNotNull { it.section }.distinct().forEach { s -> SheetChip("See ${s.label}", colors.accent) { onJump(s) } }
            checkup.checks.filter { it.kind != check.kind }.forEach { other ->
                SheetChip(other.title, if (other.ok) colors.gain else colors.watch) { onPage(other.kind) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Common rules of thumb, not financial advice.", style = type.micro, color = colors.textTertiary)
    }
}

/**
 * A line of the sum: its sign, label and amount; tapping one with parts opens them, and offers the
 * Wallet section they come from.
 */
@Composable
private fun LedgerRow(row: CheckLedgerRow, ok: Boolean, onJump: (WalletSection) -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    var open by remember(row) { mutableStateOf(false) }
    val expandable = row.items.isNotEmpty() || row.section != null
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "ledgerChevron")
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (row.result) Hairline(Modifier.padding(vertical = 4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .let { m -> if (expandable) m.clickable(role = Role.Button, onClickLabel = if (open) "Hide ${row.label}" else "Show what's in ${row.label}") { open = !open } else m }
                .semantics(mergeDescendants = true) {}
                .padding(vertical = 9.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.op ?: "", style = type.bodyStrong, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(26.dp))
            Text(row.label, style = if (row.result) type.bodyStrong else type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(row.amount, style = type.bodyStrong, color = if (row.result) (if (ok) colors.gain else colors.watch) else colors.textPrimary)
            if (expandable) {
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(turn))
            } else {
                Spacer(Modifier.width(22.dp))
            }
        }
        AnimatedVisibility(open, enter = fadeIn(tween(180)) + expandVertically(tween(220)), exit = fadeOut(tween(120)) + shrinkVertically(tween(180))) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 26.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                if (row.items.isEmpty()) {
                    Text("Nothing in the sheet for this yet.", style = type.label, color = colors.textSecondary, modifier = Modifier.padding(vertical = 6.dp))
                }
                row.items.forEachIndexed { i, item ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.flagged) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(colors.watch))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = type.label, color = if (item.flagged) colors.watch else colors.textPrimary)
                            item.detail?.let { Text(it, style = type.micro, color = colors.textSecondary) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(item.amount, style = type.label, color = colors.textPrimary)
                    }
                }
                row.section?.let { section ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = "Go to ${section.label}") { onJump(section) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("See ${section.label} in your wallet", style = type.bodyStrong, color = colors.accent, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** The what-if: a slider over one lever, and the check's answer as it moves. Nothing is saved. */
@Composable
private fun WhatIfCard(whatIf: WhatIf, kind: CheckKind) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    var amount by remember(kind) { mutableFloatStateOf(0f) }
    val outcome = whatIf.outcome(amount.toDouble())
    val tone = if (outcome.ok) colors.gain else colors.watch
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(whatIf.label, style = type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text(FinanceFormat.money(amount.toDouble(), 0), style = type.bodyStrong, color = colors.textPrimary)
        }
        Slider(
            amount,
            { v -> amount = ((v / whatIf.step).roundToInt() * whatIf.step).coerceAtMost(whatIf.max).toFloat() },
            valueRange = 0f..whatIf.max.toFloat().coerceAtLeast(whatIf.step.toFloat()),
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.hairline),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(outcome.ok, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(outcome.headline, style = type.bodyStrong, color = tone)
        }
        outcome.note?.let { Text(it, style = type.label, color = colors.textSecondary, modifier = Modifier.padding(start = 26.dp, top = 2.dp)) }
        Spacer(Modifier.height(6.dp))
        Text("Starts from your numbers today. Nothing here changes your sheet.", style = type.micro, color = colors.textTertiary)
    }
}

@Composable
private fun SheetChip(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        style = FinanceTheme.type.label,
        color = FinanceTheme.colors.textPrimary,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
