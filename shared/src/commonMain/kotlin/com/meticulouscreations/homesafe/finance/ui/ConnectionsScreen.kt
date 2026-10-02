package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.ui.components.rememberShaderClock
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A part of the economy on the map, at ([x], [y]) as fractions of the map; [readingId] is the indicator it shows. */
@Immutable
internal data class EconNode(val id: String, val label: String, val explainerId: String, val readingId: String?, val x: Float, val y: Float)

/** "[from] pushes on [to]", and why, in a sentence. */
@Immutable
internal data class EconLink(val from: String, val to: String, val why: String)

internal object EconomyMap {
    /**
     * The parts on a ring, in an order that puts most linked parts side by side, so arrows run
     * round the rim or across the open middle instead of through another part.
     */
    val nodes: List<EconNode> = listOf(
        Triple("fed", "Fed rate", "dff"),
        Triple("mortgage", "Mortgages", "mortgage"),
        Triple("bonds", "Gov't rates", "dgs10"),
        Triple("debt", "Gov't debt", "debtgdp"),
        Triple("homes", "Homes", "homeprices"),
        Triple("credit", "Credit", "hy"),
        Triple("jobs", "Jobs", "unrate"),
        Triple("mood", "Shoppers", "umcsent"),
        Triple("prices", "Prices", "cpi"),
        Triple("stocks", "Stocks", "sp500"),
    ).mapIndexed { i, (id, label, explainer) ->
        // Turned half a step so two parts share the top and two the bottom, rather than one
        // crowding its neighbours at each end of the tall ellipse.
        val angle = -PI / 2 + PI / 10 + i * 2 * PI / 10
        EconNode(
            id = id,
            label = label,
            explainerId = explainer,
            readingId = explainer.takeIf { id != "stocks" },
            x = (0.5 + 0.38 * cos(angle)).toFloat(),
            y = (0.5 + 0.44 * sin(angle)).toFloat(),
        )
    }

    val links = listOf(
        EconLink("prices", "fed", "When prices rise too fast, the Fed raises its rate to cool the economy down."),
        EconLink("fed", "mortgage", "A higher Fed rate pushes up mortgage, car-loan and credit-card rates within weeks."),
        EconLink("fed", "jobs", "When borrowing gets expensive, businesses slow hiring and put off expanding."),
        EconLink("fed", "stocks", "Higher rates make safe savings more attractive than stocks, and make companies' borrowing cost more — so stock prices tend to fall."),
        EconLink("fed", "credit", "Rate rises that come fast can strain borrowers who took out loans when money was cheap."),
        EconLink("bonds", "mortgage", "Mortgage rates track the government's 10-year borrowing cost, usually 1.5–2 points above it."),
        EconLink("mortgage", "homes", "Expensive loans mean fewer buyers can afford a house, which cools home prices."),
        EconLink("jobs", "mood", "People with secure jobs and raises feel better about money — and spend more."),
        EconLink("mood", "prices", "When everyone's out buying, businesses can raise prices. When shoppers pull back, prices cool."),
        EconLink("stocks", "mood", "When their 401(k)s are up, people feel richer and spend more."),
        EconLink("credit", "jobs", "When companies can't borrow, they cut costs — and that usually means jobs."),
        EconLink("debt", "bonds", "The more the government borrows, the more it may have to pay lenders to keep buying its debt."),
    )

    /** "Walk me through it": the loop at the heart of it, one link at a time. */
    val tour = listOf(
        "prices" to "fed",
        "fed" to "mortgage",
        "mortgage" to "homes",
        "fed" to "jobs",
        "jobs" to "mood",
        "mood" to "prices",
    )

    fun node(id: String) = nodes.first { it.id == id }
}

/**
 * How it all connects: the economy as a map of ten parts — the Fed's rate, prices, jobs,
 * mortgages, home prices, stocks and the rest — each showing today's reading in its signal's
 * colour, with arrows for what pushes on what. Tap a part and its arrows light up with flowing
 * dashes, and below it each one is explained in a sentence; tap a sentence to follow it. "Walk me
 * through it" plays the central loop one step at a time.
 */
