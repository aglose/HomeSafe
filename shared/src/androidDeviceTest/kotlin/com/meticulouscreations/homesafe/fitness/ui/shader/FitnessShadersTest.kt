package com.meticulouscreations.homesafe.fitness.ui.shader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import com.meticulouscreations.homesafe.fitness.ui.movement.FIBRE_SHADER
import com.meticulouscreations.homesafe.fitness.ui.movement.HEAT_SHADER
import com.meticulouscreations.homesafe.fitness.ui.movement.MARBLE_BONES
import com.meticulouscreations.homesafe.fitness.ui.movement.MARBLE_HEADS
import com.meticulouscreations.homesafe.fitness.ui.movement.MARBLE_SHADER
import com.meticulouscreations.homesafe.fitness.ui.movement.PULSE_SHADER
import kotlin.test.Test

/**
 * The fitness shaders compile on the device the first time a screen draws them, and one that
 * doesn't quietly falls back to a plain gradient. Compile each here, on a real Android, where
 * AGSL's own compiler (not the desktop's Skia) gets the final say, and set every uniform it
 * declares: a name the source doesn't declare throws.
 */
class FitnessShadersTest {

    @Test
    fun theForgeShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(FORGE_SHADER)
        shader.setFloatUniform("size", 1080f, 1200f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("tint", 1f, 0.35f, 0.12f)
        shader.setFloatUniform("tint2", 0.7f, 0.07f, 0.23f)
        shader.setFloatUniform("heat", 0.6f)
        shader.setFloatUniform("intensity", 1f)
    }

    @Test
    fun theFiberShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(FIBER_SHADER)
        shader.setFloatUniform("size", 1080f, 420f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("tint", 1f, 0.35f, 0.12f)
        shader.setFloatUniform("tint2", 0.7f, 0.07f, 0.23f)
        shader.setFloatUniform("seed", 0.3f)
        shader.setFloatUniform("energy", 1f)
    }

    @Test
    fun theRingShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(RING_SHADER)
        shader.setFloatUniform("size", 400f, 400f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("level", 0.6f)
        shader.setFloatUniform("tint", 1f, 0.35f, 0.12f)
        shader.setFloatUniform("tint2", 0.7f, 0.07f, 0.23f)
        shader.setFloatUniform("track", 0.2f, 0.2f, 0.2f)
        shader.setFloatUniform("thickness", 0.16f)
    }

    @Test
    fun theBurstShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(BURST_SHADER)
        shader.setFloatUniform("size", 1080f, 2400f)
        shader.setFloatUniform("center", 540f, 900f)
        shader.setFloatUniform("progress", 0.4f)
        shader.setFloatUniform("tint", 1f, 0.85f, 0.42f)
        shader.setFloatUniform("tint2", 1f, 0.35f, 0.12f)
    }

    // The exercise figures' shaders (fitness/ui/movement), which draw the muscles of the rig and light the rendered lifter.

    @Test
    fun theMuscleGrainShaderCompilesAndTakesItsUniform() {
        val shader = RuntimeShader(FIBRE_SHADER)
        shader.setFloatUniform("grain", 3f)
    }

    @Test
    fun theMusclePulseShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(PULSE_SHADER)
        shader.setFloatUniform("grain", 3f)
        shader.setFloatUniform("time", 3.2f)
    }

    @Test
    fun theStoneFigureShaderCompilesAndTakesItsArrays() {
        val shader = RuntimeShader(MARBLE_SHADER)
        shader.setFloatUniform("size", 1080f, 900f)
        shader.setFloatUniform("unit", 420f)
        // Each is an array of float4: four floats to an entry, the whole array at once.
        shader.setFloatUniform("bones", FloatArray(MARBLE_BONES * 4))
        shader.setFloatUniform("boneR", FloatArray(MARBLE_BONES * 4))
        shader.setFloatUniform("heads", FloatArray(MARBLE_HEADS * 4))
        shader.setFloatUniform("headR", FloatArray(MARBLE_HEADS * 4))
    }

    @Test
    fun theRenderedFigureShaderCompilesAndTakesItsPicturesAndUniforms() {
        val shader = RuntimeShader(HEAT_SHADER)
        val sheet = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        // The two sheets of frames it reads from, as the app hands them over (FitnessShaders.android.kt).
        for (name in listOf("body", "muscle")) {
            shader.setInputShader(name, BitmapShader(sheet, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
        }
        shader.setFloatUniform("origin", 0f, 60f)
        shader.setFloatUniform("extent", 1080f, 1080f)
        shader.setFloatUniform("cells", floatArrayOf(0f, 0f, 2f, 0f))
        shader.setFloatUniform("lights", floatArrayOf(0f, 0f, 1f, 0f))
        shader.setFloatUniform("frames", 2f, 1f)
        shader.setFloatUniform("blend", 0.4f)
        shader.setFloatUniform("effort", 0.8f)
    }
}
