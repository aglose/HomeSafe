package com.meticulouscreations.homesafe.uitest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.groupIntoVisits
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.MomentsFeed
import com.meticulouscreations.homesafe.viewmodel.DownloadUiState
import com.meticulouscreations.homesafe.viewmodel.MomentCameraOption
import com.meticulouscreations.homesafe.viewmodel.MomentClip
import com.meticulouscreations.homesafe.viewmodel.MomentGroup
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.MomentsUiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Moments feed rendered for real. [MomentsFeed] is the stateless half of the tab, so it can
 * be driven from fixtures with no graph behind it — and with no clip open, so no player is
 * asked to connect to anything.
 *
 * `mainClock.autoAdvance = false` for the same reason as [HomeFeedUiTest]: a card's LIVE badge
 * pulses forever, so the composition is never idle.
 */
@OptIn(ExperimentalTestApi::class)
class MomentsFeedUiTest {

    private val today = LocalDate(2026, 9, 14)

    private fun item(id: String, startEpochSeconds: Double) = MomentEvent(
        id = id,
        cameraName = "front_door",
        label = "person",
        subLabel = null,
        startEpochSeconds = startEpochSeconds,
        endEpochSeconds = startEpochSeconds + 12,
        topScore = 0.9,
        hasClip = true,
        hasSnapshot = false,
    ).let { MomentItem(it, it.present(today, TimeZone.UTC), thumbnailUrl = null) }

    private val aDay = listOf(MomentGroup("Sep 10", "Sep 10", listOf(item("a", 1_789_000_000.0), item("b", 1_788_990_000.0))))

    private val cameras = listOf(MomentCameraOption("front_door", "Front Door"), MomentCameraOption("backyard", "Backyard"))

