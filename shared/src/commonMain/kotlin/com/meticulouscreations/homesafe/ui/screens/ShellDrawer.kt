package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.ui.FinanceApp
import com.meticulouscreations.homesafe.finance.ui.FinanceFormat
import com.meticulouscreations.homesafe.finance.ui.FinancePalette
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_app_title
import homesafe.shared.generated.resources.shell_drawer_cameras
import homesafe.shared.generated.resources.shell_drawer_change_today
import homesafe.shared.generated.resources.shell_drawer_close
import homesafe.shared.generated.resources.shell_drawer_finance
import homesafe.shared.generated.resources.shell_drawer_finance_subtitle
import homesafe.shared.generated.resources.shell_drawer_market_disclaimer
import homesafe.shared.generated.resources.shell_drawer_net_worth
import homesafe.shared.generated.resources.shell_drawer_open_finance
import homesafe.shared.generated.resources.shell_drawer_section_apps
import homesafe.shared.generated.resources.shell_drawer_section_home_security
import homesafe.shared.generated.resources.shell_menu
import org.jetbrains.compose.resources.stringResource
import kotlin.math.hypot

private val DrawerWidth = 304.dp

/**
 * The app drawer the top bar's menu button opens: the camera app's own tabs, and below them the
 * apps that live inside PercySafe — today, Finance, whose card carries a live S&P 500 line so the
 * drawer is worth a glance on its own. Slides in over a scrim; a tap on the scrim, Back, or a
 * swipe to the left closes it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ShellDrawer(
    open: Boolean,
    selectedTab: TopLevelRoute,
    finance: FinanceUiState,
    onSelectTab: (TopLevelRoute) -> Unit,
    onOpenFinance: (Offset) -> Unit,
    onClose: () -> Unit,
) {
    val progress by animateFloatAsState(
        if (open) 1f else 0f,
        if (open) spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow) else tween(220, easing = FastOutSlowInEasing),
        label = "drawer",
    )
    if (!open && progress == 0f) return
    BackHandler(enabled = open) { onClose() }
    val density = LocalDensity.current
    val widthPx = with(density) { DrawerWidth.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    // Reset as it opens, not as it closes: a swipe that closed it would snap back first.
    LaunchedEffect(open) { if (open) drag = 0f }
    val dragState = rememberDraggableState { delta -> drag = (drag + delta).coerceIn(-widthPx, 0f) }

    val menuTitle = stringResource(Res.string.shell_menu)
    Box(Modifier.fillMaxSize().testTag("shell_drawer")) {
        // The scrim: the shell dims and a tap anywhere on it closes the drawer.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress * (1f + drag / widthPx) }
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(Res.string.shell_drawer_close), onClick = onClose),
        )
        Column(
            Modifier
                .fillMaxHeight()
                .width(DrawerWidth)
                .graphicsLayer { translationX = -(1f - progress) * widthPx + drag }
                .draggable(
                    dragState,
                    Orientation.Horizontal,
                    onDragStopped = { velocity -> if (drag < -widthPx / 3 || velocity < -1200f) onClose() else drag = 0f },
                )
                .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                .semantics {
                    paneTitle = menuTitle
                    isTraversalGroup = true
                }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(Res.string.shell_app_title),
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 0.03.em),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 24.dp),
            )
            DrawerLabel(stringResource(Res.string.shell_drawer_section_home_security))
            DrawerRow(Icons.Filled.Home, stringResource(Res.string.shell_drawer_cameras), selectedTab == TopLevelRoute.Home) { onSelectTab(TopLevelRoute.Home) }
            DrawerRow(Icons.Filled.VideoLibrary, stringResource(TopLevelRoute.Moments.label), selectedTab == TopLevelRoute.Moments) { onSelectTab(TopLevelRoute.Moments) }
            DrawerRow(Icons.Filled.Settings, stringResource(TopLevelRoute.Settings.label), selectedTab == TopLevelRoute.Settings) { onSelectTab(TopLevelRoute.Settings) }
            Spacer(Modifier.height(24.dp))
            DrawerLabel(stringResource(Res.string.shell_drawer_section_apps))
            FinanceDrawerCard(finance, onOpenFinance)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.shell_drawer_market_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun DrawerLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.12.em),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) tint else MaterialTheme.colorScheme.onSurface)
    }
}

/** The Finance app's door: its name, today's S&P 500 with its sparkline, and net worth once the sheet is in. */
@Composable
private fun FinanceDrawerCard(finance: FinanceUiState, onOpen: (Offset) -> Unit) {
    val palette = remember { FinancePalette() }
    var center by remember { mutableStateOf(Offset.Zero) }
    val sp = finance.quotes[MarketCatalog.SP500.symbol]
    val dir = palette.direction(sp?.change ?: 0.0)
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF07140A), Color(0xFF000000))))
            .border(1.dp, Brush.linearGradient(listOf(palette.gain.copy(alpha = 0.7f), palette.accent.copy(alpha = 0.2f))), shape)
            .clickable(onClickLabel = stringResource(Res.string.shell_drawer_open_finance)) { onOpen(center) }
            .testTag("drawer_finance")
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(palette.gain), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.shell_drawer_finance), style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(stringResource(Res.string.shell_drawer_finance_subtitle), maxLines = 1, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = palette.textSecondary)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(MarketCatalog.SP500.shortName.resolve(), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                Text(sp?.let { FinanceFormat.grouped(it.price) } ?: "—", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(sp?.let { stringResource(Res.string.shell_drawer_change_today, FinanceFormat.signedPercent(it.changePercent)) } ?: " ", style = MaterialTheme.typography.labelSmall, color = dir)
            }
            if (sp != null && sp.intraday.size > 1) {
                Sparkline(sp.intraday, dir, Modifier.width(96.dp).height(40.dp), baseline = sp.previousClose)
            }
        }
        finance.finance?.netWorth?.let { nw ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.shell_drawer_net_worth, FinanceFormat.compactMoney(nw)), style = MaterialTheme.typography.labelMedium, color = palette.accent)
        }
    }
}

