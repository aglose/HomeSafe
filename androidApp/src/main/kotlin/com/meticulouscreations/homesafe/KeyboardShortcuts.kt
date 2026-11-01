package com.meticulouscreations.homesafe

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.meticulouscreations.homesafe.navigation.ShellCommand
import com.meticulouscreations.homesafe.navigation.ShellCommands
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.InnerApp

/**
 * The shell's keyboard shortcuts with a hardware keyboard (a tablet's, a Chromebook's, Android's
 * desktop windows): Ctrl and a key, the same keys the Mac's menu bar has with ⌘ (see
 * docs/desktop.md). Heard at the Activity, before anything on screen has the key, so they work
 * wherever focus is; and only while the shell is up to act on them, so on the sign-in screen
 * Ctrl+1 is just a key.
 */
internal object KeyboardShortcuts {
    private class Shortcut(val keyCode: Int, val label: Int, val shift: Boolean = false, val command: ShellCommand)

    private val shortcuts = listOf(
        Shortcut(KeyEvent.KEYCODE_1, R.string.shortcut_home, command = ShellCommand.Go(TopLevelRoute.Home)),
        Shortcut(KeyEvent.KEYCODE_2, R.string.shortcut_moments, command = ShellCommand.Go(TopLevelRoute.Moments)),
        Shortcut(KeyEvent.KEYCODE_3, R.string.shortcut_settings, command = ShellCommand.Go(TopLevelRoute.Settings)),
        Shortcut(KeyEvent.KEYCODE_4, R.string.shortcut_weather, command = ShellCommand.Open(InnerApp.WEATHER)),
        Shortcut(KeyEvent.KEYCODE_5, R.string.shortcut_atlas, command = ShellCommand.Open(InnerApp.ATLAS)),
        Shortcut(KeyEvent.KEYCODE_6, R.string.shortcut_fitness, command = ShellCommand.Open(InnerApp.FITNESS)),
        Shortcut(KeyEvent.KEYCODE_7, R.string.shortcut_finance, command = ShellCommand.Open(InnerApp.FINANCE)),
        Shortcut(KeyEvent.KEYCODE_8, R.string.shortcut_packages, command = ShellCommand.Open(InnerApp.PACKAGES)),
        Shortcut(KeyEvent.KEYCODE_K, R.string.shortcut_assistant, command = ShellCommand.Assistant),
        Shortcut(KeyEvent.KEYCODE_S, R.string.shortcut_sidebar, shift = true, command = ShellCommand.ToggleSidebar),
    )

    /** Sends [event]'s shortcut to the shell, if it is one and the shell is up; true when it was taken. */
    fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 || !ShellCommands.shellUp.value) return false
        if (!event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val shortcut = shortcuts.firstOrNull { it.keyCode == event.keyCode && it.shift == event.isShiftPressed } ?: return false
        // Only where there is a sidebar to show or hide; elsewhere Ctrl+Shift+S is left to whatever has focus.
        if (shortcut.command == ShellCommand.ToggleSidebar && !ShellCommands.sidebarAvailable.value) return false
        ShellCommands.send(shortcut.command)
        return true
    }

    /** The same list for the system's Keyboard Shortcuts helper, named as the shell names each destination. */
    fun group(context: Context): KeyboardShortcutGroup = KeyboardShortcutGroup(
        context.getString(R.string.shortcuts_group),
        shortcuts.map { shortcut ->
            val modifiers = KeyEvent.META_CTRL_ON or if (shortcut.shift) KeyEvent.META_SHIFT_ON else 0
            KeyboardShortcutInfo(context.getString(shortcut.label), shortcut.keyCode, modifiers)
        },
    )
}
