package com.meticulouscreations.homesafe.car3d

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * The Mac's whole path: Compose lays the viewer out, the GPU draws the shipped files offscreen, the
 * frame comes back as a picture, a drag turns the car. Only with `CAR_DUMP_DIR` set: it wants a GPU.
 */
class CarViewerWindowDump {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDragTurnsTheCar() {
        val out = System.getenv("CAR_DUMP_DIR")?.let(::File) ?: return
        if (System.getenv("CAR_PICTURES") != null) return
        out.mkdirs()
        runDesktopComposeUiTest(width = 1400, height = 860) {
            val orbit = CarOrbitState()
            setContent {
                Box(Modifier.fillMaxSize().background(CarBackdrop).testTag("viewer")) {
                    CarViewer(CarModel.BLUE_PERFORMANCE, Modifier.fillMaxSize(), orbit)
                }
            }
            fun settle() {
                repeat(20) {
                    runBlocking { delay(100) }
                    waitForIdle()
                }
            }
            settle()
            val before = onNodeWithTag("viewer").captureToImage()
            ImageIO.write(before.toAwtImage(), "png", File(out, "window_before.png"))
            val azimuth = orbit.azimuth
            onNodeWithTag("viewer").performTouchInput {
                down(center)
                moveBy(Offset(-260f, 0f))
                moveBy(Offset(-260f, 40f))
                up()
            }
            settle()
            assertNotEquals(azimuth, orbit.azimuth)
            ImageIO.write(onNodeWithTag("viewer").captureToImage().toAwtImage(), "png", File(out, "window_after.png"))
        }
    }
}
