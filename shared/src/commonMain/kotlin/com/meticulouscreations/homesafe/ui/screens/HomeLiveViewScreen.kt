package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.ConnectionProblem
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.components.ApertureRefreshBox
import com.meticulouscreations.homesafe.ui.components.BufferingDots
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.CaptureLastSeenStill
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.components.PinchGestureListener
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.components.ReportFullyDrawnWhen
import com.meticulouscreations.homesafe.ui.components.ShapedVideo
import com.meticulouscreations.homesafe.ui.components.SkeletonCameraCard
import com.meticulouscreations.homesafe.ui.components.SkeletonCardCornerRadius
import com.meticulouscreations.homesafe.ui.components.cropped
import com.meticulouscreations.homesafe.ui.components.pinchGestures
import com.meticulouscreations.homesafe.ui.components.rememberLoadingPhase
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import com.meticulouscreations.homesafe.viewmodel.ConnectionNoticeViewModel
import com.meticulouscreations.homesafe.viewmodel.HomeViewModel
import com.meticulouscreations.homesafe.viewmodel.InViewItem
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_frame_click_label
import homesafe.shared.generated.resources.camera_move_earlier
import homesafe.shared.generated.resources.camera_move_later
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.home_arrange
import homesafe.shared.generated.resources.home_arrange_done
import homesafe.shared.generated.resources.home_arrange_hint
import homesafe.shared.generated.resources.home_away_back
import homesafe.shared.generated.resources.home_away_banner
import homesafe.shared.generated.resources.home_badge_connecting
import homesafe.shared.generated.resources.home_badge_disabled
import homesafe.shared.generated.resources.home_badge_live
import homesafe.shared.generated.resources.home_connection_open_tailscale
import homesafe.shared.generated.resources.home_connection_retry
import homesafe.shared.generated.resources.home_connection_retrying
import homesafe.shared.generated.resources.home_connection_tailscale_off_body
import homesafe.shared.generated.resources.home_connection_tailscale_off_title
import homesafe.shared.generated.resources.home_connection_unreachable_body
import homesafe.shared.generated.resources.home_connection_unreachable_title
import homesafe.shared.generated.resources.home_in_view_check
import homesafe.shared.generated.resources.home_in_view_title
import homesafe.shared.generated.resources.home_no_cameras
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * The "Home" tab's content: the cars parked in view and the cameras reported by the connected
 * Frigate server — or, once switched over, the indoor (Nest) cameras. The outdoor ones come
 * first; the switch between them is there only when the server has indoor cameras at all.
 * [zoomState] is the quick-look layer a pinched or long-pressed card lifts its video into; the
 * shell draws it, over everything (see [CameraCardZoomOverlay]).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeTabContent(
    sharedTransitionScope: SharedTransitionScope,
    zoomState: CameraCardZoomState,
    onCameraClick: (CameraTile) -> Unit = {},
    onFrameCamera: (CameraTile) -> Unit = {},
    onTagCars: (cameraName: String) -> Unit = {},
    scrollToTopRequests: ScrollToTopRequests = ScrollToTopRequests.NONE,
) {
    val viewModel: HomeViewModel = metroViewModel()
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()
    val indoorCameras by viewModel.indoorCameras.collectAsStateWithLifecycle()
    val placement by viewModel.placement.collectAsStateWithLifecycle()
    val arranging by viewModel.arranging.collectAsStateWithLifecycle()
    val everyoneAway by viewModel.everyoneAway.collectAsStateWithLifecycle()
    val inView by viewModel.inView.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val reconnectRequests by viewModel.reconnectRequests.collectAsStateWithLifecycle()
    val connectionNotice: ConnectionNoticeViewModel = metroViewModel()
    val connectionProblem by connectionNotice.problem.collectAsStateWithLifecycle()
    val connectionRetrying by connectionNotice.retrying.collectAsStateWithLifecycle()

    // Time-to-fully-drawn: the home screen counts as drawn once the camera cache has answered.
    ReportFullyDrawnWhen { cameras != null }

    val hasIndoor = !indoorCameras.isNullOrEmpty()
    val shownPlacement = if (hasIndoor && placement == CameraPlacement.Indoor) CameraPlacement.Indoor else CameraPlacement.Outdoor
    val shown = if (shownPlacement == CameraPlacement.Indoor) indoorCameras else cameras
    HomeFeed(
        everyoneAway = everyoneAway,
        cameras = shown,
        placement = placement.takeIf { hasIndoor },
        onPlacementChange = viewModel::showPlacement,
        arranging = arranging,
        onArrangeToggle = if (arranging) viewModel::finishArranging else viewModel::startArranging,
        onMoveCamera = { from, to -> viewModel.moveCamera(shownPlacement, from, to) },
        onDrop = { viewModel.saveOrder(shownPlacement) },
        onAwayBack = viewModel::markBack,
        inView = inView,
        // A parked car's card opens the camera watching it — where its own card would have gone.
        onInViewClick = { item -> cameras?.firstOrNull { it.camera.name == item.subject.cameraName }?.let(onCameraClick) },
        // The camera the strip's first car is on: the frame to check the strip against.
        onInViewCheck = { inView.firstOrNull()?.subject?.cameraName?.let(onTagCars) },
        modifier = Modifier.testTag(HOME_FEED_TEST_TAG),
        scrollToTopRequests = scrollToTopRequests,
        refreshing = refreshing,
        onRefresh = viewModel::refresh,
        connectionProblem = connectionProblem,
        connectionRetrying = connectionRetrying,
        onConnectionRetry = connectionNotice::retry,
        onOpenTailscale = if (connectionNotice.canOpenTailscale) connectionNotice::openTailscale else null,
    ) { tile ->
        val name = tile.camera.name
        // The cameras either side, for a screen reader's "move earlier / later" (it can't drag).
        val names = shown.orEmpty().map { it.camera.name }
        val index = names.indexOf(name)
        val move = { neighbour: String ->
            viewModel.moveCamera(shownPlacement, name, neighbour)
            viewModel.saveOrder(shownPlacement)
        }
        CameraCard(
            tile = tile,
            sharedTransitionScope = sharedTransitionScope,
            zoomState = zoomState,
            onClick = { onCameraClick(tile) },
            reconnectRequests = reconnectRequests,
            arranging = arranging,
            onTurn = { clockwise -> viewModel.turnCamera(name, clockwise) },
            onFrame = { onFrameCamera(tile) },
            onMoveEarlier = names.getOrNull(index - 1)?.let { earlier -> { move(earlier) } },
            onMoveLater = names.getOrNull(index + 1)?.let { later -> { move(later) } },
        )
    }
}

