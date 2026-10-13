package com.meticulouscreations.homesafe.atlas

import com.meticulouscreations.homesafe.atlas.ui.shader.AtlasShaderSources
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The atlas app's shaders are one AGSL source drawn on Android's `RuntimeShader` and, from the
 * same text, on Skia's `RuntimeEffect` everywhere else. A source that doesn't compile does not
 * crash: the map quietly goes without its glow, which is how a typo ships. Compile each here with
 * Skia, and say what the compiler said.
 */
class AtlasShadersCompileTest {

    @Test
    fun everyAtlasShaderCompiles() {
        assertEquals(setOf("night", "heat", "contour"), AtlasShaderSources.keys)
        AtlasShaderSources.forEach { (name, source) ->
            try {
                RuntimeEffect.makeForShader(source)
            } catch (e: Throwable) {
                fail("The $name shader does not compile as a Skia runtime shader: ${e.message}")
            }
        }
    }
}
