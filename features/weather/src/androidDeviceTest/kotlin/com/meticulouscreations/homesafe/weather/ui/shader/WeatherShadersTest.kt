package com.meticulouscreations.homesafe.weather.ui.shader

import android.graphics.RuntimeShader
import kotlin.test.Test

/**
 * The weather shaders compile on the device the first time the sky or the radar draws, and one
 * that doesn't quietly falls back to a flat colour. Compile each here, on a real Android, where
 * AGSL's own compiler (not the desktop's Skia) gets the final say, and set every uniform it
 * declares: a name the source doesn't declare throws. A `uniform shader content` is the layer
 * the effect is put on and is handed over at draw time, so it is not set here.
 */
class WeatherShadersTest {

    @Test
    fun theCelestialShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(CELESTIAL_SHADER)
        shader.setFloatUniform("size", 1080f, 1200f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("unit", 2.75f)
        shader.setFloatUniform("zenith", 0.1f, 0.2f, 0.5f)
        shader.setFloatUniform("horizon", 0.6f, 0.7f, 0.9f)
        shader.setFloatUniform("glow", 0.9f, 0.5f, 0.2f)
        shader.setFloatUniform("sunColor", 1f, 0.9f, 0.7f)
        shader.setFloatUniform("sun", 700f, 400f, 1f)
        shader.setFloatUniform("moon", 300f, 300f, 1f)
        shader.setFloatUniform("moonCycle", 0.5f)
        shader.setFloatUniform("stars", 1f)
    }

    @Test
    fun theCloudShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(CLOUD_SHADER)
        shader.setFloatUniform("size", 1080f, 1200f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("unit", 2.75f)
        shader.setFloatUniform("horizon", 0.6f, 0.7f, 0.9f)
        shader.setFloatUniform("sunColor", 1f, 0.9f, 0.7f)
        shader.setFloatUniform("sun", 700f, 400f, 1f)
        shader.setFloatUniform("cloudLit", 1f, 1f, 1f)
        shader.setFloatUniform("cloudShade", 0.4f, 0.45f, 0.5f)
        shader.setFloatUniform("cover", 0.6f)
        shader.setFloatUniform("storm", 0.3f)
        shader.setFloatUniform("drift", 0.1f, 0.02f)
        shader.setFloatUniform("fog", 0.2f)
        shader.setFloatUniform("bolt", 0f, 0f, 0f, 0f)
    }

    @Test
    fun thePrecipitationShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(PRECIP_SHADER)
        shader.setFloatUniform("size", 1080f, 1200f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("unit", 2.75f)
        shader.setFloatUniform("rain", 0.7f)
        shader.setFloatUniform("snow", 0.2f)
        shader.setFloatUniform("wind", -0.3f)
        shader.setFloatUniform("light", 0.8f, 0.85f, 0.95f)
        shader.setFloatUniform("bolt", 0f, 0f, 0f, 0f)
    }

    @Test
    fun theGlassShaderCompilesAndTakesItsUniforms() {
        // Its `content` is the layer it is put on, handed over when it is drawn.
        val shader = RuntimeShader(GLASS_SHADER)
        shader.setFloatUniform("size", 1080f, 1200f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("unit", 2.75f)
        shader.setFloatUniform("amount", 0.5f)
    }

    @Test
    fun theMoonShaderCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(MOON_SHADER)
        shader.setFloatUniform("size", 200f, 200f)
        shader.setFloatUniform("cycle", 0.25f)
    }

    @Test
    fun theMapNightShaderCompilesAndTakesItsUniforms() {
        // Its `content` is the map under it.
        val shader = RuntimeShader(MAP_NIGHT_SHADER)
        shader.setFloatUniform("dim", 0.6f)
    }

    @Test
    fun theRadarShaderCompilesAndTakesItsUniforms() {
        // Its `content` is the decoded radar picture.
        val shader = RuntimeShader(RADAR_SHADER)
        shader.setFloatUniform("size", 1080f, 1200f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("unit", 2.75f)
        shader.setFloatUniform("blur", 1.5f)
        shader.setFloatUniform("forecast", 0f)
        shader.setFloatUniform("strength", 1f)
    }
}
