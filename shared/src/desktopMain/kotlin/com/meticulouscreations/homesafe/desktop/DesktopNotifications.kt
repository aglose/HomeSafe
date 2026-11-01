package com.meticulouscreations.homesafe.desktop

import com.meticulouscreations.homesafe.navigation.DoorDeepLink
import com.meticulouscreations.homesafe.navigation.DoorDeepLinks
import com.meticulouscreations.homesafe.navigation.PackageDeepLink
import com.meticulouscreations.homesafe.navigation.PackageDeepLinks
import com.meticulouscreations.homesafe.navigation.FinanceDeepLink
import com.meticulouscreations.homesafe.navigation.FinanceDeepLinks
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.navigation.MomentDeepLinks
import com.meticulouscreations.homesafe.navigation.ParkingDeepLink
import com.meticulouscreations.homesafe.navigation.ParkingDeepLinks
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.navigation.WeatherDeepLinks
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A notification for the desktop to show, and what a click on it opens: [link], the deep link's
 * URI (`homesafe://…`, as Android puts on its notifications), which [open] hands to the deep link
 * the shell is already listening on ([MomentDeepLinks] and the like). A URI rather than a lambda,
 * so the Mac's Notification Center can keep it with the notification and a click on one from
 * before the app last started still opens what it was about.
 */
class DesktopNotice(val title: String, val body: String, val urgent: Boolean, val link: String?) {
    fun open() {
        link?.let(DesktopNotifications::open)
    }
}

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

    /** Opens what a notification's [link][DesktopNotice.link] names, routed as Android's MainActivity routes a tap on one. */
    fun open(link: String) {
        WeatherDeepLink.fromUri(link)?.let { return WeatherDeepLinks.open(it) }
        DoorDeepLink.fromUri(link)?.let { return DoorDeepLinks.open(it) }
        PackageDeepLink.fromUri(link)?.let { return PackageDeepLinks.open(it) }
        ParkingDeepLink.fromUri(link)?.let { return ParkingDeepLinks.open(it) }
        FinanceDeepLink.fromUri(link)?.let { return FinanceDeepLinks.open(it) }
        MomentDeepLink.fromUri(link)?.let(MomentDeepLinks::open)
    }
}
