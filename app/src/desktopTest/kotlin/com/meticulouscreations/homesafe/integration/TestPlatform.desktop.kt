package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.meticulouscreations.homesafe.PlatformContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration

/** What the app graph is built with in the desktop journeys: nothing, as in the app itself. */
internal fun testPlatformContext(): PlatformContext = PlatformContext()

/**
 * Saves what is on screen as `<journey>/<step>.png` under app/build/journeys (`homesafe.journeyScreens`),
 * so a run on the JVM leaves a picture of every step behind it, and of the screen it failed on.
 */
@OptIn(ExperimentalTestApi::class)
internal fun saveJourneyScreen(ui: ComposeUiTest, journey: String, step: String) {
    val root = System.getProperty("homesafe.journeyScreens") ?: return
    runCatching {
        val image = ui.onRoot().captureToImage().toAwtImage()
        val file = File(root, "$journey/${step.replace(Regex("[^A-Za-z0-9 ._-]"), "_")}.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}

/** A journey in a window [width] × [height] pixels across, or the test's default 1024 × 768 with neither. */
@OptIn(ExperimentalTestApi::class)
internal fun runJourneyWindow(width: Int?, height: Int?, timeout: Duration, block: suspend ComposeUiTest.() -> Unit) =
    if (width != null && height != null) {
        runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), testTimeout = timeout, block = block)
    } else {
        runComposeUiTest(testTimeout = timeout, block = block)
    }