/**
 * The home page's list: the cars in view and one card per camera — or, while [cameras] is still null
 * (the first cache read in flight), the loading skeleton: outlined cards where the cameras will
 * land, with a runner going round each. The sign-in screen draws this same list in its loading
 * state, so the skeleton and the real page are one layout by construction, and the real cards
 * fade in exactly onto their outlines rather than near them.
 *
 * At the top sits [inView] when there is anything in it: the vehicles standing in view of a
 * camera right now. It is deliberately the first thing on the page — the question it answers
 * ("is her car in the driveway?") is the one the app gets opened for — and it disappears entirely
 * when nothing is parked, rather than spending a row to say so.
 * It usually arrives a beat after the cameras (its cache is read, and its poll runs, apart from
 * theirs), so it fades in and the cards below slide down to make room rather than jumping by the
 * strip's whole height.
 *
 * With a [placement], the [CameraPlacementSwitch] heads the cameras, and [cameras] are the ones
 * of that placement; null leaves it out (a server with no indoor cameras, and the skeleton).
 *
 * Pulling the list down from the top refreshes the page — [onRefresh], with the band open while
 * [refreshing] (see [ApertureRefreshBox]); the band opens below the shell's floating top bar,
 * which the list scrolls under. Null leaves the list unpullable: the sign-in screen's skeleton.
 *
 * A lazy grid (not a plain scrolling Column) so off-screen camera cards aren't composed.
 * Their players (pooled per camera, see CameraStreamPlayer's playerKey) pause the moment a
 * card scrolls out and resume at the live edge when it scrolls back in, so only the cameras
 * actually on screen are decoding; with several 4K streams that concurrency was a real
 * contributor to stutter.
 *
 * The cameras take as many columns as [CameraGridCells] gives them: one on a phone held upright,
 * where this is a list; two on a phone on its side or a small tablet, three on a wide window. A
 * full-width 16:9 card in a landscape window would be taller than the window itself. Everything
 * above the cameras spans the full row.
 */
