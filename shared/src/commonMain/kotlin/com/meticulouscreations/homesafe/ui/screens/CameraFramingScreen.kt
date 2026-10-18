package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.CameraCrop
import com.meticulouscreations.homesafe.domain.model.CameraFraming
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import com.meticulouscreations.homesafe.domain.model.VideoFit
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.ui.VIDEO_ASPECT
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.MeasuredVideoShapes
import com.meticulouscreations.homesafe.ui.components.ShapedVideo
import com.meticulouscreations.homesafe.ui.components.isOnItsSide
import com.meticulouscreations.homesafe.ui.components.pictureBoxSize
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.viewmodel.CameraFramingViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_frame_done
import homesafe.shared.generated.resources.camera_frame_hint
import homesafe.shared.generated.resources.camera_frame_level
import homesafe.shared.generated.resources.camera_frame_nudge_left
import homesafe.shared.generated.resources.camera_frame_nudge_right
import homesafe.shared.generated.resources.camera_frame_reset
import homesafe.shared.generated.resources.camera_frame_straighten
import homesafe.shared.generated.resources.camera_frame_straighten_value
import homesafe.shared.generated.resources.camera_frame_title
import homesafe.shared.generated.resources.camera_frame_zoom
import homesafe.shared.generated.resources.camera_frame_zoom_value
import homesafe.shared.generated.resources.camera_turn_left
import homesafe.shared.generated.resources.camera_turn_right
import homesafe.shared.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Frames one camera's picture: turned the right way up, straightened, zoomed and cropped, for
 * wherever its video plays (cards, the quick look, its own page).
 *
 * The middle of the screen is the frame, the camera card's own shape, with the live picture in
 * it as the card will show it. Zoomed in, what the frame cuts off still shows around it, dimmed,
 * so the crop is chosen against the whole view. A finger drags the picture, two pinch it larger
 * or smaller, and a twist straightens it; the grid over the frame is there to line a horizon or a
 * door frame up against. Below, the straighten ruler (a tenth of a degree at a time, the arrows a
 * tenth per tap), the zoom, the quarter turns and Reset do the same with more precision, and for a
 * screen reader.
 *
 * Every change is saved as it is let go ([CameraFramingViewModel]), so Done, Back and leaving any
 * other way all keep it; Cancel puts back the framing the editor opened on.
 *
 * The player is the card's own pooled one ([cameraName] is its key), so the picture carries on
 * from the card rather than reconnecting, and the frame is the card's video travelling here
 * (the shared element). [streamUrl] and the rest are what the card was playing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CameraFramingScreen(
    cameraName: String,
    streamUrl: String?,
    webRtcSignalingUrl: String?,
    posterUrl: String?,
    posterIsLastSeen: Boolean,
    videoFit: VideoFit?,
    sharedTransitionScope: SharedTransitionScope,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = assistedMetroViewModel<CameraFramingViewModel, CameraFramingViewModel.Factory>(key = cameraName) { create(cameraName) }
    val framing by viewModel.framing.collectAsStateWithLifecycle()
    CameraFramingEditor(
        cameraName = cameraName,
        streamUrl = streamUrl,
        webRtcSignalingUrl = webRtcSignalingUrl,
        posterUrl = posterUrl,
        posterIsLastSeen = posterIsLastSeen,
        videoFit = videoFit,
        framing = framing ?: CameraFraming.UNTOUCHED,
        latestFraming = { viewModel.framing.value ?: CameraFraming.UNTOUCHED },
        sharedTransitionScope = sharedTransitionScope,
        onChange = viewModel::change,
        onSave = viewModel::save,
        onSet = viewModel::set,
        onCancel = {
            viewModel.cancel()
            onDone()
        },
        onDone = onDone,
        modifier = modifier,
    )
}

