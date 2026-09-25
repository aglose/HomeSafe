package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentPresentation
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.MomentsFeed
import com.meticulouscreations.homesafe.viewmodel.DownloadUiState
import com.meticulouscreations.homesafe.viewmodel.MomentCameraOption
import com.meticulouscreations.homesafe.viewmodel.MomentGroup
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.MomentsUiState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Moments feed's filter row, its day picker and what it says when there is nothing to show,
 * picking up where [MomentsFeedUiTest] leaves off. Every filter in force has to be named
 * somewhere on screen — on its chip, ticked in its menu, spelled out when it empties the feed —
 * because a narrowed feed that passes for the whole one reads as a camera that saw nothing.
 *
 * The one card used here is built with its [MomentPresentation] spelled out rather than derived,
 * so nothing on screen depends on the host's time zone. The day picked is a fixed date well in the
 * past, which the calendar (limited to today and earlier) always accepts.
 *
 * `mainClock.autoAdvance = false` throughout, as in [MomentsFeedUiTest]: spinners turn forever,
 * so the composition is never idle, and menus and the calendar are given time to open and close
 * with `advanceTimeBy`.
 */
@OptIn(ExperimentalTestApi::class)
class MomentsFiltersUiTest {

    private val pickedDay = LocalDate(2026, 3, 2)

    private val cameras = listOf(MomentCameraOption("front_door", "Front Door"), MomentCameraOption("backyard", "Backyard"))

    private val porchCard = MomentEvent(
        id = "a",
        cameraName = "front_door",
        label = "person",
        subLabel = null,
        startEpochSeconds = 1_789_000_000.0,
        endEpochSeconds = 1_789_000_012.0,
        topScore = 0.9,
        hasClip = true,
        hasSnapshot = false,
    ).let { event ->
        MomentItem(
            event = event,
            presentation = MomentPresentation(
                title = "Person on the porch",
                timeLabel = "6:55 PM",
                durationLabel = "0:12",
                dateGroup = "Today",
                dateSubLabel = "Sep 14",
                badgeLabel = "person",
                locationLabel = "Front Door · Porch",
                sightingsLabel = null,
            ),
            thumbnailUrl = null,
        )
    }

    private val oneDay = listOf(MomentGroup("Today", "Sep 14", listOf(porchCard)))

