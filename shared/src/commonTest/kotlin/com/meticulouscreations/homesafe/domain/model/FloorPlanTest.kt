package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FloorPlanTest {

    private val back = PlanDoor(PlanPoint(12f, 0f), PlanPoint(15f, 0f), name = "Back door")
    private val side = PlanDoor(PlanPoint(30f, 10f), PlanPoint(30f, 13f))
    private val floor = PlanFloor("ground", "Ground floor", listOf(PlanPoint(0f, 0f), PlanPoint(30f, 0f), PlanPoint(30f, 40f), PlanPoint(0f, 40f)), doors = listOf(back, side))

    @Test
    fun aDoorPlacedNearADoorwaySnapsToTheNearestOne() {
        assertEquals(PlanPoint(13.5f, 0f), back.middle)
        assertEquals(back, nearestDoorway(floor, PlanPoint(14f, 1.5f)))
        assertEquals(side, nearestDoorway(floor, PlanPoint(29f, 12f)))
        assertNull(nearestDoorway(floor, PlanPoint(15f, 20f)), "nowhere near a doorway")
    }

    @Test
    fun aDoorwayStandingOutsideTheOutlineStillCountsTowardsTheBounds() {
        val porch = floor.copy(doors = listOf(PlanDoor(PlanPoint(10f, 44f), PlanPoint(13f, 44f))))
        assertEquals(44f, porch.bounds.bottom)
    }
}
