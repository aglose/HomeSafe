package com.meticulouscreations.homesafe

import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.ui.components.IosWebRtc
import com.meticulouscreations.homesafe.ui.components.IosWebRtcPeerFactory

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
