package com.meticulouscreations.homesafe.baselineprofile

import android.content.Context
import android.os.PowerManager
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

/**
 * Holds the screen on for as long as this test process lives. A phone left on its charger goes to
 * its screensaver, and from there locks, once nothing has touched it for its screen timeout: a
 * journey waiting on a first download, or ART compiling before one, touches nothing. Held by the
 * test's own process, so it ends with the run and changes no setting on the phone.
 */
@Suppress("DEPRECATION") // A screen wake lock is deprecated in favour of a window flag, and a test has no window.
fun keepScreenOn() {
    if (screenLock != null) return
    val power = InstrumentationRegistry.getInstrumentation().context.getSystemService(Context.POWER_SERVICE) as PowerManager
    screenLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "homesafe:benchmark").apply { acquire(60 * 60 * 1000L) }
}

private var screenLock: PowerManager.WakeLock? = null

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
    device.findObject(By.res("weather_tab_today"))?.click() ?: error("No Today tab")
    check(device.wait(Until.hasObject(By.res(WEATHER_TODAY)), UI_TIMEOUT_MS)) { "$WEATHER_TODAY not back on screen" }
    Thread.sleep(500)
}

/** `Modifier.testTag`s of the atlas app — see atlas/ui/AtlasApp.kt and the screens beside it. */
private const val ATLAS_APP = "atlas_app"
private const val ATLAS_MAP = "atlas_map"

/** The first open after an install fetches every year of the history from the relay before there is anything to show. */
private const val ATLAS_FETCH_TIMEOUT_MS = 300_000L

/** Reading the paths out of the database and counting them into the map's squares, on a new process. */
private const val ATLAS_PATHS_TIMEOUT_MS = 60_000L

/** Logcat tag for how long an atlas journey waited for the history and for the paths. */
private const val ATLAS_LOG = "AtlasBench"

/**
 * From Home into the atlas app, with the map up and its paths drawn. Opened the way a person
 * opens it, from the drawer: it has no deep link. The account signed in has to be one the relay
 * gives the timeline to (an admin, or one of `TIMELINE_USERS`), and the relay has to have a
 * timeline: without either there are no tabs and this says so.
 */
fun MacrobenchmarkScope.openAtlas() {
    val asked = System.currentTimeMillis()
    device.findObject(By.res("shell_menu"))?.click() ?: error("No menu button to open the drawer with")
    val card = device.wait(Until.findObject(By.res("drawer_atlas")), UI_TIMEOUT_MS) ?: error("No atlas card in the drawer")
    Thread.sleep(400)
    card.click()
    check(device.wait(Until.hasObject(By.res(ATLAS_APP)), UI_TIMEOUT_MS)) { "The atlas app did not open from the drawer" }
    check(device.wait(Until.hasObject(By.res("atlas_tab_map")), ATLAS_FETCH_TIMEOUT_MS)) {
        "No history within ${ATLAS_FETCH_TIMEOUT_MS}ms — does the relay have a timeline, and may this account read it? On screen: ${screenText()}"
    }
    val shown = System.currentTimeMillis()
    check(device.wait(Until.hasObject(By.res(ATLAS_MAP)), UI_TIMEOUT_MS)) { "$ATLAS_MAP not on screen" }
    check(device.wait(Until.gone(By.res("atlas_map_reading_paths")), ATLAS_PATHS_TIMEOUT_MS)) { "The paths were not read within ${ATLAS_PATHS_TIMEOUT_MS}ms" }
    Log.i(ATLAS_LOG, "historyMs=${shown - asked} pathsMs=${System.currentTimeMillis() - shown}")
    // The base map's tiles arriving are not part of any gesture.
    Thread.sleep(3_000)
}

/** A little over `LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS`, after which nothing on Home is decoding. */
private const val HOME_PLAYERS_IDLE_MS = 95_000L

/** Waits until Home's live players, left running under the app just opened, have stopped. [keepScreenOn] holds the screen meanwhile. */
fun waitOutHomePlayers() = Thread.sleep(HOME_PLAYERS_IDLE_MS)

/** A finger across the map from one point to another, as shares of the screen: [steps] of 5 ms each. */
private fun MacrobenchmarkScope.drag(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int, settleMs: Long = 500) {
    device.swipe(
        (device.displayWidth * fromX).toInt(),
        (device.displayHeight * fromY).toInt(),
        (device.displayWidth * toX).toInt(),
        (device.displayHeight * toY).toInt(),
        steps,
    )
    Thread.sleep(settleMs)
}

private fun MacrobenchmarkScope.tap(tag: String, settleMs: Long) {
    device.findObject(By.res(tag))?.click() ?: error("No $tag on screen")
    Thread.sleep(settleMs)
}

/**
 * The atlas map as it is handled, with everything on it: the glow of every path, the places, the
 * flights, the base map. Mostly it is zoomed, in and out, since that is what changes everything
 * drawn at once: pinched out and in at the scale of a town, of the whole world (where every flight
 * and place is in view) and right in (where the paths themselves are drawn), with drags and
 * flicks between, the glides from home to the whole world and back, the zoom buttons, a double
 * tap, and the years played through.
 */
