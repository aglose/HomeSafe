package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The Budget page's two shaders compile on the desktop's Skia, which is also what draws them on
 * iOS: one that didn't would quietly fall back to the plain tank and the plain bar. Android's own
 * compiler has its say in `BudgetShadersTest` on a device.
 */
class BudgetShadersDesktopTest {

    private val calm = Color(0xFF00C805)

    @Test
    fun theTankCompilesAndTakesItsUniforms() {
        val shader = assertNotNull(financeShaderOrNull(BUDGET_TANK_SHADER))
        shader.setUniform("size", 208f, 336f)
        listOf("time" to 3.2f, "level" to 0.8f, "heat" to 0.5f, "corner" to 52f).forEach { (name, value) -> shader.setUniform(name, value) }
        listOf("calm", "warn", "hot", "glass").forEach { shader.setUniform(it, calm) }
        assertNotNull(shader.brush())
    }

    @Test
    fun theHeatMeterCompilesAndTakesItsUniforms() {
        val shader = assertNotNull(financeShaderOrNull(BUDGET_HEAT_METER_SHADER))
        shader.setUniform("size", 700f, 44f)
        listOf("time" to 3.2f, "level" to 1.2f, "heat" to 1f).forEach { (name, value) -> shader.setUniform(name, value) }
        listOf("calm", "warn", "hot", "track").forEach { shader.setUniform(it, calm) }
        assertNotNull(shader.brush())
    }
}
