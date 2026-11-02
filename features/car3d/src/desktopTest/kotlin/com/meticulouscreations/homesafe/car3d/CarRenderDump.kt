package com.meticulouscreations.homesafe.car3d

import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Draws the car as the app does, from the reference photographs' own cameras, to PNGs. Only with
 * `CAR_DUMP_DIR` set, as it wants a Mac's GPU and the pack from tools/car-model/pack.sh:
 *
 *     CAR_DUMP_DIR=/tmp/out CAR_PACK_DIR=<pack> ./gradlew :car3d:desktopTest --tests '*CarRenderDump*'
 */
class CarRenderDump {
    @Test
    fun dump() {
        if (System.getenv("CAR_PICTURES") != null) return
        val out = System.getenv("CAR_DUMP_DIR")?.let(::File) ?: return
        val pack = File(System.getenv("CAR_PACK_DIR") ?: error("CAR_PACK_DIR"))
        val tier = System.getenv("CAR_TIER") ?: "mobile"
        out.mkdirs()
        val cameras = parseCameras(File("../../tools/car-model/cameras.json").readText())
        val offscreen = MacOffscreen(requestedSamples = 4, supersample = 2)
        val gl = DesktopGl()
        for (model in CarModel.entries) {
            val assets = loadAssets(pack, model, tier)
            val scene = CarScene(gl, model.look, assets, if (tier == "high") HIGH_TIER else MOBILE_TIER)
            for ((name, camera) in cameras) {
                val width = 1200
                val height = (width / camera.second).toInt()
                render(offscreen, scene, width, height, camera.first, File(out, "${model.name.lowercase()}_$name.png"))
            }
            val orbit = listOf(
                "orbit_front" to CarCamera(35f, 12f),
                "orbit_side" to CarCamera(90f, 6f),
                "orbit_rear" to CarCamera(150f, 14f),
                "orbit_top" to CarCamera(60f, 55f),
                "view_front" to CarCamera(1f, 27f, zoom = 2.6f),
                "view_rear" to CarCamera(-155f, 6f, zoom = 1.7f),
                "view_side" to CarCamera(-90f, 3f, zoom = 1.3f),
                "view_side_far" to CarCamera(90f, 5f, zoom = 1.3f),
                "view_quarter" to CarCamera(-38f, 11f, zoom = 1.5f),
                "close_wheel" to CarCamera(0f, 0f, eye = floatArrayOf(-3.0f, -1.6f, 0.42f), target = floatArrayOf(-0.9f, -1.445f, 0.36f), lensMillimetres = 60f),
                "close_head" to CarCamera(0f, 0f, eye = floatArrayOf(-2.0f, -3.7f, 1.1f), target = floatArrayOf(-0.62f, -1.9f, 0.82f), lensMillimetres = 62f),
                "close_tail" to CarCamera(0f, 0f, eye = floatArrayOf(-1.8f, 3.7f, 1.25f), target = floatArrayOf(-0.5f, 2.15f, 1.02f), lensMillimetres = 62f),
            )
            for ((name, camera) in orbit) render(offscreen, scene, 1200, 750, camera, File(out, "${model.name.lowercase()}_$name.png"))
            scene.release()
        }
        offscreen.close()
    }

    private fun render(offscreen: MacOffscreen, scene: CarScene, width: Int, height: Int, camera: CarCamera, file: File) {
        offscreen.resize(width, height)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val start = System.nanoTime()
        offscreen.render(pixels) { w, h -> scene.draw(w, h, camera, showroomFloor = true) }
        println("${file.name}: ${(System.nanoTime() - start) / 1_000_000} ms")
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                val r = pixels.get(i).toInt() and 0xff
                val g = pixels.get(i + 1).toInt() and 0xff
                val b = pixels.get(i + 2).toInt() and 0xff
                val a = pixels.get(i + 3).toInt() and 0xff
                // The viewer page's dark ground under the premultiplied picture, as the app shows it.
                val under = 16 * (255 - a) / 255
                image.setRGB(x, y, (0xff shl 24) or ((r + under) shl 16) or ((g + under) shl 8) or (b + under))
            }
        }
        ImageIO.write(image, "png", file)
    }

    private fun loadAssets(pack: File, model: CarModel, tier: String): CarAssets {
        val wheels = model.look.wheels
        val front = CarMeshFile(File(pack, "wheel_${wheels}_front_$tier.car").readBytes())
        val rear = if (model.look.rearWheelsDiffer) CarMeshFile(File(pack, "wheel_${wheels}_rear_$tier.car").readBytes()) else front
        val shadowFile = File(pack, "shadow.bin")
        val shadowSize = 512
        val shadow = if (shadowFile.exists()) shadowFile.readBytes() else ByteArray(shadowSize * shadowSize) { -1 }
        return CarAssets(
            body = CarMeshFile(File(pack, "body_$tier.car").readBytes()),
            frontWheel = front,
            rearWheel = rear,
            environment = CarEnvironmentFile(File(pack, "day_$tier.env").readBytes()),
            shadow = shadow,
            shadowSize = shadowSize,
        )
    }

    private fun parseCameras(json: String): List<Pair<String, Pair<CarCamera, Float>>> {
        val entry = Regex("\"(\\w+)\": \\{\\s*\"position\": \\[([^\\]]+)\\],\\s*\"target\": \\[([^\\]]+)\\],\\s*\"roll\": ([-\\d.e]+),\\s*\"lens\": ([-\\d.e]+),\\s*\"aspect\": ([-\\d.e]+)")
        fun floats(text: String) = text.split(",").map { it.trim().toFloat() }.toFloatArray()
        return entry.findAll(json).map { m ->
            val camera = CarCamera(0f, 0f, eye = floats(m.groupValues[2]), target = floats(m.groupValues[3]), lensMillimetres = m.groupValues[5].toFloat(), rollRadians = m.groupValues[4].toFloat())
            m.groupValues[1] to (camera to m.groupValues[6].toFloat())
        }.toList()
    }
}
