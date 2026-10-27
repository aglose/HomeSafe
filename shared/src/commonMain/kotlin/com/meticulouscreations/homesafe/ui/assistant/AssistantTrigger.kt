package com.meticulouscreations.homesafe.ui.assistant

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onLongClick

/**
 * Makes a bottom bar the way to the assistant: held still for a long press anywhere on it (on a tab
 * or between them), it opens the assistant out of the bar, or closes it if it is up. A tap still
 * goes to the tab under the finger, and a swipe across the bar still does whatever it did; only a
 * press that stays put past the long-press timeout is taken, and the tab under it is then not
 * selected. For accessibility the bar gets a long-click action that does the same.
 *
 * Nothing changes on the bar itself: the press is the whole of the way in. With no [host] (no
 * assistant installed, as in previews) this is no modifier at all.
 */
fun Modifier.assistantTrigger(host: AssistantHost?): Modifier = if (host == null) this else this then AssistantTriggerElement(host)

private data class AssistantTriggerElement(val host: AssistantHost) : ModifierNodeElement<AssistantTriggerNode>() {
    override fun create() = AssistantTriggerNode(host)

    override fun update(node: AssistantTriggerNode) {
        node.host = host
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "assistantTrigger"
    }
}

private class AssistantTriggerNode(var host: AssistantHost) :
    DelegatingNode(),
    GlobalPositionAwareModifierNode,
    SemanticsModifierNode,
    CompositionLocalConsumerModifierNode {

    private var bounds = Rect.Zero

    init {
        delegate(SuspendingPointerInputModifierNode { detectHold() })
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        bounds = coordinates.boundsInWindow()
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        onLongClick {
            host.toggle(bounds)
            true
        }
    }

    /**
     * Watches each press on the way down to the tabs (the Initial pass), without taking it, until it
     * has been held for the long-press timeout without moving. From then on the press is the
     * assistant's: what's left of it is consumed so the tab beneath reads a cancelled press.
     */
    private suspend fun PointerInputScope.detectHold() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            // Non-null when the press ends some other way first: let go, moved, a second finger, or
            // taken by something nearer the root (the drawer's swipe).
            val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var ended = false
                while (!ended) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    ended = change == null ||
                        !change.pressed ||
                        change.isConsumed ||
                        event.changes.size > 1 ||
                        (change.position - down.position).getDistance() > slop
                }
                true
            }
            if (endedEarly != null) return@awaitEachGesture
            currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.LongPress)
            host.toggle(bounds)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
