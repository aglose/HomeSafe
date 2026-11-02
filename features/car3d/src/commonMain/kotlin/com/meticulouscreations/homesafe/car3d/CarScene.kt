package com.meticulouscreations.homesafe.car3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Where the viewer stands: angles round the car and how far back, as a fraction of the framing distance. */
internal data class CarCamera(
    val azimuthDegrees: Float,
    val elevationDegrees: Float,
    val zoom: Float = 1f,
    val eye: FloatArray? = null,
    val target: FloatArray? = null,
    val lensMillimetres: Float? = null,
    val rollRadians: Float = 0f,
)

/** The bytes one car needs, read off disk on any thread. The scene turns them into GPU objects. */
internal class CarAssets(
    val body: CarMeshFile,
    val frontWheel: CarMeshFile,
    val rearWheel: CarMeshFile,
    val environment: CarEnvironmentFile,
    val shadow: ByteArray,
    val shadowSize: Int,
)

/**
 * One car, drawn by [draw]. Made and used on the thread holding the GL context: it owns the GPU's
 * copy of [assets], and [release] gives it back.
 */
internal class CarScene(private val gl: Gl, private val look: CarLook, private val assets: CarAssets, private val tier: CarTier) {
    private class Program(val id: Int, gl: Gl) {
        val viewProjection = gl.location(id, "uViewProjection")
        val model = gl.location(id, "uModel")
        val unit = gl.location(id, "uUnit")
        val environment = gl.location(id, "uEnvironment")
        val levels = gl.location(id, "uLevels")
        val exposure = gl.location(id, "uExposure")
        val camera = gl.location(id, "uCamera")
        val harmonics = gl.location(id, "uHarmonics")
        val skyTurn = gl.location(id, "uSkyTurn")
    }

    private class Draw(val mesh: GlMesh, val name: String)

    private val surfaceProgram = Program(gl.program(CarShaders.VERTEX, CarShaders.SURFACE), gl)
    private val glassProgram = Program(gl.program(CarShaders.VERTEX, CarShaders.GLASS), gl)
    private val shadowProgram = gl.program(CarShaders.SHADOW_VERTEX, CarShaders.SHADOW)
    private val base = gl.location(surfaceProgram.id, "uBase")
    private val metal = gl.location(surfaceProgram.id, "uMetal")
    private val roughness = gl.location(surfaceProgram.id, "uRoughness")
    private val coat = gl.location(surfaceProgram.id, "uCoat")
    private val coatRoughness = gl.location(surfaceProgram.id, "uCoatRoughness")
    private val flake = gl.location(surfaceProgram.id, "uFlake")
    private val flakeAmount = gl.location(surfaceProgram.id, "uFlakeAmount")
    private val emissive = gl.location(surfaceProgram.id, "uEmissive")
    private val occlusionPower = gl.location(surfaceProgram.id, "uOcclusionPower")
    private val tint = gl.location(glassProgram.id, "uTint")
    private val shadowViewProjection = gl.location(shadowProgram, "uViewProjection")
    private val shadowHalfSize = gl.location(shadowProgram, "uHalfSize")
    private val shadowTexture = gl.location(shadowProgram, "uShadow")
    private val shadowStrength = gl.location(shadowProgram, "uStrength")
    private val shadowFloor = gl.location(shadowProgram, "uFloor")

    private fun meshes(file: CarMeshFile) = file.parts.map { part ->
        Draw(gl.mesh(file.data, part.vertexOffset, part.vertexBytes, part.indexOffset, part.indexBytes, part.indexSize), part.name)
    }

    private val body = meshes(assets.body)
    private val frontWheel = meshes(assets.frontWheel)
    private val rearWheel = if (assets.rearWheel === assets.frontWheel) frontWheel else meshes(assets.rearWheel)
    private val cube = gl.cube(assets.environment.size, assets.environment.levels, assets.environment.data, assets.environment.pixelsOffset)
    private val shadow = gl.texture(assets.shadowSize, assets.shadow)
    private val quad = gl.mesh(QUAD, 0, QUAD.size - 12, QUAD.size - 12, 12, 2)
    private val harmonics = harmonicsFor(assets.environment)

