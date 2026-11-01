package com.meticulouscreations.homesafe.packages

import com.meticulouscreations.homesafe.packages.ui.shader.PackagesShaderSources
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The packages app's shaders are one AGSL source drawn on Android's `RuntimeShader` and, from the
 * same text, on Skia's `RuntimeEffect` everywhere else. A source that doesn't compile does not
 * crash: the page quietly goes without its box, which is how a typo ships. Compile each here with
 * Skia, and say what the compiler said.
 */
class PackagesShadersCompileTest {

    @Test
    fun everyPackagesShaderCompiles() {
        assertEquals(setOf("box", "backdrop", "route", "gift", "scan"), PackagesShaderSources.keys)
        PackagesShaderSources.forEach { (name, source) ->
            try {
                RuntimeEffect.makeForShader(source)
            } catch (e: Throwable) {
                fail("The $name shader does not compile as a Skia runtime shader: ${e.message}")
            }
        }
    }
}
