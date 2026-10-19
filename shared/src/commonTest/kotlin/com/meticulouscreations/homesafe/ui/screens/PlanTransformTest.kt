package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.ui.geometry.Offset
import com.meticulouscreations.homesafe.domain.model.PlanBounds
import com.meticulouscreations.homesafe.domain.model.PlanPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanTransformTest {

    private fun close(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < 0.01f, "expected $expected, was $actual")

    @Test
    fun aTallFloorFitsTheBoxHeightAndIsCentredAcross() {
        // 20 ft x 60 ft in a 400 x 340 px box with 20 px padding: the height decides, 5 px a foot.
        val transform = PlanTransform(PlanBounds(0f, 0f, 20f, 60f), width = 400f, height = 340f, padding = 20f)
        assertEquals(5f, transform.scale)
        close(150f, transform.toPx(PlanPoint(0f, 0f)).x)
        close(20f, transform.toPx(PlanPoint(0f, 0f)).y)
        close(250f, transform.toPx(PlanPoint(20f, 60f)).x)
        close(320f, transform.toPx(PlanPoint(20f, 60f)).y)
    }

    @Test
    fun aTapComesBackAsTheFeetItWasDrawnAt() {
        // An outline that juts left of the origin (the real plan's bump-outs do) still round-trips.
        val transform = PlanTransform(PlanBounds(-1f, 0f, 25.5f, 68f), width = 380f, height = 440f, padding = 20f)
        val feet = transform.toFeet(transform.toPx(PlanPoint(12.5f, 39f)))
        close(12.5f, feet.x)
        close(39f, feet.y)
        val corner = transform.toFeet(Offset(0f, 0f))
        assertTrue(corner.x < -1f && corner.y < 0f, "the box's corner is outside the floor")
    }
}
