package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.meticulouscreations.homesafe.ui.VIDEO_ASPECT
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.MeasuredVideoShapes
import com.meticulouscreations.homesafe.ui.components.PinchZoomState
import com.meticulouscreations.homesafe.ui.components.ShapedVideo
import com.meticulouscreations.homesafe.ui.components.ZoomRest
import com.meticulouscreations.homesafe.ui.components.cropAsZoomRest
import com.meticulouscreations.homesafe.ui.components.detectHeldDrag
import com.meticulouscreations.homesafe.ui.components.isOnItsSide
import com.meticulouscreations.homesafe.ui.components.liveSurfaceIsExclusive
import com.meticulouscreations.homesafe.ui.components.pictureInBox
import com.meticulouscreations.homesafe.ui.components.pinchZoomContent
import com.meticulouscreations.homesafe.ui.components.pinchZoomGestures
import com.meticulouscreations.homesafe.ui.fitVideo
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A quick look at one camera from the Home list: pinch a card and its video lifts out into a
 * full-screen layer, still under the same fingers, where it can be zoomed and panned; let go at
 * 1x, tap the backdrop, or press Back and it settles back into its card. A back swipe draws it
 * part of the way home under the finger first, so letting go finishes a move already begun.
 * Long-pressing a card opens the same layer zoomed in on the pressed spot.
 *
 * A card framed in Arrange mode shows its crop, and the look opens on that same view, but drawn by
 * the zoom resting on the crop ([cropAsZoomRest]) rather than by cutting the picture down, as on a
 * camera's page: pinching out from it reaches the whole picture, every pixel of it, and a drag
 * pans past the crop's edges. At rest the look shows the picture through the card's box, so the
 * crop is framed as it was saved; zoomed, the box grows to the whole overlay ([takeover]), as a
 * camera page's player takes over its page.
 *
 * The state is shared between the cards, which start the gesture, and [CameraCardZoomOverlay],
 * which draws it. The pinch that opens the layer keeps its pointer stream on the card it began on
 * (Compose routes a gesture to the nodes hit at touch-down), so the card forwards each increment
 * through [pinchFromCard], mapped into the overlay's frame; later pinches land on the overlay
 * itself.
 *
 * The overlay binds the card's pooled player (same `playerKey`), so the picture carries on from
 * the card's last frame rather than reconnecting. Where the player can only draw to one surface
 * at a time ([liveSurfaceIsExclusive]: Android's HLS path renders to whichever surface bound
 * last), the card gives its player up a frame after the overlay has taken it
 * ([cardWithoutPlayer]) and takes it back a frame before the overlay goes, each hand-over
 * inheriting the other's last frame. Where every surface gets every frame (iOS) both stay bound,
 * which spares the card a blank moment after the overlay leaves.
 */
@Stable
class CameraCardZoomState(private val scope: CoroutineScope) {
    /** The camera in the overlay, or null while it is closed. */
    var target: CameraTile? by mutableStateOf(null)
        private set

    /** The camera whose card draws no player right now, because the overlay has it. */
    var cardWithoutPlayer: String? by mutableStateOf(null)
        private set

    val zoom = PinchZoomState()

    /** 0 = the video sits exactly over its card, 1 = it has lifted into the overlay's own frame. */
    val lift = Animatable(0f)

    /**
     * 0 = the picture shows through the video's 16:9 box, as the card showed it; 1 = zoomed, it
     * shows through the whole overlay. Follows [PinchZoomState.isZoomed], driven by the overlay.
     */
    val takeover = Animatable(0f)

    /** The back swipe in progress, 0 to 1, set by the overlay that listens for it. */
    internal var backProgress: () -> Float = { 0f }

    // Where a committed back swipe left the video, held until [close] takes the lift on from there.
    private var heldBack by mutableFloatStateOf(0f)

    /** The lift as drawn: [lift], drawn part of the way back toward the card by a back swipe. */
    internal val shownLift: Float
        get() = lift.value * (1f - BACK_PEEK * maxOf(backProgress(), heldBack))

    // Laid out by the overlay. The sizes are snapshot state because opening by long press waits
    // for them; the origin is only ever read inside gesture and draw code.
    internal var overlayOriginInRoot = Offset.Zero
    internal var viewportSize by mutableStateOf(IntSize.Zero)
    internal var contentSize by mutableStateOf(IntSize.Zero)

    /** Where the video came from, in root coordinates: where the lift starts and lands back on. */
    private var cardBounds = Rect.Zero
    private var job: Job? = null