    fun draw(width: Int, height: Int, camera: CarCamera, exposure: Float = DEFAULT_EXPOSURE, showroomFloor: Boolean = false) {
        gl.viewport(width, height)
        gl.clear()
        val eye = camera.eye ?: orbitEye(camera, width, height)
        val target = camera.target ?: floatArrayOf(0f, 0f, ORBIT_CENTRE_Z)
        val fovY = camera.lensMillimetres?.let { fovYFor(it, width, height) } ?: ORBIT_FOV
        val viewProjection = multiply(perspective(fovY, width.toFloat() / height, 0.1f, 80f), lookAt(eye, target, camera.rollRadians))

        gl.opaque()
        prepare(surfaceProgram, viewProjection, eye, exposure)
        val bodyModel = translation(0f, 0f, look.rideHeight)
        gl.uniformMatrix(surfaceProgram.model, bodyModel)
        gl.uniform(surfaceProgram.unit, assets.body.unit)
        val shown = body.filter { draw ->
            draw.name !in GLASS_TINTS && (look.spoiler || draw.name != "spoiler") && (look.badgeUnderline || draw.name != "badge_underline")
        }
        shown.forEach { draw ->
            applySurface(look.surface(draw.name))
            gl.draw(draw.mesh)
        }
        gl.uniform(surfaceProgram.unit, assets.frontWheel.unit)
        for (wheel in WHEELS) {
            gl.uniformMatrix(surfaceProgram.model, wheelModel(wheel))
            (if (wheel.rear) rearWheel else frontWheel).forEach { draw ->
                applySurface(look.surface(draw.name))
                gl.draw(draw.mesh)
            }
        }

        gl.blended(depthWrites = false)
        gl.use(shadowProgram)
        gl.uniformMatrix(shadowViewProjection, viewProjection)
        gl.uniform(shadowHalfSize, SHADOW_HALF_SIZE)
        gl.uniform(shadowStrength, SHADOW_STRENGTH)
        gl.uniform(shadowFloor, if (showroomFloor) 1f else 0f)
        gl.bindTexture(1, shadow)
        gl.uniformInt(shadowTexture, 1)
        gl.draw(quad)

        prepare(glassProgram, viewProjection, eye, exposure)
        gl.uniformMatrix(glassProgram.model, bodyModel)
        gl.uniform(glassProgram.unit, assets.body.unit)
        drawGlass(LENSES)
        // Windows write depth, so a far pane can never paint over a near one: their parts hold both
        // sides of the car, in no order the camera could rely on.
        gl.blended(depthWrites = true)
        drawGlass(WINDOWS)
    }

    private fun drawGlass(names: List<String>) {
        for (name in names) {
            val draw = body.firstOrNull { it.name == name } ?: continue
            val color = GLASS_TINTS.getValue(name)
            gl.uniform(tint, color.r, color.g, color.b)
            gl.draw(draw.mesh)
        }
    }

    fun release() {
        gl.release(
            meshes = (body + frontWheel + rearWheel).map { it.mesh } + quad,
            textures = intArrayOf(shadow),
            cubes = intArrayOf(cube),
            programs = intArrayOf(surfaceProgram.id, glassProgram.id, shadowProgram),
        )
    }

    private fun prepare(program: Program, viewProjection: FloatArray, eye: FloatArray, exposure: Float) {
        gl.use(program.id)
        gl.uniformMatrix(program.viewProjection, viewProjection)
        gl.bindCube(0, cube)
        gl.uniformInt(program.environment, 0)
        gl.uniform(program.levels, assets.environment.levels.toFloat())
        gl.uniform(program.exposure, exposure)
        gl.uniform(program.camera, eye[0], eye[1], eye[2])
        val turn = kotlin.math.atan2(eye[0], -eye[1])
        gl.uniform2(program.skyTurn, cos(turn), sin(turn))
        gl.uniformVectors(program.harmonics, harmonics)
    }

    private fun applySurface(surface: Surface) {
        gl.uniform(base, surface.base.r, surface.base.g, surface.base.b)
        gl.uniform(metal, surface.metal)
        gl.uniform(roughness, surface.roughness)
        gl.uniform(coat, surface.coat)
        gl.uniform(coatRoughness, maxOf(surface.coatRoughness, tier.coatSoftness))
        gl.uniform(flake, surface.flake.r, surface.flake.g, surface.flake.b)
        gl.uniform(flakeAmount, surface.flakeAmount)
        gl.uniform(emissive, surface.emissive.r, surface.emissive.g, surface.emissive.b)
        gl.uniform(occlusionPower, surface.occlusionPower)
    }

    private fun wheelModel(wheel: Wheel): FloatArray {
        val m = translation(wheel.x, wheel.y, WHEEL_RADIUS)
        if (wheel.turnedRound) {
            // The same wheel on both sides: half a turn about the vertical and the axle, not a mirror.
            m[0] = -1f
            m[10] = -1f
        }
        return m
    }

    private data class Wheel(val x: Float, val y: Float, val rear: Boolean, val turnedRound: Boolean)