/**
 * The finance app over the whole shell, opening as a circle that grows from [origin] (the drawer
 * card that was tapped) to cover the screen, and draining back toward the menu button when it
 * closes. [onCovering] reports when it fully covers the screen, so the shell can stop drawing
 * — and stop streaming — the cameras underneath.
 */
@Composable
internal fun FinanceOverlay(open: Boolean, origin: Offset, onClose: () -> Unit, onCovering: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(0f) }
    val reportCovering by rememberUpdatedState(onCovering)
    var shown by remember { mutableStateOf(open) }
    LaunchedEffect(open) {
        if (open) {
            shown = true
            reveal.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
            reportCovering(true)
        } else {
            reportCovering(false)
            reveal.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            shown = false
        }
    }
    if (!shown) return
    val density = LocalDensity.current
    // Closing, it drains back toward the menu button it was reached from, below the status bar.
    val statusBar = WindowInsets.statusBars.getTop(density)
    val menuPoint = with(density) { Offset(48.dp.toPx(), statusBar + 36.dp.toPx()) }
    // [origin] is in the root's coordinates; the reveal is drawn in this box's, which a display
    // cutout's padding can shift.
    var ownOrigin by remember { mutableStateOf(Offset.Zero) }
    val center = if (open && origin != Offset.Zero) origin - ownOrigin else menuPoint
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { ownOrigin = it.positionInRoot() }
            .graphicsLayer {
                clip = true
                // Rebuilt each frame: the layer re-runs as the reveal animates.
                shape = circleReveal(center, reveal.value)
                val s = 0.94f + 0.06f * reveal.value
                scaleX = s
                scaleY = s
                alpha = (reveal.value * 2.5f).coerceAtMost(1f)
            },
    ) {
        FinanceApp(onClose = onClose, active = open)
    }
}

/** A circle centred on [center] whose radius at [fraction] 1 reaches the farthest corner. */
private fun circleReveal(center: Offset, fraction: Float) = GenericShape { size, _ ->
    val maxR = maxOf(
        hypot(center.x, center.y),
        hypot(size.width - center.x, center.y),
        hypot(center.x, size.height - center.y),
        hypot(size.width - center.x, size.height - center.y),
    )
    addOval(Rect(center, maxR * fraction))
}
