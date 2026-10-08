package com.meticulouscreations.homesafe.baselineprofile

import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The app under test: `targetAppId` from baselineprofile/build.gradle.kts, which follows -PbenchmarkAppIdSuffix. */
val PACKAGE_NAME: String = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "com.meticulouscreations.homesafe"

/** The app's one Activity, by class name: the namespace's, whatever the variant's application id. */
private const val MAIN_ACTIVITY = "com.meticulouscreations.homesafe.MainActivity"

/** `Modifier.testTag` on the Home tab's LazyColumn — see HomeLiveViewScreen.HOME_FEED_TEST_TAG. */
private const val HOME_FEED = "home_feed"

private const val SIGN_IN_TIMEOUT_MS = 30_000L
private const val UI_TIMEOUT_MS = 5_000L

/**
 * Gets from the sign-in form to the Home tab. The benchmarkRelease variant carries the local
 * test credentials behind the "Autofill test credentials" button (see androidApp/build.gradle.kts),
 * so this is two taps; it then waits for the camera list, which is when Home reports fully drawn.
 */
fun MacrobenchmarkScope.signInToHome() {
    val autofill = device.wait(Until.findObject(By.text("Autofill test credentials")), UI_TIMEOUT_MS)
        ?: error("Sign-in form without the autofill button: build the benchmarkRelease variant with local.credentials.properties present")
    autofill.click()
    device.wait(Until.findObject(By.text("Connect")), UI_TIMEOUT_MS)?.click()
        ?: error("No Connect button")
    // A biometric-save offer can appear on a device with a fingerprint enrolled; decline it.
    device.wait(Until.findObject(By.text("Not now")), 3_000)?.click()
    check(device.wait(Until.hasObject(By.res(HOME_FEED)), SIGN_IN_TIMEOUT_MS)) {
        "Home feed did not appear within ${SIGN_IN_TIMEOUT_MS}ms — is the Frigate server reachable from the device? On screen: ${screenText()}"
    }
    device.waitForIdle()
}

/**
 * What the app is showing, for a failure's message: its words, without what is typed into its
 * fields (the sign-in form holds the server's address and the account's name).
 */
private fun MacrobenchmarkScope.screenText(): String =
    device.findObjects(By.pkg(PACKAGE_NAME))
        .filter { it.className != "android.widget.EditText" }
        .mapNotNull { node -> node.text?.takeIf { it.isNotBlank() } }
        .distinct()
        .joinToString(" | ")

/** The Home tab's camera list, ready to fling. */
fun MacrobenchmarkScope.homeFeed(): UiObject2 {
    val feed = device.findObject(By.res(HOME_FEED)) ?: error("Home feed not on screen")
    // Keep flings clear of the system gesture area, or they turn into back/recents swipes.
    feed.setGestureMargin(device.displayWidth / 5)
    return feed
}

/**
 * Before the first launch on a fresh install, so none of the system's permission sheets comes up
 * over a journey: notifications, and (Android 17) the local network the Frigate server may be on,
 * which the system words as "nearby devices" and asks for right after sign-in. A phone asks; the
 * emulators these ran on had been answered already, or are too old to ask.
 */
fun MacrobenchmarkScope.grantPermissions() {
    listOf("android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK").forEach { permission ->
        // "Unknown permission" on a release that doesn't have it: nothing to grant there, nothing to ask.
        device.executeShellCommand("pm grant $PACKAGE_NAME $permission")
    }
}

/** Two flings down and one back up: enough to compose every camera card and reuse a few. */
fun MacrobenchmarkScope.scrollHomeFeed() {
    val feed = homeFeed()
    feed.fling(Direction.DOWN)
    device.waitForIdle()
    feed.fling(Direction.DOWN)
    device.waitForIdle()
    feed.fling(Direction.UP)
    device.waitForIdle()
}

/** `Modifier.testTag`s of the weather app — see weather/ui/WeatherApp.kt, TodayScreen.kt and ForecastScreen.kt. */
private const val WEATHER_APP = "weather_app"
private const val WEATHER_TODAY = "weather_today"
private const val WEATHER_FORECAST = "weather_forecast"

/** A forecast is three requests to Open-Meteo over whatever network the device has. */
private const val FORECAST_TIMEOUT_MS = 30_000L

/**
 * A place for the weather journeys to forecast for instead of the one the app opens on. What the
 * sky draws is the weather there at this minute, and the sky is most of what a weather frame
 * costs, so a run can ask for somewhere overcast, wet or dark:
 * `-Pandroid.testInstrumentationRunnerArguments.weatherPlace=Glasgow`. Empty: wherever the app shows.
 */
private val WEATHER_PLACE: String = InstrumentationRegistry.getArguments().getString("weatherPlace").orEmpty()

/** `weatherStillSky=true` runs the weather journeys with the Still sky setting on: the same screens over a sky that isn't redrawn. */
private val WEATHER_STILL_SKY: Boolean = InstrumentationRegistry.getArguments().getString("weatherStillSky") == "true"