@Composable
internal fun HomeFeed(
    everyoneAway: Boolean,
    cameras: List<CameraTile>?,
    onAwayBack: () -> Unit,
    modifier: Modifier = Modifier,
    inView: List<InViewItem> = emptyList(),
    onInViewClick: (InViewItem) -> Unit = {},
    onInViewCheck: () -> Unit = {},
    scrollToTopRequests: ScrollToTopRequests = ScrollToTopRequests.NONE,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    connectionProblem: ConnectionProblem? = null,
    connectionRetrying: Boolean = false,
    onConnectionRetry: () -> Unit = {},
    onOpenTailscale: (() -> Unit)? = null,
    placement: CameraPlacement? = null,
    onPlacementChange: (CameraPlacement) -> Unit = {},
    arranging: Boolean = false,
    onArrangeToggle: (() -> Unit)? = null,
    onMoveCamera: (from: String, to: String) -> Unit = { _, _ -> },
    onDrop: () -> Unit = {},
    cameraCard: @Composable LazyGridItemScope.(CameraTile) -> Unit,
) {
    // The skeleton's frame clock runs only while there is a skeleton to drive.
    val loadingPhase = if (cameras == null) rememberLoadingPhase() else null
    val gridState = rememberLazyGridState()
    val compactLandscape = isCompactLandscape()
    val columns = remember(compactLandscape) { CameraGridCells(twoAbreastWhenTheyFit = compactLandscape) }
    LaunchedEffect(gridState, scrollToTopRequests) { scrollToTopRequests.collect { gridState.animateScrollToItem(0) } }
    // Only the camera cards are ReorderableItems, so only they are ever handed over here.
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromName = from.key as? String
        val toName = to.key as? String
        if (fromName != null && toName != null) onMoveCamera(fromName, toName)
    }

    PullToRefreshUnderTopBar(refreshing = refreshing, onRefresh = onRefresh) {
        LazyVerticalGrid(
            columns = columns,
            state = gridState,
            modifier = modifier.fillMaxSize(),
            contentPadding = tabContentPadding(maxWidth = HOME_GRID_MAX_WIDTH),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalArrangement = Arrangement.spacedBy(CAMERA_CARD_COLUMN_GAP),
        ) {
            // First on the page: with no server, everything below it is what the device kept.
            if (connectionProblem != null) {
                item(key = "connection-notice", span = { GridItemSpan(maxLineSpan) }) {
                    ConnectionProblemNotice(
                        problem = connectionProblem,
                        retrying = connectionRetrying,
                        onRetry = onConnectionRetry,
                        modifier = Modifier.animateItem(),
                        onOpenTailscale = onOpenTailscale,
                    )
                }
            }
            if (everyoneAway) {
                item(key = "away-banner", span = { GridItemSpan(maxLineSpan) }) { AwayBanner(onBack = onAwayBack) }
            }

            if (inView.isNotEmpty()) {
                item(key = "in-view", span = { GridItemSpan(maxLineSpan) }) {
                    InViewNowSection(items = inView, onClick = onInViewClick, onCheck = onInViewCheck, modifier = Modifier.animateItem())
                }
            }

            val canArrange = onArrangeToggle != null && !cameras.isNullOrEmpty()
            if (placement != null || canArrange) {
                item(key = "camera-header", span = { GridItemSpan(maxLineSpan) }) {
                    CamerasHeader(
                        placement = placement,
                        onPlacementChange = onPlacementChange,
                        arranging = arranging,
                        onArrangeToggle = onArrangeToggle.takeIf { canArrange },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            val loadedCameras = cameras
            if (loadedCameras == null) {
                items(SKELETON_CARD_COUNT, key = { "camera-skeleton-$it" }) { index ->
                    SkeletonCameraCard(
                        phase = { loadingPhase?.value ?: 0f },
                        phaseOffset = index / SKELETON_CARD_COUNT.toFloat(),
                        modifier = Modifier.animateItem(),
                    )
                }
            } else if (loadedCameras.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(Res.string.home_no_cameras),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(loadedCameras, key = { it.camera.name }) { tile ->
                    val itemScope = this
                    // Always a ReorderableItem, draggable only in Arrange mode, so entering it never
                    // recomposes the card into a different structure (and its player stays bound).
                    ReorderableItem(reorderState, key = tile.camera.name, enabled = arranging) {
                        Box(modifier = Modifier.longPressDraggableHandle(enabled = arranging, onDragStopped = onDrop)) {
                            itemScope.cameraCard(tile)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What heads the cameras: the Outdoor | Indoor switch when there is anything indoors, and the way
 * into and out of Arrange mode, with a line saying how it works while it is on.
 */
@Composable
private fun CamerasHeader(
    placement: CameraPlacement?,
    onPlacementChange: (CameraPlacement) -> Unit,
    arranging: Boolean,
    onArrangeToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (placement != null) CameraPlacementSwitch(selected = placement, onSelect = onPlacementChange)
            Spacer(modifier = Modifier.weight(1f))
            if (onArrangeToggle != null) {
                TextButton(onClick = onArrangeToggle, modifier = Modifier.testTag(HOME_ARRANGE_BUTTON_TEST_TAG)) {
                    Text(stringResource(if (arranging) Res.string.home_arrange_done else Res.string.home_arrange))
                }
            }
        }
        AnimatedVisibility(visible = arranging) {
            Text(
                text = stringResource(Res.string.home_arrange_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * [content] — a tab's list, scrolling under the shell's floating top bar — with pull to refresh
 * when there is an [onRefresh], and as it is when there isn't. The band opens just below the bar.
 */
@Composable
private fun PullToRefreshUnderTopBar(refreshing: Boolean, onRefresh: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onRefresh == null) {
        content()
    } else {
        ApertureRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize(), bandTop = shellTopBarClearance(), content = content)
    }
}

/**
 * "In view now": what is standing in the yard, as a strip of cards across the top of the home
 * page. Usually one or two cars, which is why this is a plain [Row] that scrolls sideways rather
 * than a lazy one — there is never enough here for laziness to pay for itself, and a Row measures
 * its children in one pass.
 *
 * It is headed by a small caption rather than a heading, so the cameras start as high up the
 * screen as they can; the caption and its info mark are [onCheck]: the camera's frame, to see whether the strip is right and put
 * it right by tagging the cars actually there (see [CarTaggingScreen]).
 */
@Composable
private fun InViewNowSection(items: List<InViewItem>, onClick: (InViewItem) -> Unit, onCheck: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        // A 48dp target, as the IconButton it replaced was, with the caption centred in it: the
        // space below the text is the gap to the cards. heightIn, not
        // minimumInteractiveComponentSize, so the button itself is 48dp tall and not just the room around it.
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = stringResource(Res.string.home_in_view_check), role = Role.Button, onClick = onCheck)
                .testTag(IN_VIEW_CHECK_TEST_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.home_in_view_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEach { item -> InViewCard(item = item, onClick = { onClick(item) }) }
        }
    }
}

/**
 * One parked vehicle: the crop Frigate cut of its most recent sighting, what the classifier calls
 * it, and where it is. The second line is where and since when ("Driveway · since 8:12 AM"); the
 * arrival is left off when the app only ever saw the car already parked, rather than guessing one.
 * A third line appears only once the sighting has gone stale, because a card that says nothing
 * about time is claiming the camera can see the car right now.
 */
@Composable
private fun InViewCard(item: InViewItem, onClick: () -> Unit) {
    val extraColors = LocalFrigateExtraColors.current
    val presentation = item.presentation
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .width(IN_VIEW_CARD_WIDTH)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            // Coil rides the app's authenticated Ktor client, which is what lets this hit Frigate's thumbnail endpoint.
            if (item.thumbnailUrl != null) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.DirectionsCar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = presentation.title.resolve(),
                style = MaterialTheme.typography.labelLarge,
                color = extraColors.textPrimary,
                maxLines = 1,
            )
            Text(
                text = listOfNotNull(presentation.placeLabel, presentation.sinceLabel?.resolve()).joinToString(stringResource(Res.string.common_dot_separator)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            presentation.lastSeenLabel?.let { lastSeen ->
                Text(
                    text = lastSeen.resolve(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One camera's card: its live video, framed as the camera has been ([CameraTile.orientation],
 * [CameraTile.crop]), with its name and status. A tap is [onClick] (the camera's own screen);
 * spreading two fingers on it, or holding one, lifts the video into [zoomState]'s quick-look
 * layer — the pinch, or the held finger's drag, continuing there without a lift of the fingers.
 *
 * In Arrange mode a tap is [onFrame] instead (the framing editor), and the card carries quick
 * quarter turns ([onTurn]) and the editor's button along its bottom.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CameraCard(
    tile: CameraTile,
    sharedTransitionScope: SharedTransitionScope,
    zoomState: CameraCardZoomState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reconnectRequests: Int = 0,
    arranging: Boolean = false,
    onTurn: (clockwise: Boolean) -> Unit = {},
    onFrame: () -> Unit = {},
    onMoveEarlier: (() -> Unit)? = null,
    onMoveLater: (() -> Unit)? = null,
) {
    val camera = tile.camera
    val extraColors = LocalFrigateExtraColors.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(CAMERA_CARD_CORNER_RADIUS)
    // What the badge says. Connecting until the player reports otherwise, so a card that has no
    // stream to play — or whose player hasn't got a frame up yet — never claims to be live.
    var streamStatus by remember(camera.name) { mutableStateOf(LiveStreamStatus.Connecting) }
    // Where the card is on screen, for the quick-look layer to lift its video from. A Ref, not
    // state: it moves on every scrolled frame and is only read inside gesture handlers.
    val coordinates = remember { Ref<LayoutCoordinates>() }
    // Where the finger last went down, card-local: the spot a long press zooms in on.
    val lastPress = remember { Ref<Offset>() }
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { if (it is PressInteraction.Press) lastPress.value = it.pressPosition }
    }
    val pinchListener = remember(tile, zoomState, haptic) {
        object : PinchGestureListener {
            // Fingers closing on a card mean nothing; only a spread is a look.
            override fun onPinchStarted(zoom: Float): Boolean {
                val bounds = coordinates.value?.boundsInRoot()
                if (zoom <= CARD_PINCH_OPEN_ZOOM_THRESHOLD || bounds == null) return false
                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                zoomState.openByPinch(tile, bounds)
                return true
            }

            override fun onPinch(zoomChange: Float, pan: Offset, centroid: Offset) {
                val coords = coordinates.value ?: return
                zoomState.pinchFromCard(zoomChange, pan, coords.localToRoot(centroid))
            }

            override fun onPinchEnded() = zoomState.pinchEnded()
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.surfaceContainerHigh),
                ),
            )
            .onGloballyPositioned { coordinates.value = it }
            .then(if (arranging) Modifier.arrangeActions(onMoveEarlier, onMoveLater) else Modifier)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = if (arranging) stringResource(Res.string.camera_frame_click_label) else null,
                // In Arrange mode a hold is the drag (see HomeFeed), and a tap frames the picture.
                onLongClick = if (arranging) {
                    null
                } else {
                    {
                        val bounds = coordinates.value?.boundsInRoot()
                        if (bounds != null) zoomState.openByLongPress(tile, bounds, lastPress.value ?: Offset(bounds.width / 2f, bounds.height / 2f))
                    }
                },
                onClick = if (arranging) onFrame else onClick,
            )
            .quickLookHeldDrag(zoomState, camera.name)
            .then(if (arranging) Modifier else Modifier.pinchGestures(pinchListener)),
    ) {
        // Only the video is the shared element. The card's chrome — border, title, status
        // badge — stays behind on the list while the video lifts out to the detail screen and
        // settles back into it, which is what makes the move read as one object travelling
        // rather than the whole screen being dragged along.
        Box(
            modifier = with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = cameraVideoSharedKey(camera.name)),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = CameraVideoBoundsTransform,
                    clipInOverlayDuringTransition = rememberCameraVideoOverlayClip(
                        animatedVisibilityScope = animatedVisibilityScope,
                        visibleRadius = CAMERA_CARD_CORNER_RADIUS,
                        hiddenRadius = 0.dp,
                    ),
                )
            }.fillMaxSize(),
        ) {
            val streamUrl = tile.streamUrl
            if (streamUrl == null) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                )
            } else if (zoomState.cardWithoutPlayer != camera.name) {
                // The player is keyed by camera name so the detail screen picks up this very
                // player (already decoding) when the card is tapped, and this card gets it back
                // — still warm — on the way out. The quick-look layer borrows it the same way,
                // and while it has it this card draws nothing (see CameraCardZoomState).
                val turnDegrees = tile.orientation.degrees
                ShapedVideo(
                    streamUrl = streamUrl,
                    fit = tile.videoFit,
                    modifier = Modifier.fillMaxSize().cropped(tile.crop),
                    turnDegrees = turnDegrees,
                ) { videoModifier, onStreamStats ->
                    CameraStreamPlayer(
                        streamUrl = streamUrl,
                        modifier = videoModifier,
                        posterUrl = tile.posterUrl,
                        webRtcSignalingUrl = tile.webRtcSignalingUrl,
                        playerKey = camera.name,
                        onStreamStatusChanged = { streamStatus = it },
                        reconnectRequests = reconnectRequests,
                        onStreamStats = onStreamStats,
                        turnDegrees = turnDegrees,
                        lastSeenPoster = tile.posterIsLastSeen,
                    )
                }
            }
            // The still a Nest card opens on next time, taken once its video is playing.
            CaptureLastSeenStill(stillUrl = tile.posterUrl.takeIf { tile.posterIsLastSeen }, live = streamStatus == LiveStreamStatus.Live)
        }

        // The name at one end and the badge at the other, on one line where both fit. On a
        // narrow card (two abreast on a small phone on its side) they don't, and the badge drops
        // to a line of its own rather than squeezing the name until it breaks mid-word.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = camera.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = extraColors.textPrimary,
                modifier = Modifier.padding(end = 12.dp),
            )
            StatusBadge(
                enabled = camera.enabled,
                status = streamStatus,
                textColor = extraColors.textPrimary,
                pillColor = extraColors.glassFill,
            )
        }

        if (arranging) {
            CameraArrangeControls(
                onTurn = onTurn,
                onFrame = onFrame,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Arrange mode's moves for a screen reader, which can't hold and drag: one place earlier or later
 * in the list, from the card's actions menu. Either is left out at the end it would run off.
 */
@Composable
private fun Modifier.arrangeActions(onMoveEarlier: (() -> Unit)?, onMoveLater: (() -> Unit)?): Modifier {
    val earlier = stringResource(Res.string.camera_move_earlier)
    val later = stringResource(Res.string.camera_move_later)
    return semantics {
        customActions = listOfNotNull(
            onMoveEarlier?.let { move -> CustomAccessibilityAction(earlier) { move().let { true } } },
            onMoveLater?.let { move -> CustomAccessibilityAction(later) { move().let { true } } },
        )
    }
}

/**
 * Away mode: nobody is home, so every person on any camera goes out loud to both phones. One
 * slim pill in the style of the camera cards, with the way back on it.
 */
@Composable
private fun AwayBanner(onBack: () -> Unit) {
    val extraColors = LocalFrigateExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulsingDot(color = MaterialTheme.colorScheme.primary, size = 8.dp)
        Text(
            text = stringResource(Res.string.home_away_banner),
            style = MaterialTheme.typography.labelMedium,
            color = extraColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack) { Text(stringResource(Res.string.home_away_back)) }
    }
}

/**
 * Why the cameras below aren't live: the app is signed in but can't reach the server. Names the
 * cause when the device knows it (Tailscale off here, which the server can do nothing about)
 * and offers one more try. Without it the page showed kept pictures and endless spinners, which
 * read as the server being down. With Tailscale the cause and [onOpenTailscale] given (the
 * platform can open it), a second button goes straight there.
 */
@Composable
internal fun ConnectionProblemNotice(
    problem: ConnectionProblem,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTailscale: (() -> Unit)? = null,
) {
    val (title, body) = when (problem) {
        ConnectionProblem.TailscaleOff -> Res.string.home_connection_tailscale_off_title to Res.string.home_connection_tailscale_off_body
        ConnectionProblem.ServerUnreachable -> Res.string.home_connection_unreachable_title to Res.string.home_connection_unreachable_body
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), shape)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
            .testTag(CONNECTION_NOTICE_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Text(text = stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        }
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (problem == ConnectionProblem.TailscaleOff && onOpenTailscale != null) {
                TextButton(onClick = onOpenTailscale) { Text(stringResource(Res.string.home_connection_open_tailscale)) }
            }
            TextButton(onClick = onRetry, enabled = !retrying) {
                Text(stringResource(if (retrying) Res.string.home_connection_retrying else Res.string.home_connection_retry))
            }
        }
    }
}

/** The notice at the top of Home while the server can't be reached, for tests to find. */
internal const val CONNECTION_NOTICE_TEST_TAG = "connection_notice"

/**
 * A card opens only once the fingers have spread by a deliberate amount, not on the tiny outward
 * wobble an otherwise inward pinch can produce while crossing touch slop on Android.
 */
private const val CARD_PINCH_OPEN_ZOOM_THRESHOLD = 1.1f

/** UiAutomator handle (`By.res`) for the home feed, used by the :baselineprofile journeys. */
const val HOME_FEED_TEST_TAG = "home_feed"

/** The Home page's Arrange / Done button, above the cameras. */
const val HOME_ARRANGE_BUTTON_TEST_TAG = "home_arrange_button"

/** The "In view now" caption, which opens the car-tagging screen. */
internal const val IN_VIEW_CHECK_TEST_TAG = "in_view_check"

/** Wide enough for "Ron and Judy's Mercedes" on one line, narrow enough that a second card shows it scrolls. */
private val IN_VIEW_CARD_WIDTH: Dp = 232.dp

/** Outlined placeholder cards shown until the camera cache answers — about a phone screen's worth. */
private const val SKELETON_CARD_COUNT = 3

/**
 * How many camera cards go side by side in the width the grid has: as many as fit at
 * [CAMERA_CARD_MIN_WIDTH], and never more than [CAMERA_GRID_MAX_COLUMNS].
 *
 * [twoAbreastWhenTheyFit] is a phone on its side, where one card per row is the wrong answer
 * even when a second doesn't fit at the usual width: the smallest phones are 640dp long, which
 * the side nav and the gutters bring under two cards' worth, and a single card that wide is
 * taller than the window. There two go side by side so long as each still has
 * [CAMERA_CARD_SIDE_BY_SIDE_MIN_WIDTH].
 *
 * The columns share the width equally, as [GridCells.Fixed] would share it.
 */
internal class CameraGridCells(private val twoAbreastWhenTheyFit: Boolean) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        fun fitting(minWidth: Dp): Int = (availableSize + spacing) / (minWidth.roundToPx() + spacing)
        val fit = fitting(CAMERA_CARD_MIN_WIDTH)
        val count = when {
            fit < 2 && twoAbreastWhenTheyFit && fitting(CAMERA_CARD_SIDE_BY_SIDE_MIN_WIDTH) >= 2 -> 2
            else -> fit.coerceIn(1, CAMERA_GRID_MAX_COLUMNS)
        }
        val shared = availableSize - spacing * (count - 1)
        // Whole pixels each; the first few columns take the remainder, one apiece.
        return List(count) { index -> shared / count + if (index < shared % count) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is CameraGridCells && other.twoAbreastWhenTheyFit == twoAbreastWhenTheyFit

    override fun hashCode(): Int = twoAbreastWhenTheyFit.hashCode()
}

/**
 * The narrowest a camera card gets before the grid gives up a column: wide enough that a phone
 * held upright (even a small one, 360dp less its gutters) shows one card per row, as it always has.
 */
private val CAMERA_CARD_MIN_WIDTH: Dp = 300.dp

/** The narrowest a card gets on a phone on its side, where two abreast matter more than their size. */
private val CAMERA_CARD_SIDE_BY_SIDE_MIN_WIDTH: Dp = 200.dp

/** Three generous cards across; a wider window centres the grid rather than growing a fourth column. */
private const val CAMERA_GRID_MAX_COLUMNS = 3

/** Between two cards side by side; tighter than the 24dp between rows, which also separates sections. */
private val CAMERA_CARD_COLUMN_GAP: Dp = 16.dp

/** The widest the grid's three columns get; past this it centres. */
private val HOME_GRID_MAX_WIDTH: Dp = 1280.dp

/** The camera cards' corner radius; the loading skeleton and the in-flight video clip use the same. */
internal val CAMERA_CARD_CORNER_RADIUS: Dp = SkeletonCardCornerRadius

/** The shared-element key for a camera's video area, matched between the grid card and the detail screen. */
internal fun cameraVideoSharedKey(cameraName: String): String = "camera-video-$cameraName"

/**
 * The video's flight between card and detail player: one beat, on the same easing as the
 * screen fade it travels over, rather than the default spring that settles a good while after
 * the screens have finished changing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val CameraVideoBoundsTransform = BoundsTransform { _, _ ->
    tween(NAV_TRANSITION_MS, easing = NavEnterEasing)
}

/**
 * Clips the in-flight video to corners that morph between the card's radius and the detail
 * player's square edges, so it neither pokes out of the rounded card at take-off nor snaps
 * from rounded to square on landing. [visibleRadius] is this side's own radius; [hiddenRadius]
 * the other side's. Whichever side draws the overlay animates the same way, so the result is the
 * same whether the video is on its way out or its way back.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberCameraVideoOverlayClip(
    animatedVisibilityScope: AnimatedVisibilityScope,
    visibleRadius: Dp,
    hiddenRadius: Dp,
): SharedTransitionScope.OverlayClip {
    val radius by animatedVisibilityScope.transition.animateDp(
        transitionSpec = { tween(NAV_TRANSITION_MS, easing = NavEnterEasing) },
        label = "camera-video-corner",
    ) { state -> if (state == EnterExitState.Visible) visibleRadius else hiddenRadius }
    return remember {
        object : SharedTransitionScope.OverlayClip {
            private val path = Path()
            override fun getClipPath(
                sharedContentState: SharedTransitionScope.SharedContentState,
                bounds: Rect,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Path {
                path.rewind()
                path.addRoundRect(RoundRect(bounds, CornerRadius(with(density) { radius.toPx() })))
                return path
            }
        }
    }
}

/**
 * The pill in the corner of a camera card, reporting what is actually on the card rather than
 * what the server has configured:
 *
 *  - the camera is turned off in Frigate — "Disabled", on a still dot;
 *  - its stream is up and playing — "Live", on a pulsing dot;
 *  - its stream is up but starved — still "Live" (the connection is good), with the pulse
 *    replaced by [BufferingDots] so the stalled picture is explained rather than just frozen;
 *  - nothing playing yet — "Connecting", also on [BufferingDots]. Never "Live": the word is
 *    reserved for a stream that has actually put a frame on screen.
 */
@Composable
internal fun StatusBadge(
    enabled: Boolean,
    status: LiveStreamStatus,
    textColor: Color,
    pillColor: Color,
) {
    val label = stringResource(statusBadgeLabel(enabled = enabled, status = status))
    val statusColor = when {
        !enabled -> MaterialTheme.colorScheme.error
        status == LiveStreamStatus.Live -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .background(pillColor, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // A disabled camera is a settled state, so it keeps a still dot; anything the player is
        // still working towards gets the three dots.
        if (enabled && status != LiveStreamStatus.Live) {
            BufferingDots(color = statusColor, dotSize = 5.dp)
        } else {
            PulsingDot(color = statusColor, size = 6.dp, pulsing = enabled)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

/** The badge's word for a camera in this state. Separated out so it can be asserted directly. */
internal fun statusBadgeLabel(enabled: Boolean, status: LiveStreamStatus): StringResource = when {
    !enabled -> Res.string.home_badge_disabled

    status == LiveStreamStatus.Connecting -> Res.string.home_badge_connecting

    // Buffering is a stream that *is* up and has merely run dry; the dots carry that, not the word.
    else -> Res.string.home_badge_live
}
