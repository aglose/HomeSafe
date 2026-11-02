package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

/**
 * A WKWebView a point wide. It has to be in a window, or iOS stops its timers along with the
 * page's own, but it's behind everything and nobody sees it. Its own non-persistent store keeps
 * a resort's cookies out of everything else.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosSkiBrowser : SkiBrowser {
    override val isSupported = true

    private val oneAtATime = Mutex()

    override suspend fun read(url: String, script: String, timeoutMillis: Long): String? = oneAtATime.withLock {
        withContext(Dispatchers.Main) {
            val address = NSURL.URLWithString(url) ?: return@withContext null
            val configuration = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore() }
            val view = WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = configuration)
            view.alpha = 0.01
            keyWindow()?.insertSubview(view, atIndex = 0)
            try {
                view.loadRequest(NSURLRequest.requestWithURL(address))
                withTimeoutOrNull(timeoutMillis) {
                    while (true) {
                        delay(SkiBrowser.POLL_MILLIS)
                        view.evaluate(script)?.let { return@withTimeoutOrNull it }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                view.stopLoading()
                view.removeFromSuperview()
            }
        }
    }

    private suspend fun WKWebView.evaluate(script: String): String? = suspendCancellableCoroutine { done ->
        evaluateJavaScript(script) { result, _ -> done.resume(result as? String) }
    }

    private fun keyWindow(): UIWindow? = UIApplication.sharedApplication.keyWindow ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
}

actual fun createSkiBrowser(platformContext: PlatformContext): SkiBrowser = IosSkiBrowser()
