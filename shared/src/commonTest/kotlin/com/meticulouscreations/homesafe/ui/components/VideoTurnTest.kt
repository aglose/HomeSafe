package com.meticulouscreations.homesafe.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoTurnTest {

    @Test
    fun uprightThePictureIsTheBox() {
        assertEquals(160f to 90f, turnedPictureSize(160f, 90f, 0f))
        assertEquals(160f to 90f, turnedPictureSize(160f, 90f, 180f), "upside down is still the box's shape")
    }

    @Test
    fun aQuarterTurnStandsThePictureOnItsSide() {
        assertEquals(90f to 160f, turnedPictureSize(160f, 90f, 90f))
        assertEquals(90f to 160f, turnedPictureSize(160f, 90f, 270f))
        assertEquals(90f to 160f, turnedPictureSize(160f, 90f, -90f))
    }

    @Test
    fun aTiltedPictureCoversEveryCornerOfTheBox() {
        for (degrees in listOf(5f, -12f, 30f, 45f, 95f, 260f)) {
            val box = 160f to 90f
            val (width, height) = turnedPictureSize(box.first, box.second, degrees)
            // Each corner of the box, taken into the turned picture's own frame, must lie inside it.
            val radians = degrees * PI.toFloat() / 180f
            for ((x, y) in listOf(80f to 45f, -80f to 45f, 80f to -45f, -80f to -45f)) {
                val px = x * cos(radians) + y * sin(radians)
                val py = -x * sin(radians) + y * cos(radians)
                assertTrue(abs(px) <= width / 2 + 0.01f && abs(py) <= height / 2 + 0.01f, "corner ($x, $y) left uncovered at $degrees°")
            }
        }
    }

    @Test
    fun aTiltedPictureIsNoBiggerThanItNeedsToBe() {
        val (width, _) = turnedPictureSize(160f, 90f, 10f)
        assertTrue(width < 160f * 1.4f, "10° should cost well under 40% of zoom, was ${width / 160f}x")
    }
}
