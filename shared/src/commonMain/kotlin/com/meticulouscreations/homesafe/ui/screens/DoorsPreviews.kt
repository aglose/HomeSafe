package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorPairing
import com.meticulouscreations.homesafe.domain.model.DoorSpot
import com.meticulouscreations.homesafe.domain.model.FloorPlan
import com.meticulouscreations.homesafe.domain.model.PlanDoor
import com.meticulouscreations.homesafe.domain.model.PlanFloor
import com.meticulouscreations.homesafe.domain.model.PlanPoint
import com.meticulouscreations.homesafe.domain.model.PlanRoom
import com.meticulouscreations.homesafe.domain.model.PlanStairs
import com.meticulouscreations.homesafe.domain.model.PlanWall
import com.meticulouscreations.homesafe.domain.model.StairDirection
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.viewmodel.DoorHistoryDay
import com.meticulouscreations.homesafe.viewmodel.DoorHistoryEntry
import com.meticulouscreations.homesafe.viewmodel.DoorJoinProgress
import com.meticulouscreations.homesafe.viewmodel.DoorJoinRow
import com.meticulouscreations.homesafe.viewmodel.DoorsUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_pairing_interviewing
import homesafe.shared.generated.resources.doors_pairing_paired_unnamed
import homesafe.shared.generated.resources.doors_pairing_rejoined
import homesafe.shared.generated.resources.doors_subtitle
import homesafe.shared.generated.resources.doors_title
import org.jetbrains.compose.resources.stringResource

private const val PREVIEW_NOW = 1_792_300_000_000L

private val previewDoors = listOf(
    Door("0x01", "Back door", open = true, changedAtMillis = PREVIEW_NOW - 7 * 60_000, battery = 88, linkQuality = 120, online = true, notify = DoorNotify.ALWAYS, spot = DoorSpot("ground", 14f, 0f)),
    Door("0x02", "Front door", open = false, changedAtMillis = PREVIEW_NOW - 3 * 3_600_000, battery = 97, linkQuality = 244, online = true, spot = DoorSpot("ground", 9f, 36f)),
    Door("0x03", "Garage door", open = false, changedAtMillis = PREVIEW_NOW - 2 * 86_400_000, battery = 12, linkQuality = 22, online = true, notify = DoorNotify.NEVER),
)

/**
 * A made-up two-storey house for the previews: the real plan lives only on the box (see
 * docs/doors.md, "The floor plan"). An L-shaped ground floor with the front door at the bottom.
 */
private val previewPlan = FloorPlan(
    listOf(
        PlanFloor(
            id = "ground",
            name = "Ground floor",
            outline = listOf(PlanPoint(0f, 0f), PlanPoint(28f, 0f), PlanPoint(28f, 24f), PlanPoint(18f, 24f), PlanPoint(18f, 36f), PlanPoint(0f, 36f)),
            rooms = listOf(PlanRoom("Kitchen", PlanPoint(7f, 6f)), PlanRoom("Family Room", PlanPoint(21f, 12f)), PlanRoom("Hall", PlanPoint(9f, 20f)), PlanRoom("Living Room", PlanPoint(9f, 30f))),
            stairs = listOf(PlanStairs(PlanPoint(15f, 18f), StairDirection.UP, facingDegrees = 0, flight = "main")),
            walls = listOf(
                PlanWall(PlanPoint(0f, 13f), PlanPoint(14f, 13f)),
                PlanWall(PlanPoint(14f, 0f), PlanPoint(14f, 16f)),
                PlanWall(PlanPoint(0f, 25f), PlanPoint(12f, 25f)),
            ),
            doors = listOf(
                PlanDoor(PlanPoint(12.5f, 0f), PlanPoint(15.5f, 0f), swing = 1, name = "Back door", sensorId = "0x01"),
                PlanDoor(PlanPoint(10.5f, 36f), PlanPoint(7.5f, 36f), swing = 1, name = "Front door", sensorId = "0x02"),
                PlanDoor(PlanPoint(28f, 18f), PlanPoint(28f, 21f), swing = 1, name = "Garage door"),
            ),
        ),
        PlanFloor(
            id = "upper",
            name = "Upper floor",
            outline = listOf(PlanPoint(0f, 0f), PlanPoint(28f, 0f), PlanPoint(28f, 24f), PlanPoint(0f, 24f)),
            rooms = listOf(PlanRoom("Bedroom", PlanPoint(7f, 7f)), PlanRoom("Bedroom", PlanPoint(21f, 7f)), PlanRoom("Bath", PlanPoint(7f, 18f))),
            stairs = listOf(PlanStairs(PlanPoint(15f, 18f), StairDirection.DOWN, facingDegrees = 180, flight = "main")),
        ),
    ),
    street = "Maple Street",
)

