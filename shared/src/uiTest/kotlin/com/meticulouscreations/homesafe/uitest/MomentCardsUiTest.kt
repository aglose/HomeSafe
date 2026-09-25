package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentPresentation
import com.meticulouscreations.homesafe.domain.model.VisitKind
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.MomentsFeed
import com.meticulouscreations.homesafe.viewmodel.DownloadUiState
import com.meticulouscreations.homesafe.viewmodel.MomentClip
import com.meticulouscreations.homesafe.viewmodel.MomentGroup
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.MomentsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the Moments feed draws for each entry, rendered through [MomentsFeed]: the ordinary card
 * with its badge, duration or LIVE marker and download button; a visit's clip list; a household
 * car's quiet routine row; and the inline player's way out to full screen. [MomentsFeedUiTest]
 * covers the feed's paging and a visit's first opening; this covers the rest of what each entry
 * can say and which detection each of its controls hands back.
 *
 * Every [MomentPresentation] here is spelled out rather than derived, so no label depends on the
 * host's time zone or clock. No card has a thumbnail URL, and no card is ever given a player
 * request: an open card shows its poster-less spinner or its error, and nothing connects to
 * anything.
 *
 * `mainClock.autoAdvance = false` throughout: a LIVE badge pulses and an opening clip spins
 * forever, so the composition is never idle. Lists that unfold are given time with `advanceTimeBy`.
 */
@OptIn(ExperimentalTestApi::class)
class MomentCardsUiTest {

    private val start = 1_789_000_000.0

    private fun event(
        id: String,
        startEpochSeconds: Double = start,
        inProgress: Boolean = false,
        hasClip: Boolean = true,
        cameraName: String = "front_door",
        label: String = "person",
    ) = MomentEvent(
        id = id,
        cameraName = cameraName,
        label = label,
        subLabel = null,
        startEpochSeconds = startEpochSeconds,
        endEpochSeconds = if (inProgress) null else startEpochSeconds + 12,
        topScore = 0.9,
        hasClip = hasClip,
        hasSnapshot = false,
    )

    private fun presentation(
        title: String,
        timeLabel: String = "6:55 PM",
        durationLabel: String? = "0:12",
        badgeLabel: String = "person",
        locationLabel: String = "Front Door",
        sightingsLabel: String? = null,
        clipCountLabel: String? = null,
    ) = MomentPresentation(
        title = title,
        timeLabel = timeLabel,
        durationLabel = durationLabel,
        dateGroup = "Today",
        dateSubLabel = "Sep 14",
        badgeLabel = badgeLabel,
        locationLabel = locationLabel,
        sightingsLabel = sightingsLabel,
        clipCountLabel = clipCountLabel,
    )

    private fun card(id: String, title: String = "Person detected", hasClip: Boolean = true) =
        MomentItem(event(id, hasClip = hasClip), presentation(title), thumbnailUrl = null)

    private fun today(vararg items: MomentItem) = listOf(MomentGroup("Today", "Sep 14", items.toList()))

    /** Three clips of someone in the backyard a few seconds apart, folded into one card. */
    private val visit: MomentItem = run {
        val clips = List(3) { i ->
            MomentClip(event("v$i", startEpochSeconds = start + i * 20, cameraName = "backyard"), "6:5${5 + i} PM", "Person on the patio", "0:12")
        }
        MomentItem(
            event = clips.first().event,
            presentation = presentation(
                title = "Person in the backyard",
                timeLabel = "6:55–6:57 PM",
                locationLabel = "Backyard · Patio",
                clipCountLabel = "3 clips",
            ),
            thumbnailUrl = null,
            key = "v0",
            kind = VisitKind.VISIT,
            clips = clips,
        )
    }

    private fun car(id: String, offset: Double, inProgress: Boolean = false) =
        event(id, startEpochSeconds = start + offset, inProgress = inProgress, cameraName = "driveway", label = "car")

    /** Andrew's Tesla pulling in, moving and heading out: one quiet row, its last sighting still going. */
    private val routine: MomentItem = run {
        val sightings = listOf(
            MomentClip(car("r0", 0.0), "5:51 PM", "Andrew's Tesla in the driveway", "0:40"),
            MomentClip(car("r1", 1_140.0), "6:10 PM", "Andrew's Tesla in the driveway", "1:05"),
            MomentClip(car("r2", 2_340.0, inProgress = true), "6:30 PM", "Andrew's Tesla on the street", null),
        )
        MomentItem(
            event = sightings.first().event,
            presentation = presentation(
                title = "Andrew's Tesla came and went 3×",
                timeLabel = "Since 5:51 PM",
                durationLabel = null,
                badgeLabel = "car",
                locationLabel = "Driveway, Street",
                clipCountLabel = "3 sightings",
            ),
            thumbnailUrl = null,
            key = "r0",
            kind = VisitKind.ROUTINE,
            clips = sightings,
        )
    }

