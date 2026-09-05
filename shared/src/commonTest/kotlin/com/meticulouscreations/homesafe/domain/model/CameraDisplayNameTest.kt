package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraDisplayNameTest {
    @Test
    fun knownCamerasGetThePlaceTheyWatch() {
        assertEquals("Front Door", cameraDisplayName("amcrest_1"))
        assertEquals("Front Yard", cameraDisplayName("hikvision_1"))
        assertEquals("Backyard", cameraDisplayName("hikvision_2"))
    }

    @Test
    fun lookupIgnoresCase() {
        assertEquals("Front Door", cameraDisplayName("Amcrest_1"))
    }

    @Test
    fun unknownCamerasAreHumanized() {
        assertEquals("Side Gate Cam", cameraDisplayName("side_gate_cam"))
        assertEquals("Garage", cameraDisplayName("garage"))
        assertEquals("Driveway 2", cameraDisplayName("driveway-2"))
    }

    @Test
    fun degenerateNamesFallBackToTheRawId() {
        assertEquals("___", cameraDisplayName("___"))
    }
}
