package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import com.meticulouscreations.homesafe.PlatformContext
import java.io.File
import javax.imageio.ImageIO

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
