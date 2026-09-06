package com.meticulouscreations.homesafe.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "com.meticulouscreations.homesafe"

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
        "Home feed did not appear within ${SIGN_IN_TIMEOUT_MS}ms — is the Frigate server reachable from the device?"
    }
    device.waitForIdle()
}

/** The Home tab's camera list, ready to fling. */
fun MacrobenchmarkScope.homeFeed(): UiObject2 {
    val feed = device.findObject(By.res(HOME_FEED)) ?: error("Home feed not on screen")
    // Keep flings clear of the system gesture area, or they turn into back/recents swipes.
    feed.setGestureMargin(device.displayWidth / 5)
    return feed
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
