package com.meticulouscreations.homesafe.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Scale and pan for a pinch-zoomable surface, kept within bounds: never smaller than 1x, never
 * larger than [maxScale], and never panned far enough to show an empty edge of the viewport.
 *
 * Two nodes take part. The *viewport* receives the touches and clips ([Modifier.pinchZoomGestures]);
 * the *content* sits centred inside it and is what grows ([Modifier.pinchZoomContent]). They may be
 * the same size, or the viewport may be larger — a 16:9 video letterboxed in a portrait screen —
 * in which case the content stays centred on an axis until it has grown past the viewport there,
 * and only then can be panned along it.
 */
@Stable
class PinchZoomState(private val maxScale: Float = DEFAULT_MAX_SCALE) {
    var scale by mutableFloatStateOf(1f)
        private set

    /** Translation of the scaled content's centre from the viewport's centre, in pixels. */
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > 1f

    // Layout sizes, not snapshot state: they're only read inside gesture and animation code.
    // Either changing can leave the current pan past the new edge, so both re-clamp it.
    internal var viewportSize: IntSize = IntSize.Zero
        set(value) {
            field = value
            offset = offset.clampedFor(scale)
        }

    internal var contentSize: IntSize = IntSize.Zero
        set(value) {
            field = value
            offset = offset.clampedFor(scale)
        }

    // Drags that arrived during animateTo and haven't been folded into a frame of it yet.
    private var animating = false
    private var animationRun = 0
    private var pendingPan = Offset.Zero

    /** One increment of a pinch: zoom about [centroid] (viewport-local) by [zoomChange], then drag by [pan]. */
    fun transformBy(zoomChange: Float, pan: Offset, centroid: Offset) {
        val newScale = (scale * zoomChange).coerceIn(1f, maxScale)
        val focal = centroid - viewportSize.center
        // Keep the content point under the fingers where it is, then let the drag move it.
        val newOffset = focal + pan - (focal - offset) * (newScale / scale)
        scale = newScale
        offset = newOffset.clampedFor(newScale)
    }

    /**
     * A one-finger drag of [pan]. Safe during [animateZoomTo]: the drag is carried on top of the
     * animation's path instead of being overwritten by its next frame.
     */
    fun panBy(pan: Offset) {
        if (animating) {
            pendingPan += pan
        } else {
            offset = (offset + pan).clampedFor(scale)
        }
    }

    suspend fun animateReset() = animateTo(1f, Offset.Zero)

    /** Straight back to 1x, no animation — for a surface that is about to be shown afresh. */
    fun reset() {
        scale = 1f
        offset = Offset.Zero
        pendingPan = Offset.Zero
    }

    /** Zooms to [targetScale] with the content under [centroid] (viewport-local) staying put — a double-tap zoom. */
    suspend fun animateZoomTo(targetScale: Float, centroid: Offset) {
        val newScale = targetScale.coerceIn(1f, maxScale)
        val focal = centroid - viewportSize.center
        val newOffset = (focal - (focal - offset) * (newScale / scale)).clampedFor(newScale)
        animateTo(newScale, newOffset)
    }