/**
 * The editor as [CameraFramingScreen] draws it, from [framing]: [onChange] while a finger moves
 * it, [onSave] when it lets go, [onSet] for a button. [latestFraming] is the newest framing, for
 * gestures that land between two frames. [straighteningAtFirst] draws the straightening grid
 * from the start (a preview's).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun CameraFramingEditor(
    cameraName: String,
    streamUrl: String?,
    webRtcSignalingUrl: String?,
    posterUrl: String?,
    posterIsLastSeen: Boolean,
    videoFit: VideoFit?,
    framing: CameraFraming,
    latestFraming: () -> CameraFraming,
    sharedTransitionScope: SharedTransitionScope,
    onChange: (CameraFraming) -> Unit,
    onSave: () -> Unit,
    onSet: (CameraFraming) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    straighteningAtFirst: Boolean = false,
) {
    val shown = framing
    // Whether the angle is being worked on right now: the grid fills in to level it against.
    var straightening by remember { mutableStateOf(straighteningAtFirst) }
    // How far the card-shaped frame reaches across the picture (see CameraCrop): a 4:3 Nest
    // picture shown whole has bars beside it, and the crop is in fractions of the picture.
    val aspect = streamUrl?.let { MeasuredVideoShapes[it] }.takeIf { videoFit != null }
    val onItsSide = isOnItsSide(framing.orientation.degrees)
    val spans = remember(aspect, videoFit, onItsSide) { frameSpans(aspect, videoFit, onItsSide) }

    val stage = @Composable { stageModifier: Modifier ->
        FramingStage(
            cameraName = cameraName,
            streamUrl = streamUrl,
            webRtcSignalingUrl = webRtcSignalingUrl,
            posterUrl = posterUrl,
            posterIsLastSeen = posterIsLastSeen,
            videoFit = videoFit,
            framing = shown,
            latestFraming = latestFraming,
            spans = spans,
            straightening = straightening,
            sharedTransitionScope = sharedTransitionScope,
            onChange = { changed, tilting ->
                straightening = tilting
                onChange(changed)
            },
            onLetGo = {
                straightening = false
                onSave()
            },
            modifier = stageModifier,
        )
    }
    val controls = @Composable { controlsModifier: Modifier ->
        FramingControls(
            framing = shown,
            spans = spans,
            onChange = { changed, tilting ->
                straightening = tilting
                onChange(changed)
            },
            onLetGo = {
                straightening = false
                onSave()
            },
            onSet = onSet,
            modifier = controlsModifier,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) { Text(stringResource(Res.string.common_cancel)) }
            Text(
                text = stringResource(Res.string.camera_frame_title, cameraDisplayName(cameraName)),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDone,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag(CAMERA_FRAME_DONE_TEST_TAG),
            ) { Text(stringResource(Res.string.camera_frame_done)) }
        }
        if (isCompactLandscape()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                stage(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width(CONTROLS_SIDE_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
        } else {
            stage(Modifier.weight(1f).fillMaxWidth())
            controls(Modifier.fillMaxWidth())
        }
    }
}

/**
 * The frame and the picture in it, and the gestures that move the picture: one finger drags it,
 * two pinch and twist it. [onChange] is told whether the change straightens (a twist), so the
 * grid can fill in while it does. [latestFraming] is the framing as of the last change, for
 * gestures to build on; [framing] is what to draw.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FramingStage(
    cameraName: String,
    streamUrl: String?,
    webRtcSignalingUrl: String?,
    posterUrl: String?,
    posterIsLastSeen: Boolean,
    videoFit: VideoFit?,
    framing: CameraFraming,
    latestFraming: () -> CameraFraming,
    spans: FrameSpans,
    straightening: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    onChange: (framing: CameraFraming, tilting: Boolean) -> Unit,
    onLetGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    // The frame within the stage, for the scrim's hole and for turning finger movements into
    // fractions of the frame. Set by layout; read in drawing and gestures.
    var frame by remember { mutableStateOf(Rect.Zero) }
    val current by rememberUpdatedState(framing)
    val latest by rememberUpdatedState(latestFraming)
    val currentSpans by rememberUpdatedState(spans)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnLetGo by rememberUpdatedState(onLetGo)
    val turnDegrees = framing.orientation.degrees

    Box(
        modifier = modifier
            .clipToBounds()
            .framingGestures(
                onTransform = { centroid, pan, zoom, rotation ->
                    val bounds = frame
                    if (bounds.width <= 0f || bounds.height <= 0f) return@framingGestures
                    // The newest framing, not the one last composed: two moves can land in one frame.
                    val before = latest()
                    val crop = before.crop.transformed(
                        factor = zoom,
                        focusX = (centroid.x - bounds.left) / bounds.width,
                        focusY = (centroid.y - bounds.top) / bounds.height,
                        panX = pan.x / bounds.width,
                        panY = pan.y / bounds.height,
                        spanX = currentSpans.x,
                        spanY = currentSpans.y,
                    )
                    val orientation = if (rotation == 0f) before.orientation else before.orientation.tilted(before.orientation.tiltDegrees + rotation)
                    // A click as the picture comes level, so level can be found without looking.
                    if (rotation != 0f && crossesLevel(before.orientation.tiltDegrees, orientation.tiltDegrees)) {
                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    }
                    currentOnChange(CameraFraming(orientation, crop), rotation != 0f)
                },
                onEnd = { currentOnLetGo() },
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(FRAME_MARGIN)
                .aspectRatio(VIDEO_ASPECT)
                .onPlaced { frame = it.boundsInParent() }
                .then(
                    with(sharedTransitionScope) {
                        // The card's video, travelling here and back.
                        Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = cameraVideoSharedKey(cameraName)),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = CameraVideoBoundsTransform,
                            clipInOverlayDuringTransition = rememberCameraVideoOverlayClip(
                                animatedVisibilityScope = animatedVisibilityScope,
                                visibleRadius = CAMERA_CARD_CORNER_RADIUS,
                                hiddenRadius = CAMERA_CARD_CORNER_RADIUS,
                            ),
                        )
                    },
                ),
        ) {
            if (streamUrl != null) {
                ShapedVideo(
                    streamUrl = streamUrl,
                    fit = videoFit,
                    modifier = Modifier.fillMaxSize(),
                    turnDegrees = turnDegrees,
                    crop = { current.crop },
                    // What the frame cuts off shows around it, dimmed by the overlay.
                    clip = false,
                ) { videoModifier, onStreamStats ->
                    CameraStreamPlayer(
                        streamUrl = streamUrl,
                        modifier = videoModifier,
                        posterUrl = posterUrl,
                        webRtcSignalingUrl = webRtcSignalingUrl,
                        playerKey = cameraName,
                        onStreamStats = onStreamStats,
                        turnDegrees = turnDegrees,
                        lastSeenPoster = posterIsLastSeen,
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                )
            }
        }
        FrameOverlay(frame = { frame }, denseGrid = straightening, modifier = Modifier.fillMaxSize())
        Text(
            text = stringResource(Res.string.camera_frame_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

/**
 * Over the stage: everything outside the frame dimmed, the frame's edge, and a grid inside it:
 * thirds always, and while straightening a finer one to level a horizon against.
 */
