package com.meticulouscreations.homesafe.finance.ui.components

import android.graphics.RuntimeShader
import kotlin.test.Test

/**
 * The Budget page's tank and heat meter compile on the device the first time the page draws, and
 * one that doesn't quietly falls back to a plain tank or a plain bar. Compile them here, on a
 * real Android, where AGSL's own compiler (not the desktop's Skia) gets the final say.
 */
class BudgetShadersTest {

    @Test
    fun theTankCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(BUDGET_TANK_SHADER)
        // Each is set on every frame; a name the shader doesn't declare throws.
        shader.setFloatUniform("size", 273f, 441f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("level", 0.8f)
        shader.setFloatUniform("heat", 0.5f)
        shader.setFloatUniform("corner", 68f)
        shader.setFloatUniform("calm", 0f, 0.78f, 0.02f)
        shader.setFloatUniform("warn", 1f, 0.72f, 0f)
        shader.setFloatUniform("hot", 1f, 0.31f, 0f)
        shader.setFloatUniform("glass", 1f, 1f, 1f)
    }

    @Test
    fun theHeatMeterCompilesAndTakesItsUniforms() {
        val shader = RuntimeShader(BUDGET_HEAT_METER_SHADER)
        shader.setFloatUniform("size", 920f, 58f)
        shader.setFloatUniform("time", 3.2f)
        shader.setFloatUniform("level", 1.2f)
        shader.setFloatUniform("heat", 1f)
        shader.setFloatUniform("calm", 0f, 0.78f, 0.02f)
        shader.setFloatUniform("warn", 1f, 0.72f, 0f)
        shader.setFloatUniform("hot", 1f, 0.31f, 0f)
        shader.setFloatUniform("track", 0.14f, 0.15f, 0.16f)
    }
}
