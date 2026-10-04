package com.meticulouscreations.homesafe.network

import android.content.Context
import android.content.Intent
import com.meticulouscreations.homesafe.PlatformContext

/** Tailscale's Android package. The manifest's `<queries>` names it too: without that, Android 11+ hides it from this app. */
private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/** Launches Tailscale the way its own icon would. [canOpen] is asked each time: it can be installed or removed while the app runs. */
private class AndroidTailscaleApp(private val context: Context) : TailscaleApp {
    private fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)

    override val canOpen: Boolean get() = launchIntent() != null

    override fun open(): Boolean {
        val intent = launchIntent()?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

actual fun createTailscaleApp(context: PlatformContext): TailscaleApp = AndroidTailscaleApp(context.context.applicationContext)