private val previewHistory = listOf(
    DoorHistoryDay(
        0,
        listOf(
            DoorHistoryEntry("Back door", open = true, atMillis = PREVIEW_NOW - 7 * 60_000, notified = true),
            DoorHistoryEntry("Front door", open = false, atMillis = PREVIEW_NOW - 3 * 3_600_000, notified = false),
            DoorHistoryEntry("Front door", open = true, atMillis = PREVIEW_NOW - 3 * 3_600_000 - 40_000, notified = false),
        ),
    ),
    DoorHistoryDay(2, listOf(DoorHistoryEntry("Garage door", open = false, atMillis = PREVIEW_NOW - 2 * 86_400_000, notified = false))),
)

@Composable
private fun DoorsPagePreviewContent(state: DoorsUiState) {
    FrigatePreview {
        SettingsPage(title = stringResource(Res.string.doors_title), subtitle = stringResource(Res.string.doors_subtitle), onBack = {}, onRefresh = {}) {
            DoorsContent(state = state, onNotify = { _, _ -> })
        }
    }
}

/** Three doors: the back one open and set to always notify, the garage's sensor low on battery and far from the stick. */
@Preview(name = "Doors", widthDp = 412, heightDp = 1500)
@Preview(name = "Doors, in Spanish", widthDp = 412, heightDp = 1500, locale = "es")
@Composable
private fun DoorsPreview() {
    DoorsPagePreviewContent(DoorsUiState(doors = previewDoors, history = previewHistory, nowMillis = PREVIEW_NOW, loaded = true, pairing = DoorPairing()))
}

/** The same doors over the house's plan: two placed on the ground floor, the garage's not yet. */
@Preview(name = "Doors, on the floor plan", widthDp = 412, heightDp = 1900)
@Composable
private fun DoorsFloorPlanPreview() {
    DoorsPagePreviewContent(
        DoorsUiState(doors = previewDoors, history = previewHistory, nowMillis = PREVIEW_NOW, loaded = true, floorPlan = previewPlan, floorId = "ground"),
    )
}

/** Placing doors: the garage door picked, the next tap on the plan puts it there. */
@Preview(name = "Doors, placing on the floor plan", widthDp = 412, heightDp = 915)
@Composable
private fun DoorsPlacingPreview() {
    DoorsPagePreviewContent(
        DoorsUiState(
            doors = previewDoors,
            nowMillis = PREVIEW_NOW,
            loaded = true,
            floorPlan = previewPlan,
            floorId = "ground",
            placing = true,
            placingDoorId = "0x03",
        ),
    )
}

/** Pairing: the front door's sensor, which had dropped off, is back, and a new one is being set up. */
@Preview(name = "Doors, pairing", widthDp = 412, heightDp = 1100)
@Composable
private fun DoorsPairingPreview() {
    DoorsPagePreviewContent(
        DoorsUiState(
            doors = previewDoors,
            nowMillis = PREVIEW_NOW,
            loaded = true,
            pairing = DoorPairing(open = true, untilMillis = PREVIEW_NOW + 4 * 60_000),
            joined = listOf(
                DoorJoinRow("0x02", UiText.of(Res.string.doors_pairing_rejoined, "Front door"), DoorJoinProgress.DONE),
                DoorJoinRow("0x04", UiText.of(Res.string.doors_pairing_interviewing), DoorJoinProgress.WORKING),
            ),
        ),
    )
}

/** Pairing over: the new sensor paired and is still called by its address, so its card asks for a name. */
@Preview(name = "Doors, a new sensor to name", widthDp = 412, heightDp = 1300)
@Composable
private fun DoorsNewSensorPreview() {
    DoorsPagePreviewContent(
        DoorsUiState(
            doors = listOf(Door("0xb40e060fffe1271f", "0xb40e060fffe1271f", open = false, battery = 100, linkQuality = 98, online = true)) + previewDoors,
            nowMillis = PREVIEW_NOW,
            loaded = true,
            pairing = DoorPairing(),
            joined = listOf(DoorJoinRow("0xb40e060fffe1271f", UiText.of(Res.string.doors_pairing_paired_unnamed), DoorJoinProgress.DONE)),
        ),
    )
}

/** No sensor paired yet, and the server not hearing the Zigbee network either. */
@Preview(name = "Doors, none paired yet", widthDp = 412, heightDp = 915)
@Composable
private fun DoorsEmptyPreview() {
    DoorsPagePreviewContent(DoorsUiState(nowMillis = PREVIEW_NOW, loaded = true, listening = false, pairing = DoorPairing()))
}
