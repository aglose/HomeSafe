package com.meticulouscreations.homesafe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_shortcut_command
import homesafe.shared.generated.resources.shell_shortcut_control
import homesafe.shared.generated.resources.shell_tooltip_with_shortcut
import org.jetbrains.compose.resources.stringResource
import kotlin.math.pow

/** The modifier key a platform's shortcuts are written with: ⌘ on a Mac, Ctrl elsewhere. */
enum class ShortcutKey { Command, Control }

/**
 * The key the app's keyboard shortcuts use here, for the hints beside what they do (the sidebar's
 * ⌘1 to ⌘7, a tooltip's "(⌘R)"); null, the default, where there is no keyboard to press them on,
 * and no hints are shown. The desktop app provides it; on Android it is there while a hardware
 * keyboard is.
 */
val LocalShortcutKey = staticCompositionLocalOf<ShortcutKey?> { null }

/** How a shortcut on [key] (a digit or a symbol) is written here — "⌘1", "Ctrl+1" — or null where shortcuts aren't shown. */
@Composable
fun shortcutLabel(key: String): String? = when (LocalShortcutKey.current) {
    ShortcutKey.Command -> stringResource(Res.string.shell_shortcut_command, key)
    ShortcutKey.Control -> stringResource(Res.string.shell_shortcut_control, key)
    null -> null
}

/**
 * A plain tooltip over [content] with [text], for a control whose label is an icon: shown on
 * hover and keyboard focus with a pointer, and on a long press by touch, as Material's are. With
 * [shortcut] (see [shortcutLabel]) the tooltip ends with it, where shortcuts are shown. [text] is
 * the control's own accessibility label, which stays on the control: a tooltip never replaces it.
 *
 * [modifier] places the control in its parent (an `align`, a `weight`): it goes on a box around
 * the tooltip's own, which keeps its modifier for an anchor inside it where a parent's go unheard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(text: String, modifier: Modifier = Modifier, shortcut: String? = null, content: @Composable () -> Unit) {
    val keys = shortcut?.let { shortcutLabel(it) }
    Box(modifier, propagateMinConstraints = true) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(if (keys != null) stringResource(Res.string.shell_tooltip_with_shortcut, text, keys) else text) } },
            state = rememberTooltipState(),
        ) {
            content()
        }
    }
}

/** The pointing hand over something that acts on a click, while it is [enabled]; the ordinary arrow otherwise. */
fun Modifier.handCursor(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

/**
 * Zooms a map with a mouse's wheel (or a trackpad's two-finger scroll), about the pointer: what a
 * pinch is to a finger, which a desktop has no other way to make. [onZoom] gets the factor (above 1
 * nearer, below 1 further) and where the pointer is, in this element's coordinates. A notch of the
 * wheel is a fifth of a doubling, so five go from one zoom level to the next. Nothing on a phone,
 * where no wheel turns.
 */
fun Modifier.wheelZoom(key: Any?, onZoom: (factor: Float, at: Offset) -> Unit): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val notches = change.scrollDelta.y
            if (notches == 0f) continue
            onZoom(2f.pow(-notches / WHEEL_NOTCHES_PER_DOUBLING), change.position)
            change.consume()
        }
    }
}

private const val WHEEL_NOTCHES_PER_DOUBLING = 5f