    private companion object {
        const val TRACK = 1.636f
        const val FRONT_AXLE = -1.468f
        const val REAR_AXLE = 1.422f
        const val WHEEL_RADIUS = 0.356f
        const val DEFAULT_EXPOSURE = 1f
        const val ORBIT_FOV = 24f
        const val ORBIT_CENTRE_Z = 0.62f
        const val SHADOW_HALF_SIZE = 4f
        const val SHADOW_STRENGTH = 0.96f

        val WHEELS = listOf(
            Wheel(TRACK / 2, FRONT_AXLE, rear = false, turnedRound = false),
            Wheel(TRACK / 2, REAR_AXLE, rear = true, turnedRound = false),
            Wheel(-TRACK / 2, FRONT_AXLE, rear = false, turnedRound = true),
            Wheel(-TRACK / 2, REAR_AXLE, rear = true, turnedRound = true),
        )

        /** Inner lenses before the outer: each shows through the next. */
        val LENSES = listOf("tail_lens", "tail_outer_lens", "fog_lens", "head_lens")
        val WINDOWS = listOf("rear_glass", "rear_side_glass", "rear_door_glass", "front_door_glass", "windshield")

        /** A quad in the shadow shader's corners: two shorts per corner in the mesh's layout, then its indices. */
        val QUAD: ByteArray = run {
            val corners = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
            val bytes = ByteArray(corners.size * 12 + 12)
            corners.forEachIndexed { i, (x, y) ->
                bytes[i * 12] = x.toByte()
                bytes[i * 12 + 1] = if (x < 0) -1 else 0
                bytes[i * 12 + 2] = y.toByte()
                bytes[i * 12 + 3] = if (y < 0) -1 else 0
            }
            listOf(0, 1, 2, 0, 2, 3).forEachIndexed { i, index -> bytes[corners.size * 12 + i * 2] = index.toByte() }
            bytes
        }

        /** The file's coefficients are radiance about Z up; the shader's constants turn them into irradiance. */
        fun harmonicsFor(environment: CarEnvironmentFile): FloatArray = environment.harmonics

        fun orbitEye(camera: CarCamera, width: Int, height: Int): FloatArray {
            // Far enough back to fit the car's length in whichever way the view is narrower.
            val aspect = width.toFloat() / height
            val halfHeight = tan(ORBIT_FOV * PI.toFloat() / 360f)
            val fit = max(2.75f / (halfHeight * aspect), 1.5f / halfHeight)
            val distance = fit / camera.zoom
            val azimuth = camera.azimuthDegrees * PI.toFloat() / 180f
            val elevation = camera.elevationDegrees * PI.toFloat() / 180f
            // Azimuth 0 looks at the car's front from ahead of it (it faces -Y).
            return floatArrayOf(
                distance * cos(elevation) * sin(azimuth),
                -distance * cos(elevation) * cos(azimuth),
                ORBIT_CENTRE_Z + distance * sin(elevation),
            )
        }

        fun fovYFor(lensMillimetres: Float, width: Int, height: Int): Float {
            val sensorHeight = 36f * height / width
            return 2f * kotlin.math.atan(sensorHeight / (2f * lensMillimetres)) * 180f / PI.toFloat()
        }

        fun translation(x: Float, y: Float, z: Float) = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

        fun perspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): FloatArray {
            val f = 1f / tan(fovYDegrees * PI.toFloat() / 360f)
            return floatArrayOf(
                f / aspect, 0f, 0f, 0f,
                0f, f, 0f, 0f,
                0f, 0f, (far + near) / (near - far), -1f,
                0f, 0f, 2f * far * near / (near - far), 0f,
            )
        }

        fun lookAt(eye: FloatArray, target: FloatArray, roll: Float): FloatArray {
            val f = normalized(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
            var s = normalized(cross(f, floatArrayOf(0f, 0f, 1f)))
            var u = cross(s, f)
            if (roll != 0f) {
                val c = cos(roll)
                val r = sin(roll)
                val turned = FloatArray(3) { c * s[it] + r * u[it] }
                u = FloatArray(3) { -r * s[it] + c * u[it] }
                s = turned
            }
            return floatArrayOf(
                s[0], u[0], -f[0], 0f,
                s[1], u[1], -f[1], 0f,
                s[2], u[2], -f[2], 0f,
                -dot(s, eye), -dot(u, eye), dot(f, eye), 1f,
            )
        }

        fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { i ->
            val column = i / 4
            val row = i % 4
            (0..3).sumOf { (a[it * 4 + row] * b[column * 4 + it]).toDouble() }.toFloat()
        }

        fun normalized(v: FloatArray): FloatArray {
            val length = kotlin.math.sqrt(dot(v, v))
            return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
        }

        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }
}
