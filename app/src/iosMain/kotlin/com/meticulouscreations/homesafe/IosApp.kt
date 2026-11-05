package com.meticulouscreations.homesafe

import com.meticulouscreations.homesafe.data.IosNotificationTaps
import com.meticulouscreations.homesafe.data.IosPushToken
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.fitness.IosLiveActivities
import com.meticulouscreations.homesafe.fitness.IosLiveWorkoutSurface
import com.meticulouscreations.homesafe.fitness.live.LiveWorkoutPresenter
import com.meticulouscreations.homesafe.fitness.live.LiveWorkouts
import com.meticulouscreations.homesafe.navigation.FitnessDeepLink
import com.meticulouscreations.homesafe.navigation.FitnessDeepLinks
import com.meticulouscreations.homesafe.ui.components.IosWebRtc
import com.meticulouscreations.homesafe.ui.components.IosWebRtcPeerFactory
import com.meticulouscreations.homesafe.weather.data.IosWeatherRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
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
 * [startIosApp] with the WebRTC engine and the workout's Live Activity ([IosLiveActivities],
 * `WorkoutLiveActivities.swift`), both of which live on the Swift side.
 */
@Suppress("unused")
fun startIosApp(webRtc: IosWebRtcPeerFactory?, liveActivities: IosLiveActivities?) {
    if (liveActivities != null) {
        LiveWorkoutPresenter.install(IosLiveWorkoutSurface(liveActivities))
        FitnessDeepLinks.workoutOnScreen = { LiveWorkouts.process.workout.value?.onScreen == true }
    }
    startIosApp(webRtc)
}

/**
 * A button on the workout's Live Activity, run in the app (`WorkoutRestIntents.swift`): [action]
 * `skip` skips the rest, `adjust` lengthens it by [seconds] (shortens, below zero). The rest is
 * the process's own, so the app's page and the activity follow it. False when this process has
 * no workout to act on (iOS started the app afresh for the button): Swift then changes the
 * activity by itself.
 */
@Suppress("unused")
fun onWorkoutRestAction(action: String, seconds: Int): Boolean {
    val hub = LiveWorkouts.process
    if (hub.workout.value == null) return false
    when (action) {
        "skip" -> hub.skipRest()
        "adjust" -> hub.adjustRest(seconds)
        else -> return false
    }
    return true
}

/**
 * A link the app was opened with (SwiftUI's `onOpenURL`): a tap on the workout's Live Activity
 * arrives this way, as `homesafe://fitness?page=workout`. True when it was one of the app's.
 */
@Suppress("unused")
fun onOpenUrl(url: String): Boolean {
    val fitness = FitnessDeepLink.fromUri(url) ?: return false
    FitnessDeepLinks.open(fitness)
    return true
}

/**
 * The relay's "rain on the way", when iOS wakes the app for it (`AppDelegate.swift`): [done] tells
 * iOS the app has finished. Its banner is already up; this gives it the app's own picture.
 */
@Suppress("unused")
fun onWeatherPush(payload: String, key: String, done: () -> Unit) {
    MainScope().launch {
        try {
            IosApp.graph.weatherAlertCheck.onRelayPush(payload, key, fallbackTitle = "", fallbackBody = "", shown = true)
        } finally {
            done()
        }
    }
}

/**
 * The relay's skiing notification, when iOS wakes the app for it (`AppDelegate.swift`): [done] tells
 * iOS the app has finished. Its banner is already up, in English; this puts the app's own words in its place.
 */
@Suppress("unused")
fun onSkiPush(payload: String, key: String, done: () -> Unit) {
    MainScope().launch {
        try {
            IosApp.graph.skiNoticeCheck.onRelayPush(payload, key, fallbackTitle = "", fallbackBody = "", shown = true)
        } finally {
            done()
        }
    }
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
