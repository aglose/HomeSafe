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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_shortcut_command
import homesafe.shared.generated.resources.shell_shortcut_control
import homesafe.shared.generated.resources.shell_tooltip_with_shortcut
import org.jetbrains.compose.resources.stringResource

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
