package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import com.meticulouscreations.homesafe.ui.components.BufferingDots
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.components.ReportFullyDrawnWhen
import com.meticulouscreations.homesafe.ui.components.SkeletonCameraCard
import com.meticulouscreations.homesafe.ui.components.SkeletonCardCornerRadius
import com.meticulouscreations.homesafe.ui.components.rememberLoadingPhase
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import com.meticulouscreations.homesafe.viewmodel.HomeViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The "Home" tab's content, in whichever layout the user last chose: the stacked list of camera
 * cards, or the property plan with each camera playing where it stands. Both open the same
 * full-screen camera screen, and both hand the video over as a shared element on the way.
 *
 * While the stored choice is still being read the list's skeleton stands in, since that is also
 * what the sign-in screen has just been showing — see [ShellSkeleton].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeTabContent(
    sharedTransitionScope: SharedTransitionScope,
    onCameraClick: (CameraTile) -> Unit = {},
) {
    val viewModel: HomeViewModel = metroViewModel()
    val layout by viewModel.layout.collectAsStateWithLifecycle()

    if (layout == HomeLayout.MAP) {
        PropertyMapTabContent(
            sharedTransitionScope = sharedTransitionScope,
            onCameraClick = onCameraClick,
            header = { HomeHeader(selected = HomeLayout.MAP, onSelect = viewModel::setLayout) },
        )
    } else {
        HomeListLayout(
            viewModel = viewModel,
            sharedTransitionScope = sharedTransitionScope,
            onCameraClick = onCameraClick,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeListLayout(
    viewModel: HomeViewModel,
    sharedTransitionScope: SharedTransitionScope,
    onCameraClick: (CameraTile) -> Unit,
) {
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()
    val everyoneAway by viewModel.everyoneAway.collectAsStateWithLifecycle()

    // Time-to-fully-drawn: the home screen counts as drawn once the camera cache has answered.
    ReportFullyDrawnWhen { cameras != null }

    HomeFeed(
        everyoneAway = everyoneAway,
        cameras = cameras,
        onAwayBack = viewModel::markBack,
        modifier = Modifier.testTag(HOME_FEED_TEST_TAG),
        header = { HomeHeader(selected = HomeLayout.LIST, onSelect = viewModel::setLayout) },
    ) { tile ->
        CameraCard(
            tile = tile,
            sharedTransitionScope = sharedTransitionScope,
            modifier = Modifier.animateItem().clickable { onCameraClick(tile) },
        )
    }
}

/**
 * The greeting, and the switch between the two Home layouts. Shared by both so the row stays
 * put when the layout under it changes — only the content below the header moves.
 */
@Composable
internal fun HomeHeader(
    selected: HomeLayout,
    onSelect: (HomeLayout) -> Unit,
) {
    val extraColors = LocalFrigateExtraColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Fixed for the life of this screen: a greeting that flips mid-scroll would be odd.
        val greeting = remember { greetingForHour(currentLocalHour()) }
        Text(
            text = greeting,
            style = MaterialTheme.typography.displayLarge,
            color = extraColors.textPrimary,
            modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
        )
        Row(
            modifier = Modifier
                .background(extraColors.glassFill, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LayoutSwitchButton(
                icon = Icons.Filled.ViewAgenda,
                label = "Camera list",
                isSelected = selected == HomeLayout.LIST,
                onClick = { onSelect(HomeLayout.LIST) },
            )
            LayoutSwitchButton(
                icon = Icons.Filled.Map,
                label = "Property map",
                isSelected = selected == HomeLayout.MAP,
                onClick = { onSelect(HomeLayout.MAP) },
            )
        }
    }
}

@Composable
private fun LayoutSwitchButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * The home page's list: the greeting and one card per camera — or, while [cameras] is still null
 * (the first cache read in flight), the loading skeleton: outlined cards where the cameras will
 * land, with a runner going round each. The sign-in screen draws this same list in its loading
 * state, so the skeleton and the real page are one layout by construction, and the real cards
 * fade in exactly onto their outlines rather than near them.
 *
 * A LazyColumn (not a plain scrolling Column) so off-screen camera cards aren't composed.
 * Their players (pooled per camera, see CameraStreamPlayer's playerKey) pause the moment a
 * card scrolls out and resume at the live edge when it scrolls back in, so only the cameras
 * actually on screen are decoding; with several 4K streams that concurrency was a real
 * contributor to stutter.
 */
@Composable
internal fun HomeFeed(
    everyoneAway: Boolean,
    cameras: List<CameraTile>?,
    onAwayBack: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = { HomeHeader(selected = HomeLayout.LIST, onSelect = {}) },
    cameraCard: @Composable LazyItemScope.(CameraTile) -> Unit,
) {
    // The skeleton's frame clock runs only while there is a skeleton to drive.
    val loadingPhase = if (cameras == null) rememberLoadingPhase() else null

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = tabContentPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (everyoneAway) {
            item(key = "away-banner") { AwayBanner(onBack = onAwayBack) }
        }

        item(key = "header") { header() }

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
            item {
                Text(
                    text = "No cameras found on this server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(loadedCameras, key = { it.camera.name }) { tile -> cameraCard(tile) }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CameraCard(
    tile: CameraTile,
    sharedTransitionScope: SharedTransitionScope,
    modifier: Modifier = Modifier,
) {
    val camera = tile.camera
    val extraColors = LocalFrigateExtraColors.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val shape = RoundedCornerShape(CAMERA_CARD_CORNER_RADIUS)
    // What the badge says. Connecting until the player reports otherwise, so a card that has no
    // stream to play — or whose player hasn't got a frame up yet — never claims to be live.
    var streamStatus by remember(camera.name) { mutableStateOf(LiveStreamStatus.Connecting) }
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
            ),
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
            if (streamUrl != null) {
                // The player is keyed by camera name so the detail screen picks up this very
                // player (already decoding) when the card is tapped, and this card gets it back
                // — still warm — on the way out.
                CameraStreamPlayer(
                    streamUrl = streamUrl,
                    modifier = Modifier.fillMaxSize(),
                    posterUrl = tile.posterUrl,
                    playerKey = camera.name,
                    onStreamStatusChanged = { streamStatus = it },
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = camera.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = extraColors.textPrimary,
                modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
            )
            StatusBadge(
                enabled = camera.enabled,
                status = streamStatus,
                textColor = extraColors.textPrimary,
                pillColor = extraColors.glassFill,
            )
        }
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
            text = "Away mode · nobody home · alerts escalated",
            style = MaterialTheme.typography.labelMedium,
            color = extraColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack) { Text("I'm back") }
    }
}

/** "Good Morning" / "Good Afternoon" / "Good Evening" for a 0–23 hour. */
internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    else -> "Good Evening"
}

@OptIn(ExperimentalTime::class)
private fun currentLocalHour(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

/** UiAutomator handle (`By.res`) for the home feed, used by the :baselineprofile journeys. */
const val HOME_FEED_TEST_TAG = "home_feed"

/** Outlined placeholder cards shown until the camera cache answers — about a phone screen's worth. */
private const val SKELETON_CARD_COUNT = 3

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
    val label = statusBadgeLabel(enabled = enabled, status = status)
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
internal fun statusBadgeLabel(enabled: Boolean, status: LiveStreamStatus): String = when {
    !enabled -> "Disabled"

    status == LiveStreamStatus.Connecting -> "Connecting"

    // Buffering is a stream that *is* up and has merely run dry; the dots carry that, not the word.
    else -> "Live"
}
