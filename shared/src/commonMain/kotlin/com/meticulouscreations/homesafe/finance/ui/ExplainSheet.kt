// The explainer opener is the finance app's own local, as its palette is: every screen offers an
// ⓘ and none of them should have to thread a callback through for it.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.Explainer
import com.meticulouscreations.homesafe.finance.domain.ExplainerTopic
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog

/** Opens the plain-English explainer with this id. Provided by [FinanceApp]; a no-op elsewhere (previews). */
val LocalExplainer = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The voice the narrated sentences take. Provided by [FinanceApp] from the user's choice; the default elsewhere (previews). */
val LocalEconomyTone = compositionLocalOf { EconomyTone.DEFAULT }

/**
 * The little ⓘ that sits beside a heading or a number: tap it for what this is, in plain words.
 * The tap target is the usual 48dp even though the circle is small.
 */
@Composable
fun InfoButton(explainerId: String, modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = FinanceTheme.colors.textSecondary) {
    val open = LocalExplainer.current
    val title = Explainers.byId(explainerId)?.title ?: return
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Explain $title") { open(explainerId) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = "What is $title?", tint = tint, modifier = Modifier.size(size))
    }
}

internal fun topicIcon(topic: ExplainerTopic): ImageVector = when (topic) {
    ExplainerTopic.PRICES -> Icons.Filled.LocalGroceryStore
    ExplainerTopic.JOBS -> Icons.Filled.Work
    ExplainerTopic.RATES -> Icons.Filled.AccountBalance
    ExplainerTopic.MARKETS -> Icons.AutoMirrored.Filled.ShowChart
    ExplainerTopic.CREDIT -> Icons.Filled.Warning
    ExplainerTopic.GOVERNMENT -> Icons.Filled.AccountBalance
    ExplainerTopic.HOUSING -> Icons.Filled.Home
    ExplainerTopic.YOUR_MONEY -> Icons.Filled.Savings
}

internal fun topicColor(topic: ExplainerTopic, palette: FinancePalette): Color = when (topic) {
    ExplainerTopic.PRICES -> palette.watch
    ExplainerTopic.JOBS -> palette.cool
    ExplainerTopic.RATES -> palette.violet
    ExplainerTopic.MARKETS -> palette.gain
    ExplainerTopic.CREDIT -> palette.loss
    ExplainerTopic.GOVERNMENT -> palette.accent
    ExplainerTopic.HOUSING -> palette.cool
    ExplainerTopic.YOUR_MONEY -> palette.accent
}