    /** Fingers have started spreading on [tile]'s card, whose video sits at [cardBoundsInRoot]. */
    fun openByPinch(tile: CameraTile, cardBoundsInRoot: Rect) {
        if (target == tile) return
        begin(tile, cardBoundsInRoot)
        run {
            coroutineScope {
                launch { lift.animateTo(1f, LIFT_SPEC) }
                takeCardPlayer(tile)
            }
        }
    }

    /** A long press at [pressInCard] (card-local) on [tile]'s card: open, zoomed in on that spot. */
    fun openByLongPress(tile: CameraTile, cardBoundsInRoot: Rect, pressInCard: Offset) {
        begin(tile, cardBoundsInRoot)
        val u = (pressInCard.x / cardBoundsInRoot.width).coerceIn(0f, 1f)
        val v = (pressInCard.y / cardBoundsInRoot.height).coerceIn(0f, 1f)
        run {
            // The zoom's focal point is in the overlay's frame, which exists only once it has laid out.
            snapshotFlow { viewportSize != IntSize.Zero && contentSize != IntSize.Zero }.first { it }
            val restTopLeft = viewportSize.center - Offset(contentSize.width / 2f, contentSize.height / 2f)
            val focus = restTopLeft + Offset(u * contentSize.width, v * contentSize.height)
            coroutineScope {
                launch { lift.animateTo(1f, LIFT_SPEC) }
                // Closer than the card's crop, as a hold on a camera's page is.
                launch { zoom.animateZoomIn(focus) }
                takeCardPlayer(tile)
            }
        }
    }

    /** One increment of the opening pinch, from the card: [centroidInRoot] is where the fingers are. */
    fun pinchFromCard(zoomChange: Float, pan: Offset, centroidInRoot: Offset) {
        // The overlay's content is drawn through the lift transform; undo it so the point under
        // the fingers is the same point of the video whether the lift has finished or not.
        // Until the overlay has laid out there is nothing to zoom, and an increment applied now
        // would clamp the crop's shift away: the next frame's increments carry on from rest.
        if (contentSize == IntSize.Zero || viewportSize == IntSize.Zero) return
        val frame = liftFrame()
        val center = viewportSize.center
        val local = centroidInRoot - overlayOriginInRoot
        val centroid = center + (local - center - frame.translation) / frame.scale
        zoom.transformBy(zoomChange, pan / frame.scale, centroid)
    }

    /** The finger that held a card open has moved by [pan] (root pixels): drag the picture with it. */
    fun panFromCard(pan: Offset) {
        // Through the lift transform, as for a pinch: the picture keeps up with the finger mid-lift.
        zoom.panBy(pan / liftFrame().scale)
    }

    /** The fingers lifted: a look that ended back where its card was (or near enough to settle there) is over. */
    fun pinchEnded() {
        if (target != null && zoom.settlesOnRest) close()
    }

    /** Settles the video back into its card. */
    fun close() {
        if (target == null) return
        run {
            lift.snapTo(shownLift)
            heldBack = 0f
            coroutineScope {
                launch { zoom.animateReset() }
                launch { lift.animateTo(0f, LIFT_SPEC) }
            }
            handBack()
        }
    }

    /** A back swipe let go past the commit point at [releasedAt]: settle home from where it drew the video. */
    fun closeFromBack(releasedAt: Float) {
        heldBack = releasedAt
        close()
    }

    /** Leaves without the settle — the camera's own screen is about to take the video over. */
    fun dismiss(then: () -> Unit) {
        if (target == null) return
        run {
            handBack()
            then()
        }
    }

    /** How the overlay's at-rest content is transformed to sit over the card at this point of the lift. */
    internal fun liftFrame(): LiftFrame {
        val t = shownLift
        if (contentSize == IntSize.Zero || viewportSize == IntSize.Zero) return LiftFrame(1f, Offset.Zero)
        val restScale = cardBounds.width / contentSize.width
        val cardCenter = cardBounds.center - overlayOriginInRoot - viewportSize.center
        return LiftFrame(scale = lerp(restScale, 1f, t), translation = lerp(cardCenter, Offset.Zero, t))
    }

    /**
     * What the picture shows through in the zoom's frame (the overlay before the lift's
     * transform), centred in it: the video's 16:9 box, growing to the whole overlay with [takeover].
     * The zoom's pans are bounded by it ([PinchZoomState.window]).
     */
    internal fun zoomWindow(): IntSize {
        if (contentSize == IntSize.Zero || viewportSize == IntSize.Zero) return viewportSize
        val t = takeover.value
        return IntSize(lerp(contentSize.width, viewportSize.width, t), lerp(contentSize.height, viewportSize.height, t))
    }