@Composable
private fun FrameOverlay(frame: () -> Rect, denseGrid: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val radius = with(density) { CAMERA_CARD_CORNER_RADIUS.toPx() }
    val edge = with(density) { 1.5.dp.toPx() }
    val line = with(density) { 1.dp.toPx() }
    Canvas(modifier = modifier) {
        val bounds = frame()
        if (bounds.isEmpty) return@Canvas
        val hole = RoundRect(bounds, CornerRadius(radius))
        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(hole)
        }
        drawPath(scrim, Color.Black.copy(alpha = SCRIM_ALPHA))
        val divisions = if (denseGrid) DENSE_GRID_DIVISIONS else 3
        for (i in 1 until divisions) {
            val major = !denseGrid || i * 3 % divisions == 0
            val alpha = if (major) 0.45f else 0.2f
            val x = bounds.left + bounds.width * i / divisions
            val y = bounds.top + bounds.height * i / divisions
            drawLine(Color.White.copy(alpha = alpha), Offset(x, bounds.top), Offset(x, bounds.bottom), strokeWidth = line)
            drawLine(Color.White.copy(alpha = alpha), Offset(bounds.left, y), Offset(bounds.right, y), strokeWidth = line)
        }
        drawPath(Path().apply { addRoundRect(hole) }, Color.White.copy(alpha = 0.9f), style = Stroke(width = edge))
    }
}

