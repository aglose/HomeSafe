package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorSpot
import com.meticulouscreations.homesafe.domain.model.FloorPlan
import com.meticulouscreations.homesafe.domain.model.PlanDoor
import com.meticulouscreations.homesafe.domain.model.PlanFloor
import com.meticulouscreations.homesafe.domain.model.PlanPoint
import com.meticulouscreations.homesafe.domain.model.PlanStairs
import com.meticulouscreations.homesafe.domain.model.StairDirection
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.DOORS_PLACE_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.DOORS_PLAN_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.DoorsFloorPlan
import com.meticulouscreations.homesafe.ui.screens.DoorsPlanActions
import com.meticulouscreations.homesafe.viewmodel.DoorsUiState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The floor plan on the Doors page: the dots, opening a door from its dot, and placing a door by tapping the plan. */
@OptIn(ExperimentalTestApi::class)
class DoorsFloorPlanUiTest {

    // A square floor, so a tap at the drawing's centre is the floor's centre whatever the box's shape.
    private val plan = FloorPlan(
        listOf(
            PlanFloor("ground", "Ground floor", listOf(PlanPoint(0f, 0f), PlanPoint(20f, 0f), PlanPoint(20f, 20f), PlanPoint(0f, 20f))),
            PlanFloor("upper", "Upper floor", listOf(PlanPoint(0f, 0f), PlanPoint(20f, 0f), PlanPoint(20f, 20f), PlanPoint(0f, 20f))),
        ),
    )
    private val back = Door("0x01", "Back door", open = true, spot = DoorSpot("ground", 10f, 0f))
    private val front = Door("0x02", "Front door", open = false)

    @Test
    fun aPlacedDoorIsADotThatOpensItsCard() = runComposeUiTest {
        var shown: String? = null
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Column(modifier = Modifier.width(400.dp)) {
                    DoorsFloorPlan(DoorsUiState(doors = listOf(back, front), loaded = true, floorPlan = plan, floorId = "ground"), DoorsPlanActions(), onShowDoor = { shown = it })
                }
            }
        }

        onNodeWithText("1 door isn't on the plan yet.").assertExists()
        onAllNodesWithContentDescription("Front door, Closed").assertCountEquals(0)
        onNodeWithContentDescription("Back door, Open").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("0x01", shown)
    }

    @Test
    fun whilePlacingATapOnThePlanIsWhereTheDoorGoesInFeet() = runComposeUiTest {
        var placed: Pair<Float, Float>? = null
        var started = 0
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Column(modifier = Modifier.width(400.dp)) {
                    DoorsFloorPlan(
                        DoorsUiState(doors = listOf(back, front), loaded = true, floorPlan = plan, floorId = "ground", placing = true, placingDoorId = "0x02"),
                        DoorsPlanActions(onStartPlacing = { started++ }, onPlaceAt = { x, y -> placed = x to y }),
                        onShowDoor = {},
                    )
                }
            }
        }

        onNodeWithText("Done").assertExists()
        onNodeWithTag(DOORS_PLAN_TEST_TAG).performTouchInput { click(center) }
        val (x, y) = assertNotNull(placed)
        assertTrue(abs(x - 10f) < 0.5f && abs(y - 10f) < 0.5f, "the middle of a 20 ft square floor, was ($x, $y)")
        onNodeWithTag(DOORS_PLACE_TEST_TAG).performClick()
        assertEquals(0, started, "while placing the button is Done, not Place doors")
    }

    @Test
    fun whilePlacingATapNearADoorwayPutsTheDoorInItsMiddle() = runComposeUiTest {
        var placed: Pair<Float, Float>? = null
        // A doorway a little off the floor's centre: a tap at the centre lands near it.
        val doorway = PlanDoor(PlanPoint(9f, 11f), PlanPoint(12f, 11f), name = "Hall door")
        val withDoorway = FloorPlan(listOf(plan.floors[0].copy(doors = listOf(doorway))))
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Column(modifier = Modifier.width(400.dp)) {
                    DoorsFloorPlan(
                        DoorsUiState(doors = listOf(back, front), loaded = true, floorPlan = withDoorway, floorId = "ground", placing = true, placingDoorId = "0x02"),
                        DoorsPlanActions(onPlaceAt = { x, y -> placed = x to y }),
                        onShowDoor = {},
                    )
                }
            }
        }

        onNodeWithTag(DOORS_PLAN_TEST_TAG).performTouchInput { click(center) }
        assertEquals(10.5f to 11f, placed)
    }

    @Test
    fun stairsToAnotherFloorAreAButtonToIt() = runComposeUiTest {
        var shown: String? = null
        val down = PlanStairs(PlanPoint(10f, 10f), StairDirection.DOWN, flight = "cellar")
        val withStairs = FloorPlan(
            listOf(
                plan.floors[0].copy(stairs = listOf(down)),
                plan.floors[1].copy(id = "cellar", name = "Basement", stairs = listOf(down.copy(direction = StairDirection.UP))),
            ),
        )
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Column(modifier = Modifier.width(400.dp)) {
                    DoorsFloorPlan(DoorsUiState(loaded = true, floorPlan = withStairs, floorId = "ground"), DoorsPlanActions(onSelectFloor = { shown = it }), onShowDoor = {})
                }
            }
        }

        onNodeWithContentDescription("Down to Basement").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("cellar", shown)
    }

    @Test
    fun whilePlacingATapOnAnotherDoorsDotPlacesThePickedDoorThere() = runComposeUiTest {
        var placed: Pair<Float, Float>? = null
        var chosen: String? = null
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Column(modifier = Modifier.width(400.dp)) {
                    DoorsFloorPlan(
                        DoorsUiState(doors = listOf(back, front), loaded = true, floorPlan = plan, floorId = "ground", placing = true, placingDoorId = "0x02"),
                        DoorsPlanActions(onChoosePlacingDoor = { chosen = it }, onPlaceAt = { x, y -> placed = x to y }),
                        onShowDoor = {},
                    )
                }
            }
        }

        // The back door's dot sits at (10, 0); the front door goes right there.
        onNodeWithContentDescription("Back door, Open").performTouchInput { click(center) }
        assertEquals(null, chosen, "the dot doesn't pick its door: the chips do")
        val (x, y) = assertNotNull(placed)
        assertTrue(abs(x - 10f) < 0.5f && abs(y) < 0.5f, "on the back door's spot, was ($x, $y)")
    }

    @Test
    fun rightToLeftKeepsADotOverItsSpot() = runComposeUiTest {
        // Near the floor's left wall, so a mirrored dot would land near the right one.
        val west = back.copy(spot = DoorSpot("ground", 2f, 10f))
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                FrigatePreview {
                    Column(modifier = Modifier.width(400.dp)) {
                        DoorsFloorPlan(DoorsUiState(doors = listOf(west), loaded = true, floorPlan = plan, floorId = "ground"), DoorsPlanActions(), onShowDoor = {})
                    }
                }
            }
        }

        val drawing = onNodeWithTag(DOORS_PLAN_TEST_TAG).getBoundsInRoot()
        val dot = onNodeWithContentDescription("Back door, Open").getBoundsInRoot()
        val middle = (drawing.left + drawing.right) / 2
        assertTrue((dot.left + dot.right) / 2 < middle, "the dot stays on the left, where the plan draws its spot")
    }
}
