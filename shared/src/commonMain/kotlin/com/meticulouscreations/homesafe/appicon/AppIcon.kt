package com.meticulouscreations.homesafe.appicon

import com.meticulouscreations.homesafe.PlatformContext
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.app_icon_arcade
import homesafe.shared.generated.resources.app_icon_bauhaus
import homesafe.shared.generated.resources.app_icon_blueprint
import homesafe.shared.generated.resources.app_icon_classic
import homesafe.shared.generated.resources.app_icon_cobalt
import homesafe.shared.generated.resources.app_icon_constellation
import homesafe.shared.generated.resources.app_icon_glitch
import homesafe.shared.generated.resources.app_icon_midnight
import homesafe.shared.generated.resources.app_icon_neon
import homesafe.shared.generated.resources.app_icon_paper
import homesafe.shared.generated.resources.app_icon_peephole
import homesafe.shared.generated.resources.app_icon_porch
import homesafe.shared.generated.resources.app_icon_radar
import homesafe.shared.generated.resources.app_icon_sage
import homesafe.shared.generated.resources.app_icon_terracotta
import homesafe.shared.generated.resources.app_icon_vault
import homesafe.shared.generated.resources.settings_app_icon_arcade
import homesafe.shared.generated.resources.settings_app_icon_bauhaus
import homesafe.shared.generated.resources.settings_app_icon_blueprint
import homesafe.shared.generated.resources.settings_app_icon_classic
import homesafe.shared.generated.resources.settings_app_icon_cobalt
import homesafe.shared.generated.resources.settings_app_icon_constellation
import homesafe.shared.generated.resources.settings_app_icon_glitch
import homesafe.shared.generated.resources.settings_app_icon_midnight
import homesafe.shared.generated.resources.settings_app_icon_neon
import homesafe.shared.generated.resources.settings_app_icon_paper
import homesafe.shared.generated.resources.settings_app_icon_peephole
import homesafe.shared.generated.resources.settings_app_icon_porch
import homesafe.shared.generated.resources.settings_app_icon_radar
import homesafe.shared.generated.resources.settings_app_icon_sage
import homesafe.shared.generated.resources.settings_app_icon_terracotta
import homesafe.shared.generated.resources.settings_app_icon_vault
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/**
 * The icons the app can wear on the home screen, chosen in Settings. The artwork is generated
 * by tools/app-icons from one description per icon: Android's adaptive icons and activity-aliases
 * (`AppIcon<Key>` in androidApp's manifest), iOS's `AppIcon-<Key>` icon sets, and the [preview]
 * the picker shows. An icon added here needs adding there too, under the same name.
 */
enum class AppIcon(val label: StringResource, val preview: DrawableResource) {
    CLASSIC(Res.string.settings_app_icon_classic, Res.drawable.app_icon_classic),
    TERRACOTTA(Res.string.settings_app_icon_terracotta, Res.drawable.app_icon_terracotta),
    MIDNIGHT(Res.string.settings_app_icon_midnight, Res.drawable.app_icon_midnight),
    PAPER(Res.string.settings_app_icon_paper, Res.drawable.app_icon_paper),
    BLUEPRINT(Res.string.settings_app_icon_blueprint, Res.drawable.app_icon_blueprint),
    SAGE(Res.string.settings_app_icon_sage, Res.drawable.app_icon_sage),
    COBALT(Res.string.settings_app_icon_cobalt, Res.drawable.app_icon_cobalt),
    PORCH(Res.string.settings_app_icon_porch, Res.drawable.app_icon_porch),
    NEON(Res.string.settings_app_icon_neon, Res.drawable.app_icon_neon),
    ARCADE(Res.string.settings_app_icon_arcade, Res.drawable.app_icon_arcade),
    VAULT(Res.string.settings_app_icon_vault, Res.drawable.app_icon_vault),
    BAUHAUS(Res.string.settings_app_icon_bauhaus, Res.drawable.app_icon_bauhaus),
    RADAR(Res.string.settings_app_icon_radar, Res.drawable.app_icon_radar),
    CONSTELLATION(Res.string.settings_app_icon_constellation, Res.drawable.app_icon_constellation),
    GLITCH(Res.string.settings_app_icon_glitch, Res.drawable.app_icon_glitch),
    PEEPHOLE(Res.string.settings_app_icon_peephole, Res.drawable.app_icon_peephole),
    ;

    /** The name the platforms know this icon by: `Terracotta` in `AppIconTerracotta` and `AppIcon-Terracotta`. */
    val key: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        /** The one the app installs with: on iOS the primary icon, on Android the alias enabled in the manifest. */
        val DEFAULT = CLASSIC
    }
}

/**
 * Switches the app's home-screen icon. Which one is in use is the platform's to know (the enabled
 * alias on Android, `alternateIconName` on iOS), so it is read from there rather than kept here.
 */
interface AppIconSwitcher {
    /** Whether this device can change the icon at all. */
    val supported: Boolean

    /** The icon the home screen shows now. */
    fun current(): AppIcon

    /** Puts [icon] on the home screen. False when the platform refused; [current] is unchanged then. */
    suspend fun set(icon: AppIcon): Boolean
}

expect fun createAppIconSwitcher(context: PlatformContext): AppIconSwitcher
