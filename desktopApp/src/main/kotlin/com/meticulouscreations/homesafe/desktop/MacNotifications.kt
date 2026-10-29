package com.meticulouscreations.homesafe.desktop

import com.meticulouscreations.homesafe.isMacOs
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import java.util.UUID
import javax.swing.SwingUtilities

/**
 * The Mac's own notifications (UserNotifications), posted by the app itself: they carry its name
 * and icon, and a click says which one it was, so it opens what that one was about however many
 * came after it. By way of a small Swift library in the app's resources (`macos/Notifications.swift`),
 * called through JNA.
 *
 * Absent anywhere but a packaged Mac app, and refused until the person allows HomeSafe's
 * notifications (macOS asks once, the first time it starts); then [post] says no and the
 * menu-bar icon shows each one instead ([HomeSafeTray]).
 */
internal object MacNotifications {
    @Suppress("FunctionName") // The library's own names, which JNA binds by.
    private interface NotificationsLibrary : Library {
        fun homesafe_notifications_start(clicked: Clicked, answered: Answered)

        fun homesafe_notifications_post(id: String, title: String, body: String, sound: Int)
    }

    fun interface Clicked : Callback {
        fun invoke(id: String)
    }

    fun interface Answered : Callback {
        fun invoke(allowed: Int)
    }

    private val library: NotificationsLibrary? by lazy {
        if (!isMacOs) return@lazy null
        val resources = System.getProperty("compose.application.resources.dir") ?: return@lazy null
        val file = File(resources, LIBRARY).takeIf { it.isFile } ?: return@lazy null
        runCatching {
            Native.load(file.absolutePath, NotificationsLibrary::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }.getOrNull()
    }

    @Volatile
    private var allowed = false

    /** What brings the window up; the app sets it once it has one. */
    @Volatile
    var onOpen: () -> Unit = {}

    /** The notices on screen or in Notification Center, by identifier, the newest [KEPT]. */
    private val shown = object : LinkedHashMap<String, DesktopNotice>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DesktopNotice>?): Boolean = size > KEPT
    }

    // Held here for as long as the app runs: the library keeps them, and JNA's are collected otherwise.
    private val clicked = Clicked { id ->
        val notice = synchronized(shown) { shown.remove(id) }
        // One from before the app last started has no notice here: the window opens, and that is all.
        SwingUtilities.invokeLater {
            onOpen()
            notice?.open?.invoke()
        }
    }
    private val answered = Answered { allowed = it != 0 }

    /** Whether notifications go to Notification Center (where a click names its own), not the menu-bar icon's banners. */
    val posting: Boolean get() = library != null && allowed

    /** Asks to notify, and listens for clicks: as the app starts, so a click that started it is heard. */
    fun start() {
        library?.homesafe_notifications_start(clicked, answered)
    }

    /** Shows [notice] in the Mac's Notification Center; false where it can't, for the caller to show it another way. */
    fun post(notice: DesktopNotice): Boolean {
        val native = library ?: return false
        if (!allowed) return false
        val id = UUID.randomUUID().toString()
        synchronized(shown) { shown[id] = notice }
        native.homesafe_notifications_post(id, notice.title, notice.body, if (notice.urgent) 1 else 0)
        return true
    }

    private const val LIBRARY = "libHomeSafeNotifications.dylib"
    private const val KEPT = 64
}
