@file:OptIn(ExperimentalForeignApi::class)

package com.meticulouscreations.homesafe.car3d

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLPixelFormatDepth32Float
import kotlin.test.Test
import kotlin.test.assertEquals

/** The car's Metal shaders compile and link into pipelines, where the test runner is given a GPU. */
class CarShadersCompileTest {
    @Test
    fun shadersCompile() {
        val device = MTLCreateSystemDefaultDevice() ?: return
        val gl = MetalGl(device, MTLPixelFormatBGRA8Unorm, MTLPixelFormatDepth32Float, 4)
        assertEquals(0, gl.program(CarShaders.VERTEX, CarShaders.SURFACE))
        assertEquals(1, gl.program(CarShaders.VERTEX, CarShaders.GLASS))
        assertEquals(2, gl.program(CarShaders.SHADOW_VERTEX, CarShaders.SHADOW))
    }
}
