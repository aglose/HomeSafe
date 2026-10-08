package com.meticulouscreations.homesafe.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.NavigationEventState
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Material's predictive back curve: the surface answers the first centimetre of the swipe
 * readily and slows as the finger nears the point of no return.
 */
private val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/**
 * Back, for a surface that animates with the gesture rather than only after it: the drawer, the
 * camera quick look, an app opened from the drawer, an in-app page stack. Screens on a Navigation 3
 * back stack don't need this — `NavDisplay` scrubs their `predictivePopTransitionSpec` itself.
 *
 * Built on the NavigationEvent library's [NavigationBackHandler], the same dispatcher Navigation 3
 * and the platform's own back (Android's predictive back gesture, iOS's edge swipe, Escape on
 * desktop) all feed, so whichever surface registered last and is enabled is the one Back reaches.
 */
@Stable
internal class PredictiveBack(
    internal val events: NavigationEventState<NavigationEventInfo.None>,
    private val scope: CoroutineScope,
) {
    /** The swipe's own progress, 0 to 1, while one is under way; null between swipes. */
    internal val gestureProgress: Float?
        get() = (events.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress

    /** True from the moment a back swipe starts until it is let go of. */
    val inProgress: Boolean
        get() = events.transitionState is NavigationEventTransitionState.InProgress

    /**
     * How far back the surface has been pulled: 0 at rest, 1 at the end of a full swipe, eased as
     * Material's own predictive back surfaces are. Let go short of the commit point it settles
     * back to 0; once the swipe commits it reads 0 at once, and the handler is handed the value it
     * let go at, so the surface carries on from exactly where the finger left it.
     */
    val progress: Float
        get() {
            val raw = gestureProgress ?: return settling
            return PredictiveBackEasing.transform(raw).also { lastProgress = it }
        }

    /** Bumped each time a swipe is let go of short of the commit point. */
    internal var cancellations by mutableIntStateOf(0)
        private set

    // The value [progress] showed last; a gesture's callbacks arrive after its state has gone idle.
    private var lastProgress = 0f
    private var settling by mutableFloatStateOf(0f)
    private var settleJob: Job? = null

    internal fun onCancelled() {
        settleJob?.cancel()
        settling = lastProgress
        lastProgress = 0f
        cancellations++
        settleJob = scope.launch {
            animate(settling, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { value, _ -> settling = value }
        }
    }

    internal fun onCompleted(): Float {
        settleJob?.cancel()
        settling = 0f
        return lastProgress.also { lastProgress = 0f }
    }
}

/**
 * Registers a back handler, enabled while [enabled], and returns its gesture to animate with.
 * [onBack] runs when a back swipe commits (or the back button is pressed), with the eased
 * [PredictiveBack.progress] it was let go at — 0 for a button press — for a surface to carry on
 * its exit from there instead of jumping back to rest first.
 *
 * Where nothing dispatches back (a preview, a headless test) it registers nothing.
 */
@Composable
internal fun rememberPredictiveBack(enabled: Boolean = true, onBack: (releasedAt: Float) -> Unit): PredictiveBack {
    val events = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    val scope = rememberCoroutineScope()
    val back = remember(events, scope) { PredictiveBack(events, scope) }
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(
            state = events,
            isBackEnabled = enabled,
            onBackCancelled = back::onCancelled,
            onBackCompleted = { onBack(back.onCompleted()) },
        )
    }
    return back
}

/**
 * The transition for an `AnimatedContent` page stack whose Back is predictive, to hand to
 * `Transition.AnimatedContent`: while [back] is mid-swipe the change from [target] to [previous]
 * is scrubbed by the finger, as Navigation 3 scrubs a screen's pop. Let go past the commit point,
 * the caller's back handler drops the page — [previous] becomes [target] — and the pop carries on
 * from where the finger left it; let go short of it, the scrub runs back to the page it started on.
 * Any other change of [target] animates as `AnimatedContent` would on its own.
 *
 * [previous] is null when this stack has nothing to go back to (Back is someone else's then).
 */
@Composable
internal fun <S : Any> rememberPredictiveBackTransition(target: S, previous: S?, back: PredictiveBack, label: String): Transition<S> {
    val seekable = remember { SeekableTransitionState(target) }
    val transition = rememberTransition(seekable, label)
    val cancellations = back.cancellations
    LaunchedEffect(seekable, target, previous, cancellations) {
        if (seekable.currentState != target) {
            // A page pushed or popped, or a swipe that committed: on from wherever the scrub got to.
            seekable.animateTo(target)
        } else if (seekable.targetState != target) {
            // A swipe let go of early: run the scrub back to zero at the pace it would have gone
            // forward, then settle on the page it never left.
            val from = seekable.fraction
            val durationNanos = from * transition.totalDurationNanos
            val start = withFrameNanos { it }
            var fraction = from
            while (fraction > 0f) {
                val elapsed = withFrameNanos { it } - start
                fraction = if (durationNanos > 0f) (from * (1f - elapsed / durationNanos)).coerceAtLeast(0f) else 0f
                if (fraction > 0f) seekable.seekTo(fraction)
            }
            seekable.snapTo(target)
        }
        if (previous == null) return@LaunchedEffect
        snapshotFlow { back.gestureProgress }.collect { progress ->
            if (progress != null) seekable.seekTo(progress, previous)
        }
    }
    return transition
}