/** Logcat tag for what a weather journey found on screen (the sky's kind), so a run's numbers can be read against it. */
private const val WEATHER_LOG = "WeatherBench"

/**
 * From Home into the weather app, with a forecast on screen. Opened by its deep link (what a
 * weather notification's tap sends), which the running Activity takes as a new intent, so the
 * sign-in stays. With [WEATHER_PLACE] that place is searched for and added, which also turns the
 * app to it; an install with nowhere to forecast for yet gets Denver. Search and forecast both
 * come from Open-Meteo, so the device needs the internet as well as the Frigate server.
 */
fun MacrobenchmarkScope.openWeather() {
    device.executeShellCommand("am start -a android.intent.action.VIEW -d homesafe://weather -n $PACKAGE_NAME/$MAIN_ACTIVITY")
    check(device.wait(Until.hasObject(By.res(WEATHER_APP)), UI_TIMEOUT_MS)) { "The weather app did not open from homesafe://weather" }
    val welcome = device.wait(Until.findObject(By.res("weather_welcome_add")), 2_000)
    val place = WEATHER_PLACE.ifEmpty { if (welcome != null) "Denver" else "" }
    if (place.isNotEmpty()) {
        // Adding a place that is already there only turns the app to it.
        (welcome ?: device.findObject(By.res("weather_places_button")) ?: error("No way into the weather app's places")).click()
        val search = device.wait(Until.findObject(By.res("weather_places_search")), UI_TIMEOUT_MS) ?: error("No place search field")
        search.text = place
        device.wait(Until.findObject(By.res("weather_place_result")), FORECAST_TIMEOUT_MS)?.click()
            ?: error("No place found for \"$place\" — can the device reach geocoding-api.open-meteo.com?")
    }
    check(device.wait(Until.hasObject(By.res(WEATHER_TODAY)), FORECAST_TIMEOUT_MS)) {
        "No forecast within ${FORECAST_TIMEOUT_MS}ms — can the device reach api.open-meteo.com?"
    }
    if (WEATHER_STILL_SKY) {
        device.findObject(By.res("weather_settings_button"))?.click() ?: error("No weather settings button")
        val switch = device.wait(Until.findObject(By.res("weather_still_sky")), UI_TIMEOUT_MS) ?: error("No Still sky switch")
        if (!switch.isChecked) switch.click()
        device.findObject(By.res("weather_back"))?.click()
        check(device.wait(Until.hasObject(By.res(WEATHER_TODAY)), UI_TIMEOUT_MS)) { "$WEATHER_TODAY not back on screen" }
    }
    // The forecast for a place just added, the first cards and the radar's tiles arriving are not part of any scroll.
    Thread.sleep(2_500)
    Log.i(WEATHER_LOG, "asked=$place stillSky=$WEATHER_STILL_SKY shown=${wordsOf("weather_place_title")} hero=${wordsOf("weather_hero")}")
}

/** Every piece of text under the node tagged [tag]. */
private fun MacrobenchmarkScope.wordsOf(tag: String): List<String> =
    device.findObject(By.res(tag))?.findObjects(By.clazz("android.widget.TextView")).orEmpty().mapNotNull { it.text }

/**
 * A finger's stroke up or down the middle of the screen: [steps] of 5 ms each, so 8 is a flick
 * the list flings on from and 60 a slow drag. Injected as a plain swipe and followed by a fixed
 * wait: `UiObject2.fling` waits five seconds for a scroll event these lists never send, and
 * `waitForIdle` ten for an idle a moving sky never reaches, and a run would be mostly those waits.
 */
private fun MacrobenchmarkScope.stroke(down: Boolean, steps: Int, settleMs: Long = 900) {
    val x = device.displayWidth / 2
    val top = device.displayHeight * 3 / 10
    val bottom = device.displayHeight * 7 / 10
    if (down) device.swipe(x, bottom, x, top, steps) else device.swipe(x, top, x, bottom, steps)
    Thread.sleep(settleMs)
}

/**
 * The weather app as a reader goes through it: Today's cards dragged and flung to the foot and
 * back over the moving sky, then the Forecast tab's ten days the same way. Every card composes
 * at least once, and the sky is drawing under all of it.
 */
fun MacrobenchmarkScope.scrollWeather() {
    check(device.hasObject(By.res(WEATHER_TODAY))) { "$WEATHER_TODAY not on screen" }
    stroke(down = true, steps = 60)
    repeat(3) { stroke(down = true, steps = 8) }
    repeat(3) { stroke(down = false, steps = 8) }
    device.findObject(By.res("weather_tab_forecast"))?.click() ?: error("No Forecast tab")
    check(device.wait(Until.hasObject(By.res(WEATHER_FORECAST)), UI_TIMEOUT_MS)) { "$WEATHER_FORECAST not on screen" }
    Thread.sleep(500)
    stroke(down = true, steps = 8)
    stroke(down = false, steps = 8)
    device.findObject(By.res("weather_tab_today"))?.click()
    device.wait(Until.hasObject(By.res(WEATHER_TODAY)), UI_TIMEOUT_MS)
    Thread.sleep(500)
}
