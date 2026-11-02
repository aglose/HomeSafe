package com.meticulouscreations.homesafe.weather.ski.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/** A WebView on no screen. Its pictures are left out: nothing is looking at them, and a resort's homepage is mostly pictures. */
private class AndroidSkiBrowser(private val context: Context) : SkiBrowser {
    override val isSupported = true

    private val oneAtATime = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun read(url: String, script: String, timeoutMillis: Long): String? = oneAtATime.withLock {
        withContext(Dispatchers.Main) {
            val view = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkImage = true
                webViewClient = WebViewClient()
            }
            try {
                view.loadUrl(url)
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
                // Destroyed a moment later, off the stack of whatever it may be in the middle of.
                Handler(Looper.getMainLooper()).post {
                    view.stopLoading()
                    view.destroy()
                }
            }
        }
    }

    /** The script's answer. WebView hands it over as JSON: a string in quotes, or `null`. */
    private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { done ->
        evaluateJavascript(script) { raw ->
            val text = runCatching { Json.parseToJsonElement(raw ?: "null").jsonPrimitive }.getOrNull()
            done.resume((text as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
        }
    }
}

actual fun createSkiBrowser(platformContext: PlatformContext): SkiBrowser = AndroidSkiBrowser(platformContext.context.applicationContext)