fun MacrobenchmarkScope.handleAtlasMap() {
    // Looked up afresh for each pinch: the node found before the map last changed is stale by the next.
    fun pinch(out: Boolean, share: Float = 0.6f) {
        val map = device.findObject(By.res(ATLAS_MAP)) ?: error("$ATLAS_MAP not on screen")
        map.setGestureMargins(device.displayWidth / 6, device.displayHeight / 4, device.displayWidth / 6, device.displayHeight / 4)
        if (out) map.pinchClose(share, 1_200) else map.pinchOpen(share, 1_200)
        Thread.sleep(400)
    }

    fun pinchOutAndIn(times: Int) = repeat(times) {
        pinch(out = true)
        pinch(out = false)
    }
    // A town's width, where the map opens: dragged, flicked, and pinched out and in.
    drag(0.3f, 0.45f, 0.7f, 0.6f, steps = 80)
    drag(0.5f, 0.6f, 0.5f, 0.4f, steps = 10)
    drag(0.5f, 0.4f, 0.5f, 0.6f, steps = 10)
    pinchOutAndIn(2)
    // Out to the region, and about in it.
    pinch(out = true, share = 0.8f)
    drag(0.3f, 0.45f, 0.7f, 0.6f, steps = 80)
    drag(0.7f, 0.6f, 0.3f, 0.45f, steps = 10)
    // The whole world: every flight and every place in view. Pinched in and out there.
    tap("atlas_map_everywhere", settleMs = 1_500)
    pinch(out = false)
    pinch(out = true)
    pinch(out = false)
    drag(0.35f, 0.5f, 0.65f, 0.55f, steps = 60)
    pinch(out = true)
    // Home again, then right in, to where the paths themselves are drawn.
    tap("atlas_map_home", settleMs = 1_500)
    repeat(4) { tap("atlas_map_zoom_in", settleMs = 500) }
    drag(0.3f, 0.45f, 0.7f, 0.6f, steps = 80)
    drag(0.5f, 0.6f, 0.5f, 0.4f, steps = 10)
    pinchOutAndIn(2)
    // A double tap in, and the button back out.
    device.click(device.displayWidth / 2, device.displayHeight / 2)
    Thread.sleep(80)
    device.click(device.displayWidth / 2, device.displayHeight / 2)
    Thread.sleep(700)
    repeat(2) { tap("atlas_map_zoom_out", settleMs = 500) }
    tap("atlas_map_home", settleMs = 1_500)
    // The years, a few of them: every square of the glow drawn again for each.
    tap("atlas_map_play", settleMs = 4_000)
    tap("atlas_map_play", settleMs = 500)
}

/** A finger's stroke up or down the middle of the screen, as the weather journey's: 8 steps is a flick, 60 a slow drag. */
private fun MacrobenchmarkScope.atlasStroke(down: Boolean, steps: Int = 8, settleMs: Long = 800) {
    if (down) drag(0.5f, 0.7f, 0.5f, 0.3f, steps, settleMs) else drag(0.5f, 0.3f, 0.5f, 0.7f, steps, settleMs)
}

/**
 * The rest of the atlas as a reader goes through it: each tab opened and flung down and back,
 * a place opened from the list (its own map, its week, its years), and a day opened from the
 * calendar and played.
 */
fun MacrobenchmarkScope.browseAtlas() {
    listOf("days", "places", "trips", "patterns").forEach { tab ->
        tap("atlas_tab_$tab", settleMs = 700)
        if (!device.wait(Until.hasObject(By.res("atlas_$tab")), 2_000)) {
            // Said, so a tap that did nothing shows up in a run's log rather than only as a failure.
            Log.w(ATLAS_LOG, "atlas_tab_$tab tapped and atlas_$tab not shown; on screen: ${screenText()}")
            tap("atlas_tab_$tab", settleMs = 700)
            check(device.wait(Until.hasObject(By.res("atlas_$tab")), UI_TIMEOUT_MS)) { "atlas_$tab not on screen" }
        }
        atlasStroke(down = true, steps = 60)
        repeat(2) { atlasStroke(down = true) }
        repeat(3) { atlasStroke(down = false) }
    }
    // A place, from the top of the list.
    tap("atlas_tab_places", settleMs = 700)
    device.findObject(By.res(java.util.regex.Pattern.compile("atlas_place_row_.*")))?.click() ?: error("No place in the list")
    check(device.wait(Until.hasObject(By.res("atlas_place")), UI_TIMEOUT_MS)) { "atlas_place not on screen" }
    Thread.sleep(1_200)
    atlasStroke(down = true)
    atlasStroke(down = false)
    tap("atlas_back", settleMs = 700)
    // A day, from the newest year of the calendar: a square a few weeks in.
    tap("atlas_tab_days", settleMs = 700)
    val year = device.findObject(By.res(java.util.regex.Pattern.compile("atlas_year_\\d+"))) ?: error("No year in the calendar")
    val bounds = year.visibleBounds
    val cell = bounds.width() / 53f
    device.click((bounds.left + cell * 9.5f).toInt(), (bounds.bottom - cell * 3.5f).toInt())
    if (device.wait(Until.hasObject(By.res("atlas_day")), UI_TIMEOUT_MS)) {
        Thread.sleep(1_200)
        device.findObject(By.res("atlas_day_play"))?.let { play ->
            play.click()
            Thread.sleep(4_000)
        }
        tap("atlas_day_previous", settleMs = 700)
        tap("atlas_day_previous", settleMs = 700)
        tap("atlas_back", settleMs = 700)
    }
    tap("atlas_tab_map", settleMs = 1_500)
}
