package com.meticulouscreations.homesafe.appicon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.meticulouscreations.homesafe.data.AlertNotificationPoster
import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import com.meticulouscreations.homesafe.navigation.DoorDeepLink
import com.meticulouscreations.homesafe.navigation.FinanceDeepLink
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The shortcuts a long press on the launcher icon offers: Doors, Radar and Budget, each the deep
 * link a notification of that kind opens (MainActivity hands it to the shell, as it does a tap).
 *
 * Dynamic rather than declared in the manifest because a shortcut belongs to one launcher entry,
 * and which one is enabled depends on the icon picked (see [AppIconSwitcher]): published again,
 * on the alias now enabled, every time the app starts and whenever the icon changes. The intents
 * name MainActivity itself, like [appLaunchIntent], so no alias switch can strand one.
 */
object AppShortcuts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Shortcut(val id: String, @param:StringRes val label: Int, @param:DrawableRes val glyph: Int, val uri: String)

    private val shortcuts = listOf(
        Shortcut("doors", R.string.shortcut_doors, R.drawable.ic_notification_door, DoorDeepLink().toUri()),
        Shortcut("radar", R.string.shortcut_radar, R.drawable.ic_notification_weather_rain, WeatherDeepLink(view = WeatherDeepLink.View.RADAR).toUri()),
        Shortcut("budget", R.string.shortcut_budget, R.drawable.ic_notification_budget, FinanceDeepLink(FinanceTab.BUDGET).toUri()),
    )

    /**
     * Publishes the shortcuts on [launcherAlias] (the launcher entry enabled now; by default the
     * one the launcher's own intent names), off the main thread, so a cold start never waits on
     * it. Skipped when the launcher already has them on that entry.
     */
    fun publish(context: Context, launcherAlias: ComponentName? = null) {
        val app = context.applicationContext
        scope.launch {
            runCatching { publishNow(app, launcherAlias) }.onFailure { Log.w(TAG, "couldn't publish the launcher shortcuts", it) }
        }
    }

    private fun publishNow(context: Context, launcherAlias: ComponentName?) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val alias = launcherAlias ?: context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return
        val target = context.appLaunchIntent()?.component ?: return
        val current = manager.dynamicShortcuts
        val upToDate = current.map { it.id } == shortcuts.map { it.id } && current.all { it.activity == alias && it.intent?.component == target }
        if (upToDate) return
        manager.setDynamicShortcuts(
            shortcuts.map { shortcut ->
                val label = context.getString(shortcut.label)
                ShortcutInfo.Builder(context, shortcut.id)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(icon(context, shortcut.glyph))
                    .setActivity(alias)
                    // NEW_TASK alone: the running app (singleTask) comes forward with the link rather than starting over.
                    .setIntent(Intent(Intent.ACTION_VIEW, shortcut.uri.toUri()).setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    .build()
            },
        )
    }

    /**
     * The notification glyph, white, on the app's orange, as an adaptive icon: the glyphs are
     * drawn for the status bar (white only), which would vanish on a light launcher's menu.
     */
    private fun icon(context: Context, @DrawableRes glyph: Int): Icon {
        val density = context.resources.displayMetrics.density
        val size = (ADAPTIVE_SIZE_DP * density).roundToInt()
        val glyphSize = (GLYPH_SIZE_DP * density).roundToInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AlertNotificationPoster.ACCENT_COLOR)
        val inset = (size - glyphSize) / 2
        checkNotNull(context.getDrawable(glyph)).mutate().apply {
            setBounds(inset, inset, inset + glyphSize, inset + glyphSize)
            draw(canvas)
        }
        return Icon.createWithAdaptiveBitmap(bitmap)
    }

    private const val TAG = "HomeSafeShortcuts"

    /** An adaptive icon's full square; the launcher's mask keeps the middle 72dp or so. */
    private const val ADAPTIVE_SIZE_DP = 108

    private const val GLYPH_SIZE_DP = 44
}
