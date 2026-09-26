package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import com.meticulouscreations.homesafe.PlatformContext
import java.io.File
import javax.imageio.ImageIO

/** What the app graph is built with in the JVM integration journeys; the desktop needs nothing. */
internal fun testPlatformContext(): PlatformContext = PlatformContext()

/** Set by `-PjourneyScreens` (shared/build.gradle.kts); journeys take no screenshots without it. */
private val journeyScreensDir: File? = System.getProperty("homesafe.journeyScreens")?.let(::File)

/**
 * Writes the app's window as `<journey>/<step>.png` under the journey-screenshot directory, if
 * the run has one (see [AppJourney.snapshot]). The first root is the app itself; a dialog or
 * popup open over it is a root of its own and isn't in the picture.
 */
@OptIn(ExperimentalTestApi::class)
internal fun saveJourneyScreen(ui: ComposeUiTest, journey: String, step: String) {
    val dir = journeyScreensDir ?: return
    val image = ui.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
    val file = File(dir, "$journey/$step.png")
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
}
