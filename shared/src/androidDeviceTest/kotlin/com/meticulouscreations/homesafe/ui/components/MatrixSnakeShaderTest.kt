package com.meticulouscreations.homesafe.ui.components

import android.graphics.RuntimeShader
import kotlin.test.Test

/**
 * The home skeleton's Matrix snake is compiled on the device the first time a card draws, and a
 * shader that doesn't compile quietly falls back to the plain outline runner (see
 * [loadingRunner]). So compile it here, on a real Android, where a mistake fails a test instead
 * of shipping as a snake nobody ever sees.
 */
class MatrixSnakeShaderTest {

    @Test
    fun theSnakeShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(MATRIX_SNAKE_AGSL)
        // Each is set on every frame; a name the shader doesn't declare throws.
        shader.setFloatUniform("size", 960f, 540f)
        shader.setFloatUniform("corner", 52f)
        shader.setFloatUniform("head", 0.3f)
        shader.setFloatUniform("time", 12.5f)
        shader.setFloatUniform("density", 2.625f)
        shader.setFloatUniform("seed", 12.3f)
    }
}
