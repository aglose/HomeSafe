package com.meticulouscreations.homesafe.appicon

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIApplication
import platform.UIKit.alternateIconName
import platform.UIKit.setAlternateIconName
import platform.UIKit.supportsAlternateIcons
import kotlin.coroutines.resume

/**
 * iOS's alternate icons: the `AppIcon-<Key>` sets in the asset catalog, which Xcode lists in the
 * built Info.plist (ASSETCATALOG_COMPILER_INCLUDE_ALL_APPICON_ASSETS). [AppIcon.DEFAULT] is the
 * primary icon, named nil. iOS tells the person itself, with an alert, once the icon has changed.
 */
private object IosAppIconSwitcher : AppIconSwitcher {
    private fun iconName(icon: AppIcon): String? = if (icon == AppIcon.DEFAULT) null else "AppIcon-${icon.key}"

    override val supported: Boolean get() = UIApplication.sharedApplication.supportsAlternateIcons

    override fun current(): AppIcon {
        val name = UIApplication.sharedApplication.alternateIconName ?: return AppIcon.DEFAULT
        return AppIcon.entries.firstOrNull { iconName(it) == name } ?: AppIcon.DEFAULT
    }

    override suspend fun set(icon: AppIcon): Boolean = suspendCancellableCoroutine { continuation ->
        UIApplication.sharedApplication.setAlternateIconName(iconName(icon)) { error ->
            continuation.resume(error == null)
        }
    }
}

actual fun createAppIconSwitcher(context: PlatformContext): AppIconSwitcher = IosAppIconSwitcher
