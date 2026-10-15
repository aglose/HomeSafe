package com.meticulouscreations.homesafe.weather

import com.meticulouscreations.homesafe.weather.ui.shader.CELESTIAL_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.CLOUD_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.GLASS_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.MAP_NIGHT_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.MOON_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.PRECIP_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.RADAR_SHADER
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.fail

/**
 * The weather app's shaders are one AGSL source drawn on Android's `RuntimeShader` and, from the
 * same text, on Skia's `RuntimeEffect` everywhere else. A source that doesn't compile does not
 * crash: the app quietly falls back to a flat colour, which is how a typo ships. Compile each here
 * with Skia, and say what the compiler said.
 */
class WeatherShadersCompileTest {

    private fun assertCompiles(name: String, source: String) {
        try {
            RuntimeEffect.makeForShader(source)
        } catch (e: Throwable) {
            fail("$name does not compile as a Skia runtime shader: ${e.message}")
        }
    }

    @Test
    fun theCelestialShaderCompiles() = assertCompiles("CELESTIAL_SHADER", CELESTIAL_SHADER)

    @Test
    fun theCloudShaderCompiles() = assertCompiles("CLOUD_SHADER", CLOUD_SHADER)

    @Test
    fun thePrecipitationShaderCompiles() = assertCompiles("PRECIP_SHADER", PRECIP_SHADER)

    @Test
    fun theGlassShaderCompiles() = assertCompiles("GLASS_SHADER", GLASS_SHADER)

    @Test
    fun theMoonShaderCompiles() = assertCompiles("MOON_SHADER", MOON_SHADER)

    @Test
    fun theMapNightShaderCompiles() = assertCompiles("MAP_NIGHT_SHADER", MAP_NIGHT_SHADER)

    @Test
    fun theRadarShaderCompiles() = assertCompiles("RADAR_SHADER", RADAR_SHADER)
}
