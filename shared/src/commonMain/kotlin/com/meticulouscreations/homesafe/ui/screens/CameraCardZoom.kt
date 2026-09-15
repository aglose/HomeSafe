package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer
import com.meticulouscreations.homesafe.ui.components.PinchZoomState
import com.meticulouscreations.homesafe.ui.components.liveSurfaceIsExclusive
import com.meticulouscreations.homesafe.ui.components.pinchZoomContent
import com.meticulouscreations.homesafe.ui.components.pinchZoomGestures
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A quick look at one camera from the Home list: pinch a card and its video lifts out into a
 * full-screen layer, still under the same fingers, where it can be zoomed and panned; let go at
 * 1x, tap the backdrop, or press Back and it settles back into its card. Long-pressing a card
 * opens the same layer zoomed in on the pressed spot.
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
                launch { zoom.animateZoomTo(PinchZoomState.DOUBLE_TAP_SCALE, focus) }
                takeCardPlayer(tile)
            }
        }
    }

    /** One increment of the opening pinch, from the card: [centroidInRoot] is where the fingers are. */
    fun pinchFromCard(zoomChange: Float, pan: Offset, centroidInRoot: Offset) {
        // The overlay's content is drawn through the lift transform; undo it so the point under
        // the fingers is the same point of the video whether the lift has finished or not.
        val frame = liftFrame()
        val center = viewportSize.center
        val local = centroidInRoot - overlayOriginInRoot
        val centroid = center + (local - center - frame.translation) / frame.scale
        zoom.transformBy(zoomChange, pan / frame.scale, centroid)
    }

    /** The fingers lifted: a look that ended back at 1x is over. */
    fun pinchEnded() {
        if (target != null && !zoom.isZoomed) close()
    }

    /** Settles the video back into its card. */
    fun close() {
        if (target == null) return
        run {
            coroutineScope {
                launch { zoom.animateReset() }
                launch { lift.animateTo(0f, LIFT_SPEC) }
            }
            handBack()
        }
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
        val t = lift.value
        if (contentSize == IntSize.Zero || viewportSize == IntSize.Zero) return LiftFrame(1f, Offset.Zero)
        val restScale = cardBounds.width / contentSize.width
        val cardCenter = cardBounds.center - overlayOriginInRoot - viewportSize.center
        return LiftFrame(scale = lerp(restScale, 1f, t), translation = lerp(cardCenter, Offset.Zero, t))
    }

    private fun begin(tile: CameraTile, cardBoundsInRoot: Rect) {
        job?.cancel()
        cardBounds = cardBoundsInRoot
        viewportSize = IntSize.Zero
        contentSize = IntSize.Zero
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
        zoom.reset()
    }

    internal data class LiftFrame(val scale: Float, val translation: Offset)

    private val IntSize.center: Offset get() = Offset(width / 2f, height / 2f)

    private companion object {
        val LIFT_SPEC = tween<Float>(NAV_TRANSITION_MS, easing = NavEnterEasing)
    }
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
    BackHandler { state.close() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, alpha = SCRIM_ALPHA * state.lift.value) }
            .clickable(interactionSource = null, indication = null, onClick = state::close)
            .onGloballyPositioned { state.overlayOriginInRoot = it.positionInRoot() }
            .onSizeChanged { state.viewportSize = it }
            .pinchZoomGestures(state.zoom, onPinchEnded = onPinchEnded),
    ) {
        // Two transforms, outermost first: the lift, from the card's place and size to the
        // overlay's own; then the pinch zoom inside it, about the same centre.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
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
                    // The card's rounded corners at take-off, square once lifted — as the nav
                    // transition's video does. Clipping here, inside the zoom, only ever trims
                    // the video's own edges, which a zoomed-in picture has pushed off screen.
                    .graphicsLayer {
                        shape = RoundedCornerShape(CAMERA_CARD_CORNER_RADIUS * (1f - state.lift.value))
                        clip = true
                    }
                    .clickable(interactionSource = null, indication = null) { state.dismiss { onOpenCamera(tile) } },
            ) {
                val streamUrl = tile.streamUrl
                if (streamUrl != null) {
                    CameraStreamPlayer(
                        streamUrl = streamUrl,
                        modifier = Modifier.fillMaxSize(),
                        posterUrl = tile.posterUrl,
                        webRtcSignalingUrl = tile.webRtcSignalingUrl,
                        playerKey = tile.camera.name,
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
        }

        // The name and the way out, on the same dark-to-clear wash the cards give their titles,
        // so they read over a bright picture (a zoomed-in sky, a porch light) as well as a dark one.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .graphicsLayer { alpha = state.lift.value }
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
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = extraColors.textPrimary)
            }
        }
    }
}

/** How dark the backdrop is once the video has fully lifted: the list is a ghost behind it, no more. */
private const val SCRIM_ALPHA = 0.96f