    private fun runFeed(
        state: MomentsUiState,
        onLoadOlder: () -> Unit = {},
        onShowDay: (LocalDate?) -> Unit = {},
        onSelectCategory: (MomentCategory) -> Unit = {},
        onUnfamiliarOnlyChange: (Boolean) -> Unit = {},
        onSelectCamera: (String?) -> Unit = {},
        block: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) =
        runComposeUiTest {
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

    @Test
    fun theFeedEndsInAnOfferOfOlderMomentsWhileTheServerHasThem() {
        var asked = 0
        runFeed(MomentsUiState(groups = aDay, hasOlder = true), onLoadOlder = { asked++ }) {
            onAllNodesWithText("Person detected").assertCountEquals(2)
            onNodeWithText("Load older moments").assertIsDisplayed().performClick()
            // Two cards and the footer all fit, so the lookahead asked once on its own; the tap asked again.
            assertEquals(2, asked)
        }
    }

    /**
     * The feed opens on the device's cache and the first fetch lands on top of it (2026-09-26):
     * last night's moments were on screen, this morning's arrived above them, and the list held
     * its place on "Yesterday" with every new moment scrolled out of sight above it.
     */
    @Test
    fun newerMomentsLandingAboveTheTopOfTheFeedAreShownRatherThanScrolledPast() = runComposeUiTest {
        mainClock.autoAdvance = false
        var state by mutableStateOf(MomentsUiState(groups = aDay))
        setContent {
            FrigatePreview {
                MomentsFeed(
                    state = state,
                    downloadState = DownloadUiState(),
                    onSelectCategory = {},
                    onUnfamiliarOnlyChange = {},
                    onSelectCamera = {},
                    onShowDay = {},
                    onLoadOlder = {},
                    onCardClick = {},
                    onClipBuffering = {},
                    onClipError = {},
                    onDownloadClick = {},
                    onFullScreenClick = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithText("Sep 10").assertIsDisplayed()

        state = MomentsUiState(groups = listOf(MomentGroup("Today", "Sep 14", listOf(item("new", 1_789_300_000.0)))) + aDay)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Today").assertIsDisplayed()
    }

    @Test
    fun theFeedEndsInAFullStopOnceTheServerHasNothingOlder() = runFeed(MomentsUiState(groups = aDay, hasOlder = false)) {
        onNodeWithText("That's everything the server still has.").assertIsDisplayed()
        onAllNodesWithText("Load older moments").assertCountEquals(0)
    }

    @Test
    fun aSpinnerStandsInForTheButtonWhileThePageIsOnItsWay() = runFeed(MomentsUiState(groups = aDay, hasOlder = true, loadingOlder = true)) {
        onAllNodesWithText("Load older moments").assertCountEquals(0)
    }

    @Test
    fun theCalendarChipNamesTheDayTheFeedIsOpenedAtAndClearsIt() {
        var shown: LocalDate? = LocalDate(2026, 9, 10)
        runFeed(MomentsUiState(groups = aDay, historyDay = LocalDate(2026, 9, 10)), onShowDay = { shown = it }) {
            onNodeWithText("From Sep 10").assertIsDisplayed()
            onNodeWithContentDescription("Back to the latest moments").performClick()
            assertEquals(null, shown)
        }
    }

    @Test
    fun theLiveFeedsChipOffersToPickADay() = runFeed(MomentsUiState(groups = aDay)) {
        onNodeWithContentDescription("Pick a day").assertIsDisplayed()
        onAllNodesWithContentDescription("Back to the latest moments").assertCountEquals(0)
    }

    @Test
    fun anEmptyWindowIntoThePastOffersToLookFurtherBack() {
        var asked = 0
        runFeed(
            MomentsUiState(selectedCategory = MomentCategory.ANIMALS, historyDay = LocalDate(2026, 9, 10), hasOlder = true),
            onLoadOlder = { asked++ },
        ) {
            onNodeWithText("No animals in the most recent pages before Sep 10.").assertIsDisplayed()
            onNodeWithText("Look further back").performClick()
            assertEquals(1, asked)
        }
    }

    @Test
    fun theCameraChipListsTheServersCamerasAndPicksOne() {
        var picked: String? = "unset"
        runFeed(MomentsUiState(groups = aDay, cameras = cameras), onSelectCamera = { picked = it }) {
            onNodeWithText("All cameras").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Backyard").assertIsDisplayed().performClick()
            assertEquals("backyard", picked)
        }
    }

    @Test
    fun aPickedCameraNamesItselfOnTheChipAndCanBeCleared() {
        var picked: String? = "unset"
        // No cards: every fixture card is on the front door, and would answer to its name too.
        runFeed(
            MomentsUiState(cameras = cameras, selectedCamera = cameras[0]),
            onSelectCamera = { picked = it },
        ) {
            onNodeWithText("Front Door").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("All cameras").assertIsDisplayed().performClick()
            assertEquals(null, picked)
        }
    }

    @Test
    fun theTypeChipPicksACategoryFromItsMenu() {
        var picked: MomentCategory? = null
        runFeed(MomentsUiState(groups = aDay, cameras = cameras), onSelectCategory = { picked = it }) {
            onNodeWithText("All events").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Vehicles").assertIsDisplayed().performClick()
            assertEquals(MomentCategory.VEHICLES, picked)
        }
    }

    @Test
    fun anEmptyFeedNarrowedToACameraNamesIt() = runFeed(
        MomentsUiState(selectedCategory = MomentCategory.PEOPLE, cameras = cameras, selectedCamera = cameras[1], historyDay = LocalDate(2026, 9, 10)),
    ) {
        onNodeWithText("No people on Backyard on or before Sep 10 that the server still has.").assertIsDisplayed()
    }

    @Test
    fun anEmptyWindowWithNothingOlderSaysSoAndOffersNothing() = runFeed(MomentsUiState(historyDay = LocalDate(2026, 9, 10), hasOlder = false)) {
        onNodeWithText("No detections on or before Sep 10 that the server still has.").assertIsDisplayed()
        onAllNodesWithText("Look further back").assertCountEquals(0)
    }

    @Test
    fun theTypeMenuOffersUnfamiliarOnlyAndTheChipNamesIt() {
        var unfamiliar: Boolean? = null
        runFeed(MomentsUiState(groups = aDay), onUnfamiliarOnlyChange = { unfamiliar = it }) {
            onNodeWithText("All events").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithText("Unfamiliar only").assertIsDisplayed().performClick()
            assertEquals(true, unfamiliar)
        }
    }

    @Test
    fun anUnfamiliarPeopleFeedSaysSoOnTheChipAndWhenEmpty() = runFeed(MomentsUiState(selectedCategory = MomentCategory.PEOPLE, unfamiliarOnly = true)) {
        onNodeWithText("Unfamiliar people").assertIsDisplayed()
        onNodeWithText("No unfamiliar people to show.", substring = true).assertIsDisplayed()
    }

    /** Five clips of one person a few seconds apart: one card. */
    private val visit: MomentItem = run {
        val start = 1_789_000_000.0
        val events = List(5) { i ->
            MomentEvent("v$i", "backyard", "person", null, start + i * 20, start + i * 20 + 12, 0.9, hasClip = true, hasSnapshot = false)
        }
        val folded = events.groupIntoVisits().single()
        MomentItem(
            folded.lead,
            folded.present(today, TimeZone.UTC),
            thumbnailUrl = null,
            key = folded.key,
            kind = folded.kind,
            clips = folded.events.map { e -> e.present(today, TimeZone.UTC).let { MomentClip(e, it.timeLabel, it.title, it.durationLabel) } },
        )
    }

    @Test
    fun aVisitIsOneCardWhoseTextPlaysItAndWhoseCountListsItsClips() {
        var played: MomentEvent? = null
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                FrigatePreview {
                    MomentsFeed(
                        state = MomentsUiState(groups = listOf(MomentGroup("Sep 10", "Sep 10", listOf(visit)))),
                        downloadState = DownloadUiState(),
                        onSelectCategory = {},
                        onUnfamiliarOnlyChange = {},
                        onSelectCamera = {},
                        onShowDay = {},
                        onLoadOlder = {},
                        onCardClick = { played = it },
                        onClipBuffering = {},
                        onClipError = {},
                        onDownloadClick = {},
                        onFullScreenClick = {},
                    )
                }
            }
            onAllNodesWithText("Person detected").assertCountEquals(1)
            // The title is text, not the thumbnail, and still plays the visit's first clip.
            onNodeWithText("Person detected").performClick()
            assertEquals("v0", played?.id)
            onNodeWithText("5 clips").performClick()
            mainClock.advanceTimeBy(1_000)
            onAllNodesWithText("Person detected", useUnmergedTree = true).assertCountEquals(6)
            onAllNodesWithText("Person detected", useUnmergedTree = true)[5].performClick()
            assertEquals("v4", played?.id)
        }
    }
}
