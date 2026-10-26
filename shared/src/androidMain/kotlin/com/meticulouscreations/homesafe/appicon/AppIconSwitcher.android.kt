package com.meticulouscreations.homesafe.appicon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.meticulouscreations.homesafe.PlatformContext

/**
 * The activity-aliases in androidApp's manifest, one per [AppIcon], each a launcher entry for
 * MainActivity wearing that icon. Named by the manifest's namespace, which the `.debug` build
 * shares; only its package differs.
 */
private const val ALIAS_PREFIX = "com.meticulouscreations.homesafe.AppIcon"

/**
 * An icon is a launcher entry: picking one enables its alias and disables the rest, in one call so
 * the launcher never sees the app with two entries or none. DONT_KILL_APP keeps the app running
 * through it; the launcher takes a moment to redraw.
 */
private class AndroidAppIconSwitcher(private val context: Context) : AppIconSwitcher {
    private val packageManager = context.packageManager

    private fun alias(icon: AppIcon) = ComponentName(context.packageName, ALIAS_PREFIX + icon.key)

    override val supported: Boolean = true

    override fun current(): AppIcon = AppIcon.entries.firstOrNull { isEnabled(alias(it)) } ?: AppIcon.DEFAULT

    private fun isEnabled(component: ComponentName): Boolean = when (packageManager.getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true

        // Never switched: whatever the manifest says.
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> runCatching {
            packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
        }.getOrDefault(false)

        else -> false
    }

    override suspend fun set(icon: AppIcon): Boolean = runCatching {
        packageManager.setComponentEnabledSettings(
            AppIcon.entries.map {
                val state = if (it == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                PackageManager.ComponentEnabledSetting(alias(it), state, PackageManager.DONT_KILL_APP)
            },
        )
        // A launcher shortcut belongs to one entry: they move to the one now enabled.
        AppShortcuts.publish(context, alias(icon))
    }.isSuccess
}

actual fun createAppIconSwitcher(context: PlatformContext): AppIconSwitcher = AndroidAppIconSwitcher(context.context.applicationContext)

/**
 * The launcher's intent for this app, aimed at MainActivity itself rather than at the icon alias
 * the launcher shows today. A notification keeps its intent after another icon is picked, which
 * disables the old alias, and an intent for a disabled alias opens nothing.
 */
fun Context.appLaunchIntent(): Intent? {
    val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    val alias = launch.component ?: return launch
    val target = runCatching { packageManager.getActivityInfo(alias, 0).targetActivity }.getOrNull() ?: return launch
    return launch.setComponent(ComponentName(alias.packageName, target))
}
