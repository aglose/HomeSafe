package com.meticulouscreations.homesafe.navigation

import com.meticulouscreations.homesafe.ui.screens.InnerApp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something a keyboard shortcut or a menu-bar item asks of the shell. */
sealed interface ShellCommand {
    /** One of the shell's tabs, at its root, as a tap on it in the nav would. */
    data class Go(val tab: TopLevelRoute) : ShellCommand

    /** One of the apps from the drawer: in the sidebar beside the tabs where there is one, over the shell where there isn't. */
    data class Open(val app: InnerApp) : ShellCommand

    /** Shows or hides the sidebar, where the window is wide enough to have one. */
    data object ToggleSidebar : ShellCommand
}

/**
 * The hand-off between whatever hears a shortcut outside the shell's own composition (the Mac's
 * menu bar, Android's keyboard shortcuts at the window's root) and the shell, which acts on each
 * [ShellCommand] while it is up ([FrigateAppShell][com.meticulouscreations.homesafe.ui.screens.FrigateAppShell]).
 *
 * An event stream, unlike the notifications' deep links: a shortcut pressed on the sign-in screen,
 * with no shell to act on it, means nothing later. [shellUp] says whether there is one, so a menu
 * can show its items as unavailable rather than leave them doing nothing.
 */
object ShellCommands {
    private val sent = MutableSharedFlow<ShellCommand>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val up = MutableStateFlow(false)
    private val sidebar = MutableStateFlow(false)
    private val hidden = MutableStateFlow(false)

    val commands: SharedFlow<ShellCommand> = sent.asSharedFlow()

    /** Whether a shell is on screen to act on what is sent. */
    val shellUp: StateFlow<Boolean> = up.asStateFlow()

    /** Whether the shell has a sidebar to show or hide, so the menu offers to. */
    val sidebarAvailable: StateFlow<Boolean> = sidebar.asStateFlow()

    /** Whether the sidebar has been put away ([ShellCommand.ToggleSidebar]), so the menu can offer to show it again. */
    val sidebarHidden: StateFlow<Boolean> = hidden.asStateFlow()

    fun send(command: ShellCommand) {
        sent.tryEmit(command)
    }

    internal fun shellShown(shown: Boolean) {
        up.value = shown
    }

    internal fun sidebarShown(available: Boolean, hidden: Boolean) {
        sidebar.value = available
        this.hidden.value = hidden
    }
}
