package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.domain.model.CameraCrop
import com.meticulouscreations.homesafe.domain.model.VideoFit
import kotlin.test.Test
import kotlin.test.assertEquals

class CropAsZoomRestTest {

    private val wide = 16f / 9f

    @Test
    fun theWholePictureRestsAtOneTimesCentred() {
        assertEquals(ZoomRest.NONE, cropAsZoomRest(CameraCrop.WHOLE, boxAspect = wide, aspect = null, fit = null, onItsSide = false))
    }

    @Test
    fun aStretchedPictureRestsOnItsCropTheWayShapedVideoDrawsIt() {
        // Twice as close on the right-hand half: as far right as the picture goes.
        val rest = cropAsZoomRest(CameraCrop(zoom = 2f, centerX = 0.75f, centerY = 0.5f), boxAspect = wide, aspect = null, fit = null, onItsSide = false)
        assertEquals(2f, rest.scale)
        assertEquals(-0.5f, rest.shift.x, 0.0001f, "the box slid half its width left, its right edge on the frame's")
        assertEquals(0f, rest.shift.y, 0.0001f)
    }

    @Test
    fun aCropPastThePicturesEdgeIsKeptOnThePictureAsShapedVideoKeepsIt() {
        // A 4:3 Nest picture shown whole in a 16:9 box spans three quarters of its width, so at 2x
        // its middle can go no further right than two thirds of the way across.
        val rest = cropAsZoomRest(CameraCrop(zoom = 2f, centerX = 0.9f), boxAspect = wide, aspect = 4f / 3f, fit = VideoFit.Fit, onItsSide = false)
        assertEquals(2f, rest.scale)
        assertEquals((0.5f - 2f / 3f) * 2f * 0.75f, rest.shift.x, 0.001f)
    }
}