/**
 * The editor's precise controls: the straighten ruler with its value (a tap levels it) and a
 * tenth of a degree either way, the zoom, and the quarter turns either side of Reset. [onChange]
 * moves things while a finger is still on them and [onLetGo] saves; [onSet] is a button,
 * shown and saved at once.
 */
@Composable
private fun FramingControls(
    framing: CameraFraming,
    spans: FrameSpans,
    onChange: (framing: CameraFraming, tilting: Boolean) -> Unit,
    onLetGo: () -> Unit,
    onSet: (CameraFraming) -> Unit,
    modifier: Modifier = Modifier,
) {
    val orientation = framing.orientation
    val crop = framing.crop
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabelledValue(
            label = stringResource(Res.string.camera_frame_straighten),
            value = stringResource(Res.string.camera_frame_straighten_value, signedTenths(orientation.tiltDegrees)),
            onValueClickLabel = stringResource(Res.string.camera_frame_level),
            onValueClick = { onSet(framing.copy(orientation = orientation.tilted(0f))) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSet(framing.copy(orientation = orientation.tilted(nudged(orientation.tiltDegrees, -NUDGE_DEGREES)))) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(Res.string.camera_frame_nudge_left), tint = Color.White)
            }
            StraightenRuler(
                degrees = orientation.tiltDegrees,
                onChange = { tilt -> onChange(framing.copy(orientation = orientation.tilted(tilt)), true) },
                onLetGo = onLetGo,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onSet(framing.copy(orientation = orientation.tilted(nudged(orientation.tiltDegrees, NUDGE_DEGREES)))) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(Res.string.camera_frame_nudge_right), tint = Color.White)
            }
        }
        LabelledValue(
            label = stringResource(Res.string.camera_frame_zoom),
            value = stringResource(Res.string.camera_frame_zoom_value, tenths(crop.zoom)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSet(framing.copy(crop = crop.zoomedTo(crop.zoom / ZOOM_STEP, spans.x, spans.y))) }, enabled = !crop.isWhole) {
                Icon(Icons.Filled.ZoomOut, contentDescription = null, tint = Color.White)
            }
            val zoomLabel = stringResource(Res.string.camera_frame_zoom)
            Slider(
                value = crop.zoom,
                onValueChange = { zoom -> onChange(framing.copy(crop = crop.zoomedTo(zoom, spans.x, spans.y)), false) },
                onValueChangeFinished = onLetGo,
                valueRange = 1f..CameraCrop.MAX_ZOOM,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.8f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.weight(1f).semantics { contentDescription = zoomLabel },
            )
            IconButton(onClick = { onSet(framing.copy(crop = crop.zoomedTo(crop.zoom * ZOOM_STEP, spans.x, spans.y))) }, enabled = crop.zoom < CameraCrop.MAX_ZOOM) {
                Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = Color.White)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSet(framing.copy(orientation = orientation.turned(clockwise = false))) }) {
                Icon(Icons.Filled.RotateLeft, contentDescription = stringResource(Res.string.camera_turn_left), tint = Color.White)
            }
            TextButton(
                onClick = { onSet(CameraFraming.UNTOUCHED) },
                enabled = !framing.isUntouched,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.38f)),
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(Res.string.camera_frame_reset))
            }
            IconButton(onClick = { onSet(framing.copy(orientation = orientation.turned(clockwise = true))) }) {
                Icon(Icons.Filled.RotateRight, contentDescription = stringResource(Res.string.camera_turn_right), tint = Color.White)
            }
        }
    }
}

