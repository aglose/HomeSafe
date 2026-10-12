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

/**
 * What the app graph is built with in the JVM tests. The desktop needs nothing in it, but its
 * data layer keeps state on disk, so every test that builds a graph comes through here first:
 * see [ownStateOnDisk].
 */
internal fun testPlatformContext(): PlatformContext {
    ownStateOnDisk
    return PlatformContext()
}

/**
 * Gives this test process its own copy of what the desktop app keeps on disk: the Room database
 * under `homesafe.dataDir` (AppDatabase.jvm.kt) and the Java user preferences the Finance tips
 * are remembered in (FinanceTipLedger.jvm.kt; the JDK reads `java.util.prefs.userRoot` on Linux
 * and ignores it on macOS). `-PjvmTestForks` runs the suite in several processes at once
 * (shared/build.gradle.kts), and two journeys writing one database would each see the other's
 * sign-ins. Gradle numbers every test process in `org.gradle.test.worker`; outside Gradle there
 * is only the one, and nothing to keep apart.
 */
private val ownStateOnDisk: Unit by lazy {
    val worker = System.getProperty("org.gradle.test.worker") ?: return@lazy
    val shared = System.getProperty("homesafe.dataDir") ?: return@lazy
    val own = File(shared, "worker-$worker")
    System.setProperty("homesafe.dataDir", own.path)
    System.setProperty("java.util.prefs.userRoot", own.path)
}

/** Set by `-PjourneyScreens` (shared/build.gradle.kts); journeys take no screenshots without it. */
private val journeyScreensDir: File? = System.getProperty("homesafe.journeyScreens")?.let(::File)

/**
 * Writes the app's window as `<journey>/<step>.png` under the journey-screenshot directory, if
 * the run has one (see [AppJourney.snapshot]). The first root is the app itself, which fills the
 * window. On the desktop, capturing a node redraws the whole scene and crops it to that node's
 * bounds, so a dialog or menu open over the app (a root of its own) is in the picture too.
 */
@OptIn(ExperimentalTestApi::class)
internal fun saveJourneyScreen(ui: ComposeUiTest, journey: String, step: String) {
    val dir = journeyScreensDir ?: return
    val image = ui.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
    val file = File(dir, "$journey/$step.png")
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
}
