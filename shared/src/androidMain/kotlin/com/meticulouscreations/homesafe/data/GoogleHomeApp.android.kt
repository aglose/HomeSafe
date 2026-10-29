package com.meticulouscreations.homesafe.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.meticulouscreations.homesafe.PlatformContext

/** Google Home's Android package (it kept its Chromecast-era name). The manifest's `<queries>` names it too: without that, Android 11+ hides it from this app. */
private const val GOOGLE_HOME_PACKAGE = "com.google.android.apps.chromecast.app"

/**
 * Launches Google Home the way its own icon would. When it isn't installed, its Play Store
 * page, or the same page on the web for a phone without the store. Whether it is installed is
 * asked each time: it can come and go while the app runs.
 */
private class AndroidGoogleHomeApp(private val context: Context) : GoogleHomeApp {
    override fun open(): Boolean {
        val candidates = listOfNotNull(
            context.packageManager.getLaunchIntentForPackage(GOOGLE_HOME_PACKAGE),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_HOME_PACKAGE")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_HOME_PACKAGE")),
        )
        return candidates.any { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}

actual fun createGoogleHomeApp(context: PlatformContext): GoogleHomeApp = AndroidGoogleHomeApp(context.context.applicationContext)
