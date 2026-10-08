package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.fitness.ui.shader.FITNESS_SHADERS
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.fail

/**
 * The fitness app's shaders are one AGSL source drawn on Android's `RuntimeShader` and, from the
 * same text, on Skia's `RuntimeEffect` everywhere else. A source that doesn't compile does not
 * crash: the app quietly falls back to a plain gradient, which is how a typo ships. Compile each
 * here with Skia, and say what the compiler said.
 */
class FitnessShadersCompileTest {

    @Test
    fun everyShaderCompiles() {
        for ((name, source) in FITNESS_SHADERS) {
            try {
                RuntimeEffect.makeForShader(source)
            } catch (e: Throwable) {
                fail("The $name shader does not compile as a Skia runtime shader: ${e.message}")
            }
        }
    }
}
