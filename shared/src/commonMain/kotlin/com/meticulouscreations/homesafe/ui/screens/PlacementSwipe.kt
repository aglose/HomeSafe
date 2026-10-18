package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The Home page's sideways swipe between its camera lists, so a phone held in one hand can switch
 * without reaching for the switch at the top. Outdoor lies to the left and Indoor to the right,
 * as the switch draws them: a swipe to the left goes indoors and one to the right comes back out.
 * The drawer lies left of Outdoor, so on the outdoor list a swipe to the right is left for it.
 *
 * The cards follow the finger ([drawCards]); let go far or fast enough, they carry on out and the
 * other list's come in from the far side. Short of that they settle back.
 */
@Stable
internal class PlacementSwipe(private val scope: CoroutineScope) {
    private val offset = Animatable(0f)

    /** The settle under way after the finger came up, which may still switch lists at its end. */
    private var settling: Job? = null

    /** Pushes the cards sideways and fades them as they go; a graphicsLayer block, so the swipe never recomposes them. */
    fun drawCards(layer: GraphicsLayerScope) {
        val pushed = offset.value
        layer.translationX = pushed
        if (layer.size.width > 0f) layer.alpha = 1f - (abs(pushed) / layer.size.width).coerceAtMost(1f) * SWIPE_FADE
    }

    /** The finger moved [dx]; the cards follow, only ever toward the other list ([direction]: -1 left, +1 right). */
    internal fun dragBy(dx: Float, width: Float, direction: Float) {
        val moved = offset.value + dx
        val held = if (direction < 0f) moved.coerceIn(-width, 0f) else moved.coerceIn(0f, width)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(held) }
    }

    /** The finger came up at [velocity] (px/s; null when the gesture was cut short): switch to the other list, or settle back. */
    internal fun letGo(velocity: Float?, width: Float, direction: Float, fling: Float, onSwitch: () -> Unit) {
        val along = (velocity ?: 0f) * direction
        val far = offset.value * direction > width * SWIPE_COMMIT_FRACTION
        val switch = velocity != null && (along > fling || (far && along > -fling))
        settling?.cancel()
        settling = scope.launch {
            if (!switch) {
                offset.animateTo(0f, spring())
                return@launch
            }
            offset.animateTo(direction * width, tween(SWIPE_OUT_MS, easing = FastOutLinearInEasing))
            onSwitch()
            offset.snapTo(-direction * width * SWIPE_ENTRY_FRACTION)
            offset.animateTo(0f, tween(SWIPE_IN_MS, easing = LinearOutSlowInEasing))
        }
    }

    /**
     * Drops a swipe still on its way to switching lists, and puts the cards back at rest: for when
     * the swipe stops applying (Arrange mode started), so a switch let go just before can't land after.
     */
    fun cancel() {
        settling?.cancel()
        settling = null
        scope.launch { offset.snapTo(0f) }
    }
}

@Composable
internal fun rememberPlacementSwipe(): PlacementSwipe {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlacementSwipe(scope) }
}

/**
 * Listens for [swipe] across this, on the list of [placement]; null turns it off (no indoor
 * cameras to go to, or Arrange mode, whose holds are drags). Whatever is under the finger has
 * first refusal, as with the drawer's pull: a row that scrolls sideways keeps its swipe, and a
 * pinch on a card is the card's.
 */
internal fun Modifier.placementSwipe(
    swipe: PlacementSwipe,
    placement: CameraPlacement?,
    onSwitch: (CameraPlacement) -> Unit,
): Modifier = if (placement == null) {
    this
} else {
    pointerInput(swipe, placement, onSwitch) {
        val target = if (placement == CameraPlacement.Outdoor) CameraPlacement.Indoor else CameraPlacement.Outdoor
        val direction = if (placement == CameraPlacement.Outdoor) -1f else 1f
        val fling = SWIPE_FLING_VELOCITY.toPx()
        awaitEachGesture {
            val width = size.width.toFloat()
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop * direction > 0f && currentEvent.changes.count { it.pressed } == 1) {
                    change.consume()
                    swipe.dragBy(overSlop, width, direction)
                }
            } ?: return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPointerInputChange(start)
            var lifted = false
            try {
                lifted = horizontalDrag(start.id) { change ->
                    velocity.addPointerInputChange(change)
                    swipe.dragBy(change.positionChange().x, width, direction)
                    change.consume()
                }
                // The finger-up isn't handed to the callback; without it a quick drag held still
                // before letting go would keep its earlier speed and switch anyway.
                if (lifted) currentEvent.changes.firstOrNull { it.changedToUpIgnoreConsumed() }?.let(velocity::addPointerInputChange)
            } finally {
                // Also when the gesture is cut short: the cards never stay pushed half out.
                swipe.letGo(if (lifted) velocity.calculateVelocity().x else null, width, direction, fling) { onSwitch(target) }
            }
        }
    }
}

/** How far across the page a slow swipe has to carry the cards before letting go switches lists. */
private const val SWIPE_COMMIT_FRACTION = 0.3f

/** A flick at least this fast switches however short it was. */
private val SWIPE_FLING_VELOCITY = 600.dp

/** How much a card pushed all the way out has faded. */
private const val SWIPE_FADE = 0.8f

/** Where across the page the other list's cards start coming in from. */
private const val SWIPE_ENTRY_FRACTION = 0.3f

private const val SWIPE_OUT_MS = 140
private const val SWIPE_IN_MS = 260