    private fun runFeed(
        state: MomentsUiState,
        onLoadOlder: () -> Unit = {},
        onShowDay: (LocalDate?) -> Unit = {},
        onSelectCategory: (MomentCategory) -> Unit = {},
        onUnfamiliarOnlyChange: (Boolean) -> Unit = {},
        onSelectCamera: (String?) -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                MomentsFeed(
                    state = state,
                    downloadState = DownloadUiState(),
                    onSelectCategory = onSelectCategory,
                    onUnfamiliarOnlyChange = onUnfamiliarOnlyChange,
                    onSelectCamera = onSelectCamera,
                    onShowDay = onShowDay,
                    onLoadOlder = onLoadOlder,
                    onCardClick = {},
                    onClipBuffering = {},
                    onClipError = {},
                    onDownloadClick = {},
                    onFullScreenClick = {},
                )
            }
        }
        block()
    }

    /**
     * Plays frames until nothing matches [matcher], for at most [maxFrames]. A menu leaving plays
     * its exit out first; on Android it is a popup window of its own, and a single jump of the
     * frozen clock was not always enough for it to go, where a frame at a time (each check also
     * waits for the device's next real frame) is.
     */
    private fun ComposeUiTest.advanceUntilGone(matcher: SemanticsMatcher, maxFrames: Int = 120) {
        repeat(maxFrames) {
            if (onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) return
            mainClock.advanceTimeByFrame()
        }
    }

    @Test
    fun theTypeMenuTicksTheTypeInForceAndOnlyThatOne() = runFeed(MomentsUiState(selectedCategory = MomentCategory.VEHICLES)) {
        onNodeWithText("Vehicles").performClick()
        mainClock.advanceTimeBy(500)
        onNode(hasText("Vehicles") and hasContentDescription("Selected")).assertIsDisplayed()
        onAllNodesWithContentDescription("Selected").assertCountEquals(1)
    }

    @Test
    fun unfamiliarOnlyIsTickedInTheMenuAndTurnsOffFromThere() {
        var unfamiliar: Boolean? = null
        runFeed(MomentsUiState(unfamiliarOnly = true), onUnfamiliarOnlyChange = { unfamiliar = it }) {
            // With every type shown, the chip names the switch alone.
            onNodeWithText("Unfamiliar").performClick()
            mainClock.advanceTimeBy(500)
            // Both the type and the switch are ticked: "Unfamiliar only" sits alongside the type, not in place of it.
            onAllNodesWithContentDescription("Selected").assertCountEquals(2)
            onNode(hasText("All events") and hasContentDescription("Selected")).assertIsDisplayed()
            onNode(hasText("Unfamiliar only") and hasContentDescription("Selected")).assertIsDisplayed().performClick()
            assertEquals(false, unfamiliar)
        }
    }

    @Test
    fun aTypeAndUnfamiliarOnlyAreNamedTogetherOnTheChipAndWhenEmpty() = runFeed(
        MomentsUiState(selectedCategory = MomentCategory.VEHICLES, unfamiliarOnly = true),
    ) {
        onNodeWithText("Unfamiliar vehicles").assertIsDisplayed()
        onAllNodesWithText("All events").assertCountEquals(0)
        onNodeWithText(
            "No unfamiliar vehicles to show. Detections appear here when they happen in a zone set to watch for them, or when they're recognised.",
        ).assertIsDisplayed()
    }

    @Test
    fun theCameraMenuTicksAllCamerasWhileNoneIsPicked() = runFeed(MomentsUiState(groups = oneDay, cameras = cameras)) {
        onNodeWithText("All cameras").performClick()
        mainClock.advanceTimeBy(500)
        onNode(hasText("All cameras") and hasContentDescription("Selected")).assertIsDisplayed()
        onAllNodesWithContentDescription("Selected").assertCountEquals(1)
    }

    @Test
    fun aFilterMenuClosesOnceItsPickIsMade() {
        var picked: String? = null
        runFeed(MomentsUiState(groups = oneDay, cameras = cameras), onSelectCamera = { picked = it }) {
            onNodeWithText("All cameras").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Backyard").performClick()
            advanceUntilGone(hasText("Backyard"))
            assertEquals("backyard", picked)
            // The chip still says "All cameras" (the state here never changes), so the menu is all that could show Backyard.
            onAllNodesWithText("Backyard").assertCountEquals(0)
        }
    }

    @Test
    fun theCalendarOpensAtTheDayAlreadyPickedAndShowReopensTheFeedThere() {
        var shown: LocalDate? = null
        runFeed(MomentsUiState(historyDay = pickedDay), onShowDay = { shown = it }) {
            onNodeWithText("From Mar 2").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Cancel").assertIsDisplayed()
            // Nothing is touched in the calendar: the day it confirms is the one it was opened at.
            onNodeWithText("Show").performClick()
            assertEquals(pickedDay, shown)
            mainClock.advanceTimeBy(500)
            onAllNodesWithText("Show").assertCountEquals(0)
        }
    }

    @Test
    fun cancellingTheCalendarLeavesTheFeedWhereItWas() {
        var asked = 0
        runFeed(MomentsUiState(groups = oneDay), onShowDay = { asked++ }) {
            onNodeWithContentDescription("Pick a day").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Cancel").performClick()
            mainClock.advanceTimeBy(500)
            assertEquals(0, asked)
            onAllNodesWithText("Show").assertCountEquals(0)
            onNodeWithContentDescription("Pick a day").assertIsDisplayed()
        }
    }

    @Test
    fun aQuietLiveFeedExplainsWhyRatherThanLookingBroken() = runFeed(MomentsUiState()) {
        onNodeWithText(
            "Nothing to show yet. Detections appear here when they happen in a zone set to watch for them, or when Frigate recognises who or what they are.",
        ).assertIsDisplayed()
        onAllNodesWithText("Look further back").assertCountEquals(0)
    }

    @Test
    fun anEmptyCameraFilterNamesTheCamera() = runFeed(MomentsUiState(cameras = cameras, selectedCamera = cameras[1])) {
        onNodeWithText(
            "No detections on Backyard to show. Detections appear here when they happen in a zone set to watch for them, or when Frigate recognises who or what they are.",
        ).assertIsDisplayed()
    }

    @Test
    fun everyFilterInForceIsNamedAtOnce() = runFeed(
        MomentsUiState(
            selectedCategory = MomentCategory.PEOPLE,
            unfamiliarOnly = true,
            cameras = cameras,
            selectedCamera = cameras[0],
            historyDay = pickedDay,
            hasOlder = true,
        ),
    ) {
        onNodeWithText("Front Door").assertIsDisplayed()
        onNodeWithText("Unfamiliar people").assertIsDisplayed()
        onNodeWithText("From Mar 2").assertIsDisplayed()
        onNodeWithText("No unfamiliar people on Front Door in the most recent pages before Mar 2.").assertIsDisplayed()
    }

    @Test
    fun anEmptyWindowWhoseNextPageIsOnItsWaySpinsRatherThanOffering() = runFeed(
        MomentsUiState(historyDay = pickedDay, hasOlder = true, loadingOlder = true),
    ) {
        onNodeWithText("No detections in the most recent pages before Mar 2.").assertIsDisplayed()
        onAllNodesWithText("Look further back").assertCountEquals(0)
    }

    @Test
    fun anUnreachableServerSaysSoInPlaceOfTheEmptyFeed() = runFeed(MomentsUiState(error = "Connection refused")) {
        onNodeWithText("Connection refused").assertIsDisplayed()
        onNodeWithText("Couldn't reach the server for detections.").assertIsDisplayed()
    }

    @Test
    fun anErrorAboveLoadedMomentsLeavesThemInPlace() = runFeed(MomentsUiState(groups = oneDay, error = "Couldn't refresh: timed out")) {
        onNodeWithText("Couldn't refresh: timed out").assertIsDisplayed()
        onNodeWithText("Person on the porch").assertIsDisplayed()
        onAllNodesWithText("Couldn't reach the server for detections.").assertCountEquals(0)
    }

    @Test
    fun theFeedDoesNotAskForMoreWhileAPageIsOnItsWay() {
        var asked = 0
        runFeed(MomentsUiState(groups = oneDay, hasOlder = true, loadingOlder = true), onLoadOlder = { asked++ }) {
            mainClock.advanceTimeBy(500)
            assertEquals(0, asked)
        }
    }

    @Test
    fun theFeedDoesNotAskForMoreOnceTheServerHasNothingOlder() {
        var asked = 0
        runFeed(MomentsUiState(groups = oneDay, hasOlder = false), onLoadOlder = { asked++ }) {
            mainClock.advanceTimeBy(500)
            assertEquals(0, asked)
        }
    }
}