/** A control's name, and its value at the other end; a tap on the value is [onValueClick] when there is one. */
@Composable
private fun LabelledValue(label: String, value: String, onValueClickLabel: String? = null, onValueClick: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = 56.dp)
                .then(if (onValueClick != null) Modifier.clickable(onClickLabel = onValueClickLabel, onClick = onValueClick) else Modifier)
                .padding(vertical = 4.dp),
        )
    }
}

/**
 * The straighten ruler: a strip of degree marks that slides under a fixed pointer, like a
 * photo editor's. Dragging it sets the tilt a tenth of a degree at a time, [RULER_DP_PER_DEGREE]
 * of finger for every degree, with a tick of haptics at each whole one and a firmer one at level.
 * Let go within [LEVEL_SNAP_DEGREES] of level, it settles on level.
 */
@Composable
private fun StraightenRuler(
    degrees: Float,
    onChange: (Float) -> Unit,
    onLetGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pxPerDegree = with(density) { RULER_DP_PER_DEGREE.toPx() }
    val shortTick = with(density) { 8.dp.toPx() }
    val mediumTick = with(density) { 14.dp.toPx() }
    val longTick = with(density) { 22.dp.toPx() }
    val tickWidth = with(density) { 1.dp.toPx() }
    val pointerWidth = with(density) { 2.dp.toPx() }
    val pointerColor = MaterialTheme.colorScheme.primary
    val current by rememberUpdatedState(degrees)
    val currentOnChange by rememberUpdatedState(onChange)
    // The tilt as the finger has it, unrounded, so slow drags still add up.
    var dragged by remember { mutableFloatStateOf(0f) }
    val state = rememberDraggableState { delta ->
        val before = dragged
        dragged = (dragged - delta / pxPerDegree).coerceIn(-CameraOrientation.MAX_TILT_DEGREES, CameraOrientation.MAX_TILT_DEGREES)
        when {
            crossesLevel(before, dragged) -> haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            floor(before) != floor(dragged) -> haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
        currentOnChange(roundedToTenth(dragged))
    }
    val label = stringResource(Res.string.camera_frame_straighten)
    val valueText = stringResource(Res.string.camera_frame_straighten_value, signedTenths(degrees))
    Canvas(
        modifier = modifier
            .height(RULER_HEIGHT)
            .draggable(
                state = state,
                orientation = Orientation.Horizontal,
                onDragStarted = { dragged = current },
                onDragStopped = {
                    if (abs(dragged) < LEVEL_SNAP_DEGREES && dragged != 0f) currentOnChange(0f)
                    onLetGo()
                },
            )
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(degrees, -CameraOrientation.MAX_TILT_DEGREES..CameraOrientation.MAX_TILT_DEGREES)
                setProgress { target ->
                    currentOnChange(roundedToTenth(target))
                    onLetGo()
                    true
                }
            },
    ) {
        val centre = size.width / 2f
        val bottom = size.height
        val reach = centre / pxPerDegree
        val max = CameraOrientation.MAX_TILT_DEGREES.toInt()
        val first = ceil(degrees - reach).toInt().coerceAtLeast(-max)
        val last = floor(degrees + reach).toInt().coerceAtMost(max)
        for (mark in first..last) {
            val x = centre + (mark - degrees) * pxPerDegree
            val length = when {
                mark % 15 == 0 -> longTick
                mark % 5 == 0 -> mediumTick
                else -> shortTick
            }
            // Fading toward the ends, so the strip reads as a dial turning under the pointer.
            val fade = 1f - (abs(x - centre) / centre).coerceIn(0f, 1f) * 0.75f
            val color = if (mark == 0) pointerColor else Color.White
            drawLine(color.copy(alpha = fade), Offset(x, bottom - length), Offset(x, bottom), strokeWidth = tickWidth)
        }
        drawLine(pointerColor, Offset(centre, 0f), Offset(centre, bottom), strokeWidth = pointerWidth)
    }
}

