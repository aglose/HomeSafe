package com.meticulouscreations.homesafe.atlas.ui.shader

import android.graphics.RuntimeShader
import kotlin.test.Test

/**
 * The atlas shaders compile on the device the first time the map or a card draws, and one that
 * doesn't quietly leaves the map without its glow. Compile each here, on a real Android, where
 * AGSL's own compiler (not the desktop's Skia) gets the final say, and set every uniform it
 * declares: a name the source doesn't declare throws. A `uniform shader content` is the layer
 * the effect is put on and is handed over at draw time, so it is not set here.
 */
class AtlasShadersTest {

    @Test
    fun theNightShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(ATLAS_NIGHT_SHADER)
        shader.setFloatUniform("size", 1080f, 2200f)
        shader.setFloatUniform("origin", 120f, 340f)
        shader.setFloatUniform("cell", 158f)
        shader.setFloatUniform("time", 3.2f)
    }

    @Test
    fun theHeatShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(ATLAS_HEAT_SHADER)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("air", 0.69f, 0.55f, 1f)
    }

    @Test
    fun theContourShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(ATLAS_CONTOUR_SHADER)
        shader.setFloatUniform("size", 1080f, 600f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("seed", 0.4f)
        shader.setFloatUniform("tint", 1f, 0.7f, 0.33f)
        shader.setFloatUniform("tint2", 0.69f, 0.55f, 1f)
        shader.setFloatUniform("energy", 0.6f)
    }
}
