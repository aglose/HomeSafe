package com.meticulouscreations.homesafe.desktop

import com.meticulouscreations.homesafe.isMacOs
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import javax.swing.SwingUtilities

/**
 * The Mac's own notifications (UserNotifications), posted by the app itself: they carry its name
 * and icon, and each keeps the deep link it opens ([DesktopNotice.link]), which a click hands back,
 * so it opens what that one was about however many came after it, and after HomeSafe has quit and
 * started again. By way of a small Swift library in the app's resources (`macos/Notifications.swift`),
 * called through JNA.
 *
 * Absent anywhere but a packaged Mac app, and refused while the person hasn't allowed HomeSafe's
 * notifications (macOS asks once, the first time it starts, and the switch is in System Settings
 * from then on, asked again for each one); then [post] says no and the menu-bar icon shows it
 * instead ([HomeSafeTray]).
 */
internal object MacNotifications {
    @Suppress("FunctionName") // The library's own names, which JNA binds by.
    private interface NotificationsLibrary : Library {
        fun homesafe_notifications_start(clicked: Clicked)

        fun homesafe_notifications_post(title: String, body: String, link: String, sound: Int): Int
    }

    fun interface Clicked : Callback {
        fun invoke(link: String)
    }

    private val library: NotificationsLibrary? by lazy {
        if (!isMacOs) return@lazy null
        val resources = System.getProperty("compose.application.resources.dir") ?: return@lazy null
        val file = File(resources, LIBRARY).takeIf { it.isFile } ?: return@lazy null
        runCatching {
            Native.load(file.absolutePath, NotificationsLibrary::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }.getOrNull()
    }

    /** What brings the window up; the app sets it once it has one. */
    @Volatile
    var onOpen: () -> Unit = {}

    /** Whether the last notification went to Notification Center (where a click names its own), not the menu-bar icon's banners. */
    @Volatile
    var posting: Boolean = false
        private set

    // Held here for as long as the app runs: the library keeps it, and JNA's are collected otherwise.
    private val clicked = Clicked { link ->
        SwingUtilities.invokeLater {
            onOpen()
            if (link.isNotEmpty()) DesktopNotifications.open(link)
        }
    }

    /** Asks to notify, and listens for clicks: as the app starts, so a click that started it is heard. */
    fun start() {
        library?.homesafe_notifications_start(clicked)
    }

    /** Shows [notice] in the Mac's Notification Center; false where it can't, for the caller to show it another way. */
    fun post(notice: DesktopNotice): Boolean {
        val native = library ?: return false
        posting = native.homesafe_notifications_post(notice.title, notice.body, notice.link.orEmpty(), if (notice.urgent) 1 else 0) != 0
        return posting
    }

    private const val LIBRARY = "libHomeSafeNotifications.dylib"
}