    private fun runFeed(
        state: MomentsUiState,
        downloadState: DownloadUiState = DownloadUiState(),
        onCardClick: (MomentEvent) -> Unit = {},
        onDownloadClick: (MomentEvent) -> Unit = {},
        onFullScreenClick: (MomentEvent) -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                MomentsFeed(
                    state = state,
                    downloadState = downloadState,
                    onSelectCategory = {},
                    onUnfamiliarOnlyChange = {},
                    onSelectCamera = {},
                    onShowDay = {},
                    onLoadOlder = {},
                    onCardClick = onCardClick,
                    onClipBuffering = {},
                    onClipError = {},
                    onDownloadClick = onDownloadClick,
                    onFullScreenClick = onFullScreenClick,
                )
            }
        }
        block()
    }

    @Test
    fun eachDayIsHeadedByItsNameAndDate() = runFeed(
        MomentsUiState(
            groups = listOf(
                MomentGroup("Today", "Sep 14", listOf(card("a"))),
                MomentGroup("Yesterday", "Sep 13", listOf(card("b"))),
            ),
        ),
    ) {
        onNodeWithText("Today").assertIsDisplayed()
        onNodeWithText("SEP 14").assertIsDisplayed()
        onNodeWithText("Yesterday").assertIsDisplayed()
        onNodeWithText("SEP 13").assertIsDisplayed()
    }

    @Test
    fun aCardReadsWhatWhenWhereAndItsLabel() {
        val car = MomentItem(
            event("a", label = "car"),
            presentation(
                title = "Sarah's Tesla in the driveway",
                timeLabel = "5:51 PM",
                durationLabel = "0:40",
                badgeLabel = "car",
                locationLabel = "Front Door · Driveway",
            ),
            thumbnailUrl = null,
        )
        runFeed(MomentsUiState(groups = today(car))) {
            onNodeWithText("Sarah's Tesla in the driveway").assertIsDisplayed()
            onNodeWithText("5:51 PM · Front Door · Driveway").assertIsDisplayed()
            onNodeWithText("0:40").assertIsDisplayed()
            // The pill shouts Frigate's label; the name stays in the title.
            onNodeWithText("CAR").assertIsDisplayed()
        }
    }

    @Test
    fun aDetectionStillGoingIsMarkedLiveInPlaceOfADuration() {
        val ongoing = MomentItem(event("a", inProgress = true), presentation("Person on the porch", durationLabel = null), thumbnailUrl = null)
        runFeed(MomentsUiState(groups = today(ongoing))) {
            onNodeWithText("LIVE").assertIsDisplayed()
        }
    }

    @Test
    fun aParkedVehicleSaysHowOftenItWasSeen() {
        val parked = MomentItem(
            event("a", label = "car"),
            presentation("Car in the driveway", badgeLabel = "car", sightingsLabel = "Seen 3 times · still there"),
            thumbnailUrl = null,
        )
        runFeed(MomentsUiState(groups = today(parked))) {
            onNodeWithText("Seen 3 times · still there").assertIsDisplayed()
        }
    }

    @Test
    fun theDownloadButtonDownloadsThatCardsClip() {
        var downloaded: MomentEvent? = null
        runFeed(MomentsUiState(groups = today(card("a"))), onDownloadClick = { downloaded = it }) {
            onNodeWithContentDescription("Download clip").performClick()
            assertEquals("a", downloaded?.id)
        }
    }

    @Test
    fun aFinishedDownloadIsTickedOnItsOwnCardOnly() = runFeed(
        MomentsUiState(groups = today(card("a"), card("b"))),
        downloadState = DownloadUiState(resultEventId = "a"),
    ) {
        onNodeWithContentDescription("Downloaded").assertIsDisplayed()
        onAllNodesWithContentDescription("Download clip").assertCountEquals(1)
    }

    @Test
    fun aFailedDownloadSaysWhyAndCanBeTriedAgain() {
        var downloaded: MomentEvent? = null
        runFeed(
            MomentsUiState(groups = today(card("a"))),
            downloadState = DownloadUiState(resultEventId = "a", resultError = "Not enough space"),
            onDownloadClick = { downloaded = it },
        ) {
            onNodeWithContentDescription("Download failed: Not enough space").assertIsDisplayed().performClick()
            assertEquals("a", downloaded?.id)
        }
    }

    @Test
    fun aDownloadInFlightTakesItsCardsButtonAwayAndLeavesTheOthers() = runFeed(
        MomentsUiState(groups = today(card("a"), card("b"))),
        downloadState = DownloadUiState(downloadingEventId = "a"),
    ) {
        onAllNodesWithContentDescription("Download clip").assertCountEquals(1)
        onAllNodesWithContentDescription("Downloaded").assertCountEquals(0)
    }

    @Test
    fun aDetectionWithNoClipNeitherPlaysNorDownloads() {
        var played: MomentEvent? = null
        runFeed(MomentsUiState(groups = today(card("a", title = "Package detected", hasClip = false))), onCardClick = { played = it }) {
            onAllNodesWithContentDescription("Download clip").assertCountEquals(0)
            onNodeWithText("Package detected").assertIsNotEnabled().performClick()
            assertEquals(null, played)
        }
    }

    @Test
    fun anOpenCardOffersFullScreenForItsDetection() {
        var fullScreen: MomentEvent? = null
        runFeed(MomentsUiState(groups = today(card("a")), expandedEventId = "a"), onFullScreenClick = { fullScreen = it }) {
            // An open card scrolls itself into view for a moment after it grows (KeepGrowingEntryInView);
            // tap once that has finished, or the tap lands where the button used to be.
            mainClock.advanceTimeBy(1_000)
            onNodeWithContentDescription("Play full screen").assertIsDisplayed().performClick()
            assertEquals("a", fullScreen?.id)
        }
    }

    @Test
    fun aClipThatWontPlaySaysWhyAndStillOffersFullScreen() = runFeed(
        MomentsUiState(groups = today(card("a"), card("b")), expandedEventId = "a", clipError = "This clip is no longer on the server"),
    ) {
        onNodeWithText("This clip is no longer on the server").assertIsDisplayed()
        // Only the open card gets the player: the other one stays shut.
        onAllNodesWithContentDescription("Play full screen").assertCountEquals(1)
        onNodeWithContentDescription("Play full screen").assertIsDisplayed()
    }

    @Test
    fun fullScreenFromAVisitHandsOnTheClipPlayingRatherThanTheFirst() {
        var fullScreen: MomentEvent? = null
        runFeed(MomentsUiState(groups = today(visit), expandedEventId = "v2"), onFullScreenClick = { fullScreen = it }) {
            // An open card scrolls itself into view for a moment after it grows (KeepGrowingEntryInView);
            // tap once that has finished, or the tap lands where the button used to be.
            mainClock.advanceTimeBy(1_000)
            onNodeWithContentDescription("Play full screen").assertIsDisplayed().performClick()
            assertEquals("v2", fullScreen?.id)
        }
    }

    @Test
    fun theClipCountClosesTheListItOpened() = runFeed(MomentsUiState(groups = today(visit))) {
        onNodeWithText("3 clips").performClick()
        mainClock.advanceTimeBy(1_000)
        onAllNodesWithText("Person on the patio", useUnmergedTree = true).assertCountEquals(3)
        onNodeWithText("3 clips").performClick()
        mainClock.advanceTimeBy(2_000)
        onAllNodesWithText("Person on the patio", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText("Person in the backyard").assertIsDisplayed()
    }

    @Test
    fun aRoutineRowIsOneQuietLineWithNoBadgeCountOrDownload() = runFeed(MomentsUiState(groups = today(routine))) {
        onNodeWithText("Andrew's Tesla came and went 3×").assertIsDisplayed()
        onNodeWithText("Since 5:51 PM · Driveway, Street").assertIsDisplayed()
        onAllNodesWithText("CAR").assertCountEquals(0)
        onAllNodesWithText("3 sightings").assertCountEquals(0)
        onAllNodesWithContentDescription("Download clip").assertCountEquals(0)
    }

    @Test
    fun tappingARoutineRowListsItsSightingsRatherThanPlaying() {
        var played: MomentEvent? = null
        runFeed(MomentsUiState(groups = today(routine)), onCardClick = { played = it }) {
            onAllNodesWithText("Andrew's Tesla in the driveway", useUnmergedTree = true).assertCountEquals(0)
            onNodeWithText("Andrew's Tesla came and went 3×").performClick()
            mainClock.advanceTimeBy(1_000)
            onAllNodesWithText("Andrew's Tesla in the driveway", useUnmergedTree = true).assertCountEquals(2)
            onNodeWithText("Andrew's Tesla on the street", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(null, played)
        }
    }

    @Test
    fun anyListedSightingPlaysAndOneStillGoingIsMarkedLive() {
        var played: MomentEvent? = null
        runFeed(MomentsUiState(groups = today(routine)), onCardClick = { played = it }) {
            onNodeWithText("Andrew's Tesla came and went 3×").performClick()
            mainClock.advanceTimeBy(1_000)
            onNodeWithText("LIVE").assertIsDisplayed()
            onNodeWithText("6:10 PM").performClick()
            assertEquals("r1", played?.id)
        }
    }

    @Test
    fun tappingAnOpenRoutineRowFoldsItsSightingsAway() = runFeed(MomentsUiState(groups = today(routine))) {
        onNodeWithText("Andrew's Tesla came and went 3×").performClick()
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("6:10 PM").assertIsDisplayed()
        onNodeWithText("Andrew's Tesla came and went 3×").performClick()
        mainClock.advanceTimeBy(2_000)
        onAllNodesWithText("6:10 PM").assertCountEquals(0)
    }

    @Test
    fun aPlayingSightingOpensThePlayerBeneathTheRowWithTheWayToFullScreen() {
        var fullScreen: MomentEvent? = null
        runFeed(MomentsUiState(groups = today(routine), expandedEventId = "r2"), onFullScreenClick = { fullScreen = it }) {
            // An open card scrolls itself into view for a moment after it grows (KeepGrowingEntryInView);
            // tap once that has finished, or the tap lands where the button used to be.
            mainClock.advanceTimeBy(1_000)
            onNodeWithContentDescription("Play full screen").assertIsDisplayed().performClick()
            assertEquals("r2", fullScreen?.id)
        }
    }
}
