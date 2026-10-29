package com.meticulouscreations.homesafe.appicon

import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The icon the desktop app wears in the Dock and its window, chosen in Settings as on the phones.
 * There is no installed icon to swap, so the choice is kept in the preferences and the app draws
 * it into the Dock as it runs (`DesktopAppIcon` in :desktopApp follows [icon]). The packaged
 * app's own icon, the one Finder and the Dock show before it starts, stays the classic one.
 */
object DesktopAppIcon : AppIconSwitcher {
    private const val KEY = "app_icon"
    private val preferences = DesktopStorage.preferences("appearance")

    private val chosen = MutableStateFlow(
        preferences.get(KEY, null)?.let { key -> AppIcon.entries.firstOrNull { it.key == key } } ?: AppIcon.DEFAULT,
    )

    /** The icon to draw: what was last chosen. */
    val icon: StateFlow<AppIcon> = chosen.asStateFlow()

    override val supported = true

    override fun current(): AppIcon = chosen.value

    override suspend fun set(icon: AppIcon): Boolean {
        chosen.value = icon
        preferences.put(KEY, icon.key)
        preferences.flush()
        return true
    }
}

actual fun createAppIconSwitcher(context: PlatformContext): AppIconSwitcher = DesktopAppIcon