/**
 * The stage's gestures, reported as increments: [onTransform] with the fingers' centroid, the
 * drag, the zoom and the twist since the last event; [onEnd] when the fingers lift after any of
 * it. A twist counts only once it has turned past [TWIST_SLOP_DEGREES] on its own, so a pinch or
 * a drag doesn't knock the picture crooked on the way.
 */
private fun Modifier.framingGestures(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var pastSlop = false
        var twisting = false
        var twist = 0f
        var pan = Offset.Zero
        var zoom = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val zoomChange = event.calculateZoom()
            val rotationChange = event.calculateRotation()
            val panChange = event.calculatePan()
            twist += rotationChange
            if (!twisting && abs(twist) > TWIST_SLOP_DEGREES) twisting = true
            if (!pastSlop) {
                zoom *= zoomChange
                pan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1 - zoom) * centroidSize
                val panMotion = pan.getDistance()
                pastSlop = zoomMotion > viewConfiguration.touchSlop || panMotion > viewConfiguration.touchSlop || twisting
            }
            if (pastSlop) {
                val rotation = if (twisting) rotationChange else 0f
                if (zoomChange != 1f || panChange != Offset.Zero || rotation != 0f) {
                    onTransform(event.calculateCentroid(useCurrent = false), panChange, zoomChange, rotation)
                    moved = true
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (moved) onEnd()
    }
}

/** How far the editor's frame reaches across the picture at 1x, across and down (see CameraCrop). */
internal data class FrameSpans(val x: Float, val y: Float)

/** [FrameSpans] for the card-shaped frame and a picture of [aspect] (null: the frame's), sitting by [fit], maybe [onItsSide]. */
internal fun frameSpans(aspect: Float?, fit: VideoFit?, onItsSide: Boolean): FrameSpans {
    val frameWidth = 1600
    val frameHeight = (frameWidth / VIDEO_ASPECT).roundToInt()
    val picture = pictureBoxSize(frameWidth, frameHeight, aspect, fit, onItsSide)
    return FrameSpans(frameWidth.toFloat() / picture.width, frameHeight.toFloat() / picture.height)
}

/** Whether a tilt going from [before] to [after] reaches or passes level, coming from either side. */
private fun crossesLevel(before: Float, after: Float): Boolean = before != 0f && (after == 0f || (before < 0f) != (after < 0f))

private fun roundedToTenth(degrees: Float): Float = (degrees * 10f).roundToInt() / 10f

/** [degrees] moved by [by], landing on a whole tenth. */
private fun nudged(degrees: Float, by: Float): Float = roundedToTenth(degrees + by)

/** A tilt as "+2.4", "-0.5" or "0.0": data for the value's string, which adds the degree sign. */
internal fun signedTenths(degrees: Float): String {
    val tenths = (degrees * 10f).roundToInt()
    val sign = when {
        tenths > 0 -> "+"
        tenths < 0 -> "-"
        else -> ""
    }
    return sign + tenths(abs(tenths) / 10f)
}

/** [value] to one decimal place: "1.0", "2.5". */
internal fun tenths(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/** The editor's Done button. */
const val CAMERA_FRAME_DONE_TEST_TAG = "camera_frame_done"

private val FRAME_MARGIN: Dp = 24.dp
private val CONTROLS_SIDE_WIDTH: Dp = 340.dp
private val RULER_HEIGHT: Dp = 40.dp

/** How far a finger goes along the ruler for a degree: a tenth is under 2dp, about as fine as a finger steers. */
private val RULER_DP_PER_DEGREE: Dp = 16.dp

/** A tap on either side of the ruler. */
private const val NUDGE_DEGREES = 0.1f

/** Let go nearer level than this and the ruler settles on it. */
private const val LEVEL_SNAP_DEGREES = 0.3f

/** How far two fingers turn before the twist straightens the picture. */
private const val TWIST_SLOP_DEGREES = 4f

/** The zoom buttons' step: four presses from 1x to about 2x. */
private const val ZOOM_STEP = 1.2f

private const val SCRIM_ALPHA = 0.6f

/** The straightening grid: sixths, with the thirds drawn stronger. */
private const val DENSE_GRID_DIVISIONS = 6
