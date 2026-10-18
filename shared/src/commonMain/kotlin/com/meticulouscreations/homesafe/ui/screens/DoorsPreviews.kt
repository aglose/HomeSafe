// Public on purpose: :androidApp's screenshot tests call these from another module (see HomePreviews.kt).
@file:Suppress("ktlint:compose:preview-public-check")

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.DoorHistoryDay
import com.meticulouscreations.homesafe.viewmodel.DoorHistoryEntry
import com.meticulouscreations.homesafe.viewmodel.DoorsUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_subtitle
import homesafe.shared.generated.resources.doors_title
import org.jetbrains.compose.resources.stringResource

private const val PREVIEW_NOW = 1_792_300_000_000L

private val previewDoors = listOf(
    Door("0x01", "Back door", open = true, changedAtMillis = PREVIEW_NOW - 7 * 60_000, battery = 88, linkQuality = 120, online = true, notify = DoorNotify.ALWAYS),
    Door("0x02", "Front door", open = false, changedAtMillis = PREVIEW_NOW - 3 * 3_600_000, battery = 97, linkQuality = 140, online = true),
    Door("0x03", "Garage door", open = false, changedAtMillis = PREVIEW_NOW - 2 * 86_400_000, battery = 12, linkQuality = 22, online = true, notify = DoorNotify.NEVER),
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
@Composable
fun DoorsPreview() {
    DoorsPagePreviewContent(DoorsUiState(doors = previewDoors, history = previewHistory, nowMillis = PREVIEW_NOW, loaded = true))
}

/** No sensor paired yet, and the server not hearing the Zigbee network either. */
@Preview(name = "Doors, none paired yet", widthDp = 412, heightDp = 915)
@Composable
fun DoorsEmptyPreview() {
    DoorsPagePreviewContent(DoorsUiState(nowMillis = PREVIEW_NOW, loaded = true, listening = false))
}

/** The strip on Home: one door open, one closed, one whose sensor has gone quiet. */
@Preview(name = "Home doors strip", widthDp = 412)
@Composable
fun HomeDoorsStripPreview() {
    FrigateTheme {
        Surface {
            HomeDoorsStrip(
                doors = listOf(
                    Door("0x01", "Back door", open = true),
                    Door("0x02", "Front door", open = false),
                    Door("0x03", "Garage door", open = false, online = false),
                ),
                onOpen = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
