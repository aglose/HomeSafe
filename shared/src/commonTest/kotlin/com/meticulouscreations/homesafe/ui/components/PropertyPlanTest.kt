package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The site plan is real geometry, so it can be checked like geometry: everything the drawing
 * puts on the lot has to actually be on the lot, and the pieces have to line up with each other
 * rather than leaving gaps between kerb, verge and pavement.
 *
 * This is what stops a later tweak to one rectangle quietly leaving the house hanging over the
 * pavement or the driveway starting in the middle of the road.
 */
class PropertyPlanTest {

    @Test
    fun theStreetSideBandsMeetWithoutGaps() {
        assertEquals(PropertyPlan.road.top, PropertyPlan.plantingStrip.bottom, "the verge starts at the kerb")
        assertEquals(PropertyPlan.plantingStrip.top, PropertyPlan.pavement.bottom, "the pavement starts where the verge ends")
        assertEquals(PropertyPlan.pavement.top, PropertyPlan.lot.bottom, "the front property line is the back of the pavement")
        assertEquals(PropertyPlan.size.height, PropertyPlan.road.bottom, "the road runs off the bottom edge")
    }

    @Test
    fun theHouseSitsInsideTheLot() {
        listOf(PropertyPlan.house, PropertyPlan.houseRearUpper, PropertyPlan.houseRearLower, PropertyPlan.porch)
            .forEach { part ->
                assertTrue(part.top >= PropertyPlan.lot.top, "$part stops short of the back fence")
                assertTrue(part.bottom <= PropertyPlan.lot.bottom, "$part starts behind the front property line")
                assertTrue(part.left >= PropertyPlan.lot.left && part.right <= PropertyPlan.lot.right, "$part is within the lot's width")
            }
    }

    @Test
    fun theHouseIsTheFootprintTheSurveySays() {
        // 9.4 m across the front by 20.2 m deep — OSM way 466290227, to the centimetre.
        assertEquals(9.4f, PropertyPlan.house.width, absoluteTolerance = 0.05f)
        assertEquals(20.2f, PropertyPlan.house.height, absoluteTolerance = 0.05f)
    }

    @Test
    fun theRearProjectionStepsBackFromTheMainMass() {
        assertEquals(PropertyPlan.house.top, PropertyPlan.houseRearUpper.bottom, "the first step starts at the back wall")
        assertEquals(PropertyPlan.houseRearUpper.top, PropertyPlan.houseRearLower.bottom, "the second step starts at the first")
        assertTrue(PropertyPlan.houseRearUpper.width < PropertyPlan.house.width, "the projection is narrower than the house")
        assertTrue(PropertyPlan.houseRearLower.width < PropertyPlan.houseRearUpper.width, "and narrows again")
    }

    @Test
    fun theStreetIsAtTheBottomAndTheBackGardenAtTheTop() {
        assertTrue(PropertyPlan.porch.bottom > PropertyPlan.house.bottom, "the porch faces the road")
        assertTrue(PropertyPlan.houseRearLower.top < PropertyPlan.house.top, "and the rear projection faces away from it")
        assertTrue(PropertyPlan.lot.top < PropertyPlan.houseRearLower.top, "with back garden beyond that")
    }

    @Test
    fun theDrivewayRunsFromTheKerbUpToTheHouse() {
        assertEquals(PropertyPlan.road.top, PropertyPlan.driveway.bottom, "you drive on to it from the road")
        assertEquals(PropertyPlan.house.bottom, PropertyPlan.driveway.top, "it stops at the front wall; there is no room to pass down the side")
        assertTrue(PropertyPlan.driveway.left >= PropertyPlan.lot.left, "and stays on this lot rather than on the neighbour's")
    }

    @Test
    fun theLotLinesSitInTheGapsBetweenTheThreeHouses() {
        assertTrue(PropertyPlan.neighbourLeft.right < PropertyPlan.lot.left, "3101's wall is on its own side of the left boundary")
        assertTrue(PropertyPlan.lot.left < PropertyPlan.house.left, "and 3103's wall on its side")
        assertTrue(PropertyPlan.house.right < PropertyPlan.lot.right, "same the other way")
        assertTrue(PropertyPlan.lot.right < PropertyPlan.neighbourRight.left, "with 3107 beyond the right boundary")
    }

    @Test
    fun theFrontWalkJoinsThePavementToThePorch() {
        assertEquals(PropertyPlan.pavement.top, PropertyPlan.frontWalk.bottom)
        assertEquals(PropertyPlan.porch.bottom, PropertyPlan.frontWalk.top)
        assertTrue(
            PropertyPlan.frontWalk.left >= PropertyPlan.porch.left && PropertyPlan.frontWalk.right <= PropertyPlan.porch.right,
            "the path arrives at the porch rather than beside it",
        )
    }

    @Test
    fun everythingDrawnIsInsideThePanel() {
        val onPlan = listOf(
            PropertyPlan.road, PropertyPlan.plantingStrip, PropertyPlan.pavement, PropertyPlan.lot,
            PropertyPlan.house, PropertyPlan.houseRearUpper, PropertyPlan.houseRearLower,
            PropertyPlan.porch, PropertyPlan.driveway, PropertyPlan.frontWalk,
        )
        onPlan.forEach { r ->
            assertTrue(r.left >= 0f && r.right <= PropertyPlan.size.width, "$r is within the plan's width")
            assertTrue(r.top >= 0f && r.bottom <= PropertyPlan.size.height, "$r is within the plan's height")
        }
    }

    @Test
    fun theNeighboursRunOffTheEdgesRatherThanFloatingInTheGarden() {
        assertTrue(PropertyPlan.neighbourLeft.left < 0f, "3101 is cut off by the left edge")
        assertTrue(PropertyPlan.neighbourLeft.right <= PropertyPlan.lot.left, "and never crosses on to this lot")
        assertTrue(PropertyPlan.neighbourRight.right > PropertyPlan.size.width, "3107 is cut off by the right edge")
        assertTrue(PropertyPlan.neighbourRight.left >= PropertyPlan.lot.right, "and never crosses on to this lot either")
    }

    @Test
    fun theTreesAreOnThePlan() {
        (PropertyPlan.trees + PropertyPlan.shrubs).forEach { (x, y, radius) ->
            assertTrue(x - radius >= 0f && x + radius <= PropertyPlan.size.width, "canopy at $x,$y fits across the plan")
            assertTrue(y - radius >= 0f && y + radius <= PropertyPlan.size.height, "canopy at $x,$y fits down the plan")
        }
    }
}
