package com.meticulouscreations.homesafe.fitness.ui.shader

import android.graphics.RuntimeShader
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
}