    private suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
        val fromScale = scale
        val fromOffset = offset
        var drift = Offset.Zero
        // A newer animation may start before this one's cancellation reaches its finally.
        val run = ++animationRun
        animating = true
        try {
            animate(0f, 1f) { t, _ ->
                scale = fromScale + (targetScale - fromScale) * t
                val path = fromOffset + (targetOffset - fromOffset) * t
                // Clamped per frame: the viewport may be growing underneath the animation. Drift
                // the clamp cut off is dropped, so dragging back moves the picture straight away.
                offset = (path + drift + pendingPan).clampedFor(scale)
                drift = offset - path
                pendingPan = Offset.Zero
            }
        } finally {
            if (run == animationRun) {
                animating = false
                // A drag that landed after the last frame.
                offset = (offset + pendingPan).clampedFor(scale)
                pendingPan = Offset.Zero
            }
        }
    }

    private fun Offset.clampedFor(scale: Float): Offset {
        val maxX = ((contentSize.width * scale - viewportSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((contentSize.height * scale - viewportSize.height) / 2f).coerceAtLeast(0f)
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }

    private val IntSize.center: Offset get() = Offset(width / 2f, height / 2f)

    companion object {
        const val DEFAULT_MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

@Composable
fun rememberPinchZoomState(maxScale: Float = PinchZoomState.DEFAULT_MAX_SCALE): PinchZoomState = remember { PinchZoomState(maxScale) }

/** Scales and pans this node by [state], about its centre. It must be centred in the viewport, which clips. */
fun Modifier.pinchZoomContent(state: PinchZoomState): Modifier = onSizeChanged { state.contentSize = it }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

/**
 * Makes this node the viewport: pinch to zoom, and — only once zoomed in — one-finger drag to pan.
 * At 1x a single-finger drag is deliberately left alone so an enclosing scroll container still
 * scrolls over the surface. Taps are never consumed, so tap handlers on descendants keep working.
 *
 * [onPinchEnded] runs when the fingers lift after a pinch or pan this node handled.
 */
fun Modifier.pinchZoomGestures(state: PinchZoomState, onPinchEnded: () -> Unit = {}): Modifier =
    onSizeChanged { state.viewportSize = it }
        .pointerInput(state, onPinchEnded) {
            detectPinch(
                isZoomed = { state.isZoomed },
                onPinchStarted = { true },
                onPinch = state::transformBy,
                onPinchEnded = onPinchEnded,
            )
        }

/**
 * The one-finger ways into a zoom, for a node that lies over the content and fills the viewport
 * of [state] (so its touches are viewport-local). A double tap zooms in on the tapped spot, or
 * back out. Holding a finger down zooms in on the held spot, and that finger can carry straight
 * on into dragging the picture without lifting; a hold on a picture already zoomed in only takes
 * hold of it to drag. A single tap is [onTap]'s.
 *
 * [onHoldZoom] runs as a hold zooms in — the place for a haptic tick. Both callbacks key the
 * gesture node, so hand it remembered ones: fresh lambdas would restart it under a finger.
 */
fun Modifier.zoomTapGestures(state: PinchZoomState, onTap: () -> Unit, onHoldZoom: () -> Unit = {}): Modifier =
    pointerInput(state, onTap, onHoldZoom) {
        // Whether the press now down has turned into a hold.
        var held = false
        coroutineScope {
            // Alongside the tap detector rather than inside it: once a long press has fired, that
            // detector only swallows what the finger does next. Those moves are taken here whether
            // or not it got to them first. Undispatched, so it is listening before this block
            // returns: pointer input starts on the first touch it is sent, and a watcher launched
            // the ordinary way would miss that finger landing, and with it the whole first gesture.
            launch(start = CoroutineStart.UNDISPATCHED) {
                detectHeldDrag(
                    isHeld = { held },
                    requireUnconsumed = false,
                    onDrag = state::panBy,
                    onReleased = { held = false },
                )
            }
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { tapAt ->
                    launch {
                        if (state.isZoomed) state.animateReset() else state.animateZoomTo(PinchZoomState.DOUBLE_TAP_SCALE, tapAt)
                    }
                },
                onLongPress = { pressAt ->
                    held = true
                    if (!state.isZoomed) {
                        onHoldZoom()
                        launch { state.animateZoomTo(PinchZoomState.DOUBLE_TAP_SCALE, pressAt) }
                    }
                },
            )
        }
    }

/**
 * Lets the finger whose long press took hold of something carry straight on into dragging it,
 * without lifting: while [isHeld], each move of a lone finger goes to [onDrag] and is consumed,
 * so nothing behind scrolls along. [requireUnconsumed] leaves alone the moves something nearer
 * has already taken. [onReleased] runs when the fingers lift, held or not.
 */
internal suspend fun PointerInputScope.detectHeldDrag(
    isHeld: () -> Boolean,
    requireUnconsumed: Boolean = true,
    onDrag: (Offset) -> Unit,
    onReleased: () -> Unit = {},
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val finger = event.changes.filter { it.pressed }.singleOrNull()
            if (finger != null && finger.previousPressed && !(requireUnconsumed && finger.isConsumed) && isHeld()) {
                // Not positionChange(), which is zero for a move something else has consumed.
                val delta = finger.positionChangeIgnoreConsumed()
                if (delta != Offset.Zero) {
                    onDrag(delta)
                    finger.consume()
                }
            }
        } while (event.changes.any { it.pressed })
        onReleased()
    }
}

/**
 * What a [pinchGestures] node reports. One remembered object rather than three lambdas: the
 * node is keyed on it, and a recomposition that handed it fresh lambdas would restart the
 * pointer input and drop a pinch in progress.
 */
@Stable
interface PinchGestureListener {
    /**
     * The pinch has passed touch slop, with [zoom] the amount accumulated so far (above 1 for
     * fingers spreading, below for closing). Return false to let this gesture go: nothing more is
     * reported for it and nothing is consumed, so an enclosing scroll container keeps it.
     */
    fun onPinchStarted(zoom: Float): Boolean

    /** One increment of a taken pinch, with the [centroid] in the node's own coordinates. */
    fun onPinch(zoomChange: Float, pan: Offset, centroid: Offset)

    /** The fingers lifted after a taken pinch. */
    fun onPinchEnded() {}
}

/**
 * Recognises a pinch on this node and hands it to [listener] rather than to a [PinchZoomState]
 * of its own — for a surface whose zoom is drawn somewhere else, such as a camera card whose
 * video lifts into an overlay the moment the fingers spread. Taken pinches have their moves
 * consumed; single-finger drags are never taken, so the enclosing list still scrolls.
 */
fun Modifier.pinchGestures(listener: PinchGestureListener): Modifier = pointerInput(listener) {
    detectPinch(
        isZoomed = { false },
        onPinchStarted = listener::onPinchStarted,
        onPinch = listener::onPinch,
        onPinchEnded = listener::onPinchEnded,
    )
}

private suspend fun PointerInputScope.detectPinch(
    isZoomed: () -> Boolean,
    onPinchStarted: (zoom: Float) -> Boolean,
    onPinch: (zoomChange: Float, pan: Offset, centroid: Offset) -> Unit,
    onPinchEnded: () -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        // Once past slop, whether the caller took this gesture; a declined one is left alone.
        var tracking = false
        var declined = false
        var secondFingerSeen = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled && !secondFingerSeen && event.changes.count { it.pressed } >= 2) {
                // A second finger makes this a pinch, whatever it goes on to do, so no tap or
                // long press on this node should fire for it (a two-finger hold otherwise reads
                // as a long press after the platform's timeout). Consuming that finger's landing
                // stands the press down; the first finger is left alone, so a scroll container
                // tracking it is unaffected.
                secondFingerSeen = true
                event.changes.forEach { if (it.changedToDown()) it.consume() }
            }
            if (!canceled && !declined) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                    val panMotion = pan.getDistance()
                    if (zoomMotion > touchSlop || (panMotion > touchSlop && isZoomed())) {
                        pastTouchSlop = true
                        if (onPinchStarted(zoom)) tracking = true else declined = true
                    }
                }

                if (tracking) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onPinch(zoomChange, panChange, centroid)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
        if (tracking) onPinchEnded()
    }
}
