package com.meticulouscreations.homesafe.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.meticulouscreations.homesafe.PlatformContext

/**
 * What the app graph is built with in the on-device integration journeys: the test APK's own
 * context. Not an Activity, so biometrics resolve to the headless store and location to its
 * no-activity path, as they would for a graph built by a background wake.
 */
internal fun testPlatformContext(): PlatformContext =
    PlatformContext(InstrumentationRegistry.getInstrumentation().targetContext)
