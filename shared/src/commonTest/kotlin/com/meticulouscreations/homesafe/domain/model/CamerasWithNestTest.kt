package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CamerasWithNestTest {

    private fun nest(name: String, placement: CameraPlacement = CameraPlacement.Indoor) =
        Camera(name = name, enabled = true, placement = placement, source = CameraSource.Nest)

    @Test
    fun aFrigateCameraReadingANestStreamTakesItsPlace() {
        val frigate = listOf(
            Camera(name = "hikvision_1", enabled = true, gridStreamName = "hikvision_1_sub"),
            Camera(name = "living_room", enabled = true, gridStreamName = "living_room_sub"),
            Camera(name = "front", enabled = true, liveStreamName = "outdoor_doorbell", gridStreamName = "outdoor_doorbell_sub"),
        )
        val merged = camerasWithNest(frigate, listOf(nest("living_room"), nest("outdoor_doorbell", CameraPlacement.Outdoor), nest("kitchen")))

        assertEquals(
            listOf(
                Triple("hikvision_1", CameraPlacement.Outdoor, false),
                Triple("living_room", CameraPlacement.Indoor, true),
                Triple("front", CameraPlacement.Outdoor, true),
                Triple("kitchen", CameraPlacement.Indoor, true),
            ),
            merged.map { Triple(it.name, it.placement, it.fromNest) },
        )
        assertEquals(
            listOf(CameraSource.Frigate, CameraSource.Frigate, CameraSource.Frigate, CameraSource.Nest),
            merged.map { it.source },
            "a Nest camera Frigate reads is Frigate's, with its page",
        )
    }

    @Test
    fun withoutNestCamerasFrigatesAreAsTheyWere() {
        val frigate = listOf(Camera(name = "hikvision_1", enabled = true))
        assertEquals(frigate, camerasWithNest(frigate, emptyList()))
    }
}
