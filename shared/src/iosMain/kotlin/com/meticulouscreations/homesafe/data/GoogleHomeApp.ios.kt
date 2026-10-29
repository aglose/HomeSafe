package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Google Home's own URL scheme. Google doesn't document one, so it is only used when iOS says
 * something installed answers to it (`canOpenURL`, which needs the scheme listed under
 * `LSApplicationQueriesSchemes` in Info.plist); otherwise the App Store page, whose button reads
 * Open when the app is installed.
 */
private const val GOOGLE_HOME_SCHEME_URL = "googlehome://"
private const val GOOGLE_HOME_APP_STORE_URL = "https://apps.apple.com/app/id680819774"

private object IosGoogleHomeApp : GoogleHomeApp {
    override fun open(): Boolean {
        val application = UIApplication.sharedApplication
        val url = NSURL.URLWithString(GOOGLE_HOME_SCHEME_URL)?.takeIf { application.canOpenURL(it) }
            ?: NSURL.URLWithString(GOOGLE_HOME_APP_STORE_URL)
            ?: return false
        application.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        return true
    }
}

actual fun createGoogleHomeApp(context: PlatformContext): GoogleHomeApp = IosGoogleHomeApp
