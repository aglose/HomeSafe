package com.meticulouscreations.homesafe.ui.components

import android.graphics.RuntimeShader
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The pull-to-refresh band's shader is compiled on the device the first time the band draws, and
 * one that doesn't compile quietly falls back to the plain ring (see [apertureScanShaderOrNull]).
 * So compile it here, on a real Android, where a mistake fails a test instead of shipping as a
 * lens nobody ever sees. The desktop renders compile the same source through Skia, but AGSL is
 * Android's own compiler.
 */
class ApertureScanShaderTest {

    @Test
    fun theApertureShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(APERTURE_SCAN_SHADER)
        // Each is set on every frame; a name the shader doesn't declare throws.
        shader.setFloatUniform("size", 1080f, 231f)
        shader.setFloatUniform("progress", 1.1f)
        shader.setFloatUniform("scanning", 1f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("density", 2.625f)
        shader.setFloatUniform("accent", 1f, 0.71f, 0.62f)
        shader.setFloatUniform("ember", 0.84f, 0.46f, 0.33f)
        shader.setFloatUniform("metal", 0.21f, 0.21f, 0.2f)
    }

    @Test
    fun theAndroidShaderIsTheCompiledOneNotTheFallback() {
        assertNotNull(apertureScanShaderOrNull(), "the band would draw its plain ring")
    }
}
