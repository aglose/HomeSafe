package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.platform.app.InstrumentationRegistry
import com.meticulouscreations.homesafe.PlatformContext

/**
 * What the app graph is built with in the on-device integration journeys: the test APK's own
 * context. Not an Activity, so biometrics resolve to the headless store and location to its
 * no-activity path, as they would for a graph built by a background wake.
 */
internal fun testPlatformContext(): PlatformContext =
    PlatformContext(InstrumentationRegistry.getInstrumentation().targetContext)

/**
 * Journey screenshots are a JVM feature (`-PjourneyScreens`, see docs/ui-previews.md): there the
 * files land straight in the build directory. On a device they would need pulling off it, and
 * the same journeys already run on the JVM, so here a snapshot is a no-op.
 */
@OptIn(ExperimentalTestApi::class)
@Suppress("UNUSED_PARAMETER")
internal fun saveJourneyScreen(ui: ComposeUiTest, journey: String, step: String) = Unit
