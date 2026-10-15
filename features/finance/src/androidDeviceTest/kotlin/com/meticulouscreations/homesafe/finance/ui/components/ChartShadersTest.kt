package com.meticulouscreations.homesafe.finance.ui.components

import android.graphics.RuntimeShader
import kotlin.test.Test

/**
 * The chart looks' fill shaders compile on the device the first time a chart draws in them, and
 * one that doesn't quietly falls back to the plain wash. Compile them here, on a real Android,
 * where AGSL's own compiler (not the desktop's Skia) gets the final say.
 */
class ChartShadersTest {

    @Test
    fun theAuroraFillCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(CHART_AURORA_SHADER)
        // Each is set on every frame; a name the shader doesn't declare throws.
        shader.setFloatUniform("size", 1080f, 630f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("tint", 0f, 0.78f, 0.02f)
        shader.setFloatUniform("top", 40f)
        shader.setFloatUniform("bottom", 600f)
    }

    @Test
    fun theHalftoneFillCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(CHART_HALFTONE_SHADER)
        shader.setFloatUniform("tint", 0f, 0.78f, 0.02f)
        shader.setFloatUniform("top", 40f)
        shader.setFloatUniform("bottom", 600f)
        shader.setFloatUniform("cell", 15.75f)
        shader.setFloatUniform("scrub", -1f)
    }
}
