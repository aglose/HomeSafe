package com.meticulouscreations.homesafe.uitest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentPresentation
import com.meticulouscreations.homesafe.domain.model.VisitKind
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.MomentsFeed
import com.meticulouscreations.homesafe.ui.screens.TagCarDialog
import com.meticulouscreations.homesafe.ui.screens.TagCarPrompt
import com.meticulouscreations.homesafe.viewmodel.CarTagTarget
import com.meticulouscreations.homesafe.viewmodel.DownloadUiState
import com.meticulouscreations.homesafe.viewmodel.MomentCarTagUiState
import com.meticulouscreations.homesafe.viewmodel.MomentClip
import com.meticulouscreations.homesafe.viewmodel.MomentGroup
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.MomentsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tagging an unnamed car: the "Tag car" action on a moment the classifier left as plain "Car"
 * (and on a visit's clip of one), never on a named car; the picker's known cars and new-car field;
 * and the prompt on a camera screen a notification opened. Each composable is driven from
 * fixtures, so nothing here reaches a server.
 */
@OptIn(ExperimentalTestApi::class)
class MomentCarTagUiTest {

    private val start = 1_789_000_000.0

    private fun event(id: String, subLabel: String? = null) = MomentEvent(
        id = id,
        cameraName = "hikvision_1",
        label = "car",
        subLabel = subLabel,
        startEpochSeconds = start,
        endEpochSeconds = start + 20,
        topScore = 0.9,
        hasClip = true,
        hasSnapshot = false,
    )

    private fun presentation(title: String, clipCountLabel: String? = null) = MomentPresentation(
        title = title,
        timeLabel = "8:42 PM",
        durationLabel = "0:20",
        dateGroup = "Today",
        dateSubLabel = "Sep 14",
        badgeLabel = "car",
        locationLabel = "Front Yard · Driveway",
        sightingsLabel = null,
        clipCountLabel = clipCountLabel,
    )

    private fun feed(vararg items: MomentItem, onTagCar: (MomentEvent) -> Unit) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                MomentsFeed(
                    state = MomentsUiState(groups = listOf(MomentGroup("Today", "Sep 14", items.toList()))),
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
                    onTagCar = onTagCar,
                )
            }
        }
        onNodeWithText("Car in the driveway").assertIsDisplayed()
        onAllNodesWithText("Tag car").assertCountEquals(1)
        onNodeWithText("Tag car").performClick()
    }

    @Test
    fun anUnnamedCarsCardOffersTheTagAndANamedOneDoesNot() {
        val tapped = mutableListOf<String>()
        feed(
            MomentItem(event("stranger"), presentation("Car in the driveway"), thumbnailUrl = null, canTagCar = true),
            MomentItem(event("tesla", "andrews_tesla"), presentation("Andrew's Tesla on the street"), thumbnailUrl = null, key = "tesla"),
            onTagCar = { tapped += it.id },
        )
        assertEquals(listOf("stranger"), tapped)
    }

    @Test
    fun aVisitsClipOfAnUnnamedCarCanBeTaggedFromItsRow() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tapped = mutableListOf<String>()
        val clips = listOf(
            MomentClip(event("v0", "andrews_tesla"), "8:42 PM", "Andrew's Tesla in the driveway", "0:20"),
            MomentClip(event("v1"), "8:43 PM", "Car in the driveway", "0:15", canTagCar = true),
        )
        val visit = MomentItem(clips.first().event, presentation("Andrew's Tesla in the driveway", "2 clips"), null, key = "v0", kind = VisitKind.VISIT, clips = clips)
        setContent {
            FrigatePreview {
                MomentsFeed(
                    state = MomentsUiState(groups = listOf(MomentGroup("Today", "Sep 14", listOf(visit)))),
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
                    onTagCar = { tapped += it.id },
                )
            }
        }
        onAllNodesWithText("Tag car").assertCountEquals(0)
        onNodeWithText("2 clips").performClick()
        mainClock.advanceTimeBy(1_000)
        // A 16dp glyph, but a full-size touch target around it.
        onNodeWithContentDescription("Tag this car")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf("v1"), tapped)
    }

    private val target = CarTagTarget("stranger", "Car in the driveway · 8:42 PM")

    private fun dialog(
        state: MomentCarTagUiState,
        onTag: (String) -> Unit = {},
        onNewCarDraftChange: (String) -> Unit = {},
        onTagAsNewCar: () -> Unit = {},
        onDismiss: () -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            FrigatePreview {
                TagCarDialog(
                    state = state,
                    onTag = onTag,
                    onNewCarDraftChange = onNewCarDraftChange,
                    onTagAsNewCar = onTagAsNewCar,
                    onRetry = {},
                    onDismiss = onDismiss,
                )
            }
        }
        block()
    }

    @Test
    fun thePickerOffersTheKnownCarsByName() {
        val tagged = mutableListOf<String>()
        dialog(MomentCarTagUiState(target = target, knownCars = listOf("andrews_tesla", "in-laws_mercedes")), onTag = { tagged += it }) {
            onNodeWithText("Whose car is this?").assertIsDisplayed()
            onNodeWithText("Car in the driveway · 8:42 PM").assertIsDisplayed()
            onNodeWithText("In-Laws' Mercedes").assertIsDisplayed()
            onNodeWithText("Andrew's Tesla").performClick()
        }
        assertEquals(listOf("andrews_tesla"), tagged)
    }

    @Test
    fun aNewCarIsTypedThenAdded() {
        val typed = mutableListOf<String>()
        var added = 0
        dialog(MomentCarTagUiState(target = target, knownCars = emptyList()), onNewCarDraftChange = { typed += it }) {
            onNodeWithText("The classifier doesn't know any cars yet. Name this one below.").assertIsDisplayed()
            onNodeWithText("Add").assertIsNotEnabled()
            onNode(hasSetTextAction()).performTextInput("Grandma's Van")
        }
        assertEquals(listOf("Grandma's Van"), typed)
        dialog(MomentCarTagUiState(target = target, newCarDraft = "Grandma's Van"), onTagAsNewCar = { added++ }) {
            onNodeWithText("Add").assertIsEnabled().performClick()
        }
        assertEquals(1, added)
    }

    @Test
    fun onceTaggedItSaysHowItWentAndCloses() {
        var dismissed = 0
        dialog(
            MomentCarTagUiState(target = target, done = true, notice = "Tagged as Grandma's Van. Retraining now."),
            onDismiss = { dismissed++ },
        ) {
            onNodeWithText("Car tagged").assertIsDisplayed()
            onNodeWithText("Tagged as Grandma's Van. Retraining now.").assertIsDisplayed()
            onAllNodesWithText("Add").assertCountEquals(0)
            onNodeWithText("Done").performClick()
        }
        assertEquals(1, dismissed)
    }

    @Test
    fun theLandingPromptIsOneTapFromTheTagAndThenSaysWhatItWasTaggedAs() = runComposeUiTest {
        var taps = 0
        var taggedAs: String? by mutableStateOf(null)
        setContent {
            FrigatePreview { TagCarPrompt(summary = target.summary, taggedAs = taggedAs, onTag = { taps++ }) }
        }
        onNodeWithText("Frigate didn't recognise this car").assertIsDisplayed()
        onNodeWithText("Tag car").performClick()
        assertEquals(1, taps)

        taggedAs = "sarahs_car"
        waitForIdle()
        onNodeWithText("Tagged as Sarah's Car").assertIsDisplayed()
        onAllNodesWithText("Tag car").assertCountEquals(0)
    }
}
