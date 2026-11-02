package com.meticulouscreations.homesafe.car3d

import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The pictures the app shows without a GPU of its own, drawn by the renderer itself: each car's
 * poster and its sheet of map frames. Only with `CAR_PICTURES` and `CAR_DUMP_DIR` set (tools/car-model/pack.sh).
 */
class CarPictureDump {
    @Test
    fun dump() {
        val out = System.getenv("CAR_DUMP_DIR")?.let(::File)?.takeIf { System.getenv("CAR_PICTURES") != null } ?: return
        val pack = File(System.getenv("CAR_PACK_DIR") ?: error("CAR_PACK_DIR"))
        out.mkdirs()
        val offscreen = MacOffscreen(requestedSamples = 4, supersample = 2)
        val gl = DesktopGl()
        for (model in CarModel.entries) {
            val key = if (model == CarModel.BLUE_PERFORMANCE) "blue" else "red"
            val scene = CarScene(gl, model.look, loadAssets(pack, model), HIGH_TIER)
            write(render(offscreen, scene, POSTER_WIDTH, POSTER_HEIGHT, CarCamera(CarOrbitState.START_AZIMUTH, CarOrbitState.START_ELEVATION)), File(out, "poster_$key.png"))
            val sheet = BufferedImage(CELL * COLUMNS, CELL * (FRAMES / COLUMNS), BufferedImage.TYPE_INT_ARGB)
            for (frame in 0 until FRAMES) {
                // Heading clockwise from north on a north-up map, seen from the south: the car's
                // nose is its -Y, and azimuth 0 looks straight at it.
                val heading = 360f * frame / FRAMES
                val cell = render(offscreen, scene, CELL, CELL, CarCamera(180f + heading, MAP_ELEVATION, MAP_ZOOM))
                sheet.createGraphics().apply { drawImage(cell, frame % COLUMNS * CELL, frame / COLUMNS * CELL, null) }.dispose()
            }
            write(sheet, File(out, "map_$key.png"))
            scene.release()
        }
        offscreen.close()
    }

    private fun render(offscreen: MacOffscreen, scene: CarScene, width: Int, height: Int, camera: CarCamera): BufferedImage {
        offscreen.resize(width, height)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        offscreen.render(pixels) { w, h -> scene.draw(w, h, camera) }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                val a = pixels.get(i + 3).toInt() and 0xff
                fun straight(v: Int) = if (a == 0) 0 else minOf(255, (v and 0xff) * 255 / a)
                image.setRGB(x, y, (a shl 24) or (straight(pixels.get(i).toInt()) shl 16) or (straight(pixels.get(i + 1).toInt()) shl 8) or straight(pixels.get(i + 2).toInt()))
            }
        }
        return image
    }

    private fun write(image: BufferedImage, file: File) {
        ImageIO.write(image, "png", file)
    }

    private fun loadAssets(pack: File, model: CarModel): CarAssets {
        val wheels = model.look.wheels
        val front = CarMeshFile(File(pack, "wheel_${wheels}_front_high.car").readBytes())
        val rear = if (model.look.rearWheelsDiffer) CarMeshFile(File(pack, "wheel_${wheels}_rear_high.car").readBytes()) else front
        return CarAssets(
            body = CarMeshFile(File(pack, "body_high.car").readBytes()),
            frontWheel = front,
            rearWheel = rear,
            environment = CarEnvironmentFile(File(pack, "day_high.env").readBytes()),
            shadow = File(pack, "shadow.bin").readBytes(),
            shadowSize = 512,
        )
    }

    private companion object {
        const val POSTER_WIDTH = 1280
        const val POSTER_HEIGHT = 800
        const val CELL = 320 // twice what the app's sheet keeps: pack_pictures.py halves it
        const val FRAMES = 72
        const val COLUMNS = 9
        const val MAP_ELEVATION = 58f
        const val MAP_ZOOM = 1.02f
    }
}