/**
 * The explainer, as a sheet from the bottom: the idea in one sentence, what it's saying right
 * now (from the live numbers), what it means for this household, how it works, what's normal,
 * an everyday comparison, and the ideas it's tied to — each a chip that turns the sheet to that
 * one. An indicator's sheet ends with a way to its full chart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplainSheet(
    id: String,
    state: FinanceUiState,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenChart: (String) -> Unit,
) {
    val colors = FinanceTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        AnimatedContent(
            id,
            transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 8 }) togetherWith fadeOut(tween(120)) },
            label = "explainer",
        ) { current ->
            val explainer = Explainers.byId(current)
            if (explainer == null) {
                Spacer(Modifier.height(1.dp))
            } else {
                ExplainerBody(explainer, state, onNavigate, onOpenChart)
            }
        }
    }
}

@Composable
internal fun ExplainerBody(e: Explainer, state: FinanceUiState, onNavigate: (String) -> Unit, onOpenChart: (String) -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val reading = state.readings[e.id]
    val tone = state.tone
    val accent = topicColor(e.topic, colors)
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(topicIcon(e.topic), contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = type.title, color = colors.textPrimary)
                if (e.technical.isNotEmpty()) Text(e.technical, style = type.label, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(e.oneLiner, style = type.body.copy(fontSize = type.section.fontSize, lineHeight = type.section.lineHeight), color = colors.textPrimary)

        val now = when {
            reading != null && reading.latest != null -> Narrator.rightNow(reading, tone)

            e.id == "realrate" -> {
                val ff = state.readings["dff"]?.latest
                val cpi = state.readings["cpi"]?.latest
                if (ff != null && cpi != null) {
                    val real = ff - cpi
                    "The Fed's ${FinanceFormat.grouped(ff, 2)}% minus inflation's ${FinanceFormat.grouped(cpi, 2)}% leaves a real rate of ${FinanceFormat.grouped(real, 2)}%" +
                        if (real >= 0) " — money is a little tight, and savings keep up with prices." else " — savings lose ground to prices."
                } else {
                    null
                }
            }

            e.id == "stress" -> state.stress?.let { "The gauge reads ${FinanceFormat.grouped(it.score, 0)} — ${it.label.lowercase()}. ${it.dangers} warning signs are in the danger zone and ${it.watches} are worth watching." }

            else -> symbolFor(e.id)?.let { s -> state.quotes[s]?.let { q -> Narrator.quoteVerdict(s, q) } }
        }
        if (now != null) {
            Callout("Right now", now, colors.signal(reading?.signal).takeIf { reading?.signal != null } ?: colors.cool, Icons.AutoMirrored.Filled.TrendingUp)
        }
        reading?.let { Narrator.perspective(it, tone) }?.let { PerspectiveCallout(it, tone) }
        Narrator.forYou(e.id, state.readings, state.quotes, state.finance)?.let { mine ->
            Callout("What it means for you", mine, colors.accent, Icons.Filled.Person)
        }
        ExplainSection("Why it matters", e.whyYou)
        if (e.analogy.isNotEmpty()) Callout("An everyday comparison", e.analogy, colors.violet, Icons.Outlined.Lightbulb)
        if (e.normal.isNotEmpty()) ExplainSection("What's normal", e.normal)
        if (e.howItWorks.isNotEmpty()) ExplainSection("How it works", e.howItWorks)

        val related = e.related.mapNotNull { Explainers.byId(it) }
        if (related.isNotEmpty()) {
            ExplainHeading("Connected to")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                related.forEach { r ->
                    val c = topicColor(r.topic, colors)
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(c.copy(alpha = 0.12f))
                            .border(1.dp, c.copy(alpha = 0.4f), CircleShape)
                            .clickable { onNavigate(r.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(topicIcon(r.topic), contentDescription = null, tint = c, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(r.title, style = type.label, color = colors.textPrimary)
                    }
                }
            }
        }
        if (IndicatorCatalog.byId(e.id) != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                "See the full chart",
                style = type.bodyStrong,
                color = colors.background,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.gain)
                    .clickable { onOpenChart(e.id) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** The market symbol an explainer is about, for its "Right now" line. */
private fun symbolFor(id: String): String? =
    (MarketCatalog.indices + MarketCatalog.macro).firstOrNull { Explainers.forSymbol(it.symbol) == id }?.symbol

@Composable
private fun ExplainHeading(text: String) {
    Text(
        text.uppercase(),
        style = FinanceTheme.type.micro,
        color = FinanceTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun ExplainSection(heading: String, body: String) {
    Column {
        ExplainHeading(heading)
        Text(body, style = FinanceTheme.type.body, color = FinanceTheme.colors.textPrimary.copy(alpha = 0.88f))
    }
}

/** A tinted card with a small icon and heading, for the parts of an explainer that are about now or about you. */
@Composable
internal fun Callout(heading: String, body: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(heading.uppercase(), style = FinanceTheme.type.micro, color = color)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, style = FinanceTheme.type.body, color = FinanceTheme.colors.textPrimary)
        content()
    }
}

/** What sits under "Right now": the reading's record against its own history (straight talk) or its bright side. */
@Composable
internal fun PerspectiveCallout(perspective: Perspective, tone: EconomyTone, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    when (tone) {
        EconomyTone.STRAIGHT -> Callout(perspective.heading, perspective.body, colors.cool, Icons.Filled.History, modifier)
        EconomyTone.BRIGHT_SIDE -> Callout(perspective.heading, perspective.body, colors.gain, Icons.Outlined.WbSunny, modifier)
    }
}