@Composable
internal fun ConnectionsScreen(state: FinanceUiState, contentPadding: PaddingValues) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    var selected by rememberSaveable { mutableStateOf("fed") }
    var touring by rememberSaveable { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(touring) {
        if (!touring) return@LaunchedEffect
        step = 0
        while (touring) {
            selected = EconomyMap.tour[step].first
            delay(3800)
            step = (step + 1) % EconomyMap.tour.size
        }
    }
    val tourLink = if (touring) EconomyMap.tour[step].let { (a, b) -> EconomyMap.links.first { it.from == a && it.to == b } } else null

    LazyColumn(contentPadding = contentPadding) {
        item {
            Column(Modifier.padding(horizontal = PageGutter, vertical = 8.dp)) {
                Text("How it all connects", style = type.title, color = colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Nothing in the economy moves on its own. Tap any part to see what it pushes on and what pushes on it. Colors show today's reading: green calm, amber worth watching, orange-red a warning.",
                    style = type.body,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (touring) colors.loss.copy(alpha = 0.16f) else colors.accent.copy(alpha = 0.16f))
                        .clickable(role = Role.Button) { touring = !touring }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (touring) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null, tint = if (touring) colors.loss else colors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (touring) "Stop the walkthrough" else "Walk me through it", style = type.bodyStrong, color = if (touring) colors.loss else colors.accent)
                }
            }
        }
        // On the walkthrough the step's caption sits above the map, where it's seen with the
        // arrow it describes; otherwise the selected part's panel follows the map.
        if (tourLink != null) {
            item { AnimatedContent(tourLink, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "tourCaption") { TourCaption(it, step) } }
        }
        item {
            EconomyMapView(
                state = state,
                selected = selected,
                highlight = tourLink,
                onSelect = {
                    touring = false
                    selected = it
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (tourLink == null) {
            item {
                AnimatedContent(selected, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "mapPanel") { target ->
                    NodePanel(target, state) { selected = it }
                }
            }
        }
        item { FinePrint("A simplified picture: real economies have many more links, and effects can take months to show up.") }
    }
}

@Composable
private fun EconomyMapView(state: FinanceUiState, selected: String, highlight: EconLink?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val time = rememberShaderClock()
    val active = highlight?.let { setOf(it.from, it.to) } ?: setOf(selected)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(0.75f)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        Canvas(Modifier.fillMaxSize()) {
            EconomyMap.links.forEach { link ->
                val a = EconomyMap.node(link.from)
                val b = EconomyMap.node(link.to)
                val lit = if (highlight != null) link == highlight else link.from == selected || link.to == selected
                val outgoing = link.from == selected
                val start = Offset(a.x * w, a.y * h)
                val end = Offset(b.x * w, b.y * h)
                // A gentle bow, so arrows between the same two parts don't overlap and the map reads as flow.
                val mid = Offset((start.x + end.x) / 2, (start.y + end.y) / 2)
                val nx = -(end.y - start.y)
                val ny = end.x - start.x
                val len = hypot(nx, ny).coerceAtLeast(1f)
                val ctrl = Offset(mid.x + nx / len * 26.dp.toPx(), mid.y + ny / len * 26.dp.toPx())
                // Stop short of the node pills.
                val pad = 26.dp.toPx()
                val t0 = (pad / hypot(end.x - start.x, end.y - start.y)).coerceIn(0f, 0.45f)
                fun q(t: Float) = Offset(
                    (1 - t) * (1 - t) * start.x + 2 * (1 - t) * t * ctrl.x + t * t * end.x,
                    (1 - t) * (1 - t) * start.y + 2 * (1 - t) * t * ctrl.y + t * t * end.y,
                )
                val p0 = q(t0)
                val p1 = q(1 - t0)
                val path = Path().apply {
                    moveTo(p0.x, p0.y)
                    val c = Offset((ctrl.x + mid.x) / 2, (ctrl.y + mid.y) / 2)
                    quadraticTo(c.x, c.y, p1.x, p1.y)
                }
                val color = when {
                    !lit -> colors.hairline
                    highlight != null -> colors.accent
                    outgoing -> colors.accent
                    else -> colors.cool
                }
                val width = if (lit) 2.5.dp.toPx() else 1.5.dp.toPx()
                if (lit) {
                    drawPath(path, color.copy(alpha = 0.18f), style = Stroke(width * 4, cap = StrokeCap.Round))
                    drawPath(
                        path,
                        color,
                        style = Stroke(width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()), -time.value * 40.dp.toPx())),
                    )
                } else {
                    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
                }
                // Arrowhead at the target end.
                val tip = p1
                val back = q(1 - t0 - 0.04f)
                val ang = atan2(tip.y - back.y, tip.x - back.x)
                val ah = (if (lit) 9 else 7).dp.toPx()
                val left = Offset(tip.x - ah * cos(ang - 0.45f), tip.y - ah * sin(ang - 0.45f))
                val right = Offset(tip.x - ah * cos(ang + 0.45f), tip.y - ah * sin(ang + 0.45f))
                drawPath(
                    Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(left.x, left.y)
                        lineTo(right.x, right.y)
                        close()
                    },
                    color,
                )
            }
        }
        EconomyMap.nodes.forEach { node ->
            val isActive = node.id in active
            val scale by animateFloatAsState(if (isActive) 1.08f else 1f, tween(220), label = "nodeScale")
            val signal = node.readingId?.let { state.readings[it]?.signal }
            val value = nodeValue(node, state)
            val dot = when {
                node.id == "stocks" -> state.quotes[MarketCatalog.SP500.symbol]?.let { colors.direction(it.change) } ?: colors.textTertiary
                else -> colors.signal(signal).takeIf { signal != null } ?: colors.cool
            }
            val border by animateColorAsState(if (isActive) colors.accent else colors.hairline, tween(220), label = "nodeBorder")
            Box(
                Modifier
                    .offset(x = with(density) { (node.x * w).toDp() } - 42.dp, y = with(density) { (node.y * h).toDp() } - 20.dp)
                    .widthIn(min = 84.dp, max = 84.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isActive) colors.surfaceRaised else colors.surface)
                    .border(1.5.dp, border, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "Show ${node.label}") { onSelect(node.id) }
                    .semantics { this.selected = isActive }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(5.dp))
                        Text(node.label, style = FinanceTheme.type.micro, color = colors.textPrimary, maxLines = 1, textAlign = TextAlign.Center)
                    }
                    if (value != null) Text(value, style = FinanceTheme.type.label, color = dot, maxLines = 1)
                }
            }
        }
    }
}

