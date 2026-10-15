package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
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
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_app_title
import homesafe.shared.generated.resources.shell_drawer_cameras
import homesafe.shared.generated.resources.shell_drawer_close
import homesafe.shared.generated.resources.shell_drawer_market_disclaimer
import homesafe.shared.generated.resources.shell_drawer_section_apps
import homesafe.shared.generated.resources.shell_drawer_section_home_security
import homesafe.shared.generated.resources.shell_menu
import org.jetbrains.compose.resources.stringResource
import kotlin.math.hypot

private val DrawerWidth = 304.dp

/** How much of its width a full back swipe draws the drawer in by before letting go closes it. */
private const val DRAWER_BACK_PEEK = 0.25f

/** Let go of slower than the fling below, the drawer goes whichever way it is past this much of its width. */
private const val DRAWER_SETTLE_FRACTION = 1f / 3f

/** A flick this fast, in pixels a second, sends the drawer the way it was flicked however far it had got. */
private const val DRAWER_FLING_VELOCITY = 1200f

/**
 * The drawer drawn out by hand: a swipe across the shell toward the drawer's side, which needs no
 * reach for the menu button at the top of the screen — a thumb anywhere on the page will do. The
 * shell's chrome wears the gesture ([drawerPull]); the drawer follows [fraction] while the finger
 * is on it ([active]) and carries on from there when it is let go: out ([onOpen]) past a third of
 * its width or on a flick toward it, shut again otherwise.
 */
@Stable
class DrawerPull(private val onOpen: () -> Unit) {
    /** True from the moment a swipe starts to show the drawer until the finger lets go of it. */
    var active by mutableStateOf(false)
        private set

    /** How far out the swipe has drawn it, 0 (shut) to 1 (out); where it was let go, between swipes. */
    var fraction by mutableFloatStateOf(0f)
        private set

    // From shut, always: while the drawer is anywhere but shut (out, or still sliding shut) it and
    // its scrim are drawn over the shell and take the touch, so no swipe starts under them.
    internal fun start() {
        fraction = 0f
        active = true
    }

    internal fun dragBy(fractionDelta: Float) {
        if (active) fraction = (fraction + fractionDelta).coerceIn(0f, 1f)
    }

    /** The finger left at [velocity] pixels a second; null when the swipe was taken away from it instead. */
    internal fun letGo(velocity: Float?) {
        if (!active) return
        active = false
        val open = when {
            velocity == null -> false
            velocity > DRAWER_FLING_VELOCITY -> true
            velocity < -DRAWER_FLING_VELOCITY -> false
            else -> fraction > DRAWER_SETTLE_FRACTION
        }
        if (open) onOpen()
    }
}

/**
 * The swipe that draws the drawer out, for the part of the shell it can start on; null [pull]
 * where it can't (a nested screen, or under something already over the shell).
 *
 * Whatever is under the finger has first refusal — a row that scrolls sideways keeps its swipe, a
 * list still scrolls — and only a swipe toward the drawer's side is taken, with one finger: a
 * pinch on a camera card is the card's. It starts clear of the screen's edge on Android, where a
 * swipe in from the edge is the system's Back.
 */
internal fun Modifier.drawerPull(pull: DrawerPull?): Modifier = if (pull == null) {
    this
} else {
    pointerInput(pull) {
        val width = DrawerWidth.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop > 0f && currentEvent.changes.count { it.pressed } == 1) {
                    change.consume()
                    pull.start()
                    pull.dragBy(overSlop / width)
                }
            } ?: return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPointerInputChange(start)
            var lifted = false
            try {
                lifted = horizontalDrag(start.id) { change ->
                    velocity.addPointerInputChange(change)
                    pull.dragBy(change.positionChange().x / width)
                    change.consume()
                }
            } finally {
                // Also when the gesture is cut short (the shell went under something): never left half out.
                pull.letGo(if (lifted) velocity.calculateVelocity().x else null)
            }
        }
    }
}

/** How much of an app opened from the drawer a full back swipe drains before letting go closes it. */
private const val APP_BACK_PEEK = 0.35f

/**
 * The app drawer the top bar's menu button opens: the camera app's own tabs, and below them the
 * apps that live inside PercySafe — Weather, whose card is a window onto the sky outside,
 * Atlas, whose card says how far its owner's history reaches, Fitness, whose card says which
 * workout is up next, and Finance, whose card carries a live S&P 500 line — so the drawer is
 * worth a glance on its own.
 * Slides in over a scrim; a tap on the scrim, Back, or a swipe to the left closes it. A swipe
 * the other way across the shell draws it out too ([pull]), under the finger as it goes.
 *
 * The sign-in screen opens it too, before there are any cameras to go to: there
 * [showHomeSecurity] is false and it lists the apps alone. The apps' cards are [apps]'s to draw
 * (ShellApps in :app, which alone sees every feature), told whether to animate.
 */
