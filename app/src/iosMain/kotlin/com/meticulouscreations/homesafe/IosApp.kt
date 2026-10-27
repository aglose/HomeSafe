package com.meticulouscreations.homesafe

import com.meticulouscreations.homesafe.data.IosNotificationTaps
import com.meticulouscreations.homesafe.data.IosPushToken
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.ui.components.IosWebRtc
import com.meticulouscreations.homesafe.ui.components.IosWebRtcPeerFactory
import com.meticulouscreations.homesafe.weather.data.IosWeatherRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The iOS app's one graph, built at process start. It has to exist before any screen does:
 * when iOS relaunches the app in the background to deliver a home-geofence crossing, no screen
 * ever appears — but the graph's location manager and its delegate must, or the crossing is
 * lost. `iOSApp.swift` calls [startIosApp] from its initialiser for exactly that.
 */
object IosApp {
    val graph: AppGraph by lazy { createAppGraph(platformContext = PlatformContext()) }
}

/** Called once from Swift at launch — foreground or background. Idempotent. */
@Suppress("unused")
fun startIosApp() {
    // First, so a notification tap that launched the app is heard (see IosNotificationTaps).
    IosNotificationTaps.install()
    // Before launch finishes, as iOS requires of a background task's handler (see IosWeatherRefresh).
    IosWeatherRefresh.register { IosApp.graph.weatherAlertCheck }
    IosApp.graph.deviceRegistrar.start()
    IosApp.graph.presenceAutomation.start()
}

/**
 * [startIosApp] plus the app's WebRTC engine, which lives on the Swift side over the `WebRTC`
 * package (see `WebRtcPeer.ios.kt`). Without one registered, live cameras play HLS.
 */
@Suppress("unused")
fun startIosApp(webRtc: IosWebRtcPeerFactory?) {
    IosWebRtc.peerFactory = webRtc
    startIosApp()
}

/**
 * Called from Swift with the APNs device token iOS handed the app delegate, as hex: kept for
 * every later registration ([IosPushToken]), and the relay told at once, as Android's
 * `PushRegistrar` does when Firebase rotates its token. The same token at every launch
 * re-registers to no effect.
 */
@Suppress("unused")
fun onApnsToken(token: String) {
    IosPushToken.set(token)
    CoroutineScope(Dispatchers.Default).launch {
        IosApp.graph.deviceRegistrar.onPushTokenChanged(token)
            .onFailure { println("HomeSafe: push re-registration failed: ${it.message}") }
    }
}
