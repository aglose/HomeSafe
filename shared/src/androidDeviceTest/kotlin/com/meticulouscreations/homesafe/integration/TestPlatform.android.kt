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
 * Where a journey would save what is on screen. On a device the files would need pulling off it,
 * so a snapshot is a no-op; a failing journey still prints the screen's semantics tree.
 */
@OptIn(ExperimentalTestApi::class)
@Suppress("UNUSED_PARAMETER")
internal fun saveJourneyScreen(ui: ComposeUiTest, journey: String, step: String) = Unit
