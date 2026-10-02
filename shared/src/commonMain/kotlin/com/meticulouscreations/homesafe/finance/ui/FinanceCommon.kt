package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.Position
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Signal
import com.meticulouscreations.homesafe.finance.ui.components.ChangePill
import com.meticulouscreations.homesafe.finance.ui.components.RollingNumber
import com.meticulouscreations.homesafe.finance.ui.components.Shimmer
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_range_52_week
import homesafe.shared.generated.resources.finance_signal_none
import homesafe.shared.generated.resources.watchlist_in_sheet
import org.jetbrains.compose.resources.stringResource

/** Horizontal padding every finance page uses. */
internal val PageGutter = 20.dp

/** A page section's heading, with an optional trailing note or [action] (a button) at its end. */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    subtitle: String? = null,
    info: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(top = 28.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = FinanceTheme.type.section, color = FinanceTheme.colors.textPrimary)
            // The ⓘ sits right after the title, where the question comes up.
            if (info != null) InfoButton(info, Modifier.padding(start = 0.dp))
            Spacer(Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary)
            action?.invoke()
        }
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary)
        }
    }
}

/** A raised card on the black page. */
@Composable
internal fun FinanceCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter)
            .clip(shape)
            .background(FinanceTheme.colors.surface)
            .border(1.dp, FinanceTheme.colors.hairline, shape)
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
            .padding(padding),
        content = content,
    )
}

/** A thin rule between list rows. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FinanceTheme.colors.hairline))
}

/** A small labelled number, for grids of stats. */
@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = FinanceTheme.colors.textPrimary, note: String? = null) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(label, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(value, style = FinanceTheme.type.bodyStrong, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) Text(note, style = FinanceTheme.type.micro, color = FinanceTheme.colors.textTertiary, maxLines = 1)
    }
}

/** Two stat tiles a row, ruled between rows, Robinhood's "Stats" block. */
@Composable
internal fun StatGrid(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter)) {
        stats.chunked(2).forEachIndexed { i, row ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (label, value) ->
                    Row(Modifier.weight(1f).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(value, style = FinanceTheme.type.bodyStrong, color = FinanceTheme.colors.textPrimary)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A coloured dot and the signal's word. */
@Composable
internal fun SignalChip(signal: Signal?, modifier: Modifier = Modifier) {
    val color = FinanceTheme.colors.signal(signal)
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(signal?.label ?: Res.string.finance_signal_none), style = FinanceTheme.type.micro, color = color)
    }
}

/**
 * A watchlist row the way Robinhood draws one: symbol and name on the left, today's sparkline in
 * the middle, the price over a filled change pill on the right. [inSheet] marks a ticker the
 * budget sheet names; a [position] adds what's held and its worth under the name.
 */
@Composable
internal fun QuoteRow(
    symbol: String,
    quote: Quote?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inSheet: Boolean = false,
    position: Position? = null,
    fallbackName: String? = null,
    fallbackKind: InstrumentKind? = null,
) {
    val meta = MarketCatalog.lookup(symbol, quote, fallbackName, fallbackKind)
    val colors = FinanceTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(), onClick = onClick)
            .padding(horizontal = PageGutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val shortName = meta.shortName.resolve()
            val name = meta.name.resolve()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shortName, style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1)
                if (inSheet) {
                    Spacer(Modifier.width(6.dp))
                    SheetBadge()
                }
            }
            // What it is in plain words where there's an explainer ("Government's 10-year borrowing cost"), else its full name.
            val plain = Explainers.forSymbol(symbol)?.let { Explainers.byId(it)?.title }?.let { stringResource(it) }?.takeIf { it != shortName }
            Text(plain ?: if (name != shortName) name else symbol, style = FinanceTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (position != null) {
                Text(
                    FinanceFormat.positionLine(position, quote?.price, meta.kind, shortName, meta.currency).resolve(),
                    style = FinanceTheme.type.label,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (quote != null) {
            Sparkline(quote.intraday, colors.direction(quote.change), Modifier.width(72.dp).height(30.dp), baseline = quote.previousClose)
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
                Text(FinanceFormat.price(quote.price, meta.kind, meta.currency), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                ChangePill(FinanceFormat.signedPercent(quote.changePercent), positive = quote.change >= 0)
            }
        } else {
            Shimmer(Modifier.width(72.dp).height(30.dp))
            Spacer(Modifier.width(16.dp))
            Shimmer(Modifier.width(96.dp).height(36.dp))
        }
    }
}

/**
 * A page's headline: a caption, a big rolling number, and the change under it in its colour.
 * While a finger scrubs a chart, the caller passes what's under the finger instead, and the
 * change line crossfades to the scrubbed date.
 */
@Composable
internal fun HeroNumber(
    caption: String,
    value: String,
    change: String,
    changeColor: Color,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // One line whatever the name: "Dow Jones Industrial Average" beside the market-hours
            // pill wrapped where "Nasdaq Composite" didn't. A long name shrinks a little instead.
            OneLineText(caption, FinanceTheme.type.label, FinanceTheme.colors.textSecondary, Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(4.dp))
        RollingNumber(value, FinanceTheme.type.hero, FinanceTheme.colors.textPrimary)
        Spacer(Modifier.height(2.dp))
        AnimatedContent(change, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "heroChange") { text ->
            // Likewise a scrubbed date and a long change on a narrow phone.
            OneLineText(text, FinanceTheme.type.bodyStrong, changeColor)
        }
    }
}

/** A labelled 52-week range: a track from low to high with a marker where [current] sits. */
@Composable
internal fun RangeBar(low: Double, high: Double, current: Double, lowLabel: String, highLabel: String, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val fraction = if (high > low) ((current - low) / (high - low)).toFloat().coerceIn(0f, 1f) else 0.5f
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.finance_range_52_week), style = FinanceTheme.type.label, color = colors.textSecondary)
            InfoButton("range52w", size = 15.dp)
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(colors.loss, colors.watch, colors.gain))),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth - 14.dp) * fraction)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(colors.textPrimary)
                    .border(3.dp, colors.background, CircleShape),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(lowLabel, style = FinanceTheme.type.micro, color = colors.textTertiary, modifier = Modifier.weight(1f))
            Text(highLabel, style = FinanceTheme.type.micro, color = colors.textTertiary)
        }
    }
}

/** A row of small tappable chips, one selected: a radio group to accessibility services. */
@Composable
internal fun <T> ChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, color: Color, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = PageGutter).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) color.copy(alpha = 0.18f) else FinanceTheme.colors.surfaceRaised)
                    .border(1.dp, if (isSelected) color.copy(alpha = 0.6f) else Color.Transparent, CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(label(option), style = FinanceTheme.type.label, color = if (isSelected) color else FinanceTheme.colors.textSecondary)
            }
        }
    }
}

/**
 * [text] on one line at [style]'s size, stepping down to three-quarters of it when it wouldn't
 * fit, then ending in an ellipsis: a line that keeps its height whatever it says.
 */
@Composable
internal fun OneLineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.75f, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** "In sheet": a ticker the household's budget sheet names, so it's followed from there. */
@Composable
internal fun SheetBadge(modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.cool.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.TableChart, contentDescription = null, tint = colors.cool, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(stringResource(Res.string.watchlist_in_sheet), style = FinanceTheme.type.micro, color = colors.cool, maxLines = 1)
    }
}

/** Small print under a section. */
@Composable
internal fun FinePrint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = FinanceTheme.type.micro,
        color = FinanceTheme.colors.textTertiary,
        modifier = modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp),
    )
}