/** A node's reading, short: "3.4%", "+0.6% today". */
private fun nodeValue(node: EconNode, state: FinanceUiState): String? {
    if (node.id == "stocks") return state.quotes[MarketCatalog.SP500.symbol]?.let { FinanceFormat.signedPercent(it.changePercent) + " today" }
    val r = node.readingId?.let { state.readings[it] } ?: return null
    val v = r.latest ?: return null
    return FinanceFormat.indicator(v, r.indicator.unit)
}

/** The selected part: its verdict, what it pushes on and what pushes on it, each a sentence you can follow. */
@Composable
private fun NodePanel(id: String, state: FinanceUiState, onFollow: (String) -> Unit) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val node = EconomyMap.node(id)
    val reading = node.readingId?.let { state.readings[it] }
    val verdict = when {
        reading?.latest != null -> Narrator.verdict(reading, state.tone)
        node.id == "stocks" -> state.quotes[MarketCatalog.SP500.symbol]?.let { Narrator.quoteVerdict(MarketCatalog.SP500.symbol, it) }
        else -> null
    }
    FinanceCard(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.label, style = type.section, color = colors.textPrimary, modifier = Modifier.weight(1f))
            InfoButton(node.explainerId)
        }
        if (verdict != null) {
            Spacer(Modifier.height(4.dp))
            Text(verdict, style = type.body, color = colors.textPrimary.copy(alpha = 0.88f))
        }
        val out = EconomyMap.links.filter { it.from == id }
        val inbound = EconomyMap.links.filter { it.to == id }
        if (out.isNotEmpty()) {
            LinkGroup("${node.label} pushes on", out, colors.accent, outgoing = true, onFollow)
        }
        if (inbound.isNotEmpty()) {
            LinkGroup("What pushes on ${node.label.lowercase()}", inbound, colors.cool, outgoing = false, onFollow)
        }
    }
}

@Composable
private fun LinkGroup(title: String, links: List<EconLink>, color: Color, outgoing: Boolean, onFollow: (String) -> Unit) {
    val type = FinanceTheme.type
    val colors = FinanceTheme.colors
    Column {
        Spacer(Modifier.height(14.dp))
        Text(title.uppercase(), style = type.micro, color = color)
        links.forEach { link ->
            val other = EconomyMap.node(if (outgoing) link.to else link.from)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "Go to ${other.label}") { onFollow(other.id) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Column(Modifier.weight(1f)) {
                    Text(other.label, style = type.bodyStrong, color = colors.textPrimary)
                    Text(link.why, style = type.label, color = colors.textSecondary)
                }
            }
        }
    }
}

/** The walkthrough's caption for the link it's on. */
@Composable
private fun TourCaption(link: EconLink, step: Int) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    FinanceCard(Modifier.padding(bottom = 8.dp)) {
        Text("STEP ${step + 1} OF ${EconomyMap.tour.size}", style = type.micro, color = colors.accent)
        Spacer(Modifier.height(4.dp))
        Text("${EconomyMap.node(link.from).label} → ${EconomyMap.node(link.to).label}", style = type.section, color = colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(link.why, style = type.body, color = colors.textPrimary.copy(alpha = 0.88f))
    }
}

/** The way in from the Economy tab: a card that previews the map's idea and opens it. */
@Composable
internal fun ConnectionsEntryCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    FinanceCard(modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HOW IT ALL CONNECTS", style = type.micro, color = colors.accent)
                Text("Why does the Fed raising rates change your mortgage — and your job?", style = type.bodyStrong, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text("An interactive map of cause and effect, with today's readings.", style = type.label, color = colors.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.accent)
        }
    }
}