// Public for another module to place; it fills whatever it is given, so it takes no Modifier.
@Suppress("ktlint:compose:modifier-missing-check")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShellDrawer(
    open: Boolean,
    selectedTab: TopLevelRoute,
    pull: DrawerPull,
    onSelectTab: (TopLevelRoute) -> Unit,
    onClose: () -> Unit,
    showHomeSecurity: Boolean = true,
    apps: @Composable (animated: Boolean) -> Unit = {},
) {
    val reveal = remember { Animatable(if (open) 1f else 0f) }
    val pulling = pull.active
    LaunchedEffect(open, pulling) {
        if (pulling) {
            // By hand: wherever the finger has drawn it to.
            snapshotFlow { pull.fraction }.collect { reveal.snapTo(it) }
        } else {
            // On from wherever it is: shut, out, or where a swipe let go of it.
            reveal.animateTo(if (open) 1f else 0f, if (open) spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow) else tween(220, easing = FastOutSlowInEasing))
        }
    }
    val shut by remember { derivedStateOf { reveal.value == 0f } }
    if (!open && !pulling && shut) return
    val density = LocalDensity.current
    val widthPx = with(density) { DrawerWidth.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    // A back swipe draws the drawer toward its edge as the finger moves, as a drag on it would;
    // let go past the commit point, the close carries on from there.
    val back = rememberPredictiveBack(enabled = open) { releasedAt ->
        drag = (drag - releasedAt * widthPx * DRAWER_BACK_PEEK).coerceIn(-widthPx, 0f)
        onClose()
    }

    // How far the drawer stands off its open place: a drag on it, or a back swipe.
    fun offset() = (drag - back.progress * widthPx * DRAWER_BACK_PEEK).coerceIn(-widthPx, 0f)

    // Reset as it opens, not as it closes: a swipe that closed it would snap back first.
    LaunchedEffect(open) { if (open) drag = 0f }
    val dragState = rememberDraggableState { delta -> drag = (drag + delta).coerceIn(-widthPx, 0f) }

    val menuTitle = stringResource(Res.string.shell_menu)
    Box(Modifier.fillMaxSize().testTag("shell_drawer")) {
        // The scrim: the shell dims and a tap anywhere on it closes the drawer.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.value * (1f + offset() / widthPx) }
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(Res.string.shell_drawer_close), onClick = onClose),
        )
        Column(
            Modifier
                .fillMaxHeight()
                .width(DrawerWidth)
                .graphicsLayer { translationX = -(1f - reveal.value) * widthPx + offset() }
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
            // Everything but the small print scrolls: on a phone on its side the drawer is
            // shorter than what is in it, and the Finance card must not be cut off at the fold.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(Res.string.shell_app_title),
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 0.03.em),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 24.dp),
                )
                if (showHomeSecurity) {
                    DrawerLabel(stringResource(Res.string.shell_drawer_section_home_security))
                    DrawerRow(Icons.Filled.Home, stringResource(Res.string.shell_drawer_cameras), selectedTab == TopLevelRoute.Home) { onSelectTab(TopLevelRoute.Home) }
                    DrawerRow(Icons.Filled.VideoLibrary, stringResource(TopLevelRoute.Moments.label), selectedTab == TopLevelRoute.Moments) { onSelectTab(TopLevelRoute.Moments) }
                    DrawerRow(Icons.Filled.Settings, stringResource(TopLevelRoute.Settings.label), selectedTab == TopLevelRoute.Settings) { onSelectTab(TopLevelRoute.Settings) }
                    Spacer(Modifier.height(24.dp))
                }
                DrawerLabel(stringResource(Res.string.shell_drawer_section_apps))
                apps(open || pulling)
            }
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

/**
 * An app opened from the drawer ([content]: finance, weather, fitness, the atlas), over the whole shell, opening as
 * a circle that grows from [origin] (the drawer card that was tapped) to cover the screen, and
 * draining back toward the menu button when it closes. [onCovering] reports when it fully covers
 * the screen, so the shell can stop drawing — and stop streaming — the cameras underneath.
 *
 * Back that the app has no page of its own to take ([onClose]) is this overlay's, and predictive:
 * the swipe starts the drain under the finger, the shell showing through beneath, and letting go
 * finishes it from there. The app's own page stack registers after this, so it hears Back first
 * while it has somewhere to go back to.
 */
@Composable
fun InnerAppOverlay(
    open: Boolean,
    origin: Offset,
    onCovering: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reveal = remember { Animatable(0f) }
    val reportCovering by rememberUpdatedState(onCovering)
    var shown by remember { mutableStateOf(open) }
    // Where a committed back swipe left the drain, held until the close below takes it on from there.
    var heldBack by remember { mutableFloatStateOf(0f) }
    val back = rememberPredictiveBack(enabled = open) { releasedAt ->
        heldBack = releasedAt
        onClose()
    }

    // The reveal as drawn: drained part of the way by a back swipe.
    fun shownReveal() = reveal.value * (1f - APP_BACK_PEEK * maxOf(back.progress, heldBack))

    // A swipe has started the drain (or let go and is settling back, or committed and is closing).
    val peeking by remember(back) { derivedStateOf { back.inProgress || back.progress > 0f || heldBack > 0f } }
    LaunchedEffect(open) {
        if (open) {
            shown = true
            reveal.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
            // Covering, until a back swipe starts to drain it: the shell is drawn again to show through.
            snapshotFlow { peeking }.collect { reportCovering(!it) }
        } else {
            reportCovering(false)
            reveal.snapTo(shownReveal())
            heldBack = 0f
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
    // A back swipe drains toward the menu button too. Mid-swipe the switch is unseen: a circle at
    // full size covers the box from any centre.
    val center = if (open && origin != Offset.Zero && !peeking) origin - ownOrigin else menuPoint
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { ownOrigin = it.positionInRoot() }
            .graphicsLayer {
                clip = true
                // Rebuilt each frame: the layer re-runs as the reveal animates.
                val fraction = shownReveal()
                shape = circleReveal(center, fraction)
                val s = 0.94f + 0.06f * fraction
                scaleX = s
                scaleY = s
                alpha = (fraction * 2.5f).coerceAtMost(1f)
            },
    ) {
        content()
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
