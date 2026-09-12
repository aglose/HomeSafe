package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import com.meticulouscreations.homesafe.ui.components.PropertyPlan
import com.meticulouscreations.homesafe.ui.components.PropertyPlanColors
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.components.ReportFullyDrawnWhen
import com.meticulouscreations.homesafe.ui.components.drawPropertyPlan
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import com.meticulouscreations.homesafe.viewmodel.MappedCamera
import com.meticulouscreations.homesafe.viewmodel.PropertyMapViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * The Home tab's map layout: a bird's-eye plan of the property with each camera playing where it
 * actually stands. Tapping one opens the same full-screen camera screen the list layout opens,
 * with the video carried across as a shared element.
 *
 * A camera is only on the plan once it has been put somewhere — until then it waits in the
 * arrange tray. That is deliberate: a marker in the wrong place is worse than no marker, because
 * the whole point of this layout is that position means something.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PropertyMapTabContent(
    sharedTransitionScope: SharedTransitionScope,
    onCameraClick: (CameraTile) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    val viewModel: PropertyMapViewModel = metroViewModel()
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()

    ReportFullyDrawnWhen { cameras != null }

    // Arranging is a mode, not a persisted setting: it ends with the screen.
    var arranging by remember { mutableStateOf(false) }
    // In arrange mode, the tray camera waiting for a spot on the plan. Cleared once placed.
    var pendingCamera by remember { mutableStateOf<String?>(null) }

    val loaded = cameras.orEmpty()
    val placed = loaded.filter { it.placement != null }
    val unplaced = loaded.filter { it.placement == null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(tabContentPadding())
            .testTag(PROPERTY_MAP_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) { header() }

        PropertyMapPanel(
            cameras = placed,
            arranging = arranging,
            sharedTransitionScope = sharedTransitionScope,
            onCameraClick = onCameraClick,
            onMove = viewModel::place,
            onRemove = viewModel::removePlacement,
            onTapEmpty = { x, y ->
                pendingCamera?.let { name ->
                    viewModel.place(name, x, y)
                    pendingCamera = null
                }
            },
            modifier = Modifier.weight(1f),
        )

        ArrangeBar(
            arranging = arranging,
            unplacedCount = unplaced.size,
            pendingCamera = pendingCamera?.let { name -> loaded.firstOrNull { it.tile.camera.name == name }?.tile?.camera?.displayName },
            onToggleArranging = {
                arranging = !arranging
                pendingCamera = null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = arranging && unplaced.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            CameraTray(
                cameras = unplaced,
                selected = pendingCamera,
                onSelect = { name -> pendingCamera = if (pendingCamera == name) null else name },
            )
        }

        if (cameras != null && loaded.isEmpty()) {
            Text(
                text = "No cameras found on this server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The plan itself, at its true aspect ratio, with a marker for every placed camera.
 *
 * The panel is the coordinate system for everything on it: a placement's `x`/`y` are fractions
 * of this box, so a marker lands in the same place on a phone, a tablet and the desktop window.
 * Markers are clamped so that a camera dropped at the very edge stays fully on the plan rather
 * than being cut in half by it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PropertyMapPanel(
    cameras: List<MappedCamera>,
    arranging: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    onCameraClick: (CameraTile) -> Unit,
    onMove: (cameraName: String, x: Float, y: Float) -> Unit,
    onRemove: (cameraName: String) -> Unit,
    onTapEmpty: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CAMERA_CARD_CORNER_RADIUS)
    val planColors = propertyPlanColors()

    // The plan is a tall, narrow lot, so the panel takes the height it is offered and works its
    // width back from that (matchHeightConstraintsFirst) — filling the width instead would make
    // a plan far taller than any phone screen.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .aspectRatio(PropertyPlan.size.width / PropertyPlan.size.height, matchHeightConstraintsFirst = true)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), shape),
    ) {
        val panelWidth = maxWidth
        val panelHeight = maxHeight

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(arranging) {
                    if (!arranging) return@pointerInput
                    detectTapGestures { offset ->
                        onTapEmpty(offset.x / size.width, offset.y / size.height)
                    }
                },
        ) {
            drawPropertyPlan(planColors)
        }

        // Sits inside a box exactly as tall as the asphalt, so the label is centred on the road
        // rather than straddling the kerb whatever size the panel ends up.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(PropertyPlan.road.height / PropertyPlan.size.height),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CENTRAL AVE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.18.em),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }

        cameras.forEach { mapped ->
            key(mapped.tile.camera.name) {
                CameraMarker(
                    mapped = mapped,
                    arranging = arranging,
                    panelWidth = panelWidth,
                    panelHeight = panelHeight,
                    sharedTransitionScope = sharedTransitionScope,
                    onClick = { onCameraClick(mapped.tile) },
                    onMove = onMove,
                    onRemove = { onRemove(mapped.tile.camera.name) },
                )
            }
        }
    }
}

/**
 * One camera on the plan: a small live picture where the camera is, with its name under it.
 *
 * While arranging, the marker is dragged rather than tapped, and the drag is tracked locally so
 * the picture follows the finger at frame rate; only the final position is written back, which
 * keeps the database out of the gesture. The offset is applied in a lambda modifier so a drag
 * re-lays-out the marker without recomposing it — the player inside must not be torn down and
 * rebuilt sixty times a second.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CameraMarker(
    mapped: MappedCamera,
    arranging: Boolean,
    panelWidth: Dp,
    panelHeight: Dp,
    sharedTransitionScope: SharedTransitionScope,
    onClick: () -> Unit,
    onMove: (cameraName: String, x: Float, y: Float) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val camera = mapped.tile.camera
    val placement = mapped.placement ?: return
    val extraColors = LocalFrigateExtraColors.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val shape = RoundedCornerShape(MARKER_CORNER_RADIUS)

    // Where the finger has dragged this marker to, as fractions, while a drag is in flight.
    var dragged by remember(camera.name) { mutableStateOf<Offset?>(null) }
    val position = dragged ?: Offset(placement.x, placement.y)

    Column(
        modifier = modifier
            .offset {
                // Fractions are of the whole panel. The placement point is the centre of the
                // picture — not of the picture-plus-label — so a marker dropped on the porch has
                // its video on the porch. Clamping is on the whole marker, label included, so
                // nothing dropped at an edge hangs off the plan.
                val maxX = (panelWidth - MARKER_WIDTH).toPx().coerceAtLeast(0f)
                val maxY = (panelHeight - MARKER_HEIGHT).toPx().coerceAtLeast(0f)
                IntOffset(
                    x = (position.x * panelWidth.toPx() - MARKER_WIDTH.toPx() / 2f).coerceIn(0f, maxX).toInt(),
                    y = (position.y * panelHeight.toPx() - MARKER_VIDEO_HEIGHT.toPx() / 2f).coerceIn(0f, maxY).toInt(),
                )
            }
            .width(MARKER_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(MARKER_WIDTH, MARKER_VIDEO_HEIGHT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = if (arranging) 2.dp else 1.5.dp,
                    color = if (arranging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.35f)
                    },
                    shape = shape,
                )
                .pointerInput(arranging, camera.name) {
                    if (arranging) {
                        detectDragGestures(
                            onDragStart = { dragged = position },
                            onDragEnd = {
                                dragged?.let { onMove(camera.name, it.x, it.y) }
                                dragged = null
                            },
                            onDragCancel = { dragged = null },
                        ) { change, amount ->
                            change.consume()
                            val current = dragged ?: position
                            dragged = Offset(
                                x = (current.x + amount.x / panelWidth.toPx()).coerceIn(0f, 1f),
                                y = (current.y + amount.y / panelHeight.toPx()).coerceIn(0f, 1f),
                            )
                        }
                    } else {
                        detectTapGestures { onClick() }
                    }
                },
        ) {
            MarkerVideo(
                tile = mapped.tile,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )

            if (arranging) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Take ${camera.displayName} off the plan",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-7).dp, y = (-7).dp)
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .clickable(onClick = onRemove)
                        .padding(4.dp),
                )
            }
        }

        // The name is allowed to be wider than the picture it sits under — "Front Door" does not
        // fit in 52dp, and a marker whose label reads "Front" is worse than one that overhangs.
        Text(
            text = camera.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = extraColors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .padding(top = 3.dp)
                .wrapContentWidth(unbounded = true)
                .background(extraColors.glassFill, CircleShape)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * The picture inside a marker. Keyed by camera name like the list's cards are, so it binds to the
 * very same pooled player — the stream a marker is showing is the stream the full-screen view
 * picks up, already decoding, and the video flies between the two rather than restarting.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MarkerVideo(
    tile: CameraTile,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    var status by remember(tile.camera.name) { mutableStateOf(LiveStreamStatus.Connecting) }
    Box(
        modifier = modifier.then(
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = cameraVideoSharedKey(tile.camera.name)),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = CameraVideoBoundsTransform,
                    clipInOverlayDuringTransition = rememberCameraVideoOverlayClip(
                        animatedVisibilityScope = animatedVisibilityScope,
                        visibleRadius = MARKER_CORNER_RADIUS,
                        hiddenRadius = 0.dp,
                    ),
                )
            },
        ).fillMaxSize(),
    ) {
        val streamUrl = tile.streamUrl
        if (streamUrl != null) {
            CameraStreamPlayer(
                streamUrl = streamUrl,
                modifier = Modifier.fillMaxSize(),
                posterUrl = tile.posterUrl,
                webRtcSignalingUrl = tile.webRtcSignalingUrl,
                playerKey = tile.camera.name,
                onStreamStatusChanged = { status = it },
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center).size(18.dp),
            )
        }
        // Too small for the list's wordy badge — a live camera gets a dot in the corner and
        // nothing else, and one that isn't live gets no dot rather than a word nobody can read.
        if (tile.camera.enabled && status == LiveStreamStatus.Live) {
            PulsingDot(
                color = MaterialTheme.colorScheme.secondary,
                size = 5.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            )
        }
    }
}

/** The row under the plan: what arranging is for, and the way in and out of it. */
@Composable
private fun ArrangeBar(
    arranging: Boolean,
    unplacedCount: Int,
    pendingCamera: String?,
    onToggleArranging: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extraColors = LocalFrigateExtraColors.current
    val message = when {
        pendingCamera != null -> "Tap the plan where $pendingCamera is"
        arranging -> "Drag a camera to move it, or pick one below"
        unplacedCount > 0 -> "$unplacedCount camera${if (unplacedCount == 1) "" else "s"} still to place"
        else -> "Tap a camera to open it full screen"
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = if (pendingCamera != null) MaterialTheme.colorScheme.primary else extraColors.textPrimary.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
        )
        TextButton(onClick = onToggleArranging) {
            Icon(
                imageVector = if (arranging) Icons.Filled.Check else Icons.Filled.OpenWith,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(text = if (arranging) "Done" else "Arrange", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * The cameras with nowhere to be yet. Tapping one arms it for the next tap on the plan.
 *
 * One row that scrolls sideways rather than a wrapping grid: the tray's height then never
 * changes as cameras are used up, so the plan above it — which takes whatever height is left —
 * stays exactly where it is between placements instead of growing a row at a time.
 */
@Composable
private fun CameraTray(
    cameras: List<MappedCamera>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extraColors = LocalFrigateExtraColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cameras.forEach { mapped ->
            val isSelected = selected == mapped.tile.camera.name
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else extraColors.glassFill)
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(mapped.tile.camera.name) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = mapped.tile.camera.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else extraColors.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The plan's palette. Every surface is an opaque theme role rather than a tinted overlay: the
 * app's scheme is very dark and near-black-on-near-black would leave the property indistinct
 * from the ground around it. Read from lightest to darkest the plan is roof, pavement, drive,
 * lawn, road, neighbour, ground — which is roughly how an aerial photograph of the block reads
 * on a bright day, and gives every camera marker something to sit against.
 */
@Composable
private fun propertyPlanColors(): PropertyPlanColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        PropertyPlanColors(
            ground = scheme.surfaceDim,
            road = scheme.surfaceContainerHigh,
            roadMarking = scheme.outline.copy(alpha = 0.35f),
            pavement = scheme.surfaceBright,
            // The theme's sage green container: the one role that reads as planting rather than concrete.
            lawn = scheme.secondaryContainer,
            hardstanding = scheme.surfaceContainerHighest,
            building = scheme.onTertiaryFixedVariant,
            buildingOutline = scheme.primary.copy(alpha = 0.75f),
            neighbour = scheme.surfaceContainer,
            neighbourOutline = scheme.outline.copy(alpha = 0.3f),
            foliage = scheme.onSecondaryContainer.copy(alpha = 0.5f),
            lotLine = scheme.outline.copy(alpha = 0.6f),
        )
    }
}

/** UiAutomator handle for the property map layout. */
const val PROPERTY_MAP_TEST_TAG = "property_map"

/** A marker's live picture, and the width its name is centred under. */
private val MARKER_WIDTH: Dp = 52.dp
private val MARKER_VIDEO_HEIGHT: Dp = 30.dp

/** Picture plus the name pill under it — what the marker occupies, and what it is clamped by. */
private val MARKER_HEIGHT: Dp = 50.dp
private val MARKER_CORNER_RADIUS: Dp = 8.dp