    /**
     * [zoomWindow] as drawn in an overlay [size] across, through the lift: over the card, with
     * its [cornerRadius], at take-off, and square once the look has lifted.
     */
    internal fun liftClip(size: Size, cornerRadius: Float): Shape {
        if (contentSize == IntSize.Zero) return WindowShape(Rect.Zero, 0f)
        val frame = liftFrame()
        val window = zoomWindow()
        val center = Offset(size.width / 2f, size.height / 2f) + frame.translation
        val half = Offset(window.width * frame.scale / 2f, window.height * frame.scale / 2f)
        return WindowShape(Rect(center - half, center + half), cornerRadius * (1f - shownLift))
    }

    private fun begin(tile: CameraTile, cardBoundsInRoot: Rect) {
        job?.cancel()
        cardBounds = cardBoundsInRoot
        viewportSize = IntSize.Zero
        contentSize = IntSize.Zero
        // On the card's crop from the first frame, so the lift starts from exactly what the card shows.
        val framing = tile.lookFraming()
        zoom.restOn(framing.rest, framing.picture)
        zoom.reset()
        target = tile
    }

    private fun run(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    /** Two frames on: the overlay has composed and bound the player, taking the card's last frame with it. */
    private suspend fun takeCardPlayer(tile: CameraTile) {
        if (!liveSurfaceIsExclusive) return
        withFrameNanos {}
        withFrameNanos {}
        cardWithoutPlayer = tile.camera.name
    }

    /** The card composes its player again (taking the overlay's last frame), then the overlay goes. */
    private suspend fun handBack() {
        cardWithoutPlayer = null
        withFrameNanos {}
        withFrameNanos {}
        target = null
        lift.snapTo(0f)
        // The overlay that drove these has gone: back to the card's box, for the next look's rest.
        takeover.snapTo(0f)
        zoom.window = zoomWindow()
        zoom.reset()
    }

    internal data class LiftFrame(val scale: Float, val translation: Offset)

    private val IntSize.center: Offset get() = Offset(width / 2f, height / 2f)

    private companion object {
        val LIFT_SPEC = tween<Float>(NAV_TRANSITION_MS, easing = NavEnterEasing)

        /** How much of the way home to its card a full back swipe draws the video. */
        const val BACK_PEEK = 0.5f
    }
}

/**
 * Lets the finger that long-pressed a card open the quick look carry straight on into panning it,
 * without lifting. The press keeps its pointer stream on the card (Compose routes a gesture to the
 * nodes hit at touch-down), so the card forwards the finger's moves to [state]. Only the look for
 * [cameraName] is dragged, and only moves nothing nearer has consumed — a pinch that opened it
 * pans on its own.
 */
internal fun Modifier.quickLookHeldDrag(state: CameraCardZoomState, cameraName: String): Modifier = pointerInput(state, cameraName) {
    detectHeldDrag(isHeld = { state.target?.camera?.name == cameraName }, onDrag = state::panFromCard)
}

@Composable
internal fun rememberCameraCardZoomState(): CameraCardZoomState {
    val scope = rememberCoroutineScope()
    return remember { CameraCardZoomState(scope) }
}

/**
 * The layer a pinched card's video lifts into — see [CameraCardZoomState]. Composed over the
 * whole shell, nav bars included, so the picture has the full screen; nothing while closed.
 * Tapping the video opens the camera's own screen via [onOpenCamera].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CameraCardZoomOverlay(state: CameraCardZoomState, onOpenCamera: (CameraTile) -> Unit) {
    val tile = state.target ?: return
    val extraColors = LocalFrigateExtraColors.current
    // Remembered: the gesture node is keyed on it, and must not restart under a pinch.
    val onPinchEnded = remember(state) { { state.pinchEnded() } }
    val back = rememberPredictiveBack(onBack = state::closeFromBack)
    SideEffect { state.backProgress = { back.progress } }
    // Read here rather than in the effect: the picture's shape may only now have been measured.
    val framing = tile.lookFraming()
    SideEffect { state.zoom.restOn(framing.rest, framing.picture) }
    // Zoomed, the picture takes the whole overlay; at rest it is back in its box. From a
    // snapshotFlow, so the pinch's per-frame changes never recompose the overlay.
    LaunchedEffect(state) {
        snapshotFlow { state.zoom.isZoomed }.collectLatest { zoomed ->
            state.takeover.animateTo(if (zoomed) 1f else 0f, tween(ZOOM_TAKEOVER_MS))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, alpha = SCRIM_ALPHA * state.shownLift) }
            .clickable(interactionSource = null, indication = null, onClick = state::close)
            .onGloballyPositioned { state.overlayOriginInRoot = it.positionInRoot() }
            .onSizeChanged { state.viewportSize = it }
            .pinchZoomGestures(state.zoom, onPinchEnded = onPinchEnded),
    ) {
        // The window the picture shows through: the card at take-off, rounded as it is, the
        // video's box once lifted, and the whole overlay while zoomed. The zoom rests on the
        // card's crop, so the picture is larger than the box, and this keeps the rest of it out
        // of sight until a pinch reaches for it. Its size bounds the zoom's pans, set here while
        // laying out so the picture is placed against it in the same frame.
        Box(
            modifier = Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    state.zoom.window = state.zoomWindow()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    shape = state.liftClip(Size(size.width, size.height), CAMERA_CARD_CORNER_RADIUS.toPx())
                    clip = true
                },
        ) {
            // Two transforms, outermost first: the lift, from the card's place and size to the
            // overlay's own; then the pinch zoom inside it, about the same centre.
            // As large as the window allows at 16:9: full width upright, full height on its side.
            Box(
                modifier = Modifier
                    .fitVideo()
                    .align(Alignment.Center)
                    .onSizeChanged { state.contentSize = it }
                    .graphicsLayer {
                        val frame = state.liftFrame()
                        scaleX = frame.scale
                        scaleY = frame.scale
                        translationX = frame.translation.x
                        translationY = frame.translation.y
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pinchZoomContent(state.zoom)
                        .clickable(interactionSource = null, indication = null) { state.dismiss { onOpenCamera(tile) } },
                ) {
                    val streamUrl = tile.streamUrl
                    if (streamUrl != null) {
                        val turnDegrees = tile.orientation.degrees
                        // Turned as its card is, and cropped by the zoom's rest rather than here, so all of
                        // the picture is there to zoom out to: unclipped, so a picture filling the box
                        // past its edges keeps them, for the window above to clip.
                        ShapedVideo(
                            streamUrl = streamUrl,
                            fit = tile.videoFit,
                            modifier = Modifier.fillMaxSize(),
                            turnDegrees = turnDegrees,
                            clip = false,
                        ) { videoModifier, onStreamStats ->
                            CameraStreamPlayer(
                                streamUrl = streamUrl,
                                modifier = videoModifier,
                                posterUrl = tile.posterUrl,
                                webRtcSignalingUrl = tile.webRtcSignalingUrl,
                                playerKey = tile.camera.name,
                                onStreamStats = onStreamStats,
                                turnDegrees = turnDegrees,
                                lastSeenPoster = tile.posterIsLastSeen,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            modifier = Modifier.align(Alignment.Center).size(56.dp),
                        )
                    }
                }
            }
        }

        // The name and the way out, on the same dark-to-clear wash the cards give their titles,
        // so they read over a bright picture (a zoomed-in sky, a porch light) as well as a dark one.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .graphicsLayer { alpha = state.shownLift }
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .statusBarsPadding()
                .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tile.camera.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = extraColors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            IconButton(onClick = state::close) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.common_close), tint = extraColors.textPrimary)
            }
        }
    }
}

/** A rounded window at [rect] within the shape's bounds, rather than the bounds themselves. */
private class WindowShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/** How the look sits at rest: on [CameraTile.crop], drawn by the zoom, with the picture's size beside its 16:9 box. */
internal class LookFraming(val rest: ZoomRest, val picture: Size)

/** [LookFraming] for this tile's card, by its crop, its picture's shape (once measured), how that sits, and its turn. */
internal fun CameraTile.lookFraming(): LookFraming {
    val aspect = streamUrl?.let { MeasuredVideoShapes[it] }
    val onItsSide = isOnItsSide(orientation.degrees)
    return LookFraming(
        rest = cropAsZoomRest(crop, boxAspect = VIDEO_ASPECT, aspect = aspect, fit = videoFit, onItsSide = onItsSide),
        picture = pictureInBox(boxAspect = VIDEO_ASPECT, aspect = aspect, fit = videoFit, onItsSide = onItsSide),
    )
}

/** How dark the backdrop is once the video has fully lifted: the list is a ghost behind it, no more. */
private const val SCRIM_ALPHA = 0.96f
