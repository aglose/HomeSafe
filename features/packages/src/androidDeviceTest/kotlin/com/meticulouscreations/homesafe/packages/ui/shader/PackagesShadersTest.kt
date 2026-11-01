package com.meticulouscreations.homesafe.packages.ui.shader

import android.graphics.RuntimeShader
import kotlin.test.Test

/**
 * The packages shaders compile on the device the first time a card draws, and one that doesn't
 * quietly leaves the page without its box. Compile each here, on a real Android, where AGSL's own
 * compiler (not the desktop's Skia) gets the final say, and set every uniform it declares: a name
 * the source doesn't declare throws.
 */
class PackagesShadersTest {

    @Test
    fun theBoxCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(PARCEL_BOX_SHADER)
        shader.setFloatUniform("size", 600f, 600f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("spin", 0.6f)
        shader.setFloatUniform("tilt", 0f)
        shader.setFloatUniform("drop", 0.3f)
        shader.setFloatUniform("land", 0.1f)
        shader.setFloatUniform("warm", 0.8f)
    }

    @Test
    fun theBackdropCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(PACKAGES_BACKDROP_SHADER)
        shader.setFloatUniform("size", 1080f, 1000f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("seed", 0.2f)
        shader.setFloatUniform("warm", 0.8f)
    }

    @Test
    fun theRouteCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(PACKAGES_ROUTE_SHADER)
        shader.setFloatUniform("size", 1000f, 50f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("progress", 0.5f)
        shader.setFloatUniform("margin", 12f)
        shader.setFloatUniform("tint", 1f, 0.7f, 0.33f)
    }

    @Test
    fun theWrappingCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(PACKAGES_GIFT_SHADER)
        shader.setFloatUniform("size", 1000f, 300f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("seed", 0.4f)
        shader.setFloatUniform("tint", 0.78f, 0.6f, 1f)
        shader.setFloatUniform("tint2", 0.29f, 0.17f, 0.52f)
    }

    @Test
    fun theScanCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(PACKAGES_SCAN_SHADER)
        shader.setFloatUniform("size", 1000f, 600f)
        shader.setFloatUniform("sweep", 0.4f)
        shader.setFloatUniform("tint", 0.34f, 0.86f, 0.8f)
    }
}
