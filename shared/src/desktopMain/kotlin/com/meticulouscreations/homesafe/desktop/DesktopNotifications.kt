package com.meticulouscreations.homesafe.desktop

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A notification for the desktop to show, and what a click on it opens.
 *
 * [open] hands the notification's subject to the deep link the shell is already listening on
 * ([com.meticulouscreations.homesafe.navigation.MomentDeepLinks] and the like), as Android's
 * notification tap and iOS's notification-center delegate do.
 */
class DesktopNotice(val title: String, val body: String, val urgent: Boolean, val open: (() -> Unit)?)

/**
 * The hand-off between the notifiers in the data layer, which have no window, and the desktop
 * app, which shows each [DesktopNotice] from its menu-bar icon (`DesktopTray`). A few are kept
 * for a window that starts listening late; beyond that the oldest go, as a notification centre's
 * would.
 */
object DesktopNotifications {
    private val posted = MutableSharedFlow<DesktopNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val notices: SharedFlow<DesktopNotice> = posted.asSharedFlow()

    fun post(notice: DesktopNotice) {
        posted.tryEmit(notice)
    }
}
